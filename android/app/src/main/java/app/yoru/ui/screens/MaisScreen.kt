package app.yoru.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yoru.data.Time
import app.yoru.ui.Ceu
import app.yoru.ui.YoruViewModel
import app.yoru.ui.theme.Serif
import app.yoru.ui.theme.Yz

/** Aba "Mais": o que não coube na barra de baixo. */
@Composable
fun MaisScreen(vm: YoruViewModel, onOpen: (String) -> Unit) {
    val rows by vm.habitRows.collectAsStateWithLifecycle(emptyList())
    val lastSync by vm.settings.lastSyncAt.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle(0)
    val feitos = rows.count { it.doneToday }

    Column(Modifier.fillMaxSize()) {
        Ceu(sobretitulo = "Mais", titulo = "Hábitos, progresso e sincronia")
        Item("習", "Hábitos", if (rows.isEmpty()) "Nenhum ainda" else "$feitos de ${rows.size} feitos hoje") { onOpen("habitos") }
        Item("跡", "Progresso", "Hoje, esta semana, este mês") { onOpen("progresso") }
        Item("同", "Sincronia",
            (if (lastSync == null) "Ainda não sincronizou" else "Última ${Time.relative(lastSync)}") +
                if (pending > 0) ", $pending por enviar" else "") { onOpen("sincronia") }
    }
}

@Composable
private fun Item(kanji: String, titulo: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(kanji, fontFamily = Serif, fontSize = 24.sp, color = Yz.text2, modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.titleLarge)
            Text(sub, style = MaterialTheme.typography.labelMedium)
        }
        Text("›", fontFamily = Serif, fontSize = 22.sp, color = Yz.text3)
    }
    HorizontalDivider(color = Yz.line, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 20.dp))
}
