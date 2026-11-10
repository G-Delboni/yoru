"""Datas no fuso local do computador onde o servidor roda.

O banco guarda carimbos (created_at, updated_at, completed_at) em UTC.
Datas de calendário (due_date, scheduled_for, dia do diário) são locais.
"""

from datetime import date, datetime, timedelta, timezone

DIAS_SEMANA = ["segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo"]
MESES = [
    "janeiro", "fevereiro", "março", "abril", "maio", "junho",
    "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
]


def today() -> date:
    return date.today()


def tomorrow() -> date:
    return today() + timedelta(days=1)


def to_local(dt: datetime | None) -> datetime | None:
    """Converte um carimbo UTC (com ou sem tzinfo, o SQLite perde a info) para local."""
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone()


def local_date(dt: datetime | None) -> date | None:
    local = to_local(dt)
    return local.date() if local else None


def days_until(d: date | datetime) -> int:
    if isinstance(d, datetime):
        d = d.date()
    return (d - today()).days


def data_longa(d: date) -> str:
    """'Sábado, 31 de outubro'"""
    return f"{DIAS_SEMANA[d.weekday()].capitalize()}, {d.day} de {MESES[d.month - 1]}"


def data_curta(d: date) -> str:
    """'31 de outubro', ou 'hoje' / 'ontem'"""
    delta = (today() - d).days
    if delta == 0:
        return "hoje"
    if delta == 1:
        return "ontem"
    return f"{d.day} de {MESES[d.month - 1]}"


def prazo(d: date | datetime) -> str:
    """'hoje', 'amanhã', 'em 5 dias', 'atrasada há 2 dias'"""
    n = days_until(d)
    if n == 0:
        return "hoje"
    if n == 1:
        return "amanhã"
    if n > 1:
        return f"em {n} dias"
    if n == -1:
        return "venceu ontem"
    return f"venceu há {-n} dias"


def saudacao(now: datetime | None = None) -> str:
    h = (now or datetime.now()).hour
    if h < 5:
        return "Boa noite"
    if h < 12:
        return "Bom dia"
    if h < 18:
        return "Boa tarde"
    return "Boa noite"


def relativo(dt: datetime | None) -> str:
    """'agora', 'há 5 min', 'há 3 h', 'ontem, 14:02' ou '28 out, 09:10'."""
    if dt is None:
        return "nunca"
    local = to_local(dt)
    seconds = (datetime.now().astimezone() - local).total_seconds()
    if seconds < 60:
        return "agora"
    if seconds < 3600:
        return f"há {int(seconds // 60)} min"
    if local.date() == today():
        return f"há {int(seconds // 3600)} h"
    hora = local.strftime("%H:%M")
    if local.date() == today() - timedelta(days=1):
        return f"ontem, {hora}"
    return f"{data_curta(local.date())}, {hora}"
