"""Gera rotas CRUD para um modelo com SyncBase.

Os esquemas de criação e de atualização são derivados do próprio modelo,
sem os campos controlados pelo servidor (id e carimbos de data).
"""

from typing import Optional, Type

from fastapi import APIRouter, Depends
from pydantic import create_model
from sqlmodel import Session, select

from app import crud
from app.database import get_session
from app.models import SyncBase

SERVER_FIELDS = {"id", "created_at", "updated_at", "deleted_at", "synced_at"}


def _schemas(model: Type[SyncBase]):
    fields = {n: f for n, f in model.model_fields.items() if n not in SERVER_FIELDS}
    create_fields = {}
    update_fields = {}
    for name, info in fields.items():
        annotation = info.annotation
        if info.is_required():
            create_fields[name] = (annotation, ...)
        else:
            default = info.get_default(call_default_factory=True)
            create_fields[name] = (annotation, default)
        update_fields[name] = (Optional[annotation], None)
    create = create_model(f"{model.__name__}Create", **create_fields)
    update = create_model(f"{model.__name__}Update", **update_fields)
    return create, update


def crud_router(model: Type[SyncBase], prefix: str, tag: str, filters: tuple[str, ...] = ()) -> APIRouter:
    Create, Update = _schemas(model)
    router = APIRouter(prefix=prefix, tags=[tag])
    name = model.__name__

    @router.get("", response_model=list[model], name=f"listar {name}")
    def list_items(session: Session = Depends(get_session), area_id: Optional[str] = None):
        stmt = select(model).where(model.deleted_at == None)  # noqa: E711
        if area_id and "area_id" in filters:
            stmt = stmt.where(model.area_id == area_id)
        return list(session.exec(stmt).all())

    @router.post("", response_model=model, status_code=201, name=f"criar {name}")
    def create_item(data: Create, session: Session = Depends(get_session)):  # type: ignore[valid-type]
        item = model(**data.model_dump())
        session.add(item)
        session.commit()
        session.refresh(item)
        return item

    @router.get("/{item_id}", response_model=model, name=f"ler {name}")
    def get_item(item_id: str, session: Session = Depends(get_session)):
        return crud.get_or_404(session, model, item_id)

    @router.patch("/{item_id}", response_model=model, name=f"atualizar {name}")
    def update_item(item_id: str, data: Update, session: Session = Depends(get_session)):  # type: ignore[valid-type]
        return crud.update(session, crud.get_or_404(session, model, item_id), data)

    @router.delete("/{item_id}", status_code=204, name=f"excluir {name}")
    def delete_item(item_id: str, session: Session = Depends(get_session)):
        crud.soft_delete(session, crud.get_or_404(session, model, item_id))

    return router
