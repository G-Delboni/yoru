from typing import Optional

from fastapi import APIRouter, Depends
from sqlmodel import Session, SQLModel

from app import crud
from app.database import get_session
from app.models import Area

router = APIRouter(prefix="/api/areas", tags=["áreas"])


class AreaCreate(SQLModel):
    name: str
    slug: str
    kanji: Optional[str] = None
    legend: Optional[str] = None
    position: int = 0


class AreaUpdate(SQLModel):
    name: Optional[str] = None
    slug: Optional[str] = None
    kanji: Optional[str] = None
    legend: Optional[str] = None
    position: Optional[int] = None


@router.get("", response_model=list[Area])
def list_areas(session: Session = Depends(get_session)):
    return sorted(crud.list_all(session, Area), key=lambda a: a.position)


@router.post("", response_model=Area, status_code=201)
def create_area(data: AreaCreate, session: Session = Depends(get_session)):
    return crud.create(session, Area, data)


@router.get("/{area_id}", response_model=Area)
def get_area(area_id: str, session: Session = Depends(get_session)):
    return crud.get_or_404(session, Area, area_id)


@router.patch("/{area_id}", response_model=Area)
def update_area(area_id: str, data: AreaUpdate, session: Session = Depends(get_session)):
    area = crud.get_or_404(session, Area, area_id)
    return crud.update(session, area, data)


@router.delete("/{area_id}", status_code=204)
def delete_area(area_id: str, session: Session = Depends(get_session)):
    crud.soft_delete(session, crud.get_or_404(session, Area, area_id))
