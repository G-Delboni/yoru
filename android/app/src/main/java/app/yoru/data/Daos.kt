// DAOs: as consultas ao banco local. O Room gera a implementação a partir das
// interfaces (parecido com Spring Data, mas o SQL é escrito por você).
//
// `suspend fun` = função que pode pausar sem travar a thread (coroutines).
// `Flow<List<T>>` = consulta "viva": a tela recebe uma lista nova sempre que a tabela muda.
package app.yoru.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Gravação comum a todas as tabelas. Só métodos anotados podem ficar numa interface
 * genérica: o Room gera Java, e um `suspend fun dirty(): List<T>` herdado sem anotação
 * vira uma assinatura que o Java não consegue sobrescrever. Por isso `dirty()` e
 * `byId()` são declarados em cada DAO e entregues ao SyncEngine como funções.
 */
interface SyncDao<T : Synced> {
    @Upsert suspend fun upsert(row: T)
    @Upsert suspend fun upsertAll(rows: List<T>)
}

@Dao
interface AreaDao : SyncDao<Area> {
    @Query("SELECT * FROM area WHERE dirty = 1") suspend fun dirty(): List<Area>
    @Query("SELECT * FROM area WHERE id = :id") suspend fun byId(id: String): Area?

    @Query("SELECT * FROM area WHERE deleted_at IS NULL ORDER BY position, name")
    fun all(): Flow<List<Area>>
}

@Dao
interface TaskDao : SyncDao<Task> {
    @Query("SELECT * FROM task WHERE dirty = 1") suspend fun dirty(): List<Task>
    @Query("SELECT * FROM task WHERE id = :id") suspend fun byId(id: String): Task?

    // "Fazendo" primeiro, depois prioridade alta, depois a ordem de criação.
    @Query(
        """SELECT * FROM task
           WHERE deleted_at IS NULL AND status != 'done'
             AND (scheduled_for = :today OR due_date <= :today)
           ORDER BY CASE status WHEN 'doing' THEN 0 ELSE 1 END,
                    CASE priority WHEN 'high' THEN 0 WHEN 'normal' THEN 1 ELSE 2 END,
                    position, created_at"""
    )
    fun today(today: String): Flow<List<Task>>

    @Query(
        """SELECT * FROM task
           WHERE deleted_at IS NULL AND status = 'done' AND completed_at >= :sinceUtc
           ORDER BY completed_at DESC"""
    )
    fun doneSince(sinceUtc: String): Flow<List<Task>>

    @Query(
        """SELECT * FROM task
           WHERE deleted_at IS NULL AND status != 'done' AND area_id IS NULL
           ORDER BY created_at DESC"""
    )
    fun inbox(): Flow<List<Task>>

    @Query("SELECT COUNT(*) FROM task WHERE deleted_at IS NULL AND status != 'done' AND area_id IS NULL")
    fun inboxCount(): Flow<Int>

    @Query(
        """SELECT * FROM task
           WHERE deleted_at IS NULL AND status != 'done' AND area_id = :areaId
           ORDER BY CASE status WHEN 'doing' THEN 0 ELSE 1 END,
                    CASE priority WHEN 'high' THEN 0 WHEN 'normal' THEN 1 ELSE 2 END,
                    due_date IS NULL, due_date, position, created_at"""
    )
    fun openByArea(areaId: String): Flow<List<Task>>

    @Query("SELECT area_id, COUNT(*) AS n FROM task WHERE deleted_at IS NULL AND status != 'done' AND area_id IS NOT NULL GROUP BY area_id")
    fun openCountByArea(): Flow<List<AreaCount>>

    @Query("SELECT COUNT(*) FROM task WHERE dirty = 1")
    fun pendingCount(): Flow<Int>

    @Query("SELECT * FROM task WHERE deleted_at IS NULL AND status = 'done' AND completed_at >= :fromUtc AND completed_at < :toUtc ORDER BY completed_at DESC")
    suspend fun doneBetween(fromUtc: String, toUtc: String): List<Task>
}

/** Resultado da consulta agrupada (não é tabela). */
data class AreaCount(val area_id: String, val n: Int)

@Dao
interface LogEntryDao : SyncDao<LogEntry> {
    @Query("SELECT * FROM logentry WHERE dirty = 1") suspend fun dirty(): List<LogEntry>
    @Query("SELECT * FROM logentry WHERE id = :id") suspend fun byId(id: String): LogEntry?

    @Query(
        """SELECT * FROM logentry
           WHERE deleted_at IS NULL AND next_step IS NOT NULL AND next_step != ''
           ORDER BY day DESC, created_at DESC LIMIT 1"""
    )
    fun lastWithNextStep(): Flow<LogEntry?>

    @Query("SELECT * FROM logentry WHERE deleted_at IS NULL AND day >= :fromDay AND day <= :toDay")
    suspend fun between(fromDay: String, toDay: String): List<LogEntry>
}

@Dao
interface ObjectiveDao : SyncDao<Objective> {
    @Query("SELECT * FROM objective WHERE dirty = 1") suspend fun dirty(): List<Objective>
    @Query("SELECT * FROM objective WHERE id = :id") suspend fun byId(id: String): Objective?
}

@Dao
interface GoalDao : SyncDao<Goal> {
    @Query("SELECT * FROM goal WHERE dirty = 1") suspend fun dirty(): List<Goal>
    @Query("SELECT * FROM goal WHERE id = :id") suspend fun byId(id: String): Goal?
}

@Dao
interface ResourceDao : SyncDao<Resource> {
    @Query("SELECT * FROM resource WHERE dirty = 1") suspend fun dirty(): List<Resource>
    @Query("SELECT * FROM resource WHERE id = :id") suspend fun byId(id: String): Resource?

    @Query("SELECT id FROM resource WHERE kind = 'book'")
    suspend fun bookIds(): List<String>
}

@Dao
interface EventDao : SyncDao<Event> {
    @Query("SELECT * FROM event WHERE dirty = 1") suspend fun dirty(): List<Event>
    @Query("SELECT * FROM event WHERE id = :id") suspend fun byId(id: String): Event?

    @Query(
        """SELECT * FROM event
           WHERE deleted_at IS NULL AND starts_at >= :fromWallClock
           ORDER BY starts_at LIMIT :limit"""
    )
    fun upcoming(fromWallClock: String, limit: Int): Flow<List<Event>>
}

@Dao
interface NoteDao : SyncDao<Note> {
    @Query("SELECT * FROM note WHERE dirty = 1") suspend fun dirty(): List<Note>
    @Query("SELECT * FROM note WHERE id = :id") suspend fun byId(id: String): Note?
}

@Dao
interface HabitDao : SyncDao<Habit> {
    @Query("SELECT * FROM habit WHERE dirty = 1") suspend fun dirty(): List<Habit>
    @Query("SELECT * FROM habit WHERE id = :id") suspend fun byId(id: String): Habit?

    @Query("SELECT * FROM habit WHERE deleted_at IS NULL AND archived_at IS NULL ORDER BY position, created_at")
    fun active(): Flow<List<Habit>>

    @Query("SELECT COUNT(*) FROM habit WHERE deleted_at IS NULL")
    suspend fun countAll(): Int
}

@Dao
interface HabitCheckDao : SyncDao<HabitCheck> {
    @Query("SELECT * FROM habitcheck WHERE dirty = 1") suspend fun dirty(): List<HabitCheck>
    @Query("SELECT * FROM habitcheck WHERE id = :id") suspend fun byId(id: String): HabitCheck?

    @Query("SELECT * FROM habitcheck WHERE deleted_at IS NULL AND done = 1 AND day >= :fromDay AND day <= :toDay")
    fun between(fromDay: String, toDay: String): Flow<List<HabitCheck>>

    @Query("SELECT * FROM habitcheck WHERE deleted_at IS NULL AND done = 1 AND day >= :fromDay AND day <= :toDay")
    suspend fun betweenOnce(fromDay: String, toDay: String): List<HabitCheck>

    @Query("SELECT * FROM habitcheck WHERE deleted_at IS NULL AND habit_id = :habitId AND day = :day")
    suspend fun forDay(habitId: String, day: String): List<HabitCheck>
}

@Dao
interface FocusSessionDao : SyncDao<FocusSession> {
    @Query("SELECT * FROM focussession WHERE dirty = 1") suspend fun dirty(): List<FocusSession>
    @Query("SELECT * FROM focussession WHERE id = :id") suspend fun byId(id: String): FocusSession?

    @Query("SELECT * FROM focussession WHERE deleted_at IS NULL AND started_at >= :fromUtc AND started_at < :toUtc ORDER BY started_at DESC")
    fun between(fromUtc: String, toUtc: String): Flow<List<FocusSession>>

    @Query("SELECT * FROM focussession WHERE deleted_at IS NULL AND started_at >= :fromUtc AND started_at < :toUtc ORDER BY started_at DESC")
    suspend fun betweenOnce(fromUtc: String, toUtc: String): List<FocusSession>
}

@Dao
interface TagDao : SyncDao<Tag> {
    @Query("SELECT * FROM tag WHERE dirty = 1") suspend fun dirty(): List<Tag>
    @Query("SELECT * FROM tag WHERE id = :id") suspend fun byId(id: String): Tag?
}
