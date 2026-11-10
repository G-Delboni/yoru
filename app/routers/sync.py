"""API de sincronização usada pelo app do celular.

    GET  /api/sync?desde=<cursor>   só baixar o que mudou
    POST /api/sync                  enviar o que mudou no aparelho e baixar o que falta
    GET  /api/sync/status           teste de conexão (serve para o app mostrar "conectado")

Toda chamada exige o cabeçalho X-Yoru-Token (veja a página /sincronia).
O app guarda o `cursor` devolvido e o manda como `desde` na vez seguinte;
na primeira vez, sem `desde`, recebe tudo.
"""

import secrets
from typing import Any, Optional

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, func, select

from app import pairing, sync
from app.database import get_session

router = APIRouter(prefix="/api/sync", tags=["sincronia"])


def require_token(x_yoru_token: str = Header(default=""), session: Session = Depends(get_session)) -> str:
    """Aceita o token de um aparelho pareado ou o token geral de data/sync_token.txt.
    Devolve o nome do aparelho, para o histórico."""
    device = pairing.device_for_token(session, x_yoru_token)
    if device is not None:
        pairing.touch(session, device)
        return device.name
    if secrets.compare_digest(x_yoru_token.encode(), sync.get_token().encode()):
        return ""
    raise HTTPException(status_code=401, detail="Token inválido. Pareie o aparelho pela página /sincronia.")


class PairIn(BaseModel):
    convite: str = Field(description="Id do convite recebido no anúncio")
    senha: str = Field(description="Senha definida no computador ao cadastrar o aparelho")
    aparelho: Optional[str] = Field(default=None, description="Nome do aparelho (modelo), opcional")


@router.post("/pair")
def pair(body: PairIn, session: Session = Depends(get_session)):
    """Aprovação do pareamento, chamada pelo app depois que o usuário digita a senha."""
    device, message = pairing.approve(session, body.convite, body.senha, body.aparelho)
    if device is None:
        raise HTTPException(status_code=403, detail=message)
    return {"token": device.token, "aparelho": device.name, "mensagem": message}


class SyncIn(BaseModel):
    dispositivo: str = Field(default="celular", description="Nome do aparelho, só para o histórico")
    desde: Optional[str] = Field(default=None, description="`cursor` devolvido na sincronia anterior")
    mudancas: dict[str, list[dict[str, Any]]] = Field(
        default_factory=dict, description="Registros alterados no aparelho, por tabela"
    )


def parse_since(value: Optional[str]) -> Optional[str]:
    try:
        sync.parse_instant(value)
    except ValueError:
        raise HTTPException(status_code=422, detail="`desde` precisa ser um carimbo ISO, como 2026-10-31T18:00:00Z")
    return value


@router.get("/status")
def status(session: Session = Depends(get_session), device: str = Depends(require_token)):
    counts = {
        model.__tablename__: session.exec(
            select(func.count()).select_from(model).where(model.deleted_at == None)  # noqa: E711
        ).one()
        for model in sync.TABLES
    }
    return {"status": "ok", "agora": sync.format_instant(sync.utcnow()), "registros": counts}


@router.get("")
def pull(desde: Optional[str] = None, dispositivo: str = "celular",
         session: Session = Depends(get_session), device: str = Depends(require_token)):
    return sync.run_sync(session, device or dispositivo, parse_since(desde))


@router.post("")
def push_and_pull(body: SyncIn, session: Session = Depends(get_session), device: str = Depends(require_token)):
    return sync.run_sync(session, device or body.dispositivo, parse_since(body.desde), body.mudancas)
