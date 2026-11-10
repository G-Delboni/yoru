// Ponto de entrada. Uma Activity só; as "telas" são funções Compose trocadas pela navegação.
package app.yoru

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.yoru.data.Time
import app.yoru.ui.YoruViewModel
import app.yoru.ui.screens.AprovarDialog
import app.yoru.ui.screens.AreasScreen
import app.yoru.ui.screens.CaixaScreen
import app.yoru.ui.screens.FocoScreen
import app.yoru.ui.screens.HabitosScreen
import app.yoru.ui.screens.HojeScreen
import app.yoru.ui.screens.MaisScreen
import app.yoru.ui.screens.ProgressoScreen
import app.yoru.ui.screens.SincroniaScreen
import app.yoru.ui.theme.Serif
import app.yoru.ui.theme.YoruTheme
import app.yoru.ui.theme.Yz

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YoruTheme { YoruRoot() }
        }
    }
}

/** As abas. `kanji` aparece com a legenda em português ao lado, como no site. */
private data class Aba(val rota: String, val kanji: String, val nome: String)

private val abas = listOf(
    Aba("hoje", "今", "Hoje"),
    Aba("caixa", "箱", "Caixa"),
    Aba("areas", "域", "Áreas"),
    Aba("foco", "集", "Foco"),
    Aba("mais", "他", "Mais"),
)
private val dentroDeMais = setOf("mais", "habitos", "progresso", "sincronia")

@Composable
fun YoruRoot() {
    val vm: YoruViewModel = viewModel()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val rotaAtual = backStack?.destination?.route
    val inboxCount by vm.inboxCount.collectAsStateWithLifecycle(0)
    val pending by vm.pendingCount.collectAsStateWithLifecycle(0)
    val invite by vm.invite.collectAsStateWithLifecycle()
    val syncUi by vm.syncUi.collectAsStateWithLifecycle()
    val lastSync by vm.settings.lastSyncAt.collectAsStateWithLifecycle()

    // Convite de pareamento chegou pela rede: pergunta antes de qualquer outra coisa.
    invite?.let { AprovarDialog(vm, it) }

    // Ao abrir o app: sincroniza se já estiver configurado. Roda uma vez por abertura.
    LaunchedEffect(Unit) { vm.sync(onlyIfConfigured = true) }

    Scaffold(
        containerColor = Yz.water,
        topBar = {
            // Botão "Atualizar" em toda tela: envia o que foi feito aqui e traz o que mudou no computador.
            Row(
                Modifier.fillMaxWidth().background(Yz.sky).statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("夜", fontFamily = Serif, fontSize = 20.sp, color = Yz.text2)
                Spacer(Modifier.width(8.dp))
                Text("Yoru", style = MaterialTheme.typography.labelMedium, color = Yz.text2)
                Spacer(Modifier.weight(1f))
                if (syncUi.running) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Yz.text2, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = { vm.sync() }, enabled = !syncUi.running) {
                    Text(
                        when {
                            syncUi.running -> "Sincronizando…"
                            pending > 0 -> "Atualizar · $pending"
                            lastSync != null -> "Atualizar · ${Time.relative(lastSync)}"
                            else -> "Atualizar"
                        },
                        color = if (pending > 0) Yz.text else Yz.text2, fontSize = 12.sp,
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Yz.sky, tonalElevation = 0.dp) {
                for (aba in abas) {
                    val rotulo = if (aba.rota == "caixa" && inboxCount > 0) "${aba.nome} $inboxCount" else aba.nome
                    val selecionada = rotaAtual == aba.rota || (aba.rota == "mais" && rotaAtual in dentroDeMais)
                    // Cor explícita: o estilo de rótulo do tema é cinza e não mudaria com a seleção.
                    val cor = if (selecionada) Yz.text else Yz.text3
                    NavigationBarItem(
                        selected = selecionada,
                        onClick = {
                            nav.navigate(aba.rota) {
                                popUpTo("hoje") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(aba.kanji, fontFamily = Serif, fontSize = 20.sp, color = cor) },
                        label = { Text(rotulo, fontSize = 11.sp, color = cor) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Yz.text,
                            selectedTextColor = Yz.text,
                            unselectedIconColor = Yz.text3,
                            unselectedTextColor = Yz.text3,
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            NavHost(nav, startDestination = "hoje") {
                composable("hoje") { HojeScreen(vm) }
                composable("caixa") { CaixaScreen(vm) }
                composable("areas") { AreasScreen(vm) }
                composable("foco") { FocoScreen(vm) }
                composable("mais") { MaisScreen(vm, onOpen = { nav.navigate(it) }) }
                composable("habitos") { HabitosScreen(vm) }
                composable("progresso") { ProgressoScreen(vm) }
                composable("sincronia") { SincroniaScreen(vm) }
            }
        }
    }
}
