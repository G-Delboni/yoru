"""Uso das IAs: limites do Claude Code e do ChatGPT (Codex), e gasto da API da Anthropic.

Três fontes, cada uma ligada à parte em /ias:

  * Claude Code (assinatura Pro/Max): o mesmo endpoint que o `/usage` do Claude Code
    consulta. Devolve a porcentagem usada da sessão (janela de 5 h) e da semana, com o
    horário de cada reset. A conta é a do login do Claude Code neste computador
    (~/.claude/.credentials.json) ou um token colado à mão.
  * ChatGPT Plus (Codex): o endpoint que o Codex CLI usa para mostrar os limites.
    A conta é a do login do Codex (~/.codex/auth.json) ou um token colado à mão.
    Sem rede, cai para o último registro de limites nas sessões locais do Codex.
  * API da Anthropic: relatórios de uso e custo da organização. Exigem uma
    Admin API key (sk-ant-admin...); chaves comuns não leem relatórios.

Os vínculos ficam em data/ia_contas.json, só neste computador (fora do git).
Os tokens dos logins locais nunca são copiados: o arquivo do CLI é lido a cada consulta,
então quando o CLI renova o login o Yoru acompanha. Se o acesso do Claude Code venceu e o
CLI ainda não o renovou, o Yoru renova com o refresh token, como o CLI faria, e grava o
resultado no mesmo arquivo (o refresh token muda a cada renovação).
"""

from __future__ import annotations

import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional

from app import timeutil
from app.database import DATA_DIR

ACCOUNTS_FILE = DATA_DIR / "ia_contas.json"
CACHE_SECONDS = 120      # cada fonte é consultada no máximo a cada 2 min (o botão força)
HTTP_TIMEOUT = 10
USER_AGENT = "yoru/0.5"

PROVIDERS = ("claude", "codex", "anthropic")
NAMES = {"claude": "Claude Code", "codex": "ChatGPT Plus", "anthropic": "API da Anthropic"}


def claude_home() -> Path:
    return Path(os.environ.get("CLAUDE_CONFIG_DIR") or Path.home() / ".claude")


def codex_home() -> Path:
    return Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex")


# --- Vínculos --------------------------------------------------------------------------

_lock = threading.Lock()


def load_accounts() -> dict[str, dict]:
    try:
        data = json.loads(ACCOUNTS_FILE.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save_accounts(data: dict) -> None:
    tmp = ACCOUNTS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(ACCOUNTS_FILE)


def link(provider: str, mode: str, token: str = "", account_id: str = "") -> None:
    """mode: "local" (login do CLI neste computador) ou "token" (colado à mão)."""
    entry: dict[str, Any] = {"mode": mode, "linked_at": datetime.now(timezone.utc).isoformat()}
    if mode == "token":
        entry["token"] = token.strip()
        if account_id.strip():
            entry["account_id"] = account_id.strip()
    with _lock:
        data = load_accounts()
        data[provider] = entry
        _save_accounts(data)
    forget(provider)


def unlink(provider: str) -> None:
    with _lock:
        data = load_accounts()
        data.pop(provider, None)
        _save_accounts(data)
    forget(provider)


def local_login_available(provider: str) -> bool:
    if provider == "claude":
        return (claude_home() / ".credentials.json").exists()
    if provider == "codex":
        return (codex_home() / "auth.json").exists()
    return False


def mask(secret: str) -> str:
    secret = secret or ""
    return secret[:10] + "…" + secret[-4:] if len(secret) > 18 else "…"


# --- Resultado -------------------------------------------------------------------------


@dataclass
class Limit:
    label: str
    percent: float                     # 0 a 100
    resets_at: Optional[datetime]      # UTC
    group: str = "weekly"              # session | weekly | other
    window: str = ""                   # "5 h", "7 dias"

    @property
    def fraction(self) -> float:
        return max(0.0, min(self.percent / 100, 1.0))

    @property
    def resets_in(self) -> str:
        return falta(self.resets_at)

    @property
    def resets_when(self) -> str:
        return quando(self.resets_at)


@dataclass
class Status:
    provider: str
    linked: bool = False
    mode: str = ""
    account: str = ""                  # "login do Claude Code", "token sk-ant-ad…1a2b"
    plan: str = ""
    error: str = ""
    note: str = ""                     # fonte alternativa, avisos leves
    limits: list[Limit] = field(default_factory=list)
    breakdown: list[tuple[str, float]] = field(default_factory=list)   # (onde, % da semana)
    days: list[tuple[date, float]] = field(default_factory=list)        # série diária
    days_unit: str = ""
    totals: dict[str, Any] = field(default_factory=dict)
    models: list[tuple[str, float]] = field(default_factory=list)       # (modelo, valor)
    fetched_at: Optional[datetime] = None

    @property
    def name(self) -> str:
        return NAMES[self.provider]

    def limit(self, group: str) -> Optional[Limit]:
        return next((l for l in self.limits if l.group == group), None)


# --- Formatação de tempo -----------------------------------------------------------------


def falta(dt: Optional[datetime]) -> str:
    """'em 3 h 12 min', 'em 2 dias', 'agora'."""
    if dt is None:
        return ""
    seconds = (dt - datetime.now(timezone.utc)).total_seconds()
    if seconds <= 60:
        return "agora"
    minutes = int(seconds // 60)
    if minutes < 60:
        return f"em {minutes} min"
    hours, minutes = divmod(minutes, 60)
    if hours < 24:
        return f"em {hours} h {minutes:02d} min" if minutes else f"em {hours} h"
    days, hours = divmod(hours, 24)
    return f"em {days} {'dia' if days == 1 else 'dias'}" + (f" e {hours} h" if hours else "")


def quando(dt: Optional[datetime]) -> str:
    """'hoje, 02:10', 'amanhã, 21:00', 'sábado, 21:00'."""
    if dt is None:
        return ""
    local = timeutil.to_local(dt)
    hora = local.strftime("%H:%M")
    delta = (local.date() - timeutil.today()).days
    if delta == 0:
        return f"hoje, {hora}"
    if delta == 1:
        return f"amanhã, {hora}"
    if 1 < delta < 7:
        return f"{timeutil.DIAS_SEMANA[local.weekday()]}, {hora}"
    return f"{timeutil.data_curta(local.date())}, {hora}"


def parse_time(value: Any) -> Optional[datetime]:
    """ISO 8601 ou segundos desde 1970 → datetime em UTC."""
    if value in (None, ""):
        return None
    try:
        if isinstance(value, (int, float)):
            return datetime.fromtimestamp(float(value), tz=timezone.utc)
        dt = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
    except (ValueError, OSError, OverflowError):
        return None


# --- HTTP ------------------------------------------------------------------------------


class FetchError(Exception):
    pass


class AuthError(FetchError):
    """401: token vencido ou revogado (vale tentar renovar)."""


def http_json(url: str, headers: dict[str, str], body: Optional[dict] = None) -> Any:
    """GET, ou POST em JSON quando há body."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    extra = {"Content-Type": "application/json"} if body is not None else {}
    req = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT, "Accept": "application/json", **extra, **headers})
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as res:
            return json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            body = json.loads(e.read().decode("utf-8", "replace"))
            detail = (body.get("error") or {}).get("message", "") if isinstance(body.get("error"), dict) else str(body.get("detail") or "")
        except Exception:  # noqa: BLE001
            pass
        if e.code == 401:
            raise AuthError("Acesso recusado: o token expirou ou foi revogado." + (f" ({detail})" if detail else ""))
        if e.code == 403:
            raise FetchError("Acesso negado: o token não tem permissão para esta consulta." + (f" ({detail})" if detail else ""))
        if e.code == 429:
            raise FetchError("Muitas consultas seguidas. Tente de novo em um minuto.")
        raise FetchError(f"O serviço respondeu {e.code}." + (f" {detail}" if detail else ""))
    except (urllib.error.URLError, TimeoutError, OSError):
        raise FetchError("Sem conexão com o serviço. Confira a internet.")
    except ValueError:
        raise FetchError("Resposta inesperada do serviço.")


# --- Claude Code ---------------------------------------------------------------------------

CLAUDE_USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
CLAUDE_KIND = {"session": "Sessão", "weekly_all": "Semana, todos os modelos"}


def _claude_plan(creds: dict) -> str:
    tier = (creds.get("rateLimitTier") or "").lower()
    sub = (creds.get("subscriptionType") or "").lower()
    for marker, label in (("max_20x", "Max 20x"), ("max_5x", "Max 5x")):
        if marker in tier:
            return label
    return {"max": "Max", "pro": "Pro", "team": "Team", "enterprise": "Enterprise"}.get(sub, sub.capitalize())


def _claude_limits(data: dict) -> list[Limit]:
    limits: list[Limit] = []
    for item in data.get("limits") or []:
        kind = item.get("kind") or ""
        group = item.get("group") or ("session" if kind == "session" else "weekly")
        label = CLAUDE_KIND.get(kind)
        if not label:
            scope = item.get("scope") or {}
            model = ((scope.get("model") or {}).get("display_name")) or ""
            surface = scope.get("surface") or ""
            label = "Semana, " + (model or (surface if isinstance(surface, str) else "") or kind.replace("_", " "))
        if item.get("percent") is None:
            continue
        limits.append(Limit(label, float(item["percent"]), parse_time(item.get("resets_at")), group,
                            "5 h" if group == "session" else "7 dias"))
    if limits:
        return limits
    # Formato antigo: chaves soltas com utilization e resets_at
    for key, label, group in (("five_hour", "Sessão", "session"), ("seven_day", "Semana, todos os modelos", "weekly"),
                              ("seven_day_opus", "Semana, Opus", "weekly"), ("seven_day_sonnet", "Semana, Sonnet", "weekly")):
        item = data.get(key)
        if isinstance(item, dict) and item.get("utilization") is not None:
            limits.append(Limit(label, float(item["utilization"]), parse_time(item.get("resets_at")), group,
                                "5 h" if group == "session" else "7 dias"))
    return limits


CLAUDE_TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
CLAUDE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"   # client_id público do Claude Code
_claude_refresh_lock = threading.Lock()


def _claude_credentials() -> tuple[Path, dict, dict]:
    """(arquivo, conteúdo inteiro, bloco claudeAiOauth), lido do disco a cada chamada."""
    path = claude_home() / ".credentials.json"
    raw = json.loads(path.read_text(encoding="utf-8"))
    return path, raw, (raw.get("claudeAiOauth") or {})


def _claude_expired(creds: dict, margin: float = 60) -> bool:
    expires = creds.get("expiresAt")
    return bool(expires) and expires / 1000 < time.time() + margin


def _claude_refresh(used_token: str) -> dict:
    """Renova o acesso com o refresh token e grava no arquivo do CLI; devolve o bloco novo.

    Relê o arquivo antes: se o CLI (ou outra consulta) já renovou, usa o que está lá.
    """
    with _claude_refresh_lock:
        path, raw, creds = _claude_credentials()
        if creds.get("accessToken") not in ("", None, used_token) and not _claude_expired(creds):
            return creds
        refresh = creds.get("refreshToken") or ""
        if not refresh:
            raise AuthError("O login do Claude Code expirou. Rode `claude` e faça login de novo.")
        body = {"grant_type": "refresh_token", "refresh_token": refresh, "client_id": CLAUDE_CLIENT_ID}
        if creds.get("scopes"):
            body["scope"] = " ".join(creds["scopes"])
        try:
            data = http_json(CLAUDE_TOKEN_URL, {}, body)
        except AuthError:
            raise AuthError("O login do Claude Code expirou e a renovação foi recusada. Rode `claude` e faça login de novo.")
        except FetchError as e:
            if "respondeu 400" in str(e):   # invalid_grant: refresh token vencido ou já usado
                raise AuthError("O login do Claude Code expirou e a renovação foi recusada. Rode `claude` e faça login de novo.")
            raise
        if not data.get("access_token"):
            raise FetchError("A renovação do login do Claude Code voltou sem token.")
        now = time.time()
        creds = {**creds, "accessToken": data["access_token"],
                 "refreshToken": data.get("refresh_token") or refresh,
                 "expiresAt": int((now + float(data.get("expires_in") or 3600)) * 1000)}
        if data.get("refresh_token_expires_in"):
            creds["refreshTokenExpiresAt"] = int((now + float(data["refresh_token_expires_in"])) * 1000)
        if data.get("scope"):
            creds["scopes"] = data["scope"].split()
        raw["claudeAiOauth"] = creds
        tmp = path.with_name(path.name + ".yoru.tmp")
        tmp.write_text(json.dumps(raw), encoding="utf-8")
        os.replace(tmp, path)
        return creds


def fetch_claude(acc: dict) -> Status:
    st = Status("claude", linked=True, mode=acc.get("mode", ""), days_unit="mil tokens")
    local = st.mode == "local"
    if local:
        try:
            _, _, creds = _claude_credentials()
        except (OSError, ValueError):
            creds = {}
        if not creds.get("accessToken"):
            st.error = "Não encontrei o login do Claude Code neste computador. Rode `claude` e faça login."
            st.days, st.totals = claude_local_activity()
            return st
        token = creds["accessToken"]
        st.account, st.plan = "login do Claude Code", _claude_plan(creds)
    else:
        token = acc.get("token") or ""
        st.account = "token " + mask(token)
    beta = {"anthropic-beta": "oauth-2025-04-20"}
    try:
        if local and _claude_expired(creds):
            token = _claude_refresh(token)["accessToken"]
        try:
            data = http_json(CLAUDE_USAGE_URL, {**beta, "Authorization": f"Bearer {token}"})
        except AuthError:
            if not local:
                raise
            token = _claude_refresh(token)["accessToken"]
            data = http_json(CLAUDE_USAGE_URL, {**beta, "Authorization": f"Bearer {token}"})
        st.limits = _claude_limits(data)
        rows = ((data.get("seven_day_breakdown") or {}).get("rows")) or []
        st.breakdown = [(r.get("display_name") or r.get("key", ""), float(r.get("percent") or 0)) for r in rows if r.get("percent")]
        if not st.limits:
            st.note = "A conta respondeu, mas sem limites de assinatura (é uma conta de API?)."
    except FetchError as e:
        st.error = str(e)
    except (OSError, ValueError):
        st.error = "Não consegui ler ou gravar o login do Claude Code (~/.claude/.credentials.json)."
    st.days, st.totals = claude_local_activity()
    return st


def claude_local_activity(days: int = 7) -> tuple[list[tuple[date, float]], dict]:
    """Tokens e sessões do Claude Code neste computador, lidos das conversas salvas.

    Conta entrada sem cache + criação de cache + saída (a leitura de cache fica de fora:
    é barata e inflaria o número). Cada mensagem aparece várias vezes no arquivo
    enquanto é gerada; vale a última, pelo id.
    """
    today = timeutil.today()
    start = today - timedelta(days=days - 1)
    cutoff = time.time() - (days + 1) * 86400
    per_msg: dict[str, tuple[date, int, str]] = {}
    root = claude_home() / "projects"
    if root.exists():
        for path in root.rglob("*.jsonl"):
            try:
                if path.stat().st_mtime < cutoff:
                    continue
                with path.open(encoding="utf-8", errors="replace") as f:
                    for line in f:
                        if '"usage"' not in line or '"assistant"' not in line:
                            continue
                        try:
                            row = json.loads(line)
                        except ValueError:
                            continue
                        msg = row.get("message") or {}
                        usage = msg.get("usage") or {}
                        ts = parse_time(row.get("timestamp"))
                        if not usage or ts is None:
                            continue
                        day = timeutil.local_date(ts)
                        if day < start:
                            continue
                        n = (usage.get("input_tokens") or 0) + (usage.get("cache_creation_input_tokens") or 0) + (usage.get("output_tokens") or 0)
                        key = msg.get("id") or row.get("uuid") or line[:80]
                        per_msg[key] = (day, n, row.get("sessionId") or path.stem)
            except OSError:
                continue
    by_day = {start + timedelta(days=i): 0 for i in range(days)}
    sessions_today, sessions_week = set(), set()
    for day, n, sid in per_msg.values():
        by_day[day] = by_day.get(day, 0) + n
        sessions_week.add(sid)
        if day == today:
            sessions_today.add(sid)
    series = [(d, round(v / 1000, 1)) for d, v in sorted(by_day.items())]
    return series, {"sessoes_hoje": len(sessions_today), "sessoes_semana": len(sessions_week),
                    "tokens_semana": sum(by_day.values()), "tokens_hoje": by_day.get(today, 0)}


# --- ChatGPT / Codex -----------------------------------------------------------------------

CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"


def _window_label(seconds: Optional[float]) -> tuple[str, str, str]:
    """(rótulo, grupo, janela) a partir da duração da janela."""
    if not seconds:
        return "Limite", "other", ""
    hours = seconds / 3600
    if hours <= 6:
        return "Sessão", "session", f"{hours:g} h"
    if 6 * 24 <= hours <= 8 * 24:
        return "Semana", "weekly", "7 dias"
    return "Limite", "other", (f"{hours / 24:g} dias" if hours >= 24 else f"{hours:g} h")


def _codex_window(item: dict, now: datetime) -> Optional[Limit]:
    if not isinstance(item, dict) or item.get("used_percent") is None:
        return None
    seconds = item.get("limit_window_seconds") or ((item.get("window_minutes") or 0) * 60) or None
    label, group, window = _window_label(seconds)
    reset = parse_time(item.get("reset_at") or item.get("resets_at"))
    if reset is None:
        after = item.get("reset_after_seconds") or item.get("resets_in_seconds")
        reset = now + timedelta(seconds=float(after)) if after is not None else None
    return Limit(label, float(item["used_percent"]), reset, group, window)


def fetch_codex(acc: dict) -> Status:
    st = Status("codex", linked=True, mode=acc.get("mode", ""))
    account_id = ""
    if st.mode == "local":
        try:
            auth = json.loads((codex_home() / "auth.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            st.error = "Não encontrei o login do Codex neste computador. Rode `codex login` com a conta do ChatGPT."
            return codex_local_fallback(st)
        tokens = auth.get("tokens") or {}
        token, account_id = tokens.get("access_token") or "", tokens.get("account_id") or ""
        st.account = "login do Codex"
        if not token:
            st.error = "O login do Codex está com chave de API, não com a conta do ChatGPT. Rode `codex login` e escolha “Sign in with ChatGPT”."
            return codex_local_fallback(st)
    else:
        token, account_id = acc.get("token") or "", acc.get("account_id") or ""
        st.account = "token " + mask(token)
    headers = {"Authorization": f"Bearer {token}"}
    if account_id:
        headers["ChatGPT-Account-Id"] = account_id
    try:
        data = http_json(CODEX_USAGE_URL, headers)
    except FetchError as e:
        st.error = str(e)
        return codex_local_fallback(st)
    now = datetime.now(timezone.utc)
    st.plan = (data.get("plan_type") or "").capitalize()
    rate = data.get("rate_limit") or {}
    for key in ("primary_window", "secondary_window", "primary", "secondary"):
        lim = _codex_window(rate.get(key), now)
        if lim:
            st.limits.append(lim)
    if rate.get("limit_reached"):
        st.note = "Limite atingido: o Codex volta quando a janela renovar."
    if not st.limits:
        st.note = "A conta respondeu, mas sem limites de uso do Codex."
    return st


def codex_local_fallback(st: Status) -> Status:
    """Sem a consulta online, usa o último registro de limites gravado pelo Codex nas sessões."""
    root = codex_home() / "sessions"
    if not root.exists():
        return st
    files = sorted(root.rglob("*.jsonl"), key=lambda p: p.stat().st_mtime, reverse=True)[:5]
    for path in files:
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for line in reversed(lines):
            if '"rate_limits"' not in line:
                continue
            try:
                row = json.loads(line)
            except ValueError:
                continue
            payload = row.get("payload") or row.get("msg") or row
            rl = payload.get("rate_limits") or (payload.get("info") or {}).get("rate_limits")
            if not isinstance(rl, dict):
                continue
            at = parse_time(row.get("timestamp")) or datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc)
            for key in ("primary", "secondary"):
                lim = _codex_window(rl.get(key), at)
                if lim:
                    if lim.resets_at and lim.resets_at < datetime.now(timezone.utc):
                        lim.percent, lim.resets_at = 0.0, None   # a janela já renovou desde então
                    st.limits.append(lim)
            if st.limits:
                st.note = f"Valores do último uso do Codex neste computador ({timeutil.relativo(at)})."
                return st
    return st


# --- API da Anthropic (Admin API) ------------------------------------------------------------

ADMIN_BASE = "https://api.anthropic.com/v1/organizations"


def _admin_pages(path: str, params: list[tuple[str, str]], key: str, max_pages: int = 6) -> list[dict]:
    headers = {"x-api-key": key, "anthropic-version": "2023-06-01"}
    buckets: list[dict] = []
    page = None
    for _ in range(max_pages):
        query = params + ([("page", page)] if page else [])
        data = http_json(f"{ADMIN_BASE}/{path}?{urllib.parse.urlencode(query)}", headers)
        buckets.extend(data.get("data") or [])
        if not data.get("has_more") or not data.get("next_page"):
            break
        page = data["next_page"]
    return buckets


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def fetch_anthropic(acc: dict) -> Status:
    key = acc.get("token") or ""
    st = Status("anthropic", linked=True, mode="token", account="chave " + mask(key))
    if not key.startswith("sk-ant-admin"):
        st.error = "Relatórios de uso só respondem a uma Admin API key (começa com sk-ant-admin). Crie uma em console.anthropic.com → Settings → Admin keys."
        return st
    now = datetime.now(timezone.utc)
    today_utc = now.replace(hour=0, minute=0, second=0, microsecond=0)
    week_start = today_utc - timedelta(days=6)
    month_start = today_utc.replace(day=1)
    cost_start = min(week_start, month_start)
    try:
        cost = _admin_pages("cost_report", [("starting_at", _iso(cost_start)), ("ending_at", _iso(now)),
                                            ("bucket_width", "1d"), ("limit", "31"), ("group_by[]", "description")], key)
        usage = _admin_pages("usage_report/messages", [("starting_at", _iso(week_start)), ("ending_at", _iso(now)),
                                                       ("bucket_width", "1d"), ("limit", "7"), ("group_by[]", "model")], key)
    except FetchError as e:
        st.error = str(e)
        return st

    # Custo: valores em centavos de dólar, como texto decimal
    per_day: dict[date, float] = {}
    by_model_cost: dict[str, float] = {}
    for bucket in cost:
        day = (parse_time(bucket.get("starting_at")) or now).date()
        for r in bucket.get("results") or []:
            usd = float(r.get("amount") or 0) / 100
            per_day[day] = per_day.get(day, 0) + usd
            if day >= week_start.date():
                name = r.get("model") or r.get("description") or "outros"
                by_model_cost[name] = by_model_cost.get(name, 0) + usd
    week_days = [week_start.date() + timedelta(days=i) for i in range(7)]
    st.days = [(d, round(per_day.get(d, 0), 2)) for d in week_days]
    st.days_unit = "US$"

    # Tokens por modelo na semana
    tokens = {"entrada": 0, "cache_leitura": 0, "cache_escrita": 0, "saida": 0}
    by_model_tokens: dict[str, int] = {}
    for bucket in usage:
        for r in bucket.get("results") or []:
            cc = r.get("cache_creation") or {}
            write = (cc.get("ephemeral_5m_input_tokens") or 0) + (cc.get("ephemeral_1h_input_tokens") or 0)
            row = {"entrada": r.get("uncached_input_tokens") or 0, "cache_leitura": r.get("cache_read_input_tokens") or 0,
                   "cache_escrita": write, "saida": r.get("output_tokens") or 0}
            for k, v in row.items():
                tokens[k] += v
            name = r.get("model") or "outros"
            by_model_tokens[name] = by_model_tokens.get(name, 0) + sum(row.values())
    input_all = tokens["entrada"] + tokens["cache_leitura"] + tokens["cache_escrita"]
    st.totals = {
        "custo_semana": sum(v for d, v in st.days),
        "custo_hoje": per_day.get(today_utc.date(), 0.0),
        "custo_mes": sum(v for d, v in per_day.items() if d >= month_start.date()),
        "tokens": tokens,
        "tokens_semana": sum(tokens.values()),
        "cache_pct": round(tokens["cache_leitura"] / input_all * 100) if input_all else None,
    }
    if by_model_cost:
        st.models = sorted(by_model_cost.items(), key=lambda kv: -kv[1])[:6]
        st.totals["modelos_em"] = "US$"
    else:
        st.models = [(m, round(v / 1000, 1)) for m, v in sorted(by_model_tokens.items(), key=lambda kv: -kv[1])[:6]]
        st.totals["modelos_em"] = "mil tokens"
    st.note = "Os relatórios atrasam cerca de 5 minutos. Dias em UTC."
    return st


# --- Consulta com cache ----------------------------------------------------------------------

FETCHERS = {"claude": fetch_claude, "codex": fetch_codex, "anthropic": fetch_anthropic}
_cache: dict[str, tuple[float, Status]] = {}


def forget(provider: str) -> None:
    _cache.pop(provider, None)


def status(provider: str, force: bool = False) -> Status:
    acc = load_accounts().get(provider)
    if not acc:
        st = Status(provider)
        if provider == "claude":
            st.days, st.totals = claude_local_activity()
            st.days_unit = "mil tokens"
        return st
    hit = _cache.get(provider)
    if hit and not force and time.time() - hit[0] < CACHE_SECONDS:
        return hit[1]
    try:
        st = FETCHERS[provider](acc)
    except Exception as e:  # noqa: BLE001  (uma fonte com defeito não derruba a página)
        st = Status(provider, linked=True, mode=acc.get("mode", ""), error=f"Falha ao ler os dados: {e}")
    st.fetched_at = datetime.now(timezone.utc)
    if not st.error:   # erro não fica em cache: depois de relogar, a próxima consulta já vale
        _cache[provider] = (time.time(), st)
    return st


def all_status() -> dict[str, Status]:
    with ThreadPoolExecutor(max_workers=len(PROVIDERS)) as pool:
        return dict(zip(PROVIDERS, pool.map(status, PROVIDERS)))
