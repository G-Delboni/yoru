"""Operações comuns a todas as tabelas com SyncBase.

Toda alteração atualiza updated_at; toda exclusão é suave (deleted_at).
"""

from typing import Type, TypeVar

from fastapi import HTTPException
from sqlmodel import Session, SQLModel, select

from app.models import SyncBase, utcnow

T = TypeVar("T", bound=SyncBase)


def list_all(session: Session, model: Type[T], include_deleted: bool = False) -> list[T]:
    stmt = select(model)
    if not include_deleted:
        stmt = stmt.where(model.deleted_at == None)  # noqa: E711
    return list(session.exec(stmt).all())


def get_or_404(session: Session, model: Type[T], item_id: str) -> T:
    item = session.get(model, item_id)
    if item is None or item.deleted_at is not None:
        raise HTTPException(status_code=404, detail=f"{model.__name__} não encontrado")
    return item


def create(session: Session, model: Type[T], data: SQLModel) -> T:
    item = model.model_validate(data)
    session.add(item)
    session.commit()
    session.refresh(item)
    return item


def update(session: Session, item: T, data: SQLModel) -> T:
    changes = data.model_dump(exclude_unset=True)
    for key, value in changes.items():
        setattr(item, key, value)
    item.updated_at = utcnow()
    session.add(item)
    session.commit()
    session.refresh(item)
    return item


def soft_delete(session: Session, item: T) -> None:
    item.deleted_at = utcnow()
    item.updated_at = item.deleted_at
    session.add(item)
    session.commit()
