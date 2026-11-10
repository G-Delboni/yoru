from pathlib import Path

from fastapi.templating import Jinja2Templates

from app import timeutil

TEMPLATES_DIR = Path(__file__).resolve().parent / "templates"

templates = Jinja2Templates(directory=str(TEMPLATES_DIR))

env = templates.env
env.filters["data_longa"] = timeutil.data_longa
env.filters["data_curta"] = timeutil.data_curta
env.filters["prazo"] = timeutil.prazo
env.filters["dias"] = timeutil.days_until
env.filters["dia_local"] = timeutil.local_date
env.filters["semana"] = lambda d: timeutil.DIAS_SEMANA[d.weekday()]
env.filters["hora"] = lambda dt: "" if (dt.hour, dt.minute) == (0, 0) else dt.strftime("%H:%M")
env.filters["num"] = lambda v: ("%g" % v).replace(".", ",") if v is not None else ""
env.filters["hora_local"] = lambda dt: (timeutil.to_local(dt).strftime("%H:%M") if dt else "")
env.filters["relativo"] = timeutil.relativo
env.globals["saudacao"] = timeutil.saudacao


def compacto(n) -> str:
    """1234 → '1,2 mil', 3400000 → '3,4 mi'."""
    n = float(n or 0)
    for limite, sufixo in ((1e9, " bi"), (1e6, " mi"), (1e3, " mil")):
        if abs(n) >= limite:
            return ("%.1f" % (n / limite)).rstrip("0").rstrip(".").replace(".", ",") + sufixo
    return "%d" % n


def dolar(v) -> str:
    v = float(v or 0)
    return "US$ " + (("%.2f" % v) if v >= 0.01 or v == 0 else "%.4f" % v).replace(".", ",")


env.filters["compacto"] = compacto
env.filters["dolar"] = dolar
env.filters["semana_curta"] = lambda d: timeutil.DIAS_SEMANA[d.weekday()][:3]
