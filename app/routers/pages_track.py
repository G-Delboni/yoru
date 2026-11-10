"""Páginas da Fase 5: Foco (Pomodoro), Hábitos e Progresso."""

from datetime import date, datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel
from sqlmodel import Session

from app import crud, services, services_track, timeutil
from app.database import get_session
from app.models import FocusSession, Habit, utcnow
from app.routers.pages import opt, render
from app.templating import templates

router = APIRouter(include_in_schema=False)


# --- Foco --------------------------------------------------------------------------


@router.get("/foco", response_class=HTMLResponse)
def page_focus(request: Request, session: Session = Depends(get_session)):
    return render(request, "foco.html", session, page="foco", **services_track.focus_view(session))


class SessionIn(BaseModel):
    kind: str = "focus"
    started_at: str                      # ISO UTC vindo do navegador
    planned_minutes: int
    minutes: int
    completed: bool = False
    area_id: Optional[str] = None
    task_id: Optional[str] = None
    note: Optional[str] = None


@router.post("/foco/sessoes", response_class=HTMLResponse)
def save_focus_session(body: SessionIn, request: Request, session: Session = Depends(get_session)):
    """Chamado pelo foco.js quando uma sessão termina ou é encerrada. Devolve a lista do dia."""
    try:
        started = datetime.fromisoformat(body.started_at.replace("Z", "+00:00")).astimezone(timezone.utc)
    except ValueError:
        raise HTTPException(422, "started_at inválido")
    if body.minutes < 1 and not body.completed:
        return _focus_list(request, session)  # menos de um minuto: não vale registrar
    session.add(FocusSession(
        kind="break" if body.kind == "break" else "focus",
        started_at=started, ended_at=utcnow(),
        planned_minutes=max(1, body.planned_minutes), minutes=max(0, body.minutes),
        completed=body.completed, area_id=opt(body.area_id), task_id=opt(body.task_id), note=opt(body.note),
    ))
    session.commit()
    return _focus_list(request, session, headers={"X-Yoru-Trigger": "foco"})


def _focus_list(request: Request, session: Session, headers: Optional[dict] = None) -> HTMLResponse:
    ctx = services.base_context(session) | services_track.focus_view(session)
    return templates.TemplateResponse(request, "partials/foco_lista.html", ctx, headers=headers)


@router.get("/hx/foco/lista", response_class=HTMLResponse)
def focus_list(request: Request, session: Session = Depends(get_session)):
    return _focus_list(request, session)


@router.post("/foco/sessoes/{session_id}/excluir")
def delete_focus_session(session_id: str, session: Session = Depends(get_session)):
    crud.soft_delete(session, crud.get_or_404(session, FocusSession, session_id))
    return RedirectResponse("/foco", status_code=303)


# --- Hábitos -----------------------------------------------------------------------


@router.get("/habitos", response_class=HTMLResponse)
def page_habits(request: Request, session: Session = Depends(get_session)):
    return render(request, "habitos.html", session, page="habitos", **services_track.habits_view(session))


@router.post("/habitos")
def create_habit(
    session: Session = Depends(get_session),
    name: str = Form(...),
    kanji: str = Form(""),
    days_per_week: int = Form(7),
    area_id: str = Form(""),
    voltar: str = Form("/habitos"),
):
    position = len(services_track.habits(session, include_archived=True))
    session.add(Habit(name=name.strip() or "Hábito", kanji=opt(kanji)[:2] if opt(kanji) else None,
                      days_per_week=min(7, max(1, days_per_week)), area_id=opt(area_id), position=position))
    session.commit()
    return RedirectResponse(voltar, status_code=303)


def get_habit(session: Session, habit_id: str) -> Habit:
    return crud.get_or_404(session, Habit, habit_id)


@router.post("/hx/habitos/{habit_id}/marcar", response_class=HTMLResponse)
def toggle_habit(habit_id: str, request: Request, session: Session = Depends(get_session),
                 day: str = Form(""), variant: str = Form("linha")):
    """Marca/desmarca um dia e devolve a linha do hábito redesenhada."""
    habit = get_habit(session, habit_id)
    d = date.fromisoformat(day) if opt(day) else timeutil.today()
    if d > timeutil.today():
        raise HTTPException(422, "Não dá para marcar o futuro")
    services_track.toggle_habit(session, habit, d)
    ctx = services.base_context(session) | {"row": services_track.habit_row(session, habit), "variant": variant}
    return templates.TemplateResponse(request, "partials/habito_linha.html", ctx, headers={"X-Yoru-Trigger": "habitos"})


@router.get("/hx/habitos/hoje", response_class=HTMLResponse)
def habits_today(request: Request, session: Session = Depends(get_session)):
    ctx = services.base_context(session) | services_track.habits_view(session)
    return templates.TemplateResponse(request, "partials/habitos_hoje.html", ctx)


@router.post("/habitos/{habit_id}/estado")
def habit_state(habit_id: str, acao: str = Form(...), session: Session = Depends(get_session)):
    habit = get_habit(session, habit_id)
    if acao == "excluir":
        crud.soft_delete(session, habit)
    else:
        habit.archived_at = None if acao == "reativar" else utcnow()
        habit.updated_at = utcnow()
        session.add(habit)
        session.commit()
    return RedirectResponse("/habitos", status_code=303)


# --- Progresso ---------------------------------------------------------------------


@router.get("/progresso", response_class=HTMLResponse)
def page_tracking(request: Request, session: Session = Depends(get_session), periodo: str = "semana"):
    track = services_track.tracking_view(session, periodo)
    return render(request, "progresso.html", session, page="progresso", track=track,
                  periods=services_track.PERIODS, habits_total=len(services_track.habits(session)))
