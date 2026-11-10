from datetime import date
from typing import Optional

from fastapi import APIRouter, Depends, Query
from sqlmodel import Session, SQLModel, select

from app import crud
from app.database import get_session
from app.models import Priority, Task, TaskStatus, utcnow

router = APIRouter(prefix="/api/tasks", tags=["tarefas"])


class TaskCreate(SQLModel):
    title: str
    area_id: Optional[str] = None        # None = Caixa de entrada
    goal_id: Optional[str] = None
    resource_id: Optional[str] = None
    notes: Optional[str] = None
    priority: Priority = Priority.normal
    due_date: Optional[date] = None
    scheduled_for: Optional[date] = None
    estimate_minutes: Optional[int] = None


class TaskUpdate(SQLModel):
    title: Optional[str] = None
    area_id: Optional[str] = None
    goal_id: Optional[str] = None
    resource_id: Optional[str] = None
    notes: Optional[str] = None
    status: Optional[TaskStatus] = None
    priority: Optional[Priority] = None
    due_date: Optional[date] = None
    scheduled_for: Optional[date] = None
    estimate_minutes: Optional[int] = None
    position: Optional[int] = None


@router.get("", response_model=list[Task])
def list_tasks(
    session: Session = Depends(get_session),
    area_id: Optional[str] = None,
    status: Optional[TaskStatus] = None,
    inbox: bool = Query(False, description="Só tarefas sem área (Caixa de entrada)"),
    today: bool = Query(False, description="Agendadas ou vencendo até hoje, não concluídas"),
):
    stmt = select(Task).where(Task.deleted_at == None)  # noqa: E711
    if inbox:
        stmt = stmt.where(Task.area_id == None)  # noqa: E711
    if area_id:
        stmt = stmt.where(Task.area_id == area_id)
    if status:
        stmt = stmt.where(Task.status == status)
    if today:
        hoje = date.today()
        stmt = stmt.where(Task.status != TaskStatus.done).where(
            (Task.scheduled_for <= hoje) | (Task.due_date <= hoje)
        )
    stmt = stmt.order_by(Task.position, Task.created_at)
    return list(session.exec(stmt).all())


@router.post("", response_model=Task, status_code=201)
def create_task(data: TaskCreate, session: Session = Depends(get_session)):
    return crud.create(session, Task, data)


@router.get("/{task_id}", response_model=Task)
def get_task(task_id: str, session: Session = Depends(get_session)):
    return crud.get_or_404(session, Task, task_id)


@router.patch("/{task_id}", response_model=Task)
def update_task(task_id: str, data: TaskUpdate, session: Session = Depends(get_session)):
    task = crud.get_or_404(session, Task, task_id)
    if data.status == TaskStatus.done and task.status != TaskStatus.done:
        task.completed_at = utcnow()
    elif data.status and data.status != TaskStatus.done:
        task.completed_at = None
    return crud.update(session, task, data)


@router.delete("/{task_id}", status_code=204)
def delete_task(task_id: str, session: Session = Depends(get_session)):
    crud.soft_delete(session, crud.get_or_404(session, Task, task_id))
