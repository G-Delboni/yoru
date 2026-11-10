"""Página /ias: limites de sessão e da semana das assinaturas, e gasto da API."""

from typing import Optional

from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlmodel import Session

from app import services_ia
from app.database import get_session
from app.routers.pages import render
from app.templating import templates

router = APIRouter(include_in_schema=False)


def check(provider: str) -> str:
    if provider not in services_ia.PROVIDERS:
        raise HTTPException(404)
    return provider


def panel_context(provider: str, st: services_ia.Status) -> dict:
    return {"p": provider, "st": st, "local_ok": services_ia.local_login_available(provider)}


@router.get("/ias", response_class=HTMLResponse)
def page_ai(request: Request, session: Session = Depends(get_session), erro: Optional[str] = None):
    statuses = services_ia.all_status()
    claude = statuses["claude"]
    return render(
        request, "ias.html", session, page="ias", statuses=statuses, erro=erro,
        local_logins={p: services_ia.local_login_available(p) for p in services_ia.PROVIDERS},
        destaque=claude.limit("weekly") if claude.linked else None,
        sessao=claude.limit("session") if claude.linked else None,
    )


@router.get("/hx/ias/{provider}", response_class=HTMLResponse)
def panel(request: Request, provider: str):
    """Trecho de cada painel; a página o recarrega sozinha a cada 2 minutos."""
    st = services_ia.status(check(provider))
    return templates.TemplateResponse(request, "partials/ia_painel.html", panel_context(provider, st))


@router.post("/hx/ias/{provider}/atualizar", response_class=HTMLResponse)
def refresh(request: Request, provider: str):
    st = services_ia.status(check(provider), force=True)
    return templates.TemplateResponse(request, "partials/ia_painel.html", panel_context(provider, st))


@router.post("/ias/{provider}/vincular")
def link(provider: str, mode: str = Form(...), token: str = Form(""), account_id: str = Form("")):
    check(provider)
    if mode == "local":
        if provider == "anthropic" or not services_ia.local_login_available(provider):
            return RedirectResponse(f"/ias?erro=sem-login-{provider}", status_code=303)
    elif mode == "token":
        if not token.strip():
            return RedirectResponse(f"/ias?erro=sem-token-{provider}", status_code=303)
    else:
        raise HTTPException(400)
    services_ia.link(provider, mode, token, account_id)
    return RedirectResponse(f"/ias#ia-{provider}", status_code=303)


@router.post("/ias/{provider}/desvincular")
def unlink(provider: str):
    services_ia.unlink(check(provider))
    return RedirectResponse(f"/ias#ia-{provider}", status_code=303)
