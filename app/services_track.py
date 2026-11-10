"""Hábitos, sessões de foco e acompanhamento (gráficos por período)."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from typing import Optional

from sqlmodel import Session, select

from app import timeutil
from app.models import FocusSession, Habit, HabitCheck, LogEntry, Resource, ResourceKind, Task, TaskStatus, utcnow

ALIVE = lambda model: model.deleted_at == None  # noqa: E731,E711


# --- Hábitos -----------------------------------------------------------------------


@dataclass
class HabitRow:
    habit: Habit
    days: list[tuple[date, bool]]      # últimos 7 dias, do mais antigo para hoje
    done_today: bool
    streak: int                        # dias seguidos até hoje (ou até ontem, se hoje ainda não)
    week_done: int                     # marcados nos últimos 7 dias
    checks_total: int


def habits(session: Session, include_archived: bool = False) -> list[Habit]:
    stmt = select(Habit).where(ALIVE(Habit))
    if not include_archived:
        stmt = stmt.where(Habit.archived_at == None)  # noqa: E711
    return list(session.exec(stmt.order_by(Habit.position, Habit.created_at)).all())


def checks_between(session: Session, start: date, end: date) -> dict[str, set[date]]:
    """{habit_id: {dias marcados}} no intervalo [start, end]."""
    rows = session.exec(
        select(HabitCheck).where(ALIVE(HabitCheck), HabitCheck.done == True,  # noqa: E712
                                 HabitCheck.day >= start, HabitCheck.day <= end)
    ).all()
    out: dict[str, set[date]] = {}
    for c in rows:
        out.setdefault(c.habit_id, set()).add(c.day)
    return out


def streak_of(days_done: set[date], today: date) -> int:
    """Conta para trás a partir de hoje; se hoje não foi marcado ainda, começa de ontem."""
    d = today if today in days_done else today - timedelta(days=1)
    n = 0
    while d in days_done:
        n += 1
        d -= timedelta(days=1)
    return n


def habits_view(session: Session) -> dict:
    today = timeutil.today()
    all_habits = habits(session)
    start = today - timedelta(days=60)
    done = checks_between(session, start, today)
    rows: list[HabitRow] = []
    for h in all_habits:
        mine = done.get(h.id, set())
        week = [today - timedelta(days=i) for i in range(6, -1, -1)]
        rows.append(HabitRow(
            habit=h,
            days=[(d, d in mine) for d in week],
            done_today=today in mine,
            streak=streak_of(mine, today),
            week_done=sum(1 for d in week if d in mine),
            checks_total=len(mine),
        ))
    done_today = sum(1 for r in rows if r.done_today)
    return {
        "habit_rows": rows,
        "habits_done_today": done_today,
        "habits_total": len(rows),
        "week_days": [today - timedelta(days=i) for i in range(6, -1, -1)],
    }


def habit_row(session: Session, habit: Habit) -> HabitRow:
    today = timeutil.today()
    mine = checks_between(session, today - timedelta(days=60), today).get(habit.id, set())
    week = [today - timedelta(days=i) for i in range(6, -1, -1)]
    return HabitRow(habit, [(d, d in mine) for d in week], today in mine, streak_of(mine, today),
                    sum(1 for d in week if d in mine), len(mine))


def toggle_habit(session: Session, habit: Habit, day: date) -> bool:
    """Marca ou desmarca um dia. Devolve o estado final."""
    existing = session.exec(
        select(HabitCheck).where(ALIVE(HabitCheck), HabitCheck.habit_id == habit.id, HabitCheck.day == day)
    ).all()
    now = utcnow()
    if existing:
        for c in existing:   # pode haver mais de um se dois aparelhos marcaram offline
            c.deleted_at = now
            c.updated_at = now
            session.add(c)
        session.commit()
        return False
    session.add(HabitCheck(habit_id=habit.id, day=day, done=True))
    session.commit()
    return True


# --- Foco (Pomodoro) ---------------------------------------------------------------


def focus_sessions_between(session: Session, start_utc: datetime, end_utc: datetime) -> list[FocusSession]:
    return list(session.exec(
        select(FocusSession).where(ALIVE(FocusSession), FocusSession.started_at >= start_utc,
                                   FocusSession.started_at < end_utc).order_by(FocusSession.started_at.desc())
    ).all())


def local_day_bounds_utc(day: date) -> tuple[datetime, datetime]:
    """Início e fim do dia local, como instantes UTC com fuso (o SQLModel exige fuso nas consultas)."""
    start = datetime.combine(day, datetime.min.time()).astimezone()
    end = start + timedelta(days=1)
    return start.astimezone(timezone.utc), end.astimezone(timezone.utc)


def focus_view(session: Session) -> dict:
    today = timeutil.today()
    s, e = local_day_bounds_utc(today)
    sessions = focus_sessions_between(session, s, e)
    focus = [x for x in sessions if x.kind == "focus"]
    minutes = sum(x.minutes for x in focus)
    open_tasks = list(session.exec(
        select(Task).where(ALIVE(Task), Task.status != TaskStatus.done)
        .order_by(Task.status != TaskStatus.doing, Task.scheduled_for != today, Task.created_at.desc())
    ).all())[:40]
    return {
        "sessions": sessions,
        "focus_minutes": minutes,
        "focus_count": sum(1 for x in focus if x.completed),
        "focus_tasks": open_tasks,
    }


# --- Acompanhamento ----------------------------------------------------------------


@dataclass
class Series:
    label: str
    unit: str
    values: list[float]
    total: float = 0
    max: float = 0

    def __post_init__(self):
        self.total = sum(self.values)
        self.max = max(self.values) if self.values else 0


@dataclass
class Tracking:
    period: str
    title: str
    labels: list[str]               # rótulo de cada barra
    series: list[Series]
    habits_pct: Optional[int]       # % dos hábitos feitos no período
    tasks_done_list: list[Task] = field(default_factory=list)


PERIODS = {"hoje": "Hoje", "semana": "Esta semana", "mes": "Este mês"}


def period_range(period: str, today: date) -> tuple[date, date]:
    if period == "semana":
        start = today - timedelta(days=today.weekday())   # segunda
        return start, start + timedelta(days=6)
    if period == "mes":
        start = today.replace(day=1)
        nxt = (start + timedelta(days=32)).replace(day=1)
        return start, nxt - timedelta(days=1)
    return today, today


def _local_date(dt: Optional[datetime]) -> Optional[date]:
    return timeutil.local_date(dt)


def tracking_view(session: Session, period: str) -> Tracking:
    period = period if period in PERIODS else "semana"
    today = timeutil.today()
    start, end = period_range(period, today)
    s_utc, _ = local_day_bounds_utc(start)
    _, e_utc = local_day_bounds_utc(end)

    done_tasks = list(session.exec(
        select(Task).where(ALIVE(Task), Task.status == TaskStatus.done, Task.completed_at != None,  # noqa: E711
                           Task.completed_at >= s_utc, Task.completed_at < e_utc).order_by(Task.completed_at.desc())
    ).all())
    focus = [x for x in focus_sessions_between(session, s_utc, e_utc) if x.kind == "focus"]
    logs = list(session.exec(
        select(LogEntry).where(ALIVE(LogEntry), LogEntry.day >= start, LogEntry.day <= end)
    ).all())
    books = {r.id for r in session.exec(select(Resource).where(Resource.kind == ResourceKind.book)).all()}

    if period == "hoje":
        # Barras por hora do dia (6h às 23h)
        hours = list(range(6, 24))
        labels = [f"{h}h" if h % 3 == 0 else "" for h in hours]

        def hour_of(dt: Optional[datetime]) -> Optional[int]:
            loc = timeutil.to_local(dt)
            return loc.hour if loc else None

        def bucket(items, when, value) -> list[float]:
            vals = [0.0] * len(hours)
            for it in items:
                h = hour_of(when(it))
                if h is not None and h >= 6:
                    vals[h - 6] += value(it)
            return vals

        tasks_vals = bucket(done_tasks, lambda t: t.completed_at, lambda t: 1)
        focus_vals = bucket(focus, lambda f: f.started_at, lambda f: f.minutes)
        study_vals = [0.0] * len(hours)   # o diário não tem hora: só o total
        pages_vals = [0.0] * len(hours)
    else:
        days = [start + timedelta(days=i) for i in range((end - start).days + 1)]
        labels = ([timeutil.DIAS_SEMANA[d.weekday()][:3] for d in days] if period == "semana"
                  else [str(d.day) if d.day % 5 == 0 or d.day == 1 else "" for d in days])
        index = {d: i for i, d in enumerate(days)}

        def bucket(items, when, value) -> list[float]:
            vals = [0.0] * len(days)
            for it in items:
                d = when(it)
                if d in index:
                    vals[index[d]] += value(it)
            return vals

        tasks_vals = bucket(done_tasks, lambda t: _local_date(t.completed_at), lambda t: 1)
        focus_vals = bucket(focus, lambda f: _local_date(f.started_at), lambda f: f.minutes)
        study_vals = bucket([l for l in logs if l.minutes], lambda l: l.day, lambda l: l.minutes or 0)
        pages_vals = bucket([l for l in logs if l.amount and l.resource_id in books], lambda l: l.day, lambda l: l.amount or 0)

    study_total = sum(l.minutes or 0 for l in logs)
    pages_total = sum(l.amount or 0 for l in logs if l.resource_id in books)
    series = [
        Series("Tarefas feitas", "tarefas", tasks_vals),
        Series("Foco", "min", focus_vals),
        Series("Estudo", "min", study_vals),
        Series("Páginas lidas", "páginas", pages_vals),
    ]
    if period == "hoje":
        series[2].total = study_total
        series[3].total = pages_total

    # Hábitos: % de (hábitos ativos × dias do período até hoje) marcados
    active = habits(session)
    last = min(end, today)
    n_days = (last - start).days + 1
    pct: Optional[int] = None
    if active and n_days > 0:
        done = checks_between(session, start, last)
        possible = 0
        got = 0
        for h in active:
            expected = n_days if h.days_per_week >= 7 else round(n_days * h.days_per_week / 7)
            possible += max(expected, 1)
            got += min(len(done.get(h.id, set())), max(expected, 1))
        pct = round(100 * got / possible) if possible else None

    return Tracking(period=period, title=PERIODS[period], labels=labels, series=series,
                    habits_pct=pct, tasks_done_list=done_tasks[:12])
