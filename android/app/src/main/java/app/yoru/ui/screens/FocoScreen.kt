package app.yoru.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yoru.data.FocusSession
import app.yoru.data.Time
import app.yoru.focus.Mode
import app.yoru.ui.Acao
import app.yoru.ui.Ceu
import app.yoru.ui.maiuscula
import app.yoru.ui.BotaoPrincipal
import app.yoru.ui.Marca
import app.yoru.ui.Secao
import app.yoru.ui.Vazio
import app.yoru.ui.YoruViewModel
import app.yoru.ui.campoCores
import app.yoru.ui.theme.Serif
import app.yoru.ui.theme.Yz

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocoScreen(vm: YoruViewModel) {
    val ui by vm.focusUi.collectAsStateWithLifecycle()
    val sessions by vm.focusToday.collectAsStateWithLifecycle(emptyList())
    val areas by vm.areas.collectAsStateWithLifecycle(emptyList())
    val areaName = areas.associate { it.id to it.name }
    val minutesToday = sessions.filter { it.kind == "focus" }.sumOf { it.minutes }
    var configurar by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Ceu(
                sobretitulo = "Foco",
                titulo = when {
                    ui.running && ui.mode == Mode.Focus -> "Em foco"
                    ui.running -> "Respire"
                    ui.paused -> "Pausado"
                    ui.mode == Mode.Focus -> "Um bloco de cada vez"
                    else -> "Hora da pausa"
                },
                paragrafo = ui.message ?: "$minutesToday min de foco hoje.",
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Relogio(ui.fraction, ui.clock, ui.mode.label.maiuscula())
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BotaoPrincipal(
                        when {
                            ui.running -> "Pausar"
                            ui.paused -> "Continuar"
                            ui.mode == Mode.Focus -> "Começar"
                            else -> "Começar a pausa"
                        },
                        onClick = { vm.focus.startOrPause() },
                    )
                    if (ui.running || ui.paused) {
                        Acao("encerrar", onClick = { vm.focus.stop() })
                        Acao("pular", onClick = { vm.focus.skip() })
                    } else {
                        Acao(if (configurar) "fechar tempos" else "tempos", onClick = { configurar = !configurar })
                    }
                }
                // Sempre visível, numa linha própria para não ser espremido. Só funciona parado
                // e com algo a zerar (como no site); fora disso aparece apagado.
                Acao(
                    "resetar sequência",
                    onClick = { vm.focus.resetSequence() },
                    enabled = !ui.running && !ui.paused && (ui.focusDoneInRow > 0 || ui.mode != Mode.Focus),
                )
                // Lê ui para recompor quando a sequência mudar
                Text(
                    if (ui.mode == Mode.LongBreak) "${vm.settings.cycles} de ${vm.settings.cycles} focos: pausa longa"
                    else "${ui.focusDoneInRow % vm.settings.cycles} de ${vm.settings.cycles} focos até a pausa longa",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (configurar && !ui.running && !ui.paused) {
            item { ConfigTempos(vm) }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Marca("場", "onde")
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
                    Acao("sem área", onClick = { vm.focus.setContext(null, null) }, cor = if (ui.areaId == null) Yz.text else Yz.text3)
                    for (a in areas) {
                        Acao(a.name, onClick = { vm.focus.setContext(a.id, null) }, cor = if (ui.areaId == a.id) Yz.text else Yz.text3)
                    }
                }
            }
        }
        item { Secao("集中", "sessões de hoje") }
        if (sessions.isEmpty()) item { Vazio("Nenhuma sessão hoje.") }
        items(sessions, key = { it.id }) { s -> LinhaSessao(s, areaName[s.areaId], onDelete = { vm.deleteFocusSession(s) }) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Anel de pincel: fecha conforme o tempo passa, como o ensō do site. */
@Composable
private fun Relogio(fraction: Float, clock: String, label: String) {
    Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke * 1.4f
            val arc = Size(size.width - inset * 2, size.height - inset * 2)
            val tl = Offset(inset, inset)
            drawArc(Yz.line, 0f, 360f, false, tl, arc, style = Stroke(1.dp.toPx()))
            if (fraction > 0f) {
                drawArc(Yz.moon, -100f, 338f * fraction, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                val inset2 = inset - stroke * 0.55f
                drawArc(Yz.moon.copy(alpha = 0.5f), -96f, 338f * fraction, false, Offset(inset2, inset2),
                    Size(size.width - inset2 * 2, size.height - inset2 * 2), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(clock, fontFamily = Serif, fontSize = 46.sp, color = Yz.text)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ConfigTempos(vm: YoruViewModel) {
    val s = vm.settings
    var foco by rememberSaveable { mutableStateOf(s.focusMinutes.toString()) }
    var pausa by rememberSaveable { mutableStateOf(s.breakMinutes.toString()) }
    var longa by rememberSaveable { mutableStateOf(s.longBreakMinutes.toString()) }
    var ciclos by rememberSaveable { mutableStateOf(s.cycles.toString()) }

    @Composable
    fun Campo(valor: String, rotulo: String, modifier: Modifier, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = valor, onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(3)) },
            label = { Text(rotulo) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = campoCores(), modifier = modifier,
        )
    }

    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Marca("間", "tempos em minutos")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Campo(foco, "Foco", Modifier.weight(1f)) { foco = it }
            Campo(pausa, "Pausa", Modifier.weight(1f)) { pausa = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Campo(longa, "Pausa longa", Modifier.weight(1f)) { longa = it }
            Campo(ciclos, "A cada N focos", Modifier.weight(1f)) { ciclos = it }
        }
        Acao("salvar tempos", onClick = {
            vm.savePomodoro(foco.toIntOrNull() ?: 25, pausa.toIntOrNull() ?: 5, longa.toIntOrNull() ?: 15, ciclos.toIntOrNull() ?: 4)
        }, cor = Yz.text)
    }
}

@Composable
private fun LinhaSessao(s: FocusSession, area: String?, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (s.kind == "break") "Pausa" else "Foco") + ", ${s.minutes} min" + if (s.completed) "" else " (encerrada antes)",
                style = MaterialTheme.typography.bodyLarge, color = if (s.kind == "break") Yz.text2 else Yz.text,
            )
            Text(listOfNotNull(horaLocal(s.startedAt), area?.lowercase()).joinToString(", ").maiuscula(), style = MaterialTheme.typography.labelMedium)
        }
        Acao("excluir", onClick = onDelete, cor = Yz.text3)
    }
    HorizontalDivider(color = Yz.line, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 20.dp))
}

fun horaLocal(iso: String?): String? {
    val i = Time.parseInstant(iso) ?: return null
    val z = i.atZone(java.time.ZoneId.systemDefault())
    return "%02d:%02d".format(z.hour, z.minute)
}
