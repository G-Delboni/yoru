// Escuta os convites de pareamento que o servidor anuncia na rede local (UDP 47890).
//
// O servidor não consegue abrir conexão com o celular, então ele "grita" na rede:
// {"yoru":1, "convite":"...", "nome":"...", "endereco":"http://192.168.0.10:8000"}.
// Enquanto o app está aberto, este listener recebe o pacote e avisa a tela.
package app.yoru.sync

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

@Serializable
data class Invite(
    val yoru: Int = 0,
    val convite: String,
    val nome: String = "Yoru",
    val endereco: String,
)

class PairingListener(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _invite = MutableStateFlow<Invite?>(null)
    val invite: StateFlow<Invite?> = _invite

    private val ignored = mutableSetOf<String>()
    private var job: Job? = null

    // Alguns aparelhos descartam pacotes de broadcast para economizar bateria;
    // o MulticastLock pede ao Wi-Fi para entregá-los enquanto estiver ativo.
    private val multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock("yoru-pareamento")
        ?.apply { setReferenceCounted(false) }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            runCatching { multicastLock?.acquire() }
            try {
                while (isActive) {
                    try {
                        listenOnce()
                    } catch (e: Exception) {
                        delay(3_000)  // porta ocupada ou Wi-Fi caiu: tenta de novo daqui a pouco
                    }
                }
            } finally {
                runCatching { multicastLock?.release() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Não mostrar este convite de novo (usuário tocou em "agora não"). */
    fun dismiss(invite: Invite) {
        ignored += invite.convite
        if (_invite.value?.convite == invite.convite) _invite.value = null
    }

    fun clear() {
        _invite.value = null
    }

    private suspend fun listenOnce() {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 1_000  // acorda a cada segundo para checar se foi cancelado
            socket.bind(InetSocketAddress(PORT))
            val buffer = ByteArray(2048)
            while (currentCoroutineIsActive()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val parsed = runCatching { json.decodeFromString(Invite.serializer(), text) }.getOrNull() ?: continue
                if (parsed.yoru != 1 || parsed.convite in ignored) continue
                _invite.value = parsed
            }
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive != false

    companion object {
        const val PORT = 47890
    }
}
