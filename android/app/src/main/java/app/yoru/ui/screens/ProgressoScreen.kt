package app.yoru.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yoru.data.Period
import app.yoru.data.Series
import app.yoru.data.Time
import app.yoru.data.Tracking
import app.yoru.ui.Acao
import app.yoru.ui.Ceu
import app.yoru.ui.maiuscula
import app.yoru.ui.Secao
import app.yoru.ui.Vazio
import app.yoru.ui.YoruViewModel
import app.yoru.ui.theme.Serif
import app.yoru.ui.theme.Yz

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressoScreen(vm: YoruViewModel) {
    var period by rememberSaveable { mutableStateOf(Period.Week) }
    val track by vm.tracking.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle(0)
    val areas by vm.areas.collectAsStateWithLifecycle(emptyList())
    val areaName = areas.associate { it.id to it.name }

    // Recalcula ao trocar o período e sempre que algo mudar (pendências mudam a cada ação).
    LaunchedEffect(period, pending) { vm.loadTracking(period) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Ceu(
                sobretitulo = "Progresso",
                titulo = period.label,
                extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in Period.entries) {
                            Acao(p.label, onClick = { period = p }, cor = if (p == period) Yz.text else Yz.text3)
                        }
                    }
                    val t = track
                    if (t != null && t.period == period) {
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (s in t.series) Resumo(fmt(s.total), s.unit, s.label.lowercase())
                            Resumo(t.habitsPct?.toString() ?: "–", "%", "hábitos")
                        }
                    }
                },
            )
        }
        val t = track
        if (t == null || t.period != period) {
            item { Vazio("Calculando…") }
        } else {
            for (s in t.series) {
                item {
                    Secao(s.kanji, s.label.lowercase())
                    if (s.total == 0f) Vazio("Nada registrado ${period.label.lowercase()}.")
                    else if (s.maxValue == 0f) Vazio("${fmt(s.total)} ${s.unit} (o diário não guarda hora).")
                    else {
                        Barras(s, t.labels, Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 20.dp))
                        Text(
                            "Máximo ${fmt(s.maxValue)} ${s.unit}" + if (period != Period.Today) ", total ${fmt(s.total)}" else "",
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (t.doneTasks.isNotEmpty()) {
                item { Secao("済", "feitas") }
                items(t.doneTasks, key = { it.id }) { task ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(task.title.maiuscula(), style = MaterialTheme.typography.bodyMedium, color = Yz.text2)
                            Text(areaName[task.areaId] ?: "Sem área", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(Time.shortDate(Tracking.localDateOf(task.completedAt)?.toString()).maiuscula(), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Resumo(valor: String, unidade: String, rotulo: String) {
    Column {
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text(valor, fontFamily = Serif, fontSize = 24.sp, color = Yz.text)
            Spacer(Modifier.padding(2.dp))
            Text(unidade, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(rotulo.maiuscula(), style = MaterialTheme.typography.labelMedium)
    }
}

/** Barras em cinza: a mais alta é a mais clara. Rótulos desenhados com o Canvas nativo. */
@Composable
private fun Barras(s: Series, labels: List<String>, modifier: Modifier) {
    val textColor = Yz.text2.toArgb()
    Canvas(modifier) {
        val n = s.values.size
        if (n == 0) return@Canvas
        val labelH = 16.dp.toPx()
        val chartH = size.height - labelH
        val step = size.width / n
        val paint = android.graphics.Paint().apply {
            color = textColor; textSize = 10.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        }
        drawLine(Yz.line, Offset(0f, chartH), Offset(size.width, chartH), 1f)
        s.values.forEachIndexed { i, v ->
            val h = if (s.maxValue > 0f) v / s.maxValue * (chartH - 14.dp.toPx()) else 0f
            val x = i * step + step * 0.22f
            val color = if (v == s.maxValue && v > 0f) Yz.moon else if (v > 0f) Yz.text3 else Yz.skyLow
            drawRect(color, Offset(x, chartH - h), Size(step * 0.56f, h))
            drawIntoCanvas { c ->
                if (v > 0f && (v == s.maxValue || n <= 7)) c.nativeCanvas.drawText(fmt(v), x + step * 0.28f, chartH - h - 4.dp.toPx(), paint)
                val label = labels.getOrNull(i).orEmpty()
                if (label.isNotEmpty()) c.nativeCanvas.drawText(label.maiuscula(), i * step + step / 2, size.height - 2.dp.toPx(), paint)
            }
        }
    }
}

private fun fmt(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v).replace('.', ',')
