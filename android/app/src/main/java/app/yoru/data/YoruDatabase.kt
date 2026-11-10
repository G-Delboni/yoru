package app.yoru.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Area::class, Objective::class, Goal::class, Task::class, Resource::class,
        Event::class, LogEntry::class, Note::class, Tag::class,
        Habit::class, HabitCheck::class, FocusSession::class],
    version = 2,
    exportSchema = true,
)
abstract class YoruDatabase : RoomDatabase() {
    abstract fun areas(): AreaDao
    abstract fun objectives(): ObjectiveDao
    abstract fun goals(): GoalDao
    abstract fun tasks(): TaskDao
    abstract fun resources(): ResourceDao
    abstract fun events(): EventDao
    abstract fun logs(): LogEntryDao
    abstract fun notes(): NoteDao
    abstract fun tags(): TagDao
    abstract fun habits(): HabitDao
    abstract fun habitChecks(): HabitCheckDao
    abstract fun focusSessions(): FocusSessionDao

    companion object {
        // `companion object` = o lugar dos membros "static" em Kotlin.
        fun open(context: Context): YoruDatabase =
            Room.databaseBuilder(context, YoruDatabase::class.java, "yoru.db")
                .addMigrations(MIGRATION_1_2)
                // Sem caminho de migração conhecido, recria: tudo está no servidor.
                .fallbackToDestructiveMigration()
                .build()

        /**
         * Versão 1 → 2 (Fase 5): três tabelas novas. O SQL tem de bater coluna a coluna com
         * o que o Room espera das @Entity (nome, tipo, NOT NULL, chave), senão ele recusa abrir.
         * Texto = TEXT, Int/Boolean = INTEGER, Double = REAL.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `habit` (`id` TEXT NOT NULL, `created_at` TEXT NOT NULL, " +
                        "`updated_at` TEXT NOT NULL, `deleted_at` TEXT, `synced_at` TEXT, `area_id` TEXT, " +
                        "`name` TEXT NOT NULL, `kanji` TEXT, `days_per_week` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                        "`archived_at` TEXT, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `habitcheck` (`id` TEXT NOT NULL, `created_at` TEXT NOT NULL, " +
                        "`updated_at` TEXT NOT NULL, `deleted_at` TEXT, `synced_at` TEXT, `habit_id` TEXT NOT NULL, " +
                        "`day` TEXT NOT NULL, `done` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `focussession` (`id` TEXT NOT NULL, `created_at` TEXT NOT NULL, " +
                        "`updated_at` TEXT NOT NULL, `deleted_at` TEXT, `synced_at` TEXT, `area_id` TEXT, `task_id` TEXT, " +
                        "`kind` TEXT NOT NULL, `started_at` TEXT NOT NULL, `ended_at` TEXT, `planned_minutes` INTEGER NOT NULL, " +
                        "`minutes` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `note` TEXT, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }
    }
}
