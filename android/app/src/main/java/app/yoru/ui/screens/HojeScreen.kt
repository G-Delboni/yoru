package app.yoru.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yoru.data.Time
import app.yoru.ui.Acao
import app.yoru.ui.Captura
import app.yoru.ui.Ceu
import app.yoru.ui.LinhaTarefa
import app.yoru.ui.Lua
import app.yoru.ui.LuaEstado
import app.yoru.ui.Marca
import app.yoru.ui.Secao
import app.yoru.ui.Vazio
import app.yoru.ui.maiuscula
import app.yoru.ui.YoruViewModel
import app.yoru.ui.theme.Yz

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HojeScreen(vm: YoruViewModel) {
    // collectAsStateWithLifecycle: transforma o Flow do banco em estado da tela.
    val tasks by vm.todayTasks.collectAsStateWithLifecycle(emptyList())
    val done by vm.doneToday.collectAsStateWithLifecycle(emptyList())
    val areas by vm.areas.collectAsStateWithLifecycle(emptyList())
    val lastLog by vm.lastNextStep.collectAsStateWithLifecycle(null)
    val events by vm.upcomingEvents.collectAsStateWithLifecycle(emptyList())
    val habits by vm.habitRows.collectAsStateWithLifecycle(emptyList())
    val areaName = areas.associate { it.id to it.name }
    var mostrarFeitas by rememberSaveable { mutableStateOf(false) }
    val passo = lastLog?.nextStep

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                Ceu(
                    sobretitulo = Time.longToday(),
                    titulo = "${Time.greeting()}.",
                    paragrafo = when {
                        tasks.isEmpty() && done.isEmpty() -> "Nada marcado para hoje."
                        tasks.isEmpty() -> "Tudo o que estava marcado para hoje foi feito."
                        tasks.size == 1 -> "Uma coisa para hoje."
                        else -> "${tasks.size} coisas para hoje."
                    },
                    extra = if (passo == null) null else {
                        {
                            Column {
                                Marca("次", "próximo passo")
                                Spacer(Modifier.height(6.dp))
                                Text(passo.maiuscula(), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "De ${Time.shortDate(lastLog?.day)}, ${areaName[lastLog?.areaId]?.lowercase() ?: "sem área"}",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Row {
                                    Acao("retomar agora", onClick = {
                                        vm.capture(passo, forToday = true, areaId = lastLog?.areaId)
                                    })
                                }
                            }
                        }
                    },
                )
            }

            item { Secao("今日", "hoje") }
            if (tasks.isEmpty()) item { Vazio("Capture algo abaixo ou puxe da caixa de entrada.") }
            items(tasks, key = { it.id }) { t ->
                LinhaTarefa(
                    task = t,
                    onDone = { vm.toggleDone(t) },
                    onDoing = { vm.toggleDoing(t) },
                    sub = areaName[t.areaId] ?: "caixa de entrada",
                    onDelete = { vm.delete(t) },
                )
            }

            if (done.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Marca("済", if (done.size == 1) "1 feita hoje" else "${done.size} feitas hoje")
                        Acao(if (mostrarFeitas) "esconder" else "mostrar", onClick = { mostrarFeitas = !mostrarFeitas })
                    }
                }
                if (mostrarFeitas) {
                    items(done, key = { "d" + it.id }) { t ->
                        LinhaTarefa(task = t, onDone = { vm.toggleDone(t) }, onDoing = {}, sub = areaName[t.areaId], onDelete = { vm.delete(t) })
                    }
                }
            }
            if (events.isNotEmpty()) {
                item {
                    Secao("締", "prazos")
                }
                items(events, key = { "e" + it.id }) { e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(e.title.maiuscula(), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(areaName[e.areaId], tipoEvento(e.kind)).joinToString(", ").maiuscula(),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Text(Time.deadline(e.startsAt).maiuscula(), style = MaterialTheme.typography.labelMedium, color = Yz.text)
                    }
                }
            }

            if (habits.isNotEmpty()) {
                item {
                    Secao("習慣", "hábitos de hoje")
                    FlowRow(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (row in habits) {
                            Row(
                                Modifier
                                    .border(1.dp, Yz.line, CircleShape)
                                    .clickable { vm.toggleHabit(row.habit) }
                                    .padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Lua(if (row.doneToday) LuaEstado.Cheia else LuaEstado.Vazia, onClick = { vm.toggleHabit(row.habit) }, descricao = row.habit.name, alvo = 18.dp)
                                Text(row.habit.name.maiuscula(), style = MaterialTheme.typography.bodyMedium, color = if (row.doneToday) Yz.text2 else Yz.text)
                                if (row.streak >= 2) Text("${row.streak} dias", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }

        Captura(
            placeholder = "Capturar para hoje…",
            onCapture = { vm.capture(it, forToday = true) },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

fun tipoEvento(kind: String): String = when (kind) {
    "exam" -> "prova"
    "deadline" -> "entrega"
    "meeting" -> "reunião"
    else -> "evento"
}
