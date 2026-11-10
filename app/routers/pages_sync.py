"""Página /sincronia: parear aparelhos, ver o histórico e revogar acessos."""

from typing import Optional

from fastapi import APIRouter, Depends, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlmodel import Session

from app import pairing, sync
from app.database import get_session
from app.routers.pages import render
from app.templating import templates

router = APIRouter(include_in_schema=False)


def server_address(request: Request) -> tuple[Optional[str], int]:
    ip = sync.local_address()
    port = request.url.port or 8000
    return (f"http://{ip}:{port}" if ip else None), port


@router.get("/sincronia", response_class=HTMLResponse)
def page_sync(request: Request, session: Session = Depends(get_session), convite: Optional[str] = None):
    address, port = server_address(request)
    return render(
        request, "sincronia.html", session, page="sincronia",
        token=sync.get_token(), address=address, port=port,
        invite=pairing.get_invite(convite) if convite else None,
        devices=pairing.devices(session),
        syncs=sync.last_syncs(session),
    )


@router.post("/sincronia/aparelhos")
def create_device_invite(request: Request, name: str = Form(...), password: str = Form(...)):
    address, _ = server_address(request)
    if not address:
        return RedirectResponse("/sincronia?erro=sem-rede", status_code=303)
    invite = pairing.create_invite(name, password, address)
    return RedirectResponse(f"/sincronia?convite={invite.id}", status_code=303)


@router.post("/sincronia/convites/{invite_id}/cancelar")
def cancel_invite(invite_id: str):
    pairing.cancel_invite(invite_id)
    return RedirectResponse("/sincronia", status_code=303)


@router.get("/hx/convite/{invite_id}", response_class=HTMLResponse)
def invite_status(request: Request, invite_id: str):
    """Trecho que a página recarrega a cada 2 s enquanto o convite aguarda."""
    return templates.TemplateResponse(request, "partials/convite.html", {"invite": pairing.get_invite(invite_id)})


@router.post("/sincronia/aparelhos/{device_id}/revogar")
def revoke_device(device_id: str, session: Session = Depends(get_session)):
    pairing.revoke(session, device_id)
    return RedirectResponse("/sincronia", status_code=303)


@router.post("/sincronia/novo-token")
def new_token():
    sync.new_token()
    return RedirectResponse("/sincronia", status_code=303)
