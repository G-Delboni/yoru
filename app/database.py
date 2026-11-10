"""Conexão com o SQLite local.

O banco é um único arquivo em data/yoru.db. Fazer backup é copiar esse arquivo.
"""

import logging
from pathlib import Path

from sqlalchemy import inspect, text
from sqlmodel import Session, SQLModel, create_engine

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
DATA_DIR.mkdir(exist_ok=True)

DB_PATH = DATA_DIR / "yoru.db"
DATABASE_URL = f"sqlite:///{DB_PATH}"

# check_same_thread=False: o FastAPI usa threads diferentes por requisição.
engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})

log = logging.getLogger("yoru")


def init_db() -> None:
    """Cria tabelas novas e acrescenta colunas novas às tabelas que já existem."""
    from app import models  # noqa: F401  (registra as tabelas no metadata)

    SQLModel.metadata.create_all(engine)
    add_missing_columns()


def add_missing_columns() -> None:
    """Migração simples: quando um modelo ganha um campo opcional, cria a coluna no SQLite.

    Só acrescenta colunas que aceitam nulo (ou têm valor padrão simples).
    Renomear ou remover colunas continua exigindo uma migração feita à mão.
    """
    inspector = inspect(engine)
    with engine.begin() as conn:
        for table in SQLModel.metadata.sorted_tables:
            existing = {c["name"] for c in inspector.get_columns(table.name)}
            for column in table.columns:
                if column.name in existing:
                    continue
                col_type = column.type.compile(dialect=engine.dialect)
                default = ""
                if column.default is not None and getattr(column.default, "is_scalar", False):
                    value = column.default.arg
                    default = f" DEFAULT {int(value) if isinstance(value, bool) else repr(value)}"
                elif not column.nullable:
                    log.warning("Coluna obrigatória %s.%s não criada: precisa de migração manual",
                                table.name, column.name)
                    continue
                conn.execute(text(f'ALTER TABLE "{table.name}" ADD COLUMN "{column.name}" {col_type}{default}'))
                log.info("Coluna criada: %s.%s", table.name, column.name)


def get_session():
    """Dependência do FastAPI: uma sessão por requisição."""
    with Session(engine) as session:
        yield session
