"""Páginas das áreas e os formulários que as alimentam.

Formulários de criação usam POST comum e voltam para a página de origem (303),
o que funciona sem JavaScript.
"""

from datetime import date, datetime, time
from typing import Optional

from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlmodel import Session

from app import crud, services, services_areas, timeutil
from app.database import get_session
from app.models import (
    Event,
    EventKind,
    Goal,
    LogEntry,
    Objective,
    Resource,
    ResourceKind,
    ResourceStatus,
    utcnow,
)
from app.routers.pages import opt, render

router = APIRouter(include_in_schema=False)


def back(to: str) -> RedirectResponse:
    # Só caminhos internos: evita redirecionar para fora do Yoru
    if not to.startswith("/") or to.startswith("//"):
        to = "/"
    return RedirectResponse(to, status_code=303)


def num(value: Optional[str]) -> Optional[float]:
    value = (value or "").strip().replace(",", ".")
    try:
        return float(value) if value else None
    except ValueError:
        return None


def day(value: Optional[str]) -> Optional[date]:
    value = opt(value)
    return date.fromisoformat(value) if value else None


def save(session: Session, *items) -> None:
    for item in items:
        if hasattr(item, "updated_at"):
            item.updated_at = utcnow()
        session.add(item)
    session.commit()


# --- Página da área: escolhe a tela pelo slug --------------------------------

SPECIAL = {
    "faculdade": ("faculdade.html", services_areas.faculdade_view),
    "leituras": ("leituras.html", services_areas.leituras_view),
    "estudos": ("estudos.html", services_areas.estudos_view),
}


@router.get("/area/{slug}", response_class=HTMLResponse)
def page_area(slug: str, request: Request, session: Session = Depends(get_session)):
    area = services.area_by_slug(session, slug)
    if area is None:
        raise HTTPException(404, "Área não encontrada")
    base = services.base_context(session)
    common = dict(page=f"area:{slug}", area=area, log=services.last_log(session, area.id),
                  today_log=services.today_log(session, area.id))

    if slug in SPECIAL:
        template, view = SPECIAL[slug]
        return render(request, template, session, **common, **view(session, area))

    return render(
        request, "area.html", session, **common,
        **services.area_tasks(session, area.id),
        objective=services_areas.area_objective(session, area.id, base["areas_by_id"]),
        learning=services_areas.area_learning(session, area.id),
        fraction=services_areas.fraction,
    )


@router.get("/metas", response_class=HTMLResponse)
def page_goals(request: Request, session: Session = Depends(get_session)):
    base = services.base_context(session)
    return render(request, "metas.html", session, page="metas",
                  **services_areas.metas_view(session, base["areas_by_id"]))


# --- Recursos (disciplinas, livros, assuntos) --------------------------------


@router.post("/recursos")
def create_resource(
    session: Session = Depends(get_session),
    voltar: str = Form("/"),
    area_id: str = Form(...),
    kind: ResourceKind = Form(...),
    title: str = Form(...),
    author: str = Form(""),
    url: str = Form(""),
    schedule: str = Form(""),
    progress_total: str = Form(""),
    progress_current: str = Form(""),
    progress_unit: str = Form(""),
    status: ResourceStatus = Form(ResourceStatus.active),
):
    title = title.strip()
    if title:
        r = Resource(
            area_id=area_id, kind=kind, title=title, status=status,
            author=opt(author), url=opt(url), schedule=opt(schedule),
            progress_total=num(progress_total), progress_unit=opt(progress_unit),
            progress_current=num(progress_current) or 0,
            started_at=timeutil.today() if status == ResourceStatus.active else None,
        )
        save(session, r)
    return back(voltar)


@router.post("/recursos/{resource_id}/estado")
def set_resource_status(resource_id: str, status: ResourceStatus = Form(...), voltar: str = Form("/"),
                        session: Session = Depends(get_session)):
    r = crud.get_or_404(session, Resource, resource_id)
    r.status = status
    if status == ResourceStatus.active and not r.started_at:
        r.started_at = timeutil.today()
    if status == ResourceStatus.finished:
        r.finished_at = timeutil.today()
        if r.progress_total:
            r.progress_current = r.progress_total
    else:
        r.finished_at = None
    save(session, r)
    return back(voltar)


@router.post("/recursos/{resource_id}/nota")
def set_grade(resource_id: str, grade: str = Form(""), voltar: str = Form("/"),
              session: Session = Depends(get_session)):
    r = crud.get_or_404(session, Resource, resource_id)
    r.grade = num(grade)
    save(session, r)
    return back(voltar)


@router.post("/recursos/{resource_id}/excluir")
def delete_resource(resource_id: str, voltar: str = Form("/"), session: Session = Depends(get_session)):
    crud.soft_delete(session, crud.get_or_404(session, Resource, resource_id))
    return back(voltar)


@router.post("/recursos/{resource_id}/progresso")
def register_progress(
    resource_id: str,
    session: Session = Depends(get_session),
    voltar: str = Form("/"),
    current: str = Form(""),      # leitura: em que página você está agora
    advance: str = Form(""),      # estudo: quantas unidades avançou nesta sessão
    minutes: str = Form(""),
    note: str = Form(""),
):
    """Registra uma sessão: atualiza o progresso e grava no diário quanto avançou."""
    r = crud.get_or_404(session, Resource, resource_id)
    before = r.progress_current or 0
    if num(current) is not None:
        r.progress_current = num(current)
    elif num(advance) is not None:
        r.progress_current = before + num(advance)
    if r.progress_total:
        r.progress_current = min(r.progress_current, r.progress_total)
    r.progress_current = max(0, r.progress_current)
    moved = r.progress_current - before

    if r.status != ResourceStatus.active and moved > 0:
        r.status = ResourceStatus.active
        r.started_at = r.started_at or timeutil.today()
    if r.progress_total and r.progress_current >= r.progress_total:
        r.status = ResourceStatus.finished
        r.finished_at = timeutil.today()

    mins = int(num(minutes)) if num(minutes) else None
    if moved > 0 or mins or opt(note):
        unidade = r.progress_unit or ("páginas" if r.kind == ResourceKind.book else "")
        if moved == 1 and unidade.endswith("s"):
            unidade = unidade[:-1]  # "1 módulo", "1 página"
        did = opt(note) or (f"{r.title}: {moved:g} {unidade}".strip() if moved > 0 else r.title)
        session.add(LogEntry(area_id=r.area_id, resource_id=r.id, day=timeutil.today(),
                             did=did, amount=moved if moved > 0 else None, minutes=mins))
    save(session, r)
    return back(voltar)


# --- Eventos (provas, entregas) ------------------------------------------------


@router.post("/eventos")
def create_event(
    session: Session = Depends(get_session),
    voltar: str = Form("/"),
    area_id: str = Form(...),
    title: str = Form(...),
    kind: EventKind = Form(EventKind.exam),
    on: str = Form(...),
    at: str = Form(""),
    resource_id: str = Form(""),
    notes: str = Form(""),
):
    hora = time.fromisoformat(at) if opt(at) else time(0, 0)
    e = Event(area_id=area_id, title=title.strip() or "Sem título", kind=kind,
              starts_at=datetime.combine(date.fromisoformat(on), hora),
              resource_id=opt(resource_id), notes=opt(notes))
    save(session, e)
    return back(voltar)


@router.post("/eventos/{event_id}/excluir")
def delete_event(event_id: str, voltar: str = Form("/"), session: Session = Depends(get_session)):
    crud.soft_delete(session, crud.get_or_404(session, Event, event_id))
    return back(voltar)


# --- Objetivos e metas -------------------------------------------------------


@router.post("/objetivos")
def create_objective(
    session: Session = Depends(get_session),
    voltar: str = Form("/metas"),
    area_id: str = Form(...),
    title: str = Form(...),
    due_date: str = Form(""),
    description: str = Form(""),
):
    if title.strip():
        save(session, Objective(area_id=area_id, title=title.strip(), due_date=day(due_date),
                                description=opt(description)))
    return back(voltar)


@router.post("/objetivos/{objective_id}/estado")
def objective_state(objective_id: str, acao: str = Form(...), voltar: str = Form("/metas"),
                    session: Session = Depends(get_session)):
    o = crud.get_or_404(session, Objective, objective_id)
    if acao == "excluir":
        crud.soft_delete(session, o)
        return back(voltar)
    o.is_done = acao == "concluir"
    o.is_paused = acao == "pausar"
    save(session, o)
    return back(voltar)


@router.post("/metas")
def create_goal(
    session: Session = Depends(get_session),
    voltar: str = Form("/metas"),
    area_id: str = Form(...),
    title: str = Form(...),
    objective_id: str = Form(""),
    target_value: str = Form(""),
    unit: str = Form(""),
    due_date: str = Form(""),
):
    if title.strip():
        save(session, Goal(area_id=area_id, title=title.strip(), objective_id=opt(objective_id),
                           target_value=num(target_value), unit=opt(unit), due_date=day(due_date)))
    return back(voltar)


@router.post("/metas/{goal_id}/valor")
def goal_value(goal_id: str, value: str = Form(""), voltar: str = Form("/metas"),
               session: Session = Depends(get_session)):
    g = crud.get_or_404(session, Goal, goal_id)
    if num(value) is not None:
        g.current_value = num(value)
        if g.target_value and g.current_value >= g.target_value:
            g.is_done = True
    save(session, g)
    return back(voltar)


@router.post("/metas/{goal_id}/estado")
def goal_state(goal_id: str, acao: str = Form(...), voltar: str = Form("/metas"),
               session: Session = Depends(get_session)):
    g = crud.get_or_404(session, Goal, goal_id)
    if acao == "excluir":
        crud.soft_delete(session, g)
        return back(voltar)
    g.is_done = acao == "concluir"
    save(session, g)
    return back(voltar)
