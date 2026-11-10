package app.yoru.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yoru.data.Time
import app.yoru.ui.Acao
import app.yoru.ui.Ceu
import app.yoru.ui.maiuscula
import app.yoru.ui.BotaoPrincipal
import app.yoru.ui.Secao
import app.yoru.ui.YoruViewModel
import app.yoru.ui.campoCores
import app.yoru.ui.theme.Yz
import kotlinx.coroutines.launch

@Composable
fun SincroniaScreen(vm: YoruViewModel) {
    val savedAddress by vm.settings.address.collectAsStateWithLifecycle()
    val savedToken by vm.settings.token.collectAsStateWithLifecycle()
    val lastSyncAt by vm.settings.lastSyncAt.collectAsStateWithLifecycle()
    val lastMessage by vm.settings.lastMessage.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle(0)
    val syncUi by vm.syncUi.collectAsStateWithLifecycle()

    // Campos editáveis começam com o que está salvo.
    var address by rememberSaveable(savedAddress) { mutableStateOf(savedAddress) }
    var token by rememberSaveable(savedToken) { mutableStateOf(savedToken) }
    var testResult by rememberSaveable { mutableStateOf<String?>(null) }
    // Detalhes que só abrem quando pedidos. A conexão manual começa aberta se ainda não há conexão.
    var manual by rememberSaveable { mutableStateOf(false) }
    var comoFunciona by rememberSaveable { mutableStateOf(false) }
    val mostrarManual = manual || savedAddress.isBlank()
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Ceu(
            sobretitulo = "Sincronia",
            titulo = if (lastSyncAt == null) "Ainda não sincronizou" else "Última sincronia ${Time.relative(lastSyncAt)}",
            paragrafo = when {
                pending == 0 -> "Tudo o que você fez aqui já está no computador."
                pending == 1 -> "1 alteração feita aqui ainda não foi para o computador."
                else -> "$pending alterações feitas aqui ainda não foram para o computador."
            },
            extra = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BotaoPrincipal(if (syncUi.running) "Sincronizando…" else "Atualizar agora", onClick = { vm.sync() }, enabled = !syncUi.running)
                    if (syncUi.running) CircularProgressIndicator(Modifier.size(18.dp), color = Yz.text2, strokeWidth = 2.dp)
                }
                val report = syncUi.lastReport
                val msg = report?.message ?: lastMessage
                if (msg != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(msg.maiuscula(), style = MaterialTheme.typography.bodyMedium, color = if (report?.ok == false) Yz.text else Yz.text2)
                }
                if (report != null && report.rejected.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    for (r in report.rejected.take(5)) {
                        Text("${r.tabela}: ${r.motivo}".maiuscula(), style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
        )

        Secao("接続", "conectar")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (savedAddress.isBlank())
                    "No computador, abra a página Sincronia do Yoru, use “Cadastrar aparelho” e envie a solicitação. Com este app aberto e no mesmo Wi‑Fi, a tela “Aprovar sincronização” aparece aqui sozinha."
                else
                    "Conectado a ${savedAddress.removePrefix("http://")}. Para trocar de computador, cadastre este aparelho de novo por lá.",
                style = MaterialTheme.typography.bodyMedium, color = Yz.text2,
            )
        }

        Secao("手動", "conexão manual")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!mostrarManual) {
                Acao("editar endereço e token", onClick = { manual = true })
            } else {
                Text(
                    "Se o anúncio na rede não chegar, digite o endereço e o token geral que estão em “Conexão manual” na página do computador.",
                    style = MaterialTheme.typography.bodyMedium, color = Yz.text2,
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; testResult = null },
                    label = { Text("Endereço do servidor") },
                    placeholder = { Text("http://192.168.0.10:8000", color = Yz.text3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = campoCores(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; testResult = null },
                    label = { Text("Token") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = campoCores(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Acao("testar conexão", onClick = {
                        testResult = "Testando…"
                        // Lança uma coroutine presa à tela: se a tela sumir, ela é cancelada.
                        scope.launch { testResult = vm.testConnection(address, token).message }
                    })
                    Acao("salvar", onClick = {
                        vm.saveConnection(address, token)
                        manual = true  // continua aberto para mostrar o "Salvo."
                        testResult = "Salvo."
                    }, cor = Yz.text)
                }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Yz.text2) }
                if (savedAddress.isNotBlank()) Acao("fechar", onClick = { manual = false })
            }
        }

        Secao("仕組", "como funciona")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (comoFunciona) {
                Text(
                    "O app guarda uma cópia de tudo e funciona sem internet. Ao abrir, e quando você toca em Atualizar, ele envia o que mudou aqui e recebe o que mudou no computador. Se o mesmo item mudou nos dois lados, vale a edição mais recente.",
                    style = MaterialTheme.typography.bodyMedium, color = Yz.text2,
                )
            }
            Acao(if (comoFunciona) "esconder" else "mostrar", onClick = { comoFunciona = !comoFunciona })
            Spacer(Modifier.height(24.dp))
        }
    }
}
