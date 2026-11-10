package app.yoru.ui.screens

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yoru.data.HabitRow
import app.yoru.ui.Acao
import app.yoru.ui.Ceu
import app.yoru.ui.maiuscula
import app.yoru.ui.Lua
import app.yoru.ui.LuaEstado
import app.yoru.ui.Secao
import app.yoru.ui.Vazio
import app.yoru.ui.YoruViewModel
import app.yoru.ui.campoCores
import app.yoru.ui.theme.Serif
import app.yoru.ui.theme.Yz

private val diaLetra = listOf("S", "T", "Q", "Q", "S", "S", "D")  // segunda..domingo

@Composable
fun HabitosScreen(vm: YoruViewModel) {
    val rows by vm.habitRows.collectAsStateWithLifecycle(emptyList())
    var novo by rememberSaveable { mutableStateOf(false) }
    var aberto by rememberSaveable { mutableStateOf<String?>(null) }
    val feitos = rows.count { it.doneToday }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Ceu(
                sobretitulo = "Hábitos",
                titulo = when {
                    rows.isEmpty() -> "Nenhum hábito ainda"
                    feitos == rows.size -> "Todos feitos hoje"
                    else -> "$feitos de ${rows.size} feitos hoje"
                },
                paragrafo = "Uma lua por dia; a de hoje é a última. Toque no nome para arquivar.",
                extra = { Acao(if (novo) "fechar" else "novo hábito", onClick = { novo = !novo }, cor = Yz.text) },
            )
        }
        if (novo) item { NovoHabito(vm, onDone = { novo = false }) }
        item { Secao("習慣", "hábitos") }
        if (rows.isEmpty()) item { Vazio("Comece com um ou dois. Os que mais mudam o dia costumam ser os menores.") }
        items(rows, key = { it.habit.id }) { row ->
            LinhaHabito(
                row = row,
                expanded = aberto == row.habit.id,
                onClick = { aberto = if (aberto == row.habit.id) null else row.habit.id },
                onToggle = { day -> vm.toggleHabit(row.habit, day) },
                onArchive = { vm.archiveHabit(row.habit); aberto = null },
                onDelete = { vm.deleteHabit(row.habit); aberto = null },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun LinhaHabito(
    row: HabitRow,
    expanded: Boolean,
    onClick: () -> Unit,
    onToggle: (java.time.LocalDate) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val h = row.habit
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (h.kanji != null) {
                Text(h.kanji, fontFamily = Serif, fontSize = 18.sp, color = Yz.text2)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 4.dp)) {
                Text(h.name.maiuscula(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        if (h.daysPerWeek < 7) "${h.daysPerWeek}× por semana" else null,
                        when (row.streak) { 0 -> "sem sequência"; 1 -> "1 dia"; else -> "${row.streak} dias seguidos" },
                        "${row.weekDone} de 7 nesta semana",
                    ).joinToString(", ").maiuscula(),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            for ((day, done) in row.days) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Lua(
                        estado = if (done) LuaEstado.Cheia else LuaEstado.Vazia,
                        onClick = { onToggle(day) },
                        descricao = "${day.dayOfMonth}/${day.monthValue}: ${if (done) "feito" else "não feito"}",
                    )
                    Text(diaLetra[day.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall, color = Yz.text3)
                }
            }
        }
        if (expanded) {
            Row(Modifier.padding(top = 4.dp)) {
                Acao("arquivar", onClick = onArchive)
                Acao("excluir", onClick = onDelete, cor = Yz.text3)
            }
        }
    }
    HorizontalDivider(color = Yz.line, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 20.dp))
}

@Composable
private fun NovoHabito(vm: YoruViewModel, onDone: () -> Unit) {
    var nome by rememberSaveable { mutableStateOf("") }
    var kanji by rememberSaveable { mutableStateOf("") }
    var vezes by rememberSaveable { mutableStateOf("7") }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(nome, { nome = it }, label = { Text("Hábito") }, singleLine = true, colors = campoCores(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(kanji, { kanji = it.take(2) }, label = { Text("Kanji") }, singleLine = true, colors = campoCores(), modifier = Modifier.weight(1f))
            OutlinedTextField(vezes, { vezes = it.filter { c -> c.isDigit() }.take(1) }, label = { Text("Vezes/semana") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = campoCores(), modifier = Modifier.weight(1f))
        }
        Acao("criar", onClick = {
            vm.addHabit(nome, kanji, vezes.toIntOrNull() ?: 7)
            nome = ""; kanji = ""; vezes = "7"
            onDone()
        }, cor = Yz.text)
    }
}
