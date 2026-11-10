// Relógio Pomodoro. O tempo é contado pelo instante de término (não por ticks),
// então continua certo mesmo se o app ficar em segundo plano por um tempo.
// Ao terminar ou encerrar, grava uma FocusSession (dirty) para sincronizar.
package app.yoru.focus

import android.media.AudioManager
import android.media.ToneGenerator
import app.yoru.data.FocusSession
import app.yoru.data.Settings
import app.yoru.data.Time
import app.yoru.data.YoruDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Mode(val label: String) { Focus("foco"), Break("pausa"), LongBreak("pausa longa") }

data class FocusUi(
    val mode: Mode = Mode.Focus,
    val running: Boolean = false,
    val paused: Boolean = false,          // começou e está parado no meio
    val remainingSeconds: Int = 25 * 60,
    val totalSeconds: Int = 25 * 60,
    val focusDoneInRow: Int = 0,
    val message: String? = null,
    val areaId: String? = null,
    val taskId: String? = null,
) {
    val fraction: Float get() = if (totalSeconds == 0) 0f else 1f - remainingSeconds.toFloat() / totalSeconds
    val clock: String get() = "%02d:%02d".format(remainingSeconds / 60, remainingSeconds % 60)
}

class FocusTimer(
    private val db: YoruDatabase,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(FocusUi(remainingSeconds = settings.focusMinutes * 60, totalSeconds = settings.focusMinutes * 60))
    val ui: StateFlow<FocusUi> = _ui

    private var endAtMillis = 0L
    private var startedAtIso: String? = null
    private var ticker: Job? = null

    private fun durationSeconds(mode: Mode): Int = 60 * when (mode) {
        Mode.Focus -> settings.focusMinutes
        Mode.Break -> settings.breakMinutes
        Mode.LongBreak -> settings.longBreakMinutes
    }

    /** Tempos mudaram nas preferências: reflete no mostrador, se não estiver rodando. */
    fun refreshDurations() {
        val u = _ui.value
        if (!u.running && !u.paused) {
            val total = durationSeconds(u.mode)
            _ui.value = u.copy(remainingSeconds = total, totalSeconds = total)
        }
    }

    fun setContext(areaId: String?, taskId: String?) {
        _ui.value = _ui.value.copy(areaId = areaId, taskId = taskId)
    }

    fun startOrPause() {
        val u = _ui.value
        if (u.running) pause() else start()
    }

    private fun start() {
        val u = _ui.value
        if (startedAtIso == null) startedAtIso = Time.now()
        endAtMillis = System.currentTimeMillis() + u.remainingSeconds * 1000L
        _ui.value = u.copy(running = true, paused = false, message = null)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val remaining = ((endAtMillis - System.currentTimeMillis()) / 1000.0).roundToInt().coerceAtLeast(0)
                _ui.value = _ui.value.copy(remainingSeconds = remaining)
                if (remaining <= 0) {
                    finish(completed = true)
                    break
                }
                delay(250)
            }
        }
    }

    private fun pause() {
        ticker?.cancel()
        val remaining = ((endAtMillis - System.currentTimeMillis()) / 1000.0).roundToInt().coerceAtLeast(0)
        _ui.value = _ui.value.copy(running = false, paused = true, remainingSeconds = remaining)
    }

    /** Botão "Encerrar": registra o que passou (a partir de 1 minuto) e volta ao início. */
    fun stop() = finish(completed = false)

    /** Botão "Resetar sequência": volta ao primeiro foco, zerando a contagem até a pausa longa. */
    fun resetSequence() {
        val u = _ui.value
        if (u.running || u.paused) return
        _ui.value = u.copy(focusDoneInRow = 0, mode = Mode.Focus)
        reset("Sequência zerada. O próximo é o primeiro foco.")
    }

    /** Botão "Pular": vai para o próximo modo sem registrar nada. */
    fun skip() {
        ticker?.cancel()
        startedAtIso = null
        advance()
        reset("Pulado.")
    }

    private fun finish(completed: Boolean) {
        ticker?.cancel()
        val u = _ui.value
        val elapsedMinutes = if (completed) u.totalSeconds / 60 else ((u.totalSeconds - u.remainingSeconds) / 60.0).roundToInt()
        val started = startedAtIso ?: Time.now()
        startedAtIso = null
        if (completed || elapsedMinutes >= 1) {
            val now = Time.now()
            val session = FocusSession(
                id = Time.newId(), createdAt = now, updatedAt = now,
                areaId = u.areaId, taskId = u.taskId,
                kind = if (u.mode == Mode.Focus) "focus" else "break",
                startedAt = started, endedAt = now,
                plannedMinutes = u.totalSeconds / 60, minutes = elapsedMinutes, completed = completed,
                dirty = true,
            )
            scope.launch { db.focusSessions().upsert(session) }
        }
        if (completed) ring()
        val wasFocus = u.mode == Mode.Focus
        if (completed) advance()
        reset(
            when {
                completed && wasFocus -> "Foco concluído. Levante, beba água, e volte quando a pausa acabar."
                completed -> "Pausa concluída. Pronto para o próximo bloco."
                wasFocus -> "Sessão encerrada. O tempo que passou ficou registrado."
                else -> "Pausa encerrada."
            }
        )
    }

    private fun advance() {
        val u = _ui.value
        val next = if (u.mode == Mode.Focus) {
            val done = u.focusDoneInRow + 1
            _ui.value = u.copy(focusDoneInRow = done)
            if (done % settings.cycles == 0) Mode.LongBreak else Mode.Break
        } else Mode.Focus
        _ui.value = _ui.value.copy(mode = next)
    }

    private fun reset(message: String?) {
        val total = durationSeconds(_ui.value.mode)
        _ui.value = _ui.value.copy(running = false, paused = false, remainingSeconds = total, totalSeconds = total, message = message)
    }

    private fun ring() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            scope.launch { delay(600); tone.release() }
        }
    }
}
