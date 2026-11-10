"""Dados das telas especializadas: Faculdade, Leituras, Estudos e Objetivos e metas."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Optional

from sqlmodel import Session, select

from app import services, timeutil
from app.models import (
    Area,
    Event,
    EventKind,
    Goal,
    LogEntry,
    Objective,
    Resource,
    ResourceKind,
    ResourceStatus,
    Task,
    TaskStatus,
)

ALIVE = lambda model: model.deleted_at == None  # noqa: E731,E711


# --- Recursos ----------------------------------------------------------------


def fraction(r: Resource) -> float:
    if not r.progress_total:
        return 1.0 if r.status == ResourceStatus.finished else 0.0
    return max(0.0, min(1.0, r.progress_current / r.progress_total))


def resources(session: Session, area_id: str, kinds: Optional[list[ResourceKind]] = None) -> list[Resource]:
    stmt = select(Resource).where(ALIVE(Resource), Resource.area_id == area_id)
    if kinds:
        stmt = stmt.where(Resource.kind.in_(kinds))
    return list(session.exec(stmt.order_by(Resource.updated_at.desc())).all())


def logs(session: Session, area_id: str, since: date, resource_id: Optional[str] = None) -> list[LogEntry]:
    stmt = select(LogEntry).where(ALIVE(LogEntry), LogEntry.area_id == area_id, LogEntry.day >= since)
    if resource_id:
        stmt = stmt.where(LogEntry.resource_id == resource_id)
    return list(session.exec(stmt.order_by(LogEntry.day.desc(), LogEntry.created_at.desc())).all())


def open_area_tasks(session: Session, area_id: str) -> list[Task]:
    stmt = select(Task).where(ALIVE(Task), Task.area_id == area_id, Task.status != TaskStatus.done)
    return services.sort_open(list(session.exec(stmt).all()))


# --- Faculdade ---------------------------------------------------------------


@dataclass
class Moon:
    day: date
    state: str     # "hoje" | "alvo" | "evento" | "livre"
    legend: str    # aparece ao passar o mouse


EVENT_KIND_LABEL = {EventKind.exam: "prova", EventKind.deadline: "entrega",
                    EventKind.meeting: "reunião", EventKind.other: "evento"}


def moons_until(target: date, events: list[Event], tasks: list[Task], max_days: int = 14) -> list[Moon]:
    """Uma lua por dia, de hoje até o alvo. Vazio se o alvo estiver longe demais."""
    hoje = timeutil.today()
    n = (target - hoje).days
    if n < 0 or n > max_days:
        return []
    marks: dict[date, list[str]] = {}
    for e in events:
        marks.setdefault(e.starts_at.date(), []).append(e.title)
    for t in tasks:
        if t.due_date:
            marks.setdefault(t.due_date, []).append(t.title)
    out = []
    for i in range(n + 1):
        d = hoje + timedelta(days=i)
        nome = timeutil.DIAS_SEMANA[d.weekday()]
        titulos = marks.get(d, [])
        if i == n:
            state = "alvo"
        elif i == 0:
            state = "hoje"
        elif titulos:
            state = "evento"
        else:
            state = "livre"
        prefixo = "hoje" if i == 0 else f"{nome}, {d.day}"
        out.append(Moon(d, state, prefixo + (": " + ", ".join(titulos) if titulos else "")))
    return out


def faculdade_view(session: Session, area: Area) -> dict:
    hoje = timeutil.today()
    subjects = sorted(
        [r for r in resources(session, area.id, [ResourceKind.subject]) if r.status != ResourceStatus.finished],
        key=lambda r: r.title.lower(),
    )
    subject_by_id = {s.id: s for s in subjects}

    events = [
        e for e in session.exec(
            select(Event).where(ALIVE(Event), Event.area_id == area.id).order_by(Event.starts_at)
        ).all()
        if e.starts_at.date() >= hoje
    ]
    tasks = open_area_tasks(session, area.id)

    next_exam = next((e for e in events if e.kind == EventKind.exam), events[0] if events else None)
    moons = moons_until(next_exam.starts_at.date(), events, tasks) if next_exam else []

    grades = [s.grade for s in subjects if s.grade is not None]
    return {
        "subjects": subjects,
        "subject_by_id": subject_by_id,
        "events": events,
        "next_exam": next_exam,
        "next_exam_days": timeutil.days_until(next_exam.starts_at) if next_exam else None,
        "moons": moons,
        "tasks": tasks,
        "average": (sum(grades) / len(grades)) if grades else None,
        "event_kinds": EVENT_KIND_LABEL,
    }


# --- Leituras ----------------------------------------------------------------


def reading_streak(entries: list[LogEntry]) -> int:
    """Dias seguidos com leitura, contando até hoje (ou até ontem, se hoje ainda não leu)."""
    days = {e.day for e in entries if (e.amount or 0) > 0}
    hoje = timeutil.today()
    d = hoje if hoje in days else hoje - timedelta(days=1)
    streak = 0
    while d in days:
        streak += 1
        d -= timedelta(days=1)
    return streak


def books_goal(session: Session, area_id: str) -> Optional[Goal]:
    goals = session.exec(select(Goal).where(ALIVE(Goal), Goal.area_id == area_id, Goal.is_done == False)).all()  # noqa: E712
    return next((g for g in goals if (g.unit or "").lower().startswith("livro")), None)


def finished_this_year(books: list[Resource]) -> list[Resource]:
    ano = timeutil.today().year
    done = [b for b in books if b.status == ResourceStatus.finished and b.finished_at and b.finished_at.year == ano]
    return sorted(done, key=lambda b: b.finished_at, reverse=True)


def leituras_view(session: Session, area: Area) -> dict:
    hoje = timeutil.today()
    books = resources(session, area.id, [ResourceKind.book])
    active = [b for b in books if b.status == ResourceStatus.active]
    wanted = sorted([b for b in books if b.status == ResourceStatus.wanted], key=lambda b: b.created_at)
    paused = [b for b in books if b.status == ResourceStatus.paused]
    done_year = finished_this_year(books)

    window = 14
    entries = logs(session, area.id, hoje - timedelta(days=60))
    per_day = {hoje - timedelta(days=i): 0.0 for i in range(window)}
    for e in entries:
        if e.day in per_day and e.amount:
            per_day[e.day] += e.amount
    bars = [per_day[d] for d in sorted(per_day)]
    avg = sum(bars) / window

    current = active[0] if active else None
    days_left = None
    if current and current.progress_total and avg > 0:
        days_left = max(1, round((current.progress_total - current.progress_current) / avg))

    goal = books_goal(session, area.id)
    return {
        "current": current,
        "other_active": active[1:],
        "wanted": wanted,
        "paused": paused,
        "done_year": done_year,
        "goal": goal,
        "goal_target": int(goal.target_value) if goal and goal.target_value else None,
        "streak": reading_streak(entries),
        "bars": bars,
        "bars_max": max(bars) if any(bars) else 1,
        "avg_pages": avg,
        "days_left": days_left,
        "last_note": last_reading_note(entries, current.id) if current else None,
        "fraction": fraction,
    }


def last_reading_note(entries: list[LogEntry], resource_id: str) -> Optional[LogEntry]:
    return next((e for e in entries if e.resource_id == resource_id and e.did), None)


# --- Estudos -----------------------------------------------------------------


def week_start(d: date) -> date:
    return d - timedelta(days=d.weekday())


def weekly_hours_goal(session: Session, area_id: str) -> Optional[Goal]:
    goals = session.exec(select(Goal).where(ALIVE(Goal), Goal.area_id == area_id, Goal.is_done == False)).all()  # noqa: E712
    return next((g for g in goals if "hora" in (g.unit or "").lower()), None)


def estudos_view(session: Session, area: Area) -> dict:
    hoje = timeutil.today()
    all_res = resources(session, area.id)
    subjects = [r for r in all_res if r.status in (ResourceStatus.active, ResourceStatus.wanted)]
    subjects.sort(key=lambda r: (r.status != ResourceStatus.active, -r.updated_at.timestamp()))
    by_id = {r.id: r for r in all_res}

    week = logs(session, area.id, week_start(hoje))
    week_sessions = [e for e in week if e.minutes]
    week_minutes = sum(e.minutes for e in week_sessions)

    goal = weekly_hours_goal(session, area.id)
    goal_minutes = int(goal.target_value * 60) if goal and goal.target_value else None

    return {
        "subjects": subjects,
        "resource_by_id": by_id,
        "materials": [r for r in all_res if r.url],
        "finished": [r for r in all_res if r.status == ResourceStatus.finished],
        "tasks": open_area_tasks(session, area.id),
        "week_sessions": week_sessions,
        "week_minutes": week_minutes,
        "goal_minutes": goal_minutes,
        "fraction": fraction,
    }


# --- Objetivos e metas -------------------------------------------------------


def auto_kind(g: Goal) -> Optional[str]:
    """Metas que se medem sozinhas, reconhecidas pela unidade."""
    unit = (g.unit or "").lower()
    if unit.startswith("livro"):
        return "conta sozinha pelos livros terminados no ano"
    if "hora" in unit and "semana" in unit:
        return "conta sozinha pelas sessões registradas desde segunda"
    return None


def goal_value(session: Session, g: Goal, areas_by_id: dict) -> float:
    unit = (g.unit or "").lower()
    if unit.startswith("livro"):
        books = resources(session, g.area_id, [ResourceKind.book])
        return float(len(finished_this_year(books)))
    if "hora" in unit and "semana" in unit:
        minutes = sum(e.minutes or 0 for e in logs(session, g.area_id, week_start(timeutil.today())))
        return round(minutes / 60, 1)
    return g.current_value


def goal_fraction(value: float, g: Goal) -> float:
    if g.is_done:
        return 1.0
    if g.target_value:
        return max(0.0, min(1.0, value / g.target_value))
    return 0.0


@dataclass
class GoalRow:
    goal: Goal
    value: float
    fraction: float
    auto: Optional[str]  # explicação, quando a meta se mede sozinha
    open_tasks: int


@dataclass
class ObjectiveCard:
    objective: Objective
    goals: list[GoalRow]
    fraction: float


def objective_cards(session: Session, areas_by_id: dict, area_id: Optional[str] = None) -> list[ObjectiveCard]:
    stmt = select(Objective).where(ALIVE(Objective))
    if area_id:
        stmt = stmt.where(Objective.area_id == area_id)
    objectives = session.exec(stmt).all()
    goals = session.exec(select(Goal).where(ALIVE(Goal))).all()
    tasks = session.exec(select(Task).where(ALIVE(Task), Task.goal_id != None, Task.status != TaskStatus.done)).all()  # noqa: E711
    open_by_goal: dict[str, int] = {}
    for t in tasks:
        open_by_goal[t.goal_id] = open_by_goal.get(t.goal_id, 0) + 1

    cards = []
    for o in objectives:
        rows = []
        for g in sorted([g for g in goals if g.objective_id == o.id], key=lambda g: (g.is_done, g.due_date or date.max, g.created_at)):
            auto = auto_kind(g)
            value = goal_value(session, g, areas_by_id)
            rows.append(GoalRow(g, value, goal_fraction(value, g), auto, open_by_goal.get(g.id, 0)))
        if o.is_done:
            frac = 1.0
        elif rows:
            frac = sum(r.fraction for r in rows) / len(rows)
        else:
            frac = 0.0
        cards.append(ObjectiveCard(o, rows, frac))
    cards.sort(key=lambda c: (c.objective.due_date or date.max, c.objective.created_at))
    return cards


def metas_view(session: Session, areas_by_id: dict) -> dict:
    cards = objective_cards(session, areas_by_id)
    return {
        "active": [c for c in cards if not c.objective.is_done and not c.objective.is_paused],
        "paused": [c for c in cards if c.objective.is_paused and not c.objective.is_done],
        "done": [c for c in cards if c.objective.is_done],
        "loose_goals": loose_goals(session, areas_by_id),
    }


def loose_goals(session: Session, areas_by_id: dict) -> list[GoalRow]:
    """Metas sem objetivo, como 'ler 12 livros' ou 'estudar 5 horas por semana'."""
    goals = session.exec(select(Goal).where(ALIVE(Goal), Goal.objective_id == None, Goal.is_done == False)).all()  # noqa: E711,E712
    rows = []
    for g in goals:
        value = goal_value(session, g, areas_by_id)
        rows.append(GoalRow(g, value, goal_fraction(value, g), auto_kind(g), 0))
    return rows


def area_objective(session: Session, area_id: str, areas_by_id: dict) -> Optional[ObjectiveCard]:
    """O objetivo ativo mais próximo do prazo nesta área (aparece no céu da página da área)."""
    cards = [c for c in objective_cards(session, areas_by_id, area_id)
             if not c.objective.is_done and not c.objective.is_paused]
    return cards[0] if cards else None


def area_learning(session: Session, area_id: str) -> list[Resource]:
    """Cursos e materiais em andamento numa área genérica (ex. estudos do trabalho)."""
    return [r for r in resources(session, area_id) if r.status == ResourceStatus.active][:3]
