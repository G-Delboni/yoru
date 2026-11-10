// Motor de sincronia do lado do aparelho. Espelha as regras de app/sync.py:
//
//   1. junta tudo que está `dirty` (mudou aqui e não foi enviado)
//   2. POST /api/sync com essas mudanças e o cursor da última vez
//   3. aplica o que veio: vence o updated_at mais recente (texto ISO, comparável)
//   4. limpa o `dirty` do que foi aceito e guarda o cursor novo
//
// Falhou no meio? Nada se perde: o `dirty` continua marcado e o cursor não avança,
// então a próxima tentativa refaz o mesmo lote. Reenviar é seguro no servidor.
package app.yoru.sync

import app.yoru.data.Area
import app.yoru.data.Event
import app.yoru.data.FocusSession
import app.yoru.data.Goal
import app.yoru.data.Habit
import app.yoru.data.HabitCheck
import app.yoru.data.LogEntry
import app.yoru.data.Note
import app.yoru.data.Objective
import app.yoru.data.Resource
import app.yoru.data.Settings
import app.yoru.data.SyncDao
import app.yoru.data.Synced
import app.yoru.data.Tag
import app.yoru.data.Task
import app.yoru.data.YoruDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** O que a tela mostra depois de uma sincronia. */
data class SyncReport(
    val ok: Boolean,
    val message: String,
    val sent: Int = 0,
    val received: Int = 0,
    val rejected: List<Rejected> = emptyList(),
) {
    companion object {
        fun failure(message: String) = SyncReport(ok = false, message = message)
    }
}

/**
 * Uma tabela que sincroniza. `T` é o tipo da linha; o motor trabalha com uma lista
 * de `TableSpec<*>` sem saber qual tabela é qual: tudo que precisa está aqui dentro.
 *
 * `clean` recebe uma linha e devolve a mesma com dirty = false (cada data class
 * tem o próprio `copy`, por isso a função vem de fora).
 */
class TableSpec<T : Synced>(
    val name: String,
    private val serializer: KSerializer<T>,
    private val dao: SyncDao<T>,
    private val dirty: suspend () -> List<T>,
    private val byId: suspend (String) -> T?,
    private val clean: (T) -> T,
) {
    private var sent: List<T> = emptyList()

    /** Lê o que está pendente e guarda a lista para confirmar depois. */
    suspend fun outgoing(json: Json): List<JsonObject> {
        sent = dirty()
        return sent.map { json.encodeToJsonElement(serializer, it).jsonObject }
    }

    /**
     * O servidor aceitou o lote: tira o `dirty` do que foi enviado, desde que a linha
     * não tenha mudado de novo enquanto a requisição estava no ar.
     */
    suspend fun confirm(rejectedIds: Set<String>) {
        for (row in sent) {
            if (row.id in rejectedIds) continue  // continua dirty: tenta de novo na próxima
            val current = byId(row.id) ?: continue
            if (current.dirty && current.updatedAt == row.updatedAt) dao.upsert(clean(current))
        }
        sent = emptyList()
    }

    /** Aplica linhas vindas do servidor. Devolve quantas de fato mudaram algo. */
    suspend fun apply(json: Json, rows: List<JsonObject>): Int {
        var applied = 0
        for (obj in rows) {
            val incoming = json.decodeFromJsonElement(serializer, obj)  // chega com dirty = false
            val local = byId(incoming.id)
            if (local == null || incoming.updatedAt > local.updatedAt) {
                dao.upsert(incoming)
                applied++
            }
            // Empate ou local mais novo: fica o local. Se ele estiver dirty, sobe na próxima.
        }
        return applied
    }

    val pendingCount: Int get() = sent.size
}

class SyncEngine(
    db: YoruDatabase,
    private val settings: Settings,
    private val client: SyncClient,
    private val deviceName: String,
) {
    private val json = client.json
    private val lock = Mutex()  // duas sincronias ao mesmo tempo (botão + abertura do app) viram uma fila

    // Mesma ordem do servidor: quem é referenciado antes de quem referencia.
    private val tables: List<TableSpec<*>> = listOf(
        spec("area", Area.serializer(), db.areas(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
        spec("objective", Objective.serializer(), db.objectives(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
        spec("goal", Goal.serializer(), db.goals(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
        spec("resource", Resource.serializer(), db.resources(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
        spec("task", Task.serializer(), db.tasks(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
        spec("event", Event.serializer(), db.events(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
        spec("logentry", LogEntry.serializer(), db.logs(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
        spec("note", Note.serializer(), db.notes(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
        spec("tag", Tag.serializer(), db.tags(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
        spec("habit", Habit.serializer(), db.habits(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
        spec("habitcheck", HabitCheck.serializer(), db.habitChecks(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
        spec("focussession", FocusSession.serializer(), db.focusSessions(), { it.dirty() }, { d, id -> d.byId(id) }) { it.copy(dirty = false) },
    )

    /** Monta um TableSpec a partir do DAO concreto, amarrando `dirty` e `byId` a ele. */
    private fun <T : Synced, D : SyncDao<T>> spec(
        name: String,
        serializer: KSerializer<T>,
        dao: D,
        dirty: suspend (D) -> List<T>,
        byId: suspend (D, String) -> T?,
        clean: (T) -> T,
    ): TableSpec<T> = TableSpec(name, serializer, dao, { dirty(dao) }, { id -> byId(dao, id) }, clean)

    /** Só testa endereço e token, sem mexer nos dados. */
    suspend fun test(address: String, token: String): SyncReport = withContext(Dispatchers.IO) {
        try {
            client.status(Settings.normalizeAddress(address), token.trim())
            SyncReport(ok = true, message = "Conectado.")
        } catch (e: SyncException) {
            SyncReport.failure(e.message ?: "Falha ao conectar.")
        }
    }

    /** Um ciclo completo. Nunca lança: devolve um relatório com ok = false. */
    suspend fun sync(): SyncReport = withContext(Dispatchers.IO) {
        if (!settings.isConfigured) {
            return@withContext SyncReport.failure("Informe o endereço e o token na aba Sincronia.")
        }
        lock.withLock {
            try {
                // 1. o que mudou aqui
                var sent = 0
                val pending = mutableMapOf<String, JsonArray>()
                for (table in tables) {
                    val rows = table.outgoing(json)
                    if (rows.isNotEmpty()) {
                        pending[table.name] = JsonArray(rows)
                        sent += rows.size
                    }
                }
                val body = buildJsonObject {
                    put("dispositivo", deviceName)
                    put("desde", settings.cursor)
                    put("mudancas", JsonObject(pending))
                }

                // 2. ida e volta
                val response = client.sync(settings.address.value, settings.token.value, body)

                // 3. o que mudou lá
                var received = 0
                for (table in tables) {
                    val rows = response.mudancas[table.name] ?: continue
                    received += table.apply(json, rows)
                }

                // 4. confirmar e avançar o cursor
                val rejectedByTable = response.rejeitados.groupBy({ it.tabela }, { it.id })
                for (table in tables) {
                    table.confirm(rejectedByTable[table.name].orEmpty().filterNotNull().toSet())
                }
                settings.cursor = response.cursor

                val report = SyncReport(
                    ok = true,
                    message = describe(sent, received, response),
                    sent = response.aplicados,
                    received = received,
                    rejected = response.rejeitados,
                )
                settings.recordSync(response.agora, report.message)
                report
            } catch (e: SyncException) {
                val report = SyncReport.failure(e.message ?: "Falha na sincronia.")
                settings.recordSync(null, report.message)
                report
            } catch (e: Exception) {
                val report = SyncReport.failure("Erro inesperado: ${e.javaClass.simpleName}: ${e.message}")
                settings.recordSync(null, report.message)
                report
            }
        }
    }

    private fun describe(sent: Int, received: Int, r: SyncResponse): String {
        val parts = mutableListOf<String>()
        parts += when (r.aplicados) {
            0 -> if (sent == 0) "nada a enviar" else "nada novo para o servidor"
            1 -> "1 enviado"
            else -> "${r.aplicados} enviados"
        }
        parts += when (received) {
            0 -> "nada novo por aqui"
            1 -> "1 recebido"
            else -> "$received recebidos"
        }
        if (r.rejeitados.isNotEmpty()) {
            parts += if (r.rejeitados.size == 1) "1 rejeitado" else "${r.rejeitados.size} rejeitados"
        }
        return parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }
}
