"""Resumo por área e "Começar o dia".

Cada área pode ser encerrada sozinha (o que fiz, onde parei, próximo passo), quantas
vezes quiser no mesmo dia: a segunda vez atualiza o resumo de hoje. No dia seguinte,
a tela Hoje mostra o último resumo pendente de cada área; "Começar o dia" transforma
os próximos passos marcados em tarefas de hoje e dispensa os outros.
"""

from typing import Optional

from fastapi import APIRouter, Depends, Form, HTTPException
from fastapi.responses import RedirectResponse
from sqlmodel import Session

from app import crud, services, timeutil
from app.database import get_session
from app.models import Area, LogEntry, Task, TaskStatus, utcnow
from app.routers.pages import opt

router = APIRouter(include_in_schema=False)


def back(to: str) -> RedirectResponse:
    return RedirectResponse(to if to.startswith("/") else "/", status_code=303)


@router.post("/areas/{area_id}/encerrar")
def close_area(
    area_id: str,
    session: Session = Depends(get_session),
    did: str = Form(""),
    stopped_at: str = Form(""),
    next_step: str = Form(""),
    minutes: str = Form(""),
    voltar: str = Form("/"),
):
    """Resumo da área para hoje. Se a área já foi encerrada hoje, atualiza o mesmo resumo."""
    crud.get_or_404(session, Area, area_id)
    if not (opt(did) or opt(stopped_at) or opt(next_step)):
        return back(voltar)
    entry = services.today_log(session, area_id) or LogEntry(area_id=area_id, day=timeutil.today())
    entry.did = opt(did)
    entry.stopped_at = opt(stopped_at)
    entry.next_step = opt(next_step)
    entry.minutes = int(minutes) if minutes.strip().isdigit() else None
    entry.resumed_at = None          # resumo novo: volta a aparecer amanhã
    entry.resumed_task_id = None
    session.add(entry)
    session.commit()
    return back(voltar)


def _resume(session: Session, log: LogEntry, status: TaskStatus) -> Optional[Task]:
    """Próximo passo vira tarefa de hoje na mesma área; o resumo sai do "Começar o dia"."""
    title = log.next_step or (f"Continuar: {log.stopped_at}" if log.stopped_at else None)
    task = None
    if title:
        task = Task(title=title[:300], area_id=log.area_id, status=status, scheduled_for=timeutil.today())
        session.add(task)
        session.flush()
    log.resumed_at = utcnow()
    log.resumed_task_id = task.id if task else None
    session.add(log)
    return task


def get_log(session: Session, log_id: str) -> LogEntry:
    return crud.get_or_404(session, LogEntry, log_id)


@router.post("/dia/retomar/{log_id}")
def resume_one(log_id: str, session: Session = Depends(get_session), voltar: str = Form("/")):
    log = get_log(session, log_id)
    if log.resumed_at is None:
        _resume(session, log, TaskStatus.doing)
        session.commit()
    return back(voltar)


@router.post("/dia/dispensar/{log_id}")
def dismiss_one(log_id: str, session: Session = Depends(get_session), voltar: str = Form("/")):
    log = get_log(session, log_id)
    if log.resumed_at is None:
        log.resumed_at = utcnow()
        session.add(log)
        session.commit()
    return back(voltar)


@router.post("/dia/comecar")
def start_day(session: Session = Depends(get_session), retomar: list[str] = Form(default=[])):
    """Marcados viram tarefas de hoje (o primeiro já "fazendo"); os outros são dispensados."""
    pending = [s for s in services.area_summaries(session) if s.pending]
    if not pending:
        return back("/")
    chosen = set(retomar)
    first = True
    for s in pending:
        if s.log.id in chosen:
            _resume(session, s.log, TaskStatus.doing if first else TaskStatus.todo)
            first = False
        else:
            s.log.resumed_at = utcnow()
            session.add(s.log)
    session.commit()
    return back("/")
