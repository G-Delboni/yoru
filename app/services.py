"""Consultas que montam o que cada tela mostra.

As rotas de página ficam finas: chamam estas funções e entregam o resultado ao template.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from typing import Optional

from sqlmodel import Session, func, select

from app import services_track, timeutil
from app.models import (
    Area,
    Event,
    LogEntry,
    Priority,
    Resource,
    ResourceKind,
    ResourceStatus,
    SyncLog,
    Task,
    TaskStatus,
)

ALIVE_TASK = Task.deleted_at == None  # noqa: E711
PRIORITY_ORDER = {Priority.high: 0, Priority.normal: 1, Priority.low: 2}


# --- Áreas -------------------------------------------------------------------


def areas(session: Session) -> list[Area]:
    stmt = select(Area).where(Area.deleted_at == None).order_by(Area.position)  # noqa: E711
    return list(session.exec(stmt).all())


def area_by_slug(session: Session, slug: str) -> Optional[Area]:
    stmt = select(Area).where(Area.slug == slug, Area.deleted_at == None)  # noqa: E711
    return session.exec(stmt).first()


def inbox_count(session: Session) -> int:
    stmt = select(func.count()).select_from(Task).where(
        ALIVE_TASK, Task.area_id == None, Task.status != TaskStatus.done  # noqa: E711
    )
    return session.exec(stmt).one()


def base_context(session: Session) -> dict:
    """O que toda página precisa: navegação, áreas por id, contador da caixa."""
    all_areas = areas(session)
    return {
        "areas": all_areas,
        "areas_by_id": {a.id: a for a in all_areas},
        "inbox_count": inbox_count(session),
        "hoje": timeutil.today(),
        "ultima_sync": session.exec(select(SyncLog).order_by(SyncLog.at.desc())).first(),
    }


# --- Tarefas -----------------------------------------------------------------


def sort_open(tasks: list[Task]) -> list[Task]:
    """Fazendo primeiro, depois prioridade, depois prazo mais próximo."""
    far = date.max
    return sorted(
        tasks,
        key=lambda t: (
            t.status != TaskStatus.doing,
            PRIORITY_ORDER.get(t.priority, 1),
            t.due_date or far,
            t.position,
            t.created_at,
        ),
    )


def is_for_today(t: Task, hoje: date) -> bool:
    if t.status == TaskStatus.done:
        return timeutil.local_date(t.completed_at) == hoje
    return (
        t.status == TaskStatus.doing
        or (t.scheduled_for is not None and t.scheduled_for <= hoje)
        or (t.due_date is not None and t.due_date <= hoje)
    )


def today_tasks(session: Session) -> tuple[list[Task], list[Task]]:
    """(abertas, concluídas hoje) que pertencem ao dia."""
    hoje = timeutil.today()
    candidates = session.exec(select(Task).where(ALIVE_TASK)).all()
    mine = [t for t in candidates if is_for_today(t, hoje)]
    open_ = sort_open([t for t in mine if t.status != TaskStatus.done])
    done = sorted(
        [t for t in mine if t.status == TaskStatus.done],
        key=lambda t: t.completed_at or datetime.min,
    )
    return open_, done


def inbox_tasks(session: Session) -> list[Task]:
    stmt = (
        select(Task)
        .where(ALIVE_TASK, Task.area_id == None, Task.status != TaskStatus.done)  # noqa: E711
        .order_by(Task.created_at.desc())
    )
    return list(session.exec(stmt).all())


def area_tasks(session: Session, area_id: str) -> dict:
    hoje = timeutil.today()
    stmt = select(Task).where(ALIVE_TASK, Task.area_id == area_id)
    tasks = session.exec(stmt).all()
    week_ago = hoje - timedelta(days=7)
    return {
        "doing": sort_open([t for t in tasks if t.status == TaskStatus.doing]),
        "todo": sort_open([t for t in tasks if t.status == TaskStatus.todo]),
        "done": sorted(
            [
                t for t in tasks
                if t.status == TaskStatus.done
                and (timeutil.local_date(t.completed_at) or hoje) >= week_ago
            ],
            key=lambda t: t.completed_at or datetime.min,
            reverse=True,
        ),
    }


# --- Diário de bordo ---------------------------------------------------------


def last_log(session: Session, area_id: Optional[str] = None) -> Optional[LogEntry]:
    """Último registro com próximo passo: é o 'onde parei'."""
    stmt = select(LogEntry).where(
        LogEntry.deleted_at == None, LogEntry.next_step != None  # noqa: E711
    )
    if area_id:
        stmt = stmt.where(LogEntry.area_id == area_id)
    stmt = stmt.order_by(LogEntry.day.desc(), LogEntry.created_at.desc())
    return session.exec(stmt).first()


@dataclass
class AreaSummary:
    """O resumo mais recente de uma área (registro do diário)."""

    area: Area
    log: LogEntry

    @property
    def is_today(self) -> bool:
        return self.log.day == timeutil.today()

    @property
    def pending(self) -> bool:
        """Aparece em "Começar o dia": é de um dia anterior e ainda não foi retomado nem dispensado."""
        return (self.log.day < timeutil.today() and self.log.resumed_at is None
                and bool(self.log.next_step or self.log.stopped_at))


def area_summaries(session: Session) -> list[AreaSummary]:
    """Último resumo de cada área, na ordem das áreas."""
    out: list[AreaSummary] = []
    for a in areas(session):
        log = session.exec(
            # Só resumos (sem resource_id): registros de páginas lidas e sessões de estudo não contam.
            select(LogEntry).where(LogEntry.deleted_at == None, LogEntry.area_id == a.id,  # noqa: E711
                                   LogEntry.resource_id == None)  # noqa: E711
            .order_by(LogEntry.day.desc(), LogEntry.created_at.desc())
        ).first()
        if log is not None:
            out.append(AreaSummary(a, log))
    return out


def today_log(session: Session, area_id: str) -> Optional[LogEntry]:
    """Resumo de hoje da área, se já foi encerrada hoje."""
    return session.exec(
        select(LogEntry).where(LogEntry.deleted_at == None, LogEntry.area_id == area_id,  # noqa: E711
                               LogEntry.resource_id == None, LogEntry.day == timeutil.today())  # noqa: E711
        .order_by(LogEntry.created_at.desc())
    ).first()


def logs_by_day(session: Session, limit: int = 60) -> list[tuple[date, list[LogEntry]]]:
    stmt = (
        select(LogEntry)
        .where(LogEntry.deleted_at == None)  # noqa: E711
        .order_by(LogEntry.day.desc(), LogEntry.created_at.desc())
        .limit(limit)
    )
    grouped: dict[date, list[LogEntry]] = {}
    for entry in session.exec(stmt).all():
        grouped.setdefault(entry.day, []).append(entry)
    return list(grouped.items())


# --- Prazos ------------------------------------------------------------------


@dataclass
class Deadline:
    title: str
    when: date
    area_id: Optional[str]
    kind: str  # "prova", "entrega", "tarefa"...
    days: int = field(init=False)

    def __post_init__(self):
        self.days = timeutil.days_until(self.when)


EVENT_LABEL = {"exam": "prova", "deadline": "entrega", "meeting": "reunião", "other": "evento"}


def upcoming_deadlines(session: Session, horizon_days: int = 45, limit: int = 5) -> list[Deadline]:
    hoje = timeutil.today()
    limite = hoje + timedelta(days=horizon_days)
    items: list[Deadline] = []

    events = session.exec(
        select(Event).where(Event.deleted_at == None)  # noqa: E711
    ).all()
    for e in events:
        d = e.starts_at.date()
        if hoje <= d <= limite:
            items.append(Deadline(e.title, d, e.area_id, EVENT_LABEL.get(e.kind, "evento")))

    tasks = session.exec(
        select(Task).where(ALIVE_TASK, Task.status != TaskStatus.done, Task.due_date != None)  # noqa: E711
    ).all()
    for t in tasks:
        if hoje < t.due_date <= limite:  # as de hoje já aparecem na lista do dia
            items.append(Deadline(t.title, t.due_date, t.area_id, "tarefa"))

    return sorted(items, key=lambda x: x.when)[:limit]


# --- Leitura -----------------------------------------------------------------


def current_book(session: Session) -> Optional[Resource]:
    stmt = (
        select(Resource)
        .where(
            Resource.deleted_at == None,  # noqa: E711
            Resource.kind == ResourceKind.book,
            Resource.status == ResourceStatus.active,
        )
        .order_by(Resource.updated_at.desc())
    )
    return session.exec(stmt).first()


# --- Tela Hoje ---------------------------------------------------------------


def enso(open_count: int, done_count: int) -> dict:
    total = open_count + done_count
    return {"done": done_count, "total": total, "fraction": (done_count / total) if total else 0.0}


def today_view(session: Session) -> dict:
    open_, done = today_tasks(session)
    deadlines = upcoming_deadlines(session)
    return {
        "open_tasks": open_,
        "done_tasks": done,
        "enso": enso(len(open_), len(done)),
        "log": last_log(session),
        "start_items": [s for s in area_summaries(session) if s.pending],
        "closed_today": [s for s in area_summaries(session) if s.is_today],
        "deadlines": deadlines,
        "book": current_book(session),
        "inbox_preview": inbox_tasks(session)[:4],
        "summary": summary_line(len(open_), deadlines),
        **services_track.habits_view(session),
        "focus_minutes": services_track.focus_view(session)["focus_minutes"],
    }


def summary_line(open_count: int, deadlines: list[Deadline]) -> str:
    if open_count == 0:
        parts = ["Nenhuma tarefa marcada para hoje"]
    elif open_count == 1:
        parts = ["Uma tarefa para hoje"]
    else:
        parts = [f"{open_count} tarefas para hoje"]
    if deadlines:
        d = deadlines[0]
        quando = "amanhã" if d.days == 1 else ("hoje" if d.days == 0 else f"em {d.days} dias")
        parts.append(f"{d.title} {quando}")
    return ". ".join(parts) + "."
