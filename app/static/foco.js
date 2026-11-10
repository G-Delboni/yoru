// Pomodoro do Yoru. Sem dependências. O tempo é contado pelo relógio do sistema
// (instante de término), não por contagem de ticks: aba em segundo plano não atrasa.
(() => {
  const $ = (id) => document.getElementById(id);
  const PREFS_KEY = "yoru.foco";
  const padrao = { foco: 25, pausa: 5, longa: 15, ciclos: 4 };

  let prefs = { ...padrao };
  try { prefs = { ...padrao, ...JSON.parse(localStorage.getItem(PREFS_KEY) || "{}") }; } catch (e) {}

  const campos = { foco: $("min-foco"), pausa: $("min-pausa"), longa: $("min-longa"), ciclos: $("ciclos") };
  for (const k in campos) {
    campos[k].value = prefs[k];
    campos[k].addEventListener("change", () => {
      const v = parseInt(campos[k].value, 10);
      if (v > 0) prefs[k] = v; else campos[k].value = prefs[k];
      try { localStorage.setItem(PREFS_KEY, JSON.stringify(prefs)); } catch (e) {}
      $("foco-ciclos-texto").textContent = prefs.ciclos;
      if (!estado.rodando) mostrar(duracaoAtual() * 60);
      mostrarSequencia();
    });
  }
  $("foco-ciclos-texto").textContent = prefs.ciclos;

  // modo: "focus" | "break" | "long"; focosFeitos conta os focos completos da sequência
  const estado = { modo: "focus", rodando: false, fim: 0, inicio: null, restanteAoPausar: 0, focosFeitos: 0, timer: null };
  const ARCO = 540.4, ARCO2 = 559.2;
  const tituloOriginal = document.title;

  function duracaoAtual() {
    return estado.modo === "focus" ? prefs.foco : estado.modo === "long" ? prefs.longa : prefs.pausa;
  }
  function rotulo() { return estado.modo === "focus" ? "Foco" : estado.modo === "long" ? "Pausa longa" : "Pausa"; }

  function mostrar(segundos) {
    const total = duracaoAtual() * 60;
    const m = Math.floor(segundos / 60), s = Math.floor(segundos % 60);
    const txt = `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
    $("foco-tempo").textContent = txt;
    $("foco-modo").textContent = rotulo();
    const f = total > 0 ? 1 - segundos / total : 0;
    $("foco-arco").setAttribute("stroke-dashoffset", (ARCO * (1 - f * 0.94)).toFixed(1));
    $("foco-arco-2").setAttribute("stroke-dashoffset", (ARCO2 * (1 - f * 0.94)).toFixed(1));
    document.title = estado.rodando ? `${txt} · ${rotulo()} · Yoru` : tituloOriginal;
    document.body.classList.toggle("foco-rodando", estado.rodando);
  }

  // Um pulso de luz no círculo quando um bloco termina
  function pulsar() {
    const relogio = document.querySelector(".foco-relogio");
    if (!relogio) return;
    relogio.classList.remove("concluiu");
    void relogio.offsetWidth;
    relogio.classList.add("concluiu");
  }

  // Onde estamos na sequência foco → pausa → … → pausa longa
  function mostrarSequencia() {
    const n = estado.focosFeitos % prefs.ciclos;
    $("foco-sequencia").textContent = estado.modo === "long"
      ? `${prefs.ciclos} de ${prefs.ciclos} focos: pausa longa`
      : `${n} de ${prefs.ciclos} focos até a pausa longa`;
    // Só faz sentido resetar parado e com algo a resetar
    $("foco-resetar").hidden = estado.rodando || estado.restanteAoPausar > 0 || (estado.focosFeitos === 0 && estado.modo === "focus");
  }

  function tick() {
    const restante = Math.max(0, (estado.fim - Date.now()) / 1000);
    mostrar(restante);
    if (restante <= 0) concluir(true);
  }

  function iniciar() {
    const segundos = estado.restanteAoPausar || duracaoAtual() * 60;
    if (!estado.inicio) estado.inicio = new Date().toISOString();
    estado.fim = Date.now() + segundos * 1000;
    estado.restanteAoPausar = 0;
    estado.rodando = true;
    estado.timer = setInterval(tick, 250);
    $("foco-resetar").hidden = true;
    $("foco-iniciar").textContent = "Pausar";
    $("foco-encerrar").hidden = false;
    $("foco-pular").hidden = false;
    $("foco-titulo").textContent = estado.modo === "focus" ? "Em foco" : "Respire";
    tick();
  }

  function pausar() {
    clearInterval(estado.timer);
    estado.restanteAoPausar = Math.max(0, (estado.fim - Date.now()) / 1000);
    estado.rodando = false;
    $("foco-iniciar").textContent = "Continuar";
    document.title = tituloOriginal;
  }

  function minutosDecorridos() {
    const total = duracaoAtual() * 60;
    const restante = estado.rodando ? Math.max(0, (estado.fim - Date.now()) / 1000) : estado.restanteAoPausar;
    return Math.round((total - restante) / 60);
  }

  async function registrar(completa) {
    const minutos = completa ? duracaoAtual() : minutosDecorridos();
    const tarefa = $("foco-tarefa");
    const body = {
      kind: estado.modo === "focus" ? "focus" : "break",
      started_at: estado.inicio || new Date().toISOString(),
      planned_minutes: duracaoAtual(),
      minutes: minutos,
      completed: completa,
      area_id: $("foco-area").value || (tarefa.selectedOptions[0]?.dataset.area || null),
      task_id: tarefa.value || null,
    };
    if (body.kind === "break" && !completa && minutos < 1) return;
    try {
      const res = await fetch("/foco/sessoes", { method: "POST", headers: { "Content-Type": "application/json", "X-Yoru": "1" }, body: JSON.stringify(body) });
      if (res.ok) {
        const html = await res.text();
        const atual = $("foco-lista");
        if (atual) atual.outerHTML = html;
        const n = document.querySelector("#foco-lista .enso-numero");
        if (n) $("foco-nota").textContent = `${n.textContent} min de foco hoje`;
      }
    } catch (e) {}
  }

  function proximoModo() {
    if (estado.modo === "focus") {
      estado.focosFeitos += 1;
      estado.modo = estado.focosFeitos % prefs.ciclos === 0 ? "long" : "break";
    } else {
      estado.modo = "focus";
    }
  }

  function reiniciarUI(frase) {
    clearInterval(estado.timer);
    estado.rodando = false;
    estado.inicio = null;
    estado.restanteAoPausar = 0;
    $("foco-iniciar").textContent = estado.modo === "focus" ? "Começar" : "Começar a pausa";
    $("foco-encerrar").hidden = true;
    $("foco-pular").hidden = true;
    $("foco-titulo").textContent = estado.modo === "focus" ? "Um bloco de cada vez" : "Hora da pausa";
    if (frase) $("foco-frase").textContent = frase;
    mostrar(duracaoAtual() * 60);
    mostrarSequencia();
  }

  function sino() {
    try {
      const ctx = new (window.AudioContext || window.webkitAudioContext)();
      [0, 0.35].forEach((t) => {
        const o = ctx.createOscillator(), g = ctx.createGain();
        o.type = "sine"; o.frequency.value = 660;
        g.gain.setValueAtTime(0.0001, ctx.currentTime + t);
        g.gain.exponentialRampToValueAtTime(0.25, ctx.currentTime + t + 0.02);
        g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + t + 1.2);
        o.connect(g).connect(ctx.destination);
        o.start(ctx.currentTime + t); o.stop(ctx.currentTime + t + 1.3);
      });
    } catch (e) {}
    if ("Notification" in window && Notification.permission === "granted") {
      new Notification("Yoru", { body: estado.modo === "focus" ? "Foco concluído. Hora da pausa." : "Pausa concluída. De volta ao foco." });
    }
  }

  async function concluir(completa) {
    if (completa) pulsar();
    const eraFoco = estado.modo === "focus";
    clearInterval(estado.timer);
    await registrar(completa);
    if (completa) sino();
    proximoModo();
    reiniciarUI(completa
      ? (eraFoco ? "Foco concluído. Levante, beba água, e volte quando a pausa acabar." : "Pausa concluída. Pronto para o próximo bloco.")
      : (eraFoco ? "Sessão encerrada. O tempo que passou ficou registrado." : "Pausa encerrada."));
  }

  $("foco-iniciar").addEventListener("click", () => {
    if ("Notification" in window && Notification.permission === "default") Notification.requestPermission();
    estado.rodando ? pausar() : iniciar();
  });
  $("foco-encerrar").addEventListener("click", () => concluir(false));
  $("foco-pular").addEventListener("click", () => { clearInterval(estado.timer); proximoModo(); reiniciarUI("Pulado."); });
  $("foco-resetar").addEventListener("click", () => {
    estado.focosFeitos = 0;
    estado.modo = "focus";
    reiniciarUI("Sequência zerada. O próximo é o primeiro foco.");
  });

  // Trocar a tarefa escolhe a área dela
  $("foco-tarefa").addEventListener("change", (ev) => {
    const area = ev.target.selectedOptions[0]?.dataset.area;
    if (area) $("foco-area").value = area;
  });

  window.addEventListener("beforeunload", (ev) => {
    if (estado.rodando && estado.modo === "focus") { ev.preventDefault(); ev.returnValue = ""; }
  });

  mostrar(duracaoAtual() * 60);
  mostrarSequencia();
})();
