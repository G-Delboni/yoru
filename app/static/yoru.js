/* yoru.js: atualiza partes da página sem recarregar, sem dependências.

   Atributos que ele entende:
     data-post="/url"        envia um POST (botão ao clicar, formulário ao enviar)
     data-alvo="seletor"     onde colocar a resposta; "this" (padrão) ou "closest .x"
     data-troca="..."        outer (padrão) | inner | inicio | apagar | nada
     data-limpar             limpa o formulário depois de enviar
     data-atualizar="/url"   recarrega este trecho quando um evento acontecer...
     data-quando="tarefas"   ...com este nome (vindo do cabeçalho X-Yoru-Trigger)
     data-cada="2000"        ...ou sozinho, a cada N milissegundos

   O servidor pode responder com:
     X-Yoru-Trigger: tarefas     dispara o evento "tarefas"
     X-Yoru-Redirect: /url       navega para outra página
*/
(() => {
  "use strict";

  function resolveTarget(el) {
    const sel = el.dataset.alvo || "this";
    if (sel === "this") return el;
    if (sel.startsWith("closest ")) return el.closest(sel.slice(8));
    return document.querySelector(sel);
  }

  const semMovimento = matchMedia("(prefers-reduced-motion: reduce)");

  // Marca um elemento com uma classe de animação e a tira quando ela termina
  function animar(el, classe, depois) {
    if (!el) return;
    if (semMovimento.matches) { depois && depois(); return; }
    el.classList.add(classe);
    let feito = false;
    const fim = () => { if (feito) return; feito = true; el.classList.remove(classe); depois && depois(); };
    el.addEventListener("animationend", fim, { once: true });
    setTimeout(fim, 1800);
  }

  // Ensō: lê os traços antigos para que os novos partam deles e se fechem devagar
  function lerTracos(el) {
    return [...(el ? el.querySelectorAll(".enso-traco") : [])].map((c) => c.getAttribute("stroke-dashoffset"));
  }
  function animarTracos(el, antigos) {
    if (!el || semMovimento.matches) return;
    const novos = [...el.querySelectorAll(".enso-traco")];
    if (!novos.length) return;
    const alvo = novos.map((c) => c.getAttribute("stroke-dashoffset"));
    novos.forEach((c, i) => c.setAttribute("stroke-dashoffset", antigos[i] ?? c.getAttribute("stroke-dasharray")));
    void el.getBoundingClientRect();
    novos.forEach((c, i) => c.setAttribute("stroke-dashoffset", alvo[i]));
  }

  // Substitui `target` pelo HTML e devolve o primeiro elemento novo
  function trocarPor(target, html) {
    const antigos = lerTracos(target);
    target.insertAdjacentHTML("beforebegin", html);
    const novo = target.previousElementSibling;
    target.remove();
    animarTracos(novo, antigos);
    return novo;
  }

  function swap(target, html, mode) {
    if (!target) return;
    switch (mode) {
      case "inner": target.innerHTML = html; break;
      case "inicio": {
        target.insertAdjacentHTML("afterbegin", html);
        animar(target.firstElementChild, "entrando");
        break;
      }
      case "apagar": animar(target, "saindo", () => target.remove()); break;
      case "nada": break;
      default: animar(trocarPor(target, html), "recem");
    }
  }

  function fire(header) {
    if (!header) return;
    header.split(",").map((s) => s.trim()).filter(Boolean)
      .forEach((name) => document.dispatchEvent(new CustomEvent("yoru:" + name)));
  }

  async function send(el, url, body) {
    if (el.dataset.ocupado) return;
    el.dataset.ocupado = "1";
    try {
      const res = await fetch(url, { method: "POST", body, headers: { "X-Yoru": "1" } });
      const redirect = res.headers.get("X-Yoru-Redirect");
      if (redirect) { window.location.href = redirect; return; }
      const html = await res.text();
      const target = resolveTarget(el);
      // Em erro de validação (422) a mensagem vai para o alvo de avisos, se houver
      if (!res.ok && el.dataset.avisos) {
        swap(document.querySelector(el.dataset.avisos), html, "inner");
        return;
      }
      if (!res.ok) { console.error("Yoru:", res.status, html); return; }
      if (el.tagName === "FORM" && el.hasAttribute("data-limpar")) el.reset();
      swap(target, html, el.dataset.troca || "outer");
      fire(res.headers.get("X-Yoru-Trigger"));
    } catch (err) {
      console.error("Yoru: sem resposta do servidor", err);
    } finally {
      delete el.dataset.ocupado;
    }
  }

  document.addEventListener("submit", (ev) => {
    const form = ev.target.closest("form[data-post]");
    if (!form) return;
    ev.preventDefault();
    send(form, form.dataset.post, new FormData(form));
  });

  document.addEventListener("click", (ev) => {
    const el = ev.target.closest("button[data-post]");
    if (!el || el.form?.hasAttribute("data-post")) return;
    ev.preventDefault();
    const body = new FormData();
    for (const [k, v] of Object.entries(el.dataset)) {
      if (k.startsWith("campo")) body.append(k.slice(5).toLowerCase(), v);
    }
    send(el, el.dataset.post, body);
  });

  // Trechos que se recarregam quando algo muda em outra parte da página
  const listening = new Set();
  function bindRefreshers() {
    document.querySelectorAll("[data-atualizar][data-quando]").forEach((el) => {
      el.dataset.quando.split(",").map((s) => s.trim()).forEach((name) => {
        const key = name + "|" + el.dataset.atualizar;
        if (listening.has(key)) return;
        listening.add(key);
        document.addEventListener("yoru:" + name, async () => {
          const current = document.querySelector(`[data-atualizar="${el.dataset.atualizar}"]`);
          if (!current) return;
          const res = await fetch(el.dataset.atualizar, { headers: { "X-Yoru": "1" } });
          if (res.ok) trocarPor(current, await res.text());
        });
      });
    });
  }
  bindRefreshers();

  // Trechos que se recarregam sozinhos a cada N ms (data-cada), enquanto existirem na página
  function bindPollers() {
    document.querySelectorAll("[data-atualizar][data-cada]").forEach((el) => {
      if (el.dataset.ligado) return;
      el.dataset.ligado = "1";
      const url = el.dataset.atualizar;
      const timer = setInterval(async () => {
        const current = document.querySelector(`[data-atualizar="${url}"]`);
        if (!current) { clearInterval(timer); return; }
        const res = await fetch(url, { headers: { "X-Yoru": "1" } });
        if (!res.ok) return;
        trocarPor(current, await res.text());
        bindPollers();
      }, Number(el.dataset.cada) || 2000);
    });
  }
  bindPollers();

  // Ao abrir a página, cada ensō se fecha até a fração do dia em vez de já aparecer pronto
  document.querySelectorAll(".enso-circulo").forEach((el) => animarTracos(el, []));

  // Atalhos "adicionar ...": o endereço termina em #id. Se o alvo for um <details>, abre;
  // depois foca o primeiro campo e rola até ele.
  function abrirAlvo() {
    const id = decodeURIComponent(location.hash.slice(1));
    const alvo = id && document.getElementById(id);
    if (!alvo) return;
    if (alvo.tagName === "DETAILS") alvo.open = true;
    const campo = alvo.matches("input, textarea, select") ? alvo : alvo.querySelector("input:not([type=hidden]), textarea, select");
    alvo.scrollIntoView({ behavior: semMovimento.matches ? "auto" : "smooth", block: "center" });
    if (campo) setTimeout(() => campo.focus({ preventScroll: true }), semMovimento.matches ? 0 : 350);
  }
  abrirAlvo();
  window.addEventListener("hashchange", abrirAlvo);
  // Clicar de novo no mesmo atalho não muda o hash: trata aqui
  document.addEventListener("click", (ev) => {
    const a = ev.target.closest("a.marca-link");
    if (a && a.hash && a.pathname === location.pathname && a.hash === location.hash) { ev.preventDefault(); abrirAlvo(); }
  });

  // Seção "Como usar": abre e fecha deslizando a altura, em vez de aparecer de uma vez.
  // Um clique no meio da animação inverte o sentido a partir da altura em que ela está.
  document.querySelectorAll("details.como-usar").forEach((d) => {
    const summary = d.querySelector("summary");
    let anim = null;
    summary.addEventListener("click", (ev) => {
      if (semMovimento.matches) return;
      ev.preventDefault();
      const atual = d.offsetHeight;                 // já reflete uma animação em curso
      const abrir = !d.open || d.classList.contains("fechando");
      if (anim) anim.cancel();
      // Mede as duas alturas sem pintar: fechada e aberta
      d.open = false; const fechada = d.offsetHeight;
      d.open = true; const aberta = d.offsetHeight;
      d.classList.toggle("fechando", !abrir);
      d.style.overflow = "hidden";
      anim = d.animate(
        { height: [atual + "px", (abrir ? aberta : fechada) + "px"] },
        { duration: abrir ? 480 : 360, easing: "cubic-bezier(.2,.8,.2,1)" },
      );
      anim.onfinish = () => {
        anim = null;
        d.style.overflow = "";
        d.classList.remove("fechando");
        if (!abrir) d.open = false;
      };
      acompanhar(abrir ? alvoAoAbrir(d, aberta) : alvoAoFechar(atual - fechada), anim);
    });
    // Sem animação: ao abrir, só pula até a seção
    d.addEventListener("toggle", () => {
      if (semMovimento.matches && d.open) d.scrollIntoView({ block: "nearest" });
    });
  });

  // A seção fica no fim da página: ao abrir ela cresce para fora da tela, ao fechar a página
  // encolhe por baixo de quem está lendo. Nos dois casos a página rola junto, no mesmo
  // ritmo da animação (mesma curva), em vez de ficar parada ou pular no fim.

  // Abrir: desce até a seção inteira ficar visível; se for mais alta que a tela,
  // para com o topo da seção no alto. Nunca sobe.
  function alvoAoAbrir(el, alturaFinal) {
    const topo = el.getBoundingClientRect().top + window.scrollY;
    return Math.max(window.scrollY, Math.min(topo - 16, topo + alturaFinal - window.innerHeight + 24));
  }

  // Fechar: sobe até o novo fim da página (a página perde `encolhe` px de altura). Nunca desce.
  function alvoAoFechar(encolhe) {
    const fimDaPagina = document.documentElement.scrollHeight - encolhe - window.innerHeight;
    return Math.max(0, Math.min(window.scrollY, fimDaPagina));
  }

  function acompanhar(fim, anim) {
    const inicio = window.scrollY;
    if (Math.abs(fim - inicio) < 1) return;
    const passo = () => {
      if (!anim || anim.playState === "idle") return;          // cancelada: outro clique assumiu
      const p = anim.effect.getComputedTiming().progress ?? 1;  // já com a curva aplicada
      window.scrollTo(0, inicio + (fim - inicio) * p);
      if (anim.playState === "running") requestAnimationFrame(passo);
    };
    requestAnimationFrame(passo);
    anim.finished.then(() => window.scrollTo(0, fim)).catch(() => {});
  }

  // Menu de áreas: abre sozinho depois de o mouse repousar sobre ele por ESPERA_MS.
  // Enquanto espera, um traço cresce sob "Áreas" mostrando que vai abrir.
  // Aberto pelo mouse, fecha logo depois que o mouse sai; aberto por clique, fica até clicar fora.
  const ESPERA_MS = 1250;
  const podeRepousar = matchMedia("(hover: hover) and (pointer: fine)");
  document.querySelectorAll(".nav-grupo").forEach((grupo) => {
    grupo.style.setProperty("--espera", ESPERA_MS + "ms");
    let abrir = 0, fechar = 0;
    grupo.addEventListener("mouseenter", () => {
      if (!podeRepousar.matches) return;
      clearTimeout(fechar);
      if (grupo.open) return;
      grupo.classList.add("esperando");
      abrir = setTimeout(() => {
        grupo.classList.remove("esperando");
        grupo.dataset.peloMouse = "1";
        grupo.open = true;
      }, ESPERA_MS);
    });
    grupo.addEventListener("mouseleave", () => {
      clearTimeout(abrir);
      grupo.classList.remove("esperando");
      if (grupo.open && grupo.dataset.peloMouse) fechar = setTimeout(() => { grupo.open = false; }, 400);
    });
    grupo.addEventListener("toggle", () => {
      clearTimeout(abrir);
      grupo.classList.remove("esperando");
      if (!grupo.open) delete grupo.dataset.peloMouse;
    });
    // Clique no "Áreas" enquanto aberto pelo mouse: fixa aberto em vez de fechar
    grupo.querySelector("summary").addEventListener("click", (ev) => {
      if (grupo.open && grupo.dataset.peloMouse) { ev.preventDefault(); delete grupo.dataset.peloMouse; }
    });
  });

  // Menu de áreas: fecha ao clicar fora ou com Esc
  document.addEventListener("click", (ev) => {
    document.querySelectorAll(".nav-grupo[open]").forEach((d) => { if (!d.contains(ev.target)) d.open = false; });
  });
  document.addEventListener("keydown", (ev) => {
    if (ev.key !== "Escape") return;
    document.querySelectorAll(".nav-grupo[open]").forEach((d) => { d.open = false; d.querySelector("summary").focus(); });
  });
})();
