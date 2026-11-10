// "Aprovar sincronização": aparece por cima de tudo quando um convite chega pela rede.
package app.yoru.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.yoru.sync.Invite
import app.yoru.ui.Acao
import app.yoru.ui.maiuscula
import app.yoru.ui.BotaoPrincipal
import app.yoru.ui.YoruViewModel
import app.yoru.ui.campoCores
import app.yoru.ui.theme.Serif
import app.yoru.ui.theme.Yz
import kotlinx.coroutines.launch

@Composable
fun AprovarDialog(vm: YoruViewModel, invite: Invite) {
    var senha by rememberSaveable(invite.convite) { mutableStateOf("") }
    var erro by rememberSaveable(invite.convite) { mutableStateOf<String?>(null) }
    var enviando by rememberSaveable(invite.convite) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun aprovar() {
        if (senha.isBlank() || enviando) return
        enviando = true
        erro = null
        scope.launch {
            erro = vm.approveInvite(invite, senha)   // null = deu certo; o diálogo some sozinho
            enviando = false
            if (erro != null) senha = ""
        }
    }

    Dialog(
        onDismissRequest = { if (!enviando) vm.dismissInvite(invite) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Yz.sky)
                .padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("同期", fontFamily = Serif, fontSize = 44.sp, color = Yz.text2)
            Spacer(Modifier.height(18.dp))
            Text("Aprovar sincronização", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "O computador em ${invite.endereco.removePrefix("http://")} quer sincronizar com este aparelho como “${invite.nome}”.",
                style = MaterialTheme.typography.bodyLarge, color = Yz.text2,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Digite a senha do aparelho que você definiu no computador. Depois disso, nada mais precisa ser digitado.",
                style = MaterialTheme.typography.bodyMedium, color = Yz.text2,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = senha,
                onValueChange = { senha = it; erro = null },
                label = { Text("Senha do aparelho") },
                singleLine = true,
                enabled = !enviando,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { aprovar() }),
                colors = campoCores(),
                modifier = Modifier.fillMaxWidth(),
            )
            erro?.let {
                Spacer(Modifier.height(10.dp))
                Text(it.maiuscula(), style = MaterialTheme.typography.bodyMedium, color = Yz.text)
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BotaoPrincipal(if (enviando) "Aprovando…" else "Aprovar", onClick = { aprovar() }, enabled = senha.isNotBlank() && !enviando)
                if (enviando) CircularProgressIndicator(Modifier.size(18.dp), color = Yz.text2, strokeWidth = 2.dp)
                else Acao("agora não", onClick = { vm.dismissInvite(invite) })
            }
        }
    }
}
