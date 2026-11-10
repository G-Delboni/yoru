"""Sincronização offline-first entre este servidor e os aparelhos (celular).

Regras:
  * Cada aparelho guarda sua própria cópia e anota tudo que muda.
  * Ao sincronizar, o aparelho envia o que mudou desde a última vez e recebe
    o que mudou aqui. O servidor nunca chama o aparelho: quem inicia é o app.
  * Conflito (o mesmo registro mudou nos dois lados): vence o `updated_at`
    mais recente. Empate mantém a versão do servidor.
  * Exclusão é um registro com `deleted_at` preenchido (lápide), que viaja
    como qualquer outra mudança.
  * Reenviar o mesmo lote é seguro: aplicar duas vezes dá o mesmo resultado.

Formato dos valores (texto ISO, para o app tratar como texto opaco):
  * carimbos em UTC: `2026-10-31T18:45:12.123456Z` (sempre com 6 casas, então
    comparar como texto funciona)
  * datas: `2026-10-31`
  * horários marcados pelo usuário (início de prova, por exemplo) não têm fuso:
    `2026-11-12T19:00:00`, hora de parede, igual em qualquer aparelho.
"""

from __future__ import annotations

import secrets
import socket
import typing
from datetime import date, datetime, timedelta, timezone
from enum import Enum
from typing import Any, Optional

from sqlalchemy import event, or_
from sqlalchemy import inspect as sa_inspect
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session as SASession
from sqlmodel import Session, select

from app.database import DATA_DIR
from app.models import (
    Area,
    Event,
    FocusSession,
    Goal,
    Habit,
    HabitCheck,
    LogEntry,
    Note,
    Objective,
    Resource,
    SyncBase,
    SyncLog,
    Tag,
    Task,
    utcnow,
)

# Ordem de aplicação: quem é referenciado vem antes de quem referencia.
TABLES: list[type[SyncBase]] = [Area, Objective, Goal, Resource, Task, Event, LogEntry, Note, Tag, Habit, HabitCheck, FocusSession]
BY_NAME = {m.__tablename__: m for m in TABLES}

# Colunas que são instantes em UTC. Os outros datetimes são hora de parede.
INSTANTS = {"created_at", "updated_at", "deleted_at", "completed_at", "synced_at", "archived_at", "ended_at", "resumed_at"}
# started_at é instante só em focussession (em resource é uma data; o encode decide pelo tipo do valor)
INSTANT_BY_TABLE = {("focussession", "started_at")}

# Relógio do aparelho adiantado além disso é tratado como "agora" do servidor,
# senão um celular com a hora errada venceria todo conflito para sempre.
CLOCK_TOLERANCE = timedelta(minutes=5)

# O cursor devolvido ao aparelho volta alguns segundos, para não perder uma
# gravação que estava em andamento no instante da consulta. Repetir é inofensivo.
CURSOR_MARGIN = timedelta(seconds=5)


# --- Carimbo automático ---------------------------------------------------------


@event.listens_for(SASession, "before_flush")
def _touch_modified(session, flush_context, instances) -> None:
    """Rede de segurança: qualquer registro alterado sem mexer no updated_at ganha um novo.

    Sem isso, uma rota que esquecesse de atualizar o carimbo teria a mudança
    ignorada pelo celular. Durante a aplicação de um lote vindo do aparelho
    isso fica desligado, porque lá o updated_at vem de fora.
    """
    if session.info.get("sync"):
        return
    for obj in session.dirty:
        if isinstance(obj, SyncBase) and session.is_modified(obj, include_collections=False):
            if not sa_inspect(obj).attrs.updated_at.history.has_changes():
                obj.updated_at = utcnow()


# --- Conversão de valores -------------------------------------------------------


def as_utc(value: datetime) -> datetime:
    """Instante em UTC com fuso. Datas sem fuso vindas do banco são UTC por convenção."""
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def naive_utc(value: datetime) -> datetime:
    """Só para formatar: o mesmo instante, em UTC, sem o sufixo de fuso."""
    return as_utc(value).replace(tzinfo=None)


def encode(name: str, value: Any, table: str = "") -> Any:
    if value is None:
        return None
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, datetime):
        if name in INSTANTS or (table, name) in INSTANT_BY_TABLE:
            return naive_utc(value).isoformat(timespec="microseconds") + "Z"
        return value.replace(tzinfo=None).isoformat(timespec="seconds")
    if isinstance(value, date):
        return value.isoformat()
    return value


def _python_type(model: type, name: str) -> type:
    """Tipo Python de um campo, lido da anotação do modelo (`Optional[date]` -> date).
    Não depende dos tipos de coluna do SQLAlchemy/SQLModel, que mudam entre versões."""
    field = model.model_fields.get(name)
    annotation = field.annotation if field is not None else str
    for arg in (typing.get_args(annotation) or (annotation,)):
        if isinstance(arg, type) and arg is not type(None):
            return arg
    return str


def decode(name: str, model: type, value: Any, table: str = "") -> Any:
    if value is None:
        return None
    instant = name in INSTANTS or (table, name) in INSTANT_BY_TABLE
    python_type = _python_type(model, name)
    if isinstance(python_type, type) and issubclass(python_type, Enum):
        return python_type(value)
    if python_type is datetime:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return as_utc(parsed) if instant else parsed.replace(tzinfo=None)
    if python_type is date:
        return date.fromisoformat(str(value)[:10])
    if python_type is bool:
        return bool(value)
    if python_type is int:
        return int(value)
    if python_type is float:
        return float(value)
    return str(value)


def to_wire(obj: SyncBase) -> dict[str, Any]:
    table = obj.__tablename__
    return {c.name: encode(c.name, getattr(obj, c.name), table) for c in obj.__table__.columns}


def parse_instant(value: Optional[str]) -> Optional[datetime]:
    if not value:
        return None
    return as_utc(datetime.fromisoformat(value.replace("Z", "+00:00")))


def format_instant(value: datetime) -> str:
    return naive_utc(value).isoformat(timespec="microseconds") + "Z"


# --- Aplicar o que veio do aparelho ---------------------------------------------


def _apply_one(session: Session, model: type[SyncBase], raw: dict, now: datetime) -> bool:
    """Aplica um registro. Devolve True se mudou algo, False se o servidor já tinha coisa igual ou mais nova."""
    if not isinstance(raw, dict) or not raw.get("id"):
        raise ValueError("registro sem id")
    columns = {c.name: c for c in model.__table__.columns}

    incoming = decode("updated_at", model, raw.get("updated_at"))
    if incoming is None:
        raise ValueError("registro sem updated_at")
    if incoming > now + CLOCK_TOLERANCE:
        incoming = now  # relógio do aparelho adiantado: vale o do servidor

    local = session.get(model, raw["id"])
    if local is not None and incoming <= as_utc(local.updated_at):
        return False

    values = {}
    for name, column in columns.items():
        if name in ("id", "synced_at") or name not in raw:
            continue  # campo ausente = mantém o que já existe (app mais antigo)
        values[name] = decode(name, model, raw[name], model.__tablename__)
    values["updated_at"] = incoming

    if local is None:
        local = model(id=raw["id"], **values)
    else:
        for name, value in values.items():
            setattr(local, name, value)
    local.synced_at = now
    session.add(local)
    session.commit()
    return True


def apply_changes(session: Session, changes: dict[str, list[dict]]) -> dict[str, Any]:
    """Aplica o lote registro a registro. Um registro com defeito é rejeitado sem derrubar os outros."""
    now = utcnow()
    applied: dict[str, set[str]] = {}
    ignored = 0
    rejected: list[dict[str, Any]] = []

    session.info["sync"] = True
    try:
        for model in TABLES:
            rows = changes.get(model.__tablename__) or []
            for raw in rows:
                row_id = raw.get("id") if isinstance(raw, dict) else None
                try:
                    if _apply_one(session, model, raw, now):
                        applied.setdefault(model.__tablename__, set()).add(row_id)
                    else:
                        ignored += 1
                except IntegrityError as exc:
                    session.rollback()
                    detail = str(exc.orig) if exc.orig else "conflito"
                    rejected.append({"tabela": model.__tablename__, "id": row_id, "motivo": detail[:160]})
                except Exception as exc:  # defeito do registro: rejeita só ele
                    session.rollback()
                    rejected.append({"tabela": model.__tablename__, "id": row_id, "motivo": str(exc)[:160]})
    finally:
        session.info.pop("sync", None)

    unknown = sorted(set(changes) - set(BY_NAME))
    for name in unknown:
        rejected.append({"tabela": name, "id": None, "motivo": "tabela desconhecida"})

    return {
        "aplicados": sum(len(ids) for ids in applied.values()),
        "ignorados": ignored,
        "rejeitados": rejected,
        "_ids": applied,
    }


# --- Coletar o que o aparelho ainda não tem ------------------------------------


def collect_changes(session: Session, since: Optional[datetime],
                    skip: Optional[dict[str, set[str]]] = None) -> dict[str, list[dict]]:
    """Registros (inclusive apagados) alterados ou recebidos depois de `since`."""
    out: dict[str, list[dict]] = {}
    for model in TABLES:
        stmt = select(model)
        if since is not None:
            stmt = stmt.where(or_(model.updated_at > since, model.synced_at > since))
        skip_ids = (skip or {}).get(model.__tablename__, set())
        rows = [to_wire(r) for r in session.exec(stmt).all() if r.id not in skip_ids]
        if rows:
            out[model.__tablename__] = rows
    return out


def run_sync(session: Session, device: str, since: Optional[str],
             changes: Optional[dict[str, list[dict]]] = None) -> dict[str, Any]:
    """Um ciclo completo: aplica o que veio e devolve o que falta."""
    started = utcnow()
    result = apply_changes(session, changes or {})
    ids = result.pop("_ids")
    outgoing = collect_changes(session, parse_instant(since), skip=ids)
    sent = sum(len(rows) for rows in outgoing.values())

    session.add(SyncLog(device=device[:60] or "desconhecido", received=result["aplicados"],
                        sent=sent, rejected=len(result["rejeitados"])))
    session.commit()

    return {
        "cursor": format_instant(started - CURSOR_MARGIN),
        "agora": format_instant(started),
        "mudancas": outgoing,
        "enviados": sent,
        **result,
    }


# --- Token e endereço ---------------------------------------------------------------

TOKEN_FILE = DATA_DIR / "sync_token.txt"


def get_token() -> str:
    """Senha do aparelho. Criada na primeira vez e guardada em data/sync_token.txt."""
    if TOKEN_FILE.exists():
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if token:
            return token
    token = secrets.token_urlsafe(18)
    TOKEN_FILE.write_text(token + "\n", encoding="utf-8")
    return token


def new_token() -> str:
    """Troca a senha: os aparelhos já ligados precisam receber a nova."""
    TOKEN_FILE.unlink(missing_ok=True)
    return get_token()


def local_address() -> Optional[str]:
    """IP deste computador na rede local, para digitar no celular."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("10.255.255.255", 1))  # não envia nada; só descobre a rota
        return probe.getsockname()[0]
    except OSError:
        return None
    finally:
        probe.close()


def last_syncs(session: Session, limit: int = 8) -> list[SyncLog]:
    return list(session.exec(select(SyncLog).order_by(SyncLog.at.desc()).limit(limit)).all())
