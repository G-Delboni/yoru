"""Páginas HTML e trechos que o yoru.js troca sem recarregar a página.

Convenção:
    GET  /...        páginas inteiras
    POST /hx/...     ações; devolvem só o trecho de HTML que muda
Cabeçalhos de resposta lidos pelo yoru.js:
    X-Yoru-Trigger   eventos para atualizar outras partes (ex. "tarefas")
    X-Yoru-Redirect  navegar para outra página
"""

from datetime import date
from typing import Optional

from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlmodel import Session

from app import crud, services, timeutil
from app.database import get_session
from app.models import LogEntry, Priority, Task, TaskStatus, utcnow
from app.templating import templates

router = APIRouter(include_in_schema=False)

TRIGGER_TASKS = {"X-Yoru-Trigger": "tarefas"}


def render(request: Request, name: str, session: Session, status_code: int = 200,
           headers: Optional[dict] = None, **ctx) -> HTMLResponse:
    context = services.base_context(session) | ctx
    return templates.TemplateResponse(request, name, context, status_code=status_code, headers=headers)


def opt(value: Optional[str]) -> Optional[str]:
    """Campos de formulário vazios viram None."""
    value = (value or "").strip()
    return value or None


# --- Páginas -----------------------------------------------------------------


@router.get("/", response_class=HTMLResponse)
def page_today(request: Request, session: Session = Depends(get_session)):
    return render(request, "hoje.html", session, page="hoje", **services.today_view(session))


@router.get("/caixa", response_class=HTMLResponse)
def page_inbox(request: Request, session: Session = Depends(get_session)):
    return render(request, "caixa.html", session, page="caixa", tasks=services.inbox_tasks(session))


@router.get("/diario", response_class=HTMLResponse)
def page_journal(request: Request, session: Session = Depends(get_session)):
    return render(request, "diario.html", session, page="diario", days=services.logs_by_day(session))


@router.get("/encerrar", response_class=HTMLResponse)
def page_close_day(request: Request, session: Session = Depends(get_session)):
    open_, done = services.today_tasks(session)
    last = services.last_log(session)
    return render(
        request, "encerrar.html", session, page="encerrar",
        open_tasks=open_, done_tasks=done,
        default_area_id=last.area_id if last else None,
    )


@router.post("/encerrar")
def close_day(
    session: Session = Depends(get_session),
    area_id: str = Form(...),
    did: str = Form(""),
    stopped_at: str = Form(""),
    next_step: str = Form(""),
    minutes: str = Form(""),
    carry_over: bool = Form(False),
):
    entry = LogEntry(
        area_id=area_id,
        day=timeutil.today(),
        did=opt(did),
        stopped_at=opt(stopped_at),
        next_step=opt(next_step),
        minutes=int(minutes) if minutes.strip().isdigit() else None,
    )
    session.add(entry)

    if carry_over:
        amanha = timeutil.tomorrow()
        open_, _ = services.today_tasks(session)
        for t in open_:
            if t.status == TaskStatus.todo and (t.due_date is None or t.due_date > timeutil.today()):
                t.scheduled_for = amanha
                t.updated_at = utcnow()
                session.add(t)

    session.commit()
    return RedirectResponse("/diario", status_code=303)


# --- Trechos: tarefas --------------------------------------------------------


def task_row(request: Request, session: Session, task: Task, variant: str, **headers) -> HTMLResponse:
    return render(
        request, "partials/task_row.html", session,
        headers=TRIGGER_TASKS | headers, task=task, variant=variant,
    )


def get_task(session: Session, task_id: str) -> Task:
    return crud.get_or_404(session, Task, task_id)


def touch(session: Session, task: Task) -> Task:
    task.updated_at = utcnow()
    session.add(task)
    session.commit()
    session.refresh(task)
    return task


@router.post("/hx/capturar", response_class=HTMLResponse)
def capture(
    request: Request,
    session: Session = Depends(get_session),
    title: str = Form(...),
    variant: str = Form("aviso"),
    area_id: str = Form(""),
    for_today: bool = Form(False),
    due_date: str = Form(""),
):
    title = title.strip()
    if not title:
        return HTMLResponse('<span class="aviso aviso-erro">Escreva algo antes de guardar.</span>', status_code=422)

    task = Task(
        title=title,
        area_id=opt(area_id),
        scheduled_for=timeutil.today() if for_today else None,
        due_date=date.fromisoformat(due_date) if opt(due_date) else None,
    )
    session.add(task)
    session.commit()
    session.refresh(task)

    if variant == "aviso":
        destino = "para hoje" if for_today else "na caixa de entrada"
        return HTMLResponse(
            f'<span class="aviso">Guardado {destino}.</span>',
            headers=TRIGGER_TASKS,
        )
    return task_row(request, session, task, variant)


@router.post("/hx/tarefas/{task_id}/concluir", response_class=HTMLResponse)
def toggle_done(task_id: str, request: Request, variant: str = Form("lista"),
                session: Session = Depends(get_session)):
    task = get_task(session, task_id)
    if task.status == TaskStatus.done:
        task.status = TaskStatus.todo
        task.completed_at = None
    else:
        task.status = TaskStatus.done
        task.completed_at = utcnow()
    return task_row(request, session, touch(session, task), variant)


@router.post("/hx/tarefas/{task_id}/fazendo", response_class=HTMLResponse)
def toggle_doing(task_id: str, request: Request, variant: str = Form("lista"),
                 session: Session = Depends(get_session)):
    task = get_task(session, task_id)
    task.status = TaskStatus.todo if task.status == TaskStatus.doing else TaskStatus.doing
    task.completed_at = None
    return task_row(request, session, touch(session, task), variant)


@router.post("/hx/tarefas/{task_id}/hoje", response_class=HTMLResponse)
def toggle_today(task_id: str, request: Request, variant: str = Form("lista"),
                 session: Session = Depends(get_session)):
    task = get_task(session, task_id)
    hoje = timeutil.today()
    task.scheduled_for = None if (task.scheduled_for and task.scheduled_for <= hoje) else hoje
    return task_row(request, session, touch(session, task), variant)


@router.post("/hx/tarefas/{task_id}/prioridade", response_class=HTMLResponse)
def toggle_priority(task_id: str, request: Request, variant: str = Form("lista"),
                    session: Session = Depends(get_session)):
    task = get_task(session, task_id)
    task.priority = Priority.normal if task.priority == Priority.high else Priority.high
    return task_row(request, session, touch(session, task), variant)


@router.post("/hx/tarefas/{task_id}/area", response_class=HTMLResponse)
def move_to_area(task_id: str, area_id: str = Form(...), session: Session = Depends(get_session)):
    """Organiza um item da caixa de entrada: some da lista."""
    task = get_task(session, task_id)
    task.area_id = area_id
    touch(session, task)
    return HTMLResponse("", headers=TRIGGER_TASKS)


@router.post("/hx/tarefas/{task_id}/excluir", response_class=HTMLResponse)
def delete_task(task_id: str, session: Session = Depends(get_session)):
    crud.soft_delete(session, get_task(session, task_id))
    return HTMLResponse("", headers=TRIGGER_TASKS)


@router.post("/hx/retomar", response_class=HTMLResponse)
def resume(session: Session = Depends(get_session)):
    """Transforma o 'próximo passo' do último registro em tarefa de hoje, já em andamento."""
    log = services.last_log(session)
    if log is None or not log.next_step:
        return HTMLResponse("", headers={"X-Yoru-Redirect": "/"})
    task = Task(
        title=log.next_step,
        area_id=log.area_id,
        status=TaskStatus.doing,
        scheduled_for=timeutil.today(),
    )
    session.add(task)
    session.flush()
    log.resumed_at = utcnow()
    log.resumed_task_id = task.id
    session.add(log)
    session.commit()
    return HTMLResponse("", headers={"X-Yoru-Redirect": "/"})


# --- Trechos: partes que se atualizam sozinhas -------------------------------


@router.get("/hx/enso", response_class=HTMLResponse)
def enso_fragment(request: Request, session: Session = Depends(get_session)):
    open_, done = services.today_tasks(session)
    return render(request, "partials/enso_hoje.html", session, enso=services.enso(len(open_), len(done)))


@router.get("/hx/contador-caixa", response_class=HTMLResponse)
def inbox_counter(request: Request, session: Session = Depends(get_session)):
    return render(request, "partials/inbox_counter.html", session)


@router.get("/hx/caixa-previa", response_class=HTMLResponse)
def inbox_preview(request: Request, session: Session = Depends(get_session)):
    return render(request, "partials/inbox_preview.html", session,
                  inbox_preview=services.inbox_tasks(session)[:4])
