/* Pétalas caindo no céu, em canvas. Só cinzas, lentas, poucas: ambiente, não espetáculo.
   Com "reduzir movimento" ativo, desenha um quadro parado. Pausa quando a aba está em segundo plano. */
(() => {
  "use strict";
  const ceu = document.querySelector(".ceu");
  const canvas = document.querySelector("canvas.petalas");
  if (!ceu || !canvas) return;

  const ctx = canvas.getContext("2d");
  const parado = matchMedia("(prefers-reduced-motion: reduce)");
  const QUANTAS = 16;
  let largura = 0, altura = 0, escala = 1, petalas = [], quadro = 0, ultimo = 0;

  function medir() {
    escala = Math.min(devicePixelRatio || 1, 2);
    largura = ceu.clientWidth;
    altura = ceu.clientHeight;
    canvas.width = largura * escala;
    canvas.height = altura * escala;
    canvas.style.width = largura + "px";
    canvas.style.height = altura + "px";
    ctx.setTransform(escala, 0, 0, escala, 0, 0);
  }

  function nova(inicio) {
    const tamanho = 2.2 + Math.random() * 2.6;
    return {
      x: Math.random() * largura,
      y: inicio ? Math.random() * altura : -12,
      tamanho,
      // as maiores parecem mais perto: caem um pouco mais rápido e mais claras
      queda: 9 + tamanho * 3.5,            // px por segundo
      balanco: 10 + Math.random() * 22,    // amplitude do vai-e-vem
      fase: Math.random() * Math.PI * 2,
      ritmo: 0.25 + Math.random() * 0.35,  // velocidade do vai-e-vem
      giro: Math.random() * Math.PI,
      giroV: (Math.random() - 0.5) * 0.8,
      alfa: 0.10 + tamanho * 0.05,
    };
  }

  function desenhar(p) {
    ctx.save();
    ctx.translate(p.x + Math.sin(p.fase) * p.balanco, p.y);
    ctx.rotate(p.giro);
    ctx.globalAlpha = p.alfa;
    ctx.fillStyle = "#D9D9D9";
    ctx.beginPath();
    ctx.ellipse(0, 0, p.tamanho, p.tamanho * 0.55, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }

  function passo(agora) {
    const dt = Math.min(0.05, (agora - ultimo) / 1000 || 0);
    ultimo = agora;
    ctx.clearRect(0, 0, largura, altura);
    for (let i = 0; i < petalas.length; i++) {
      const p = petalas[i];
      p.y += p.queda * dt;
      p.fase += p.ritmo * dt;
      p.giro += p.giroV * dt;
      if (p.y > altura + 12) petalas[i] = nova(false);
      desenhar(p);
    }
    quadro = requestAnimationFrame(passo);
  }

  function parar() { cancelAnimationFrame(quadro); quadro = 0; }
  function rodar() {
    if (quadro || parado.matches || document.hidden) return;
    ultimo = performance.now();
    quadro = requestAnimationFrame(passo);
  }

  function comecar() {
    medir();
    petalas = Array.from({ length: QUANTAS }, () => nova(true));
    ctx.clearRect(0, 0, largura, altura);
    petalas.forEach(desenhar);
    rodar();
  }

  comecar();
  new ResizeObserver(() => { medir(); petalas.forEach(desenhar); }).observe(ceu);
  document.addEventListener("visibilitychange", () => (document.hidden ? parar() : rodar()));
  parado.addEventListener("change", () => (parado.matches ? parar() : rodar()));
})();
