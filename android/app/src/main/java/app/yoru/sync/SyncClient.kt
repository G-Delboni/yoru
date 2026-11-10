// Cliente HTTP do /api/sync. Só fala com o servidor; não conhece o banco.
package app.yoru.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Resposta do servidor (mesmos nomes do JSON, em português, como o servidor manda). */
@Serializable
data class SyncResponse(
    val cursor: String,
    val agora: String,
    val mudancas: Map<String, List<JsonObject>> = emptyMap(),
    val enviados: Int = 0,
    val aplicados: Int = 0,
    val ignorados: Int = 0,
    val rejeitados: List<Rejected> = emptyList(),
)

@Serializable
data class Rejected(val tabela: String, val id: String? = null, val motivo: String)

@Serializable
data class PairResponse(val token: String, val aparelho: String, val mensagem: String = "")

@Serializable
private data class ErrorDetail(val detail: String = "")

/** Erro com uma frase pronta para mostrar na tela. */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SyncClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)   // a primeira sincronia pode trazer tudo
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    val json = Json {
        ignoreUnknownKeys = true   // campo novo no servidor não quebra o app antigo
        encodeDefaults = true      // manda os campos mesmo no valor padrão (o servidor confia no que vem)
        explicitNulls = true       // null viaja como null; ausente significaria "não mexa"
    }

    /** Teste de conexão: devolve a contagem de registros do servidor, ou lança SyncException. */
    fun status(address: String, token: String): String {
        val request = Request.Builder()
            .url("$address/api/sync/status")
            .header("X-Yoru-Token", token)
            .get()
            .build()
        return execute(request, address)
    }

    fun sync(address: String, token: String, body: JsonObject): SyncResponse {
        val request = Request.Builder()
            .url("$address/api/sync")
            .header("X-Yoru-Token", token)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        val raw = execute(request, address)
        return try {
            json.decodeFromString(SyncResponse.serializer(), raw)
        } catch (e: Exception) {
            throw SyncException("O servidor respondeu algo que não entendi. Ele está na versão da Fase 3?", e)
        }
    }

    /** Aprovação do pareamento: manda a senha, recebe o token do aparelho. */
    fun pair(address: String, inviteId: String, password: String, deviceName: String): PairResponse {
        val body = buildJsonObject {
            put("convite", inviteId)
            put("senha", password)
            put("aparelho", deviceName)
        }
        val request = Request.Builder()
            .url("$address/api/sync/pair")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (response.code == 200) return json.decodeFromString(PairResponse.serializer(), text)
                // 403 traz o motivo em {"detail": "..."}: senha errada, convite vencido...
                val detail = runCatching { json.decodeFromString(ErrorDetail.serializer(), text).detail }.getOrNull()
                throw SyncException(detail?.ifBlank { null } ?: "O servidor respondeu ${response.code}.")
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: IOException) {
            throw SyncException("Não consegui falar com o servidor em $address: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun execute(request: Request, address: String): String {
        try {
            // `use` fecha a resposta ao sair do bloco, como try-with-resources.
            http.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                return when (response.code) {
                    200 -> text
                    401 -> throw SyncException("Token recusado. Copie de novo o token da página /sincronia.")
                    403 -> throw SyncException("O servidor recusou o acesso. Confira o endereço: deve terminar com a porta, como :8000.")
                    404 -> throw SyncException("Não há sincronia nesse endereço. O servidor está na versão da Fase 3?")
                    else -> throw SyncException("O servidor respondeu ${response.code}.")
                }
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: UnknownHostException) {
            throw SyncException("Endereço inválido: $address", e)
        } catch (e: ConnectException) {
            throw SyncException("Não encontrei o servidor em $address. Ele está ligado com --host 0.0.0.0, e o celular está no mesmo Wi-Fi?", e)
        } catch (e: SocketTimeoutException) {
            throw SyncException("O servidor demorou demais para responder.", e)
        } catch (e: IOException) {
            throw SyncException("Falha de rede: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}
