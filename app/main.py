"""Yoru (夜): organizador pessoal local.

Rodar:  uvicorn app.main:app --reload
Site:   http://127.0.0.1:8000
API:    http://127.0.0.1:8000/docs
"""

from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import PlainTextResponse
from fastapi.staticfiles import StaticFiles

from app.database import init_db
from app.models import Event, FocusSession, Goal, Habit, HabitCheck, Objective, Resource
from app import sync  # noqa: F401  (liga o carimbo automático de updated_at)
from app.routers import areas, logs, pages, pages_areas, pages_dia, pages_ia, pages_sync, pages_track, tasks
from app.routers import sync as sync_routes
from app.routers.factory import crud_router

STATIC_DIR = Path(__file__).resolve().parent / "static"


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    sync.get_token()  # cria data/sync_token.txt na primeira vez
    yield


app = FastAPI(title="Yoru", version="0.5.1", lifespan=lifespan)

LOOPBACK = {"127.0.0.1", "::1", "localhost"}
OPEN_TO_NETWORK = ("/api/sync", "/api/health")


@app.middleware("http")
async def keep_pages_local(request: Request, call_next):
    """Se o servidor for aberto para a rede (--host 0.0.0.0) para o celular sincronizar,
    só a API de sincronia (que exige token) e o teste de saúde ficam acessíveis de fora.
    As páginas e o resto da API continuam valendo apenas neste computador."""
    host = request.client.host if request.client else ""
    if host not in LOOPBACK and not request.url.path.startswith(OPEN_TO_NETWORK):
        return PlainTextResponse("Acesso permitido apenas neste computador.", status_code=403)
    return await call_next(request)


app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

# API (JSON)
app.include_router(areas.router)
app.include_router(tasks.router)
app.include_router(logs.router)
app.include_router(sync_routes.router)
app.include_router(crud_router(Resource, "/api/resources", "recursos", filters=("area_id",)))
app.include_router(crud_router(Event, "/api/events", "eventos", filters=("area_id",)))
app.include_router(crud_router(Objective, "/api/objectives", "objetivos", filters=("area_id",)))
app.include_router(crud_router(Goal, "/api/goals", "metas", filters=("area_id",)))
app.include_router(crud_router(Habit, "/api/habits", "hábitos", filters=("area_id",)))
app.include_router(crud_router(HabitCheck, "/api/habit-checks", "hábitos"))
app.include_router(crud_router(FocusSession, "/api/focus-sessions", "foco", filters=("area_id",)))

# Páginas (HTML)
app.include_router(pages.router)
app.include_router(pages_areas.router)
app.include_router(pages_sync.router)
app.include_router(pages_track.router)
app.include_router(pages_ia.router)
app.include_router(pages_dia.router)


@app.get("/api/health", tags=["sistema"])
def health():
    return {"status": "ok", "app": "yoru", "version": app.version}
