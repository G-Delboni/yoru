// Peças reaproveitadas pelas telas. `@Composable` marca uma função que desenha UI;
// ela é chamada de novo (recomposta) sempre que algum estado que ela lê muda.
package app.yoru.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yoru.data.Task
import app.yoru.data.Time
import app.yoru.data.isDoing
import app.yoru.data.isDone
import app.yoru.ui.theme.Serif
import app.yoru.ui.theme.Yz

/** Primeira letra em maiúscula (pula símbolos como "→" ou "‹"): todo texto do app começa assim. */
fun String.maiuscula(): String {
    val i = indexOfFirst { it.isLetter() }
    return if (i < 0) this else substring(0, i) + this[i].titlecase() + substring(i + 1)
}

/** Botão principal da tela: claro com texto escuro; desativado, cinza com texto cinza-claro. */
@Composable
fun BotaoPrincipal(texto: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Yz.text,
            contentColor = Yz.sky,
            disabledContainerColor = Yz.line,
            disabledContentColor = Yz.text2,
        ),
    ) { Text(texto.maiuscula()) }
}

/** Kanji com a legenda em português ao lado, sempre na horizontal. */
@Composable
fun Marca(kanji: String, legenda: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(kanji, fontFamily = Serif, fontSize = 18.sp, color = Yz.text2)
        Spacer(Modifier.width(10.dp))
        Text(legenda.uppercase(), style = MaterialTheme.typography.labelSmall, color = Yz.text3)
    }
}

/**
 * Cabeçalho de seção: kanji e legenda sobre um traço fino. Mesmo recuo e mesmos
 * espaços em todas as telas, para as seções ficarem alinhadas entre si.
 */
@Composable
fun Secao(kanji: String, legenda: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        Marca(kanji, legenda)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Yz.line, thickness = 1.dp)
    }
}

/** Linha do horizonte entre o céu (cabeçalho) e a água (conteúdo). */
@Composable
fun Horizonte() {
    HorizontalDivider(color = Yz.line, thickness = 1.dp)
}

/** Cabeçalho de tela: sobretítulo pequeno, título em serifa, um parágrafo opcional. */
@Composable
fun Ceu(sobretitulo: String, titulo: String, paragrafo: String? = null, extra: (@Composable () -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Yz.sky)
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 22.dp)
    ) {
        Text(sobretitulo.maiuscula(), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Text(titulo.maiuscula(), style = MaterialTheme.typography.displaySmall)
        if (paragrafo != null) {
            Spacer(Modifier.height(10.dp))
            Text(paragrafo.maiuscula(), style = MaterialTheme.typography.bodyMedium, color = Yz.text2)
        }
        if (extra != null) {
            Spacer(Modifier.height(14.dp))
            extra()
        }
    }
    Horizonte()
}

/**
 * A lua: vazia = por fazer, cheia = feita, meia = fazendo. Toque para alternar.
 * O desenho tem 18dp, mas a área de toque tem `alvo` (44dp por padrão), para o dedo acertar.
 */
@Composable
fun Lua(estado: LuaEstado, onClick: () -> Unit, descricao: String, modifier: Modifier = Modifier, alvo: Dp = 44.dp) {
    Box(
        modifier
            .size(alvo)
            .clickable(onClick = onClick)
            .semantics { contentDescription = descricao },
        contentAlignment = Alignment.Center,
    ) { DesenhoLua(estado) }
}

@Composable
private fun DesenhoLua(estado: LuaEstado) {
    val base = Modifier.size(18.dp)
    when (estado) {
        LuaEstado.Cheia -> Box(base.background(Yz.moon, CircleShape))
        LuaEstado.Vazia -> Box(base.border(1.dp, Yz.text2, CircleShape))
        LuaEstado.Meia -> Box(base.border(1.dp, Yz.moon, CircleShape)) {
            Box(
                Modifier
                    .size(9.dp, 18.dp)
                    .align(Alignment.CenterStart)
                    .background(Yz.moon)
            )
        }
    }
}

enum class LuaEstado { Vazia, Meia, Cheia }

/**
 * Uma tarefa: lua + título + prazo. Tocar na linha abre as ações: Fazer/Pausar, Concluir/Reabrir,
 * o que cada tela passa em `trailing` (mover para área...) e, por último, Excluir (`onDelete`).
 * Quem passa `onClick` controla a abertura por `expanded`; sem `onClick`, a linha cuida sozinha.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinhaTarefa(
    task: Task,
    onDone: () -> Unit,
    onDoing: () -> Unit,
    sub: String? = null,
    onDelete: (() -> Unit)? = null,
    expanded: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var abertoLocal by rememberSaveable(task.id) { mutableStateOf(false) }
    val aberto = if (onClick != null) expanded else abertoLocal
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick ?: { abertoLocal = !abertoLocal })
            // 8 + metade da área de toque da lua (22) - metade do desenho (9) ≈ 20dp: a lua fica na margem
            .padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lua(
                estado = when {
                    task.isDone -> LuaEstado.Cheia
                    task.isDoing -> LuaEstado.Meia
                    else -> LuaEstado.Vazia
                },
                onClick = onDone,
                descricao = if (task.isDone) "Reabrir" else "Concluir",
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    task.title.maiuscula(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.isDone) Yz.text3 else Yz.text,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                    fontWeight = if (task.priority == "high" && !task.isDone) FontWeight.Normal else FontWeight.Light,
                )
                val detalhe = listOfNotNull(
                    sub,
                    task.dueDate?.let { "prazo ${Time.deadline(it)}" },
                    if (task.isDoing) "fazendo" else null,
                ).joinToString(", ").maiuscula()
                if (detalhe.isNotEmpty()) {
                    Text(detalhe, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (aberto) {
            // FlowRow quebra linha quando os botões não cabem (muitas áreas).
            FlowRow(
                Modifier.padding(start = 40.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!task.isDone) Acao(if (task.isDoing) "pausar" else "fazer", onClick = onDoing, cor = Yz.text)
                Acao(if (task.isDone) "reabrir" else "concluir", onClick = onDone, cor = Yz.text)
                trailing?.invoke()
                if (onDelete != null) Acao("excluir", onClick = onDelete, cor = Yz.text3)
            }
        }
    }
    HorizontalDivider(color = Yz.line, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 20.dp))
}

/** Botão discreto de texto, como os "acao" do site. */
@Composable
fun Acao(texto: String, onClick: () -> Unit, cor: Color = Yz.text2, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(texto.maiuscula(), color = if (enabled) cor else Yz.text3.copy(alpha = 0.5f), fontSize = 13.sp)
    }
}

/** Campo de captura: escreve, Enter, vira tarefa. */
@Composable
fun Captura(placeholder: String, onCapture: (String) -> Unit, modifier: Modifier = Modifier) {
    // `rememberSaveable`: o texto sobrevive à rotação. `by`: lê e grava como variável comum.
    var texto by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = texto,
        onValueChange = { texto = it },
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder.maiuscula(), color = Yz.text3) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            onCapture(texto)
            texto = ""
        }),
        colors = campoCores(),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
fun campoCores() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Yz.text2,
    unfocusedBorderColor = Yz.line,
    cursorColor = Yz.text,
    focusedTextColor = Yz.text,
    unfocusedTextColor = Yz.text,
    focusedLabelColor = Yz.text2,
    unfocusedLabelColor = Yz.text3,
)

@Composable
fun Vazio(texto: String) {
    Text(
        texto.maiuscula(),
        style = MaterialTheme.typography.bodyMedium,
        color = Yz.text2,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
