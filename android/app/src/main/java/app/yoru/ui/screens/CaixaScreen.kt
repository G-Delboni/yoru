package app.yoru.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yoru.ui.Acao
import app.yoru.ui.Captura
import app.yoru.ui.Ceu
import app.yoru.ui.LinhaTarefa
import app.yoru.ui.Secao
import app.yoru.ui.Vazio
import app.yoru.ui.YoruViewModel
import app.yoru.ui.theme.Yz

@Composable
fun CaixaScreen(vm: YoruViewModel) {
    val tasks by vm.inbox.collectAsStateWithLifecycle(emptyList())
    val areas by vm.areas.collectAsStateWithLifecycle(emptyList())
    // Qual item está aberto (mostrando os botões de mover). Só um por vez.
    var aberto by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                Ceu(
                    sobretitulo = "Caixa de entrada",
                    titulo = when (tasks.size) {
                        0 -> "Caixa vazia"
                        1 -> "Um item por organizar"
                        else -> "${tasks.size} itens por organizar"
                    },
                    paragrafo = "Toque em um item para mandá-lo a uma área ou para hoje.",
                )
            }
            item { Secao("箱", "por organizar") }
            if (tasks.isEmpty()) item { Vazio("Nada aqui. Tudo o que você anotou já tem lugar.") }
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
                        for (a in areas) {
                            Acao(a.name, onClick = { vm.moveToArea(t, a.id); aberto = null })
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        Captura(
            placeholder = "Anotar na caixa…",
            onCapture = { vm.capture(it, forToday = false) },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
