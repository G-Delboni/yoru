from datetime import date
from typing import Optional

from fastapi import APIRouter, Depends
from sqlmodel import Session, SQLModel, select

from app import crud
from app.database import get_session
from app.models import LogEntry

router = APIRouter(prefix="/api/logs", tags=["diário"])


class LogCreate(SQLModel):
    area_id: str
    day: Optional[date] = None
    task_id: Optional[str] = None
    resource_id: Optional[str] = None
    did: Optional[str] = None
    stopped_at: Optional[str] = None
    next_step: Optional[str] = None
    minutes: Optional[int] = None


class LogUpdate(SQLModel):
    did: Optional[str] = None
    stopped_at: Optional[str] = None
    next_step: Optional[str] = None
    minutes: Optional[int] = None


@router.get("", response_model=list[LogEntry])
def list_logs(session: Session = Depends(get_session), area_id: Optional[str] = None, limit: int = 60):
    stmt = select(LogEntry).where(LogEntry.deleted_at == None)  # noqa: E711
    if area_id:
        stmt = stmt.where(LogEntry.area_id == area_id)
    stmt = stmt.order_by(LogEntry.day.desc(), LogEntry.created_at.desc()).limit(limit)
    return list(session.exec(stmt).all())


@router.post("", response_model=LogEntry, status_code=201)
def create_log(data: LogCreate, session: Session = Depends(get_session)):
    payload = data.model_dump(exclude_none=True)
    entry = LogEntry(**payload)
    session.add(entry)
    session.commit()
    session.refresh(entry)
    return entry


@router.patch("/{log_id}", response_model=LogEntry)
def update_log(log_id: str, data: LogUpdate, session: Session = Depends(get_session)):
    return crud.update(session, crud.get_or_404(session, LogEntry, log_id), data)


@router.delete("/{log_id}", status_code=204)
def delete_log(log_id: str, session: Session = Depends(get_session)):
    crud.soft_delete(session, crud.get_or_404(session, LogEntry, log_id))
