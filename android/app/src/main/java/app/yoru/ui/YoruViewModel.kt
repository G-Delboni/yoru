// ViewModel: estado das telas e as ações do usuário. Sobrevive à rotação da tela.
// As listas vêm do banco como Flow: quando uma tarefa muda, a tela se redesenha sozinha.
package app.yoru.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.yoru.YoruApp
import app.yoru.data.Area
import app.yoru.data.Event
import app.yoru.data.Habit
import app.yoru.data.HabitCheck
import app.yoru.data.HabitRow
import app.yoru.data.LogEntry
import app.yoru.data.Period
import app.yoru.data.Task
import app.yoru.data.Time
import app.yoru.data.Tracking
import app.yoru.data.TrackingUi
import app.yoru.focus.FocusTimer
import app.yoru.focus.FocusUi
import app.yoru.data.isDoing
import app.yoru.data.isDone
import app.yoru.sync.Invite
import app.yoru.sync.SyncException
import app.yoru.sync.SyncReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SyncUi(
    val running: Boolean = false,
    val lastReport: SyncReport? = null,
)

class YoruViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YoruApp
    private val db = app.db
    val settings = app.settings

    // --- Leituras (Flow): a tela coleta e se atualiza sozinha ---------------------
    val areas: Flow<List<Area>> = db.areas().all()
    val todayTasks: Flow<List<Task>> = db.tasks().today(Time.today())
    val doneToday: Flow<List<Task>> = db.tasks().doneSince(Time.startOfTodayUtc())
    val inbox: Flow<List<Task>> = db.tasks().inbox()
    val inboxCount: Flow<Int> = db.tasks().inboxCount()
    val pendingCount: Flow<Int> = db.tasks().pendingCount()
    val lastNextStep: Flow<LogEntry?> = db.logs().lastWithNextStep()
    val upcomingEvents: Flow<List<Event>> = db.events().upcoming(Time.nowWallClock(), 3)
    val openCountByArea: Flow<Map<String, Int>> =
        db.tasks().openCountByArea().map { list -> list.associate { it.area_id to it.n } }

    fun openTasks(areaId: String): Flow<List<Task>> = db.tasks().openByArea(areaId)

    private val _syncUi = MutableStateFlow(SyncUi())
    val syncUi: StateFlow<SyncUi> = _syncUi

    // --- Hábitos ---------------------------------------------------------------------
    private val weekAgo: String get() = LocalDate.now().minusDays(60).toString()
    val habitRows: Flow<List<HabitRow>> =
        combine(db.habits().active(), db.habitChecks().between(weekAgo, Time.today())) { habits, checks ->
            Tracking.habitRows(habits, checks)
        }

    fun addHabit(name: String, kanji: String?, daysPerWeek: Int) {
        val text = name.trim()
        if (text.isEmpty()) return
        val now = Time.now()
        viewModelScope.launch {
            val position = db.habits().countAll()
            db.habits().upsert(Habit(id = Time.newId(), createdAt = now, updatedAt = now, name = text,
                kanji = kanji?.trim()?.take(2)?.ifBlank { null }, daysPerWeek = daysPerWeek.coerceIn(1, 7),
                position = position, dirty = true))
        }
    }

    /** Marca ou desmarca um dia. Desmarcar é exclusão suave, como no servidor. */
    fun toggleHabit(habit: Habit, day: LocalDate = LocalDate.now()) {
        if (day.isAfter(LocalDate.now())) return
        viewModelScope.launch {
            val existing = db.habitChecks().forDay(habit.id, day.toString())
            val now = Time.now()
            if (existing.isNotEmpty()) {
                for (c in existing) db.habitChecks().upsert(c.copy(deletedAt = now, updatedAt = now, dirty = true))
            } else {
                db.habitChecks().upsert(HabitCheck(id = Time.newId(), createdAt = now, updatedAt = now,
                    habitId = habit.id, day = day.toString(), done = true, dirty = true))
            }
        }
    }

    fun archiveHabit(habit: Habit) = viewModelScope.launch {
        db.habits().upsert(habit.copy(archivedAt = Time.now(), updatedAt = Time.now(), dirty = true))
    }

    fun deleteHabit(habit: Habit) = viewModelScope.launch {
        db.habits().upsert(habit.copy(deletedAt = Time.now(), updatedAt = Time.now(), dirty = true))
    }

    // --- Foco (Pomodoro) ---------------------------------------------------------------
    val focus = FocusTimer(db, settings, viewModelScope)
    val focusUi: StateFlow<FocusUi> = focus.ui
    val focusToday: Flow<List<app.yoru.data.FocusSession>> =
        db.focusSessions().between(Tracking.startOfDayUtc(LocalDate.now()), Tracking.endOfDayUtc(LocalDate.now()))

    fun savePomodoro(focusMin: Int, breakMin: Int, longMin: Int, cycles: Int) {
        settings.focusMinutes = focusMin
        settings.breakMinutes = breakMin
        settings.longBreakMinutes = longMin
        settings.cycles = cycles
        focus.refreshDurations()
    }

    fun deleteFocusSession(s: app.yoru.data.FocusSession) = viewModelScope.launch {
        db.focusSessions().upsert(s.copy(deletedAt = Time.now(), updatedAt = Time.now(), dirty = true))
    }

    // --- Progresso -------------------------------------------------------------------
    private val _tracking = MutableStateFlow<TrackingUi?>(null)
    val tracking: StateFlow<TrackingUi?> = _tracking

    fun loadTracking(period: Period) {
        viewModelScope.launch {
            val (start, end) = Tracking.range(period)
            val fromUtc = Tracking.startOfDayUtc(start)
            val toUtc = Tracking.endOfDayUtc(end)
            val today = LocalDate.now()
            val last = if (end.isAfter(today)) today else end
            _tracking.value = Tracking.build(
                period = period,
                doneTasks = db.tasks().doneBetween(fromUtc, toUtc),
                focus = db.focusSessions().betweenOnce(fromUtc, toUtc),
                logs = db.logs().between(start.toString(), end.toString()),
                bookIds = db.resources().bookIds().toSet(),
                habits = db.habits().active().first(),
                checks = db.habitChecks().betweenOnce(start.toString(), last.toString()),
            )
        }
    }

    // --- Pareamento: o convite que chegou pela rede, se houver -------------------
    val invite: StateFlow<Invite?> = app.pairing.invite

    init {
        // Escuta a rede enquanto o app estiver vivo (viewModelScope morre junto com ele).
        app.pairing.start(viewModelScope)
    }

    fun dismissInvite(invite: Invite) = app.pairing.dismiss(invite)

    /** Usuário digitou a senha: tenta aprovar. Devolve a mensagem para a tela; null = sucesso. */
    suspend fun approveInvite(invite: Invite, password: String): String? {
        return try {
            val result = withContext(Dispatchers.IO) {
                app.client.pair(invite.endereco, invite.convite, password, app.deviceName)
            }
            settings.saveConnection(invite.endereco, result.token)
            app.pairing.clear()
            sync()
            null
        } catch (e: SyncException) {
            e.message
        }
    }

    // --- Ações -----------------------------------------------------------------------
    // Toda alteração: updated_at = agora e dirty = true. O resto é com o SyncEngine.

    fun capture(title: String, forToday: Boolean, areaId: String? = null) {
        val text = title.trim()
        if (text.isEmpty()) return
        val now = Time.now()
        val task = Task(
            id = Time.newId(),
            createdAt = now,
            updatedAt = now,
            title = text,
            areaId = areaId,
            scheduledFor = if (forToday) Time.today() else null,
            dirty = true,
        )
        viewModelScope.launch { db.tasks().upsert(task) }
    }

    fun toggleDone(task: Task) = save(
        if (task.isDone) task.copy(status = "todo", completedAt = null)
        else task.copy(status = "done", completedAt = Time.now())
    )

    fun toggleDoing(task: Task) = save(task.copy(status = if (task.isDoing) "todo" else "doing"))

    fun togglePriority(task: Task) =
        save(task.copy(priority = if (task.priority == "high") "normal" else "high"))

    fun scheduleForToday(task: Task) = save(task.copy(scheduledFor = Time.today()))

    fun moveToArea(task: Task, areaId: String) = save(task.copy(areaId = areaId))

    fun delete(task: Task) {
        val now = Time.now()
        save(task.copy(deletedAt = now))
    }

    private fun save(task: Task) {
        val stamped = task.copy(updatedAt = Time.now(), dirty = true)
        viewModelScope.launch { db.tasks().upsert(stamped) }
    }

    // --- Sincronia -----------------------------------------------------------------

    fun saveConnection(address: String, token: String) = settings.saveConnection(address, token)

    suspend fun testConnection(address: String, token: String): SyncReport = app.sync.test(address, token)

    /** Botão "Atualizar" e também a sincronia automática ao abrir o app. */
    fun sync(onlyIfConfigured: Boolean = false) {
        if (_syncUi.value.running) return
        if (onlyIfConfigured && !settings.isConfigured) return
        _syncUi.value = _syncUi.value.copy(running = true)
        viewModelScope.launch {
            val report = app.sync.sync()
            _syncUi.value = SyncUi(running = false, lastReport = report)
        }
    }
}
