// Tabelas locais. Espelham app/models.py do servidor, coluna por coluna.
//
// Cada classe é ao mesmo tempo:
//   - uma tabela do Room (@Entity)                      -> banco SQLite do celular
//   - um objeto serializável em JSON (@Serializable)    -> o que viaja no /api/sync
//
// Convenções:
//   - Carimbos de tempo e datas ficam como texto, exatamente como o servidor manda
//     ("2026-10-31T18:45:12.123456Z" e "2026-10-31"). Comparar texto nesse formato
//     é comparar tempo, e o app não precisa converter nada para sincronizar.
//   - `dirty` é só local (@Transient: fora do JSON): marca o que mudou aqui e ainda
//     não foi enviado ao servidor.
package app.yoru.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** O que toda tabela sincronizável tem. Equivale ao SyncBase do servidor. */
interface Synced {
    val id: String
    val updatedAt: String
    val deletedAt: String?
    val dirty: Boolean
}

@Serializable
@Entity(tableName = "area")
data class Area(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    val name: String,
    val slug: String,
    val kanji: String? = null,
    val legend: String? = null,
    val position: Int = 0,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "objective")
data class Objective(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    @ColumnInfo(name = "area_id") @SerialName("area_id") val areaId: String,
    val title: String,
    val description: String? = null,
    @ColumnInfo(name = "due_date") @SerialName("due_date") val dueDate: String? = null,
    @ColumnInfo(name = "is_done") @SerialName("is_done") val isDone: Boolean = false,
    @ColumnInfo(name = "is_paused") @SerialName("is_paused") val isPaused: Boolean = false,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "goal")
data class Goal(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    @ColumnInfo(name = "objective_id") @SerialName("objective_id") val objectiveId: String? = null,
    @ColumnInfo(name = "area_id") @SerialName("area_id") val areaId: String,
    val title: String,
    @ColumnInfo(name = "target_value") @SerialName("target_value") val targetValue: Double? = null,
    @ColumnInfo(name = "current_value") @SerialName("current_value") val currentValue: Double = 0.0,
    val unit: String? = null,
    @ColumnInfo(name = "due_date") @SerialName("due_date") val dueDate: String? = null,
    @ColumnInfo(name = "is_done") @SerialName("is_done") val isDone: Boolean = false,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "task")
data class Task(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    @ColumnInfo(name = "area_id") @SerialName("area_id") val areaId: String? = null,      // null = caixa de entrada
    @ColumnInfo(name = "goal_id") @SerialName("goal_id") val goalId: String? = null,
    @ColumnInfo(name = "resource_id") @SerialName("resource_id") val resourceId: String? = null,
    val title: String,
    val notes: String? = null,
    val status: String = "todo",          // todo | doing | done   (TaskStatus no servidor)
    val priority: String = "normal",      // low | normal | high
    @ColumnInfo(name = "due_date") @SerialName("due_date") val dueDate: String? = null,
    @ColumnInfo(name = "scheduled_for") @SerialName("scheduled_for") val scheduledFor: String? = null,
    @ColumnInfo(name = "completed_at") @SerialName("completed_at") val completedAt: String? = null,
    @ColumnInfo(name = "estimate_minutes") @SerialName("estimate_minutes") val estimateMinutes: Int? = null,
    val position: Int = 0,
    @Transient override val dirty: Boolean = false,
) : Synced

// Propriedades de extensão: ficam fora da classe para o Room não as confundir com colunas.
val Task.isDone: Boolean get() = status == "done"
val Task.isDoing: Boolean get() = status == "doing"

@Serializable
@Entity(tableName = "resource")
data class Resource(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    @ColumnInfo(name = "area_id") @SerialName("area_id") val areaId: String,
    val kind: String,                     // book | course | subject | article | other
    val status: String = "wanted",        // wanted | active | finished | paused
    val title: String,
    val author: String? = null,
    val url: String? = null,
    @ColumnInfo(name = "progress_current") @SerialName("progress_current") val progressCurrent: Double = 0.0,
    @ColumnInfo(name = "progress_total") @SerialName("progress_total") val progressTotal: Double? = null,
    @ColumnInfo(name = "progress_unit") @SerialName("progress_unit") val progressUnit: String? = null,
    val schedule: String? = null,
    val grade: Double? = null,
    @ColumnInfo(name = "started_at") @SerialName("started_at") val startedAt: String? = null,
    @ColumnInfo(name = "finished_at") @SerialName("finished_at") val finishedAt: String? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "event")
data class Event(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    @ColumnInfo(name = "area_id") @SerialName("area_id") val areaId: String,
    @ColumnInfo(name = "resource_id") @SerialName("resource_id") val resourceId: String? = null,
    val kind: String = "other",           // exam | deadline | meeting | other
    val title: String,
    @ColumnInfo(name = "starts_at") @SerialName("starts_at") val startsAt: String,   // hora de parede: "2026-11-12T19:00:00"
    @ColumnInfo(name = "ends_at") @SerialName("ends_at") val endsAt: String? = null,
    val location: String? = null,
    val notes: String? = null,
    @ColumnInfo(name = "remind_days_before") @SerialName("remind_days_before") val remindDaysBefore: String? = "7,3,1",
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "logentry")
data class LogEntry(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    @ColumnInfo(name = "area_id") @SerialName("area_id") val areaId: String,
    @ColumnInfo(name = "task_id") @SerialName("task_id") val taskId: String? = null,
    @ColumnInfo(name = "resource_id") @SerialName("resource_id") val resourceId: String? = null,
    val day: String,
    val did: String? = null,
    @ColumnInfo(name = "stopped_at") @SerialName("stopped_at") val stoppedAt: String? = null,
    @ColumnInfo(name = "next_step") @SerialName("next_step") val nextStep: String? = null,
    val minutes: Int? = null,
    val amount: Double? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "note")
data class Note(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    @ColumnInfo(name = "area_id") @SerialName("area_id") val areaId: String? = null,
    @ColumnInfo(name = "task_id") @SerialName("task_id") val taskId: String? = null,
    @ColumnInfo(name = "resource_id") @SerialName("resource_id") val resourceId: String? = null,
    @ColumnInfo(name = "objective_id") @SerialName("objective_id") val objectiveId: String? = null,
    val title: String? = null,
    val body: String = "",
    val page: Int? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "habit")
data class Habit(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    @ColumnInfo(name = "area_id") @SerialName("area_id") val areaId: String? = null,
    val name: String,
    val kanji: String? = null,
    @ColumnInfo(name = "days_per_week") @SerialName("days_per_week") val daysPerWeek: Int = 7,
    val position: Int = 0,
    @ColumnInfo(name = "archived_at") @SerialName("archived_at") val archivedAt: String? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "habitcheck")
data class HabitCheck(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    @ColumnInfo(name = "habit_id") @SerialName("habit_id") val habitId: String,
    val day: String,
    val done: Boolean = true,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "focussession")
data class FocusSession(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    @ColumnInfo(name = "area_id") @SerialName("area_id") val areaId: String? = null,
    @ColumnInfo(name = "task_id") @SerialName("task_id") val taskId: String? = null,
    val kind: String = "focus",                 // focus | break
    @ColumnInfo(name = "started_at") @SerialName("started_at") val startedAt: String,   // instante UTC
    @ColumnInfo(name = "ended_at") @SerialName("ended_at") val endedAt: String? = null,
    @ColumnInfo(name = "planned_minutes") @SerialName("planned_minutes") val plannedMinutes: Int = 25,
    val minutes: Int = 0,
    val completed: Boolean = false,
    val note: String? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "tag")
data class Tag(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "created_at") @SerialName("created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") @SerialName("updated_at") override val updatedAt: String,
    @ColumnInfo(name = "deleted_at") @SerialName("deleted_at") override val deletedAt: String? = null,
    @ColumnInfo(name = "synced_at") @SerialName("synced_at") val syncedAt: String? = null,
    val name: String,
    @Transient override val dirty: Boolean = false,
) : Synced
