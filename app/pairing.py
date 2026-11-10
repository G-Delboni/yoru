"""Pareamento de aparelhos sem digitar nada no celular.

Fluxo:
  1. No site, você cadastra o aparelho com um nome e uma senha e clica em "Enviar solicitação".
  2. O servidor anuncia o convite por broadcast UDP na rede local (porta 47890), por
     alguns minutos: {"yoru": 1, "convite": id, "nome": ..., "endereco": "http://ip:porta"}.
     O servidor não consegue "ligar" para o celular; o anúncio é a forma de o app,
     que fica escutando enquanto está aberto, descobrir onde o servidor está.
  3. O app mostra "Aprovar sincronização" e pede a senha. Envia POST /api/pair.
  4. Senha certa: o servidor cria um Device com token próprio e devolve o token.
     Senha errada 5 vezes, ou convite vencido: o convite morre.

A senha nunca viaja no anúncio e não fica guardada: só um hash, enquanto o convite vive.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import socket
import threading
import time
from dataclasses import dataclass, field
from typing import Optional

from sqlmodel import Session, select

from app.models import Device, utcnow

BROADCAST_PORT = 47890
INVITE_TTL = 180          # segundos que o convite vale
ANNOUNCE_EVERY = 2.0      # segundos entre anúncios
MAX_ATTEMPTS = 5


@dataclass
class Invite:
    id: str
    name: str
    salt: bytes
    password_hash: bytes
    address: str
    expires_at: float
    attempts: int = 0
    device_id: Optional[str] = None     # preenchido quando aprovado
    device_name: Optional[str] = None
    error: Optional[str] = None
    lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    @property
    def status(self) -> str:
        if self.device_id:
            return "aprovado"
        if self.error:
            return "falhou"
        if time.time() > self.expires_at:
            return "expirado"
        return "aguardando"

    @property
    def seconds_left(self) -> int:
        return max(0, int(self.expires_at - time.time()))


def _hash(password: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 100_000)


_invites: dict[str, Invite] = {}
_invites_lock = threading.Lock()


def create_invite(name: str, password: str, address: str) -> Invite:
    """Cancela convites anteriores (só um por vez) e começa a anunciar o novo."""
    salt = secrets.token_bytes(16)
    invite = Invite(
        id=secrets.token_urlsafe(9),
        name=name.strip() or "Celular",
        salt=salt,
        password_hash=_hash(password, salt),
        address=address,
        expires_at=time.time() + INVITE_TTL,
    )
    with _invites_lock:
        for old in _invites.values():
            if old.status == "aguardando":
                old.error = "substituído por um convite novo"
        _invites[invite.id] = invite
    threading.Thread(target=_announce, args=(invite,), daemon=True, name=f"yoru-convite-{invite.id}").start()
    return invite


def get_invite(invite_id: str) -> Optional[Invite]:
    with _invites_lock:
        return _invites.get(invite_id)


def cancel_invite(invite_id: str) -> None:
    invite = get_invite(invite_id)
    if invite and invite.status == "aguardando":
        invite.error = "cancelado"


def _announce(invite: Invite) -> None:
    """Manda o convite para a rede toda a cada 2 s, até ser aprovado, cancelado ou vencer."""
    payload = json.dumps(
        {"yoru": 1, "convite": invite.id, "nome": invite.name, "endereco": invite.address},
        ensure_ascii=False,
    ).encode("utf-8")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        while invite.status == "aguardando":
            for target in broadcast_targets(invite.address):
                try:
                    sock.sendto(payload, (target, BROADCAST_PORT))
                except OSError:
                    pass
            time.sleep(ANNOUNCE_EVERY)
    finally:
        sock.close()


def broadcast_targets(address: str) -> list[str]:
    """255.255.255.255 e o broadcast da sub-rede /24 do IP anunciado (alguns roteadores só passam este)."""
    targets = ["255.255.255.255"]
    host = address.split("://", 1)[-1].split(":", 1)[0]
    parts = host.split(".")
    if len(parts) == 4 and all(p.isdigit() for p in parts):
        targets.append(".".join(parts[:3] + ["255"]))
    return targets


def approve(session: Session, invite_id: str, password: str, device_name: Optional[str]) -> tuple[Optional[Device], str]:
    """Confere a senha. Devolve (aparelho, mensagem). Aparelho None = recusado."""
    invite = get_invite(invite_id)
    if invite is None:
        return None, "Convite desconhecido. Envie a solicitação de novo no computador."
    with invite.lock:
        status = invite.status
        if status == "aprovado":
            return None, "Este convite já foi usado."
        if status != "aguardando":
            detail = f" ({invite.error})" if invite.error else ""
            return None, f"Convite {status}{detail}. Envie a solicitação de novo no computador."
        if not hmac.compare_digest(_hash(password, invite.salt), invite.password_hash):
            invite.attempts += 1
            left = MAX_ATTEMPTS - invite.attempts
            if left <= 0:
                invite.error = "senha errada 5 vezes"
                return None, "Senha errada 5 vezes. Envie a solicitação de novo no computador."
            return None, f"Senha errada. {'Resta 1 tentativa' if left == 1 else f'Restam {left} tentativas'}."
        device = Device(name=(device_name or invite.name).strip()[:60] or invite.name, token=secrets.token_urlsafe(24))
        session.add(device)
        session.commit()
        session.refresh(device)
        invite.device_id = device.id
        invite.device_name = device.name
        return device, "Aparelho conectado."


def device_for_token(session: Session, token: str) -> Optional[Device]:
    """Procura o aparelho dono do token, em tempo constante por candidato."""
    if not token:
        return None
    for device in session.exec(select(Device).where(Device.revoked_at == None)).all():  # noqa: E711
        if hmac.compare_digest(device.token.encode(), token.encode()):
            return device
    return None


def touch(session: Session, device: Device) -> None:
    device.last_seen_at = utcnow()
    session.add(device)
    session.commit()


def devices(session: Session) -> list[Device]:
    return list(session.exec(select(Device).where(Device.revoked_at == None).order_by(Device.created_at)).all())  # noqa: E711


def revoke(session: Session, device_id: str) -> None:
    device = session.get(Device, device_id)
    if device and device.revoked_at is None:
        device.revoked_at = utcnow()
        session.add(device)
        session.commit()
