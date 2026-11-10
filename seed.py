"""Cria as áreas padrão. Pode rodar quantas vezes quiser: não duplica.

    python seed.py
"""

import uuid

from sqlmodel import Session, select

from app.database import engine, init_db
from app.models import Area

AREAS = [
    # slug, nome, kanji, legenda
    ("trabalho", "Trabalho", "務", "trabalho"),
    ("estudos", "Estudos", "学", "estudos"),
    ("leituras", "Leituras", "読", "leitura"),
    ("faculdade", "Faculdade", "科", "faculdade"),
    ("pessoal", "Pessoal", "私", "pessoal"),
]


# Id fixo por área: um celular que crie as áreas do zero chega aos mesmos ids deste servidor.
NAMESPACE = uuid.UUID("6f0c2d3e-3b0a-4f55-9d0e-7a1d5c9a4e21")


def run() -> None:
    init_db()
    with Session(engine) as session:
        existing = {a.slug for a in session.exec(select(Area)).all()}
        created = 0
        for position, (slug, name, kanji, legend) in enumerate(AREAS):
            if slug in existing:
                continue
            session.add(Area(id=str(uuid.uuid5(NAMESPACE, slug)), slug=slug, name=name, kanji=kanji, legend=legend, position=position))
            created += 1
        session.commit()
    print(f"Áreas criadas: {created} (já existiam: {len(existing)})")


if __name__ == "__main__":
    run()
