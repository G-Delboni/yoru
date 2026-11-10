"""Modelo de dados do Yoru.

Hierarquia:
    Área → Objetivo → Meta → Tarefa
Entidades transversais, sempre ligadas a uma Área (e opcionalmente a outro item):
    Recurso (livro, curso, disciplina), Evento (prova, entrega), Registro (diário
    de bordo: onde parei / próximo passo), Nota (texto livre) e Tag.

Todas as tabelas herdam de SyncBase, que traz o que a sincronização
celular ↔ PC vai precisar na Fase 3:
    id          UUID gerado no aparelho que criou o registro (nunca colide)
    created_at / updated_at   em UTC; em conflito vence o updated_at mais recente
    deleted_at  exclusão suave; registros apagados continuam existindo para
                que o outro aparelho saiba que deve apagá-los também
"""

from __future__ import annotations

import uuid
from datetime import date, datetime, timezone
from enum import Enum
from typing import Optional

from sqlalchemy import Column, DateTime
from sqlmodel import Field, SQLModel


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def new_id() -> str:
    return str(uuid.uuid4())


class SyncBase(SQLModel):
    id: str = Field(default_factory=new_id, primary_key=True)
    created_at: datetime = Field(default_factory=utcnow)
    updated_at: datetime = Field(default_factory=utcnow, index=True)
    deleted_at: Optional[datetime] = Field(default=None, index=True)
    # Quando este servidor recebeu a última versão vinda de outro aparelho.
    # Sem isso, uma edição feita offline (updated_at antigo) passaria despercebida
    # por um segundo aparelho que já tinha sincronizado depois dela.
    synced_at: Optional[datetime] = Field(default=None)


# --- Enums -----------------------------------------------------------------


class TaskStatus(str, Enum):
    todo = "todo"
    doing = "doing"
    done = "done"


class Priority(str, Enum):
    low = "low"
    normal = "normal"
    high = "high"


class ResourceKind(str, Enum):
    book = "book"          # Leituras
    course = "course"      # Estudos / trabalho
    subject = "subject"    # Disciplina da faculdade
    article = "article"
    other = "other"


class ResourceStatus(str, Enum):
    wanted = "wanted"      # quero ler / quero fazer
    active = "active"      # lendo / cursando
    finished = "finished"
    paused = "paused"


class EventKind(str, Enum):
    exam = "exam"
    deadline = "deadline"
    meeting = "meeting"
    other = "other"


# --- Tabelas ---------------------------------------------------------------


class Area(SyncBase, table=True):
    """Trabalho, Estudos, Leituras, Faculdade, Pessoal..."""

    name: str = Field(index=True)
    slug: str = Field(unique=True, index=True)
    kanji: Optional[str] = None       # marca visual da área, ex. 務
    legend: Optional[str] = None      # legenda do kanji, ex. "tarefas"
    position: int = 0


class Objective(SyncBase, table=True):
    """O destino: 'Entregar o app com sincronização'."""

    area_id: str = Field(foreign_key="area.id", index=True)
    title: str
    description: Optional[str] = None
    due_date: Optional[date] = None
    is_done: bool = False
    is_paused: bool = False


class Goal(SyncBase, table=True):
    """O que dá para medir: 'Concluir o curso até março'."""

    objective_id: Optional[str] = Field(default=None, foreign_key="objective.id", index=True)
    area_id: str = Field(foreign_key="area.id", index=True)
    title: str
    target_value: Optional[float] = None   # ex. 12 (livros)
    current_value: float = 0
    unit: Optional[str] = None             # ex. "livros", "módulos", "%"
    due_date: Optional[date] = None
    is_done: bool = False


class Task(SyncBase, table=True):
    """O passo de hoje. Sem area_id = Caixa de entrada."""

    area_id: Optional[str] = Field(default=None, foreign_key="area.id", index=True)
    goal_id: Optional[str] = Field(default=None, foreign_key="goal.id", index=True)
    resource_id: Optional[str] = Field(default=None, foreign_key="resource.id", index=True)
    title: str
    notes: Optional[str] = None
    status: TaskStatus = Field(default=TaskStatus.todo, index=True)
    priority: Priority = Priority.normal
    due_date: Optional[date] = Field(default=None, index=True)
    scheduled_for: Optional[date] = Field(default=None, index=True)  # aparece em "Hoje"
    completed_at: Optional[datetime] = None
    estimate_minutes: Optional[int] = None
    position: int = 0


class Resource(SyncBase, table=True):
    """Livro, curso, disciplina, artigo. Tem progresso."""

    area_id: str = Field(foreign_key="area.id", index=True)
    kind: ResourceKind = Field(index=True)
    status: ResourceStatus = Field(default=ResourceStatus.wanted, index=True)
    title: str
    author: Optional[str] = None          # autor, professor, plataforma
    url: Optional[str] = None
    progress_current: float = 0           # página 142, módulo 3
    progress_total: Optional[float] = None  # 296 páginas, 8 módulos
    progress_unit: Optional[str] = None     # "páginas", "módulos", "aulas"
    schedule: Optional[str] = None        # "ter e qui, 19:00" (disciplinas)
    grade: Optional[float] = None         # média (disciplinas)
    started_at: Optional[date] = None
    finished_at: Optional[date] = None


class Event(SyncBase, table=True):
    """Prova, entrega, reunião. Tem data e hora."""

    area_id: str = Field(foreign_key="area.id", index=True)
    resource_id: Optional[str] = Field(default=None, foreign_key="resource.id", index=True)
    kind: EventKind = Field(default=EventKind.other, index=True)
    title: str
    # Hora de parede, sem fuso ("prova às 19h" é 19h em qualquer aparelho). Coluna DateTime
    # pura do SQLAlchemy: o tipo padrão do SQLModel novo exigiria fuso.
    starts_at: datetime = Field(sa_column=Column(DateTime(), index=True, nullable=False))
    ends_at: Optional[datetime] = Field(default=None, sa_column=Column(DateTime(), nullable=True))
    location: Optional[str] = None
    notes: Optional[str] = None
    remind_days_before: Optional[str] = "7,3,1"  # dias antes para alertar


class LogEntry(SyncBase, table=True):
    """Diário de bordo: o que fiz, onde parei, próximo passo."""

    area_id: str = Field(foreign_key="area.id", index=True)
    task_id: Optional[str] = Field(default=None, foreign_key="task.id", index=True)
    resource_id: Optional[str] = Field(default=None, foreign_key="resource.id", index=True)
    day: date = Field(default_factory=date.today, index=True)
    did: Optional[str] = None          # o que fiz
    stopped_at: Optional[str] = None   # onde parei
    next_step: Optional[str] = None    # por onde retomar
    minutes: Optional[int] = None      # tempo dedicado (sessão de estudo, foco)
    amount: Optional[float] = None     # quanto avançou: páginas lidas, módulos vistos
    # "Começar o dia": quando este resumo foi retomado (virou tarefa) ou dispensado.
    # Enquanto for None, o próximo passo aparece na tela Hoje do dia seguinte.
    resumed_at: Optional[datetime] = None
    resumed_task_id: Optional[str] = None


class Note(SyncBase, table=True):
    """Anotação livre em Markdown, ligada a qualquer item."""

    area_id: Optional[str] = Field(default=None, foreign_key="area.id", index=True)
    task_id: Optional[str] = Field(default=None, foreign_key="task.id", index=True)
    resource_id: Optional[str] = Field(default=None, foreign_key="resource.id", index=True)
    objective_id: Optional[str] = Field(default=None, foreign_key="objective.id", index=True)
    title: Optional[str] = None
    body: str = ""
    page: Optional[int] = None  # citação de livro: página


class Habit(SyncBase, table=True):
    """Hábito: algo para repetir. days_per_week = 7 é diário."""

    area_id: Optional[str] = Field(default=None, foreign_key="area.id", index=True)
    name: str
    kanji: Optional[str] = None
    days_per_week: int = 7
    position: int = 0
    archived_at: Optional[datetime] = None


class HabitCheck(SyncBase, table=True):
    """Um dia marcado de um hábito. Desmarcar = exclusão suave."""

    habit_id: str = Field(foreign_key="habit.id", index=True)
    day: date = Field(index=True)
    done: bool = True


class FocusSession(SyncBase, table=True):
    """Uma sessão do Pomodoro (foco ou pausa), registrada quando termina ou é encerrada."""

    area_id: Optional[str] = Field(default=None, foreign_key="area.id", index=True)
    task_id: Optional[str] = Field(default=None, foreign_key="task.id", index=True)
    kind: str = Field(default="focus", index=True)   # focus | break
    started_at: datetime = Field(index=True)          # instante UTC
    ended_at: Optional[datetime] = None
    planned_minutes: int = 25
    minutes: int = 0                                  # tempo de fato
    completed: bool = False                           # chegou ao fim sem encerrar antes
    note: Optional[str] = None


class Tag(SyncBase, table=True):
    name: str = Field(unique=True, index=True)


class SyncLog(SQLModel, table=True):
    """Histórico local de sincronizações. Não é sincronizado."""

    id: Optional[int] = Field(default=None, primary_key=True)
    at: datetime = Field(default_factory=utcnow, index=True)
    device: str = "desconhecido"
    received: int = 0   # registros do aparelho que foram aplicados aqui
    sent: int = 0       # registros enviados ao aparelho
    rejected: int = 0


class Device(SQLModel, table=True):
    """Aparelho pareado: cada um tem o próprio token. Não é sincronizado."""

    id: str = Field(default_factory=new_id, primary_key=True)
    name: str
    token: str = Field(index=True)
    created_at: datetime = Field(default_factory=utcnow)
    last_seen_at: Optional[datetime] = None
    revoked_at: Optional[datetime] = None


class TaskTag(SQLModel, table=True):
    task_id: str = Field(foreign_key="task.id", primary_key=True)
    tag_id: str = Field(foreign_key="tag.id", primary_key=True)
