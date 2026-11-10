package app.yoru.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yoru.data.Area
import app.yoru.ui.Acao
import app.yoru.ui.Captura
import app.yoru.ui.Ceu
import app.yoru.ui.LinhaTarefa
import app.yoru.ui.Secao
import app.yoru.ui.Vazio
import app.yoru.ui.YoruViewModel
import app.yoru.ui.theme.Serif
import app.yoru.ui.theme.Yz

@Composable
fun AreasScreen(vm: YoruViewModel) {
    val areas by vm.areas.collectAsStateWithLifecycle(emptyList())
    val counts by vm.openCountByArea.collectAsStateWithLifecycle(emptyMap())
    var selecionada by rememberSaveable { mutableStateOf<String?>(null) }

    val area = areas.firstOrNull { it.id == selecionada }
    if (area != null) {
        // Botão "voltar" do sistema fecha a área em vez de sair do app.
        BackHandler { selecionada = null }
        AreaDetalhe(vm, area, onBack = { selecionada = null })
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Ceu(
                sobretitulo = "Áreas",
                titulo = "Onde cada coisa mora",
                paragrafo = if (areas.isEmpty()) "As áreas vêm do servidor. Sincronize uma vez para vê-las aqui." else null,
            )
        }
        items(areas, key = { it.id }) { a ->
            val n = counts[a.id] ?: 0
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { selecionada = a.id }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(a.kanji ?: "", fontFamily = Serif, fontSize = 26.sp, color = Yz.text2, modifier = Modifier.width(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (n) {
                            0 -> "Nada aberto"
                            1 -> "1 tarefa aberta"
                            else -> "$n tarefas abertas"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text("›", fontFamily = Serif, fontSize = 22.sp, color = Yz.text3)
            }
            HorizontalDivider(color = Yz.line, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 20.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AreaDetalhe(vm: YoruViewModel, area: Area, onBack: () -> Unit) {
    // `remember(area.id)`: o Flow é criado uma vez por área, não a cada redesenho.
    val flow = remember(area.id) { vm.openTasks(area.id) }
    val tasks by flow.collectAsStateWithLifecycle(emptyList())
    val areas by vm.areas.collectAsStateWithLifecycle(emptyList())
    var aberto by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                Ceu(
                    sobretitulo = area.name,
                    titulo = when (tasks.size) {
                        0 -> "Nada aberto"
                        1 -> "Uma tarefa aberta"
                        else -> "${tasks.size} tarefas abertas"
                    },
                    extra = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Acao("‹ todas as áreas", onClick = onBack)
                        }
                    },
                )
            }
            item { Secao(area.kanji ?: "務", area.legend ?: "tarefas") }
            if (tasks.isEmpty()) item { Vazio("Nenhuma tarefa aberta em ${area.name}.") }
            items(tasks, key = { it.id }) { t ->
                LinhaTarefa(
                    task = t,
                    onDone = { vm.toggleDone(t) },
                    onDoing = { vm.toggleDoing(t) },
                    onDelete = { vm.delete(t); aberto = null },
                    expanded = aberto == t.id,
                    onClick = { aberto = if (aberto == t.id) null else t.id },
                    trailing = {
                        Acao("hoje", onClick = { vm.scheduleForToday(t); aberto = null }, cor = Yz.text)
                        Acao(if (t.priority == "high") "prioridade normal" else "prioridade alta", onClick = { vm.togglePriority(t) })
                        for (a in areas) if (a.id != area.id) {
                            Acao("→ ${a.name}", onClick = { vm.moveToArea(t, a.id); aberto = null })
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        Captura(
            placeholder = "Nova tarefa em ${area.name.lowercase()}…",
            onCapture = { vm.capture(it, forToday = false, areaId = area.id) },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
