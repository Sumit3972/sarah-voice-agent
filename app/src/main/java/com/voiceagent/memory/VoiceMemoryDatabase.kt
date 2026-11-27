package com.voiceagent.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatMessageEntity::class,
        MemoryFactEntity::class,
        MemoryFactFtsEntity::class,
        ConversationSummaryEntity::class,
        ChatSessionEntity::class,
        ScheduledReminderEntity::class,
        AutonomousTaskEntity::class,
        AutonomousTaskLogEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class VoiceMemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: VoiceMemoryDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_sessions` (
                        `session_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `digest` TEXT NOT NULL,
                        `started_at` INTEGER NOT NULL,
                        `last_active_at` INTEGER NOT NULL,
                        `ended_at` INTEGER,
                        `turn_count` INTEGER NOT NULL,
                        `is_archived` INTEGER NOT NULL,
                        PRIMARY KEY(`session_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_sessions_last_active_at` ON `chat_sessions` (`last_active_at`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scheduled_reminders` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `trigger_at_ms` INTEGER NOT NULL,
                        `spoken_message` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scheduled_reminders_status_trigger_at_ms` ON `scheduled_reminders` (`status`, `trigger_at_ms`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `autonomous_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `mode` TEXT NOT NULL,
                        `goal_prompt` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `watch_package` TEXT NOT NULL,
                        `watch_sender` TEXT NOT NULL,
                        `next_trigger_at_ms` INTEGER,
                        `interval_minutes` REAL,
                        `cooldown_seconds` INTEGER NOT NULL,
                        `max_runs` INTEGER NOT NULL,
                        `run_count` INTEGER NOT NULL,
                        `needs_screen_ui` INTEGER NOT NULL,
                        `speak_on_milestone` INTEGER NOT NULL,
                        `speak_every_tick` INTEGER NOT NULL,
                        `scratchpad` TEXT NOT NULL,
                        `last_result_summary` TEXT NOT NULL,
                        `last_run_at_ms` INTEGER,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_autonomous_tasks_status_next_trigger_at_ms` ON `autonomous_tasks` (`status`, `next_trigger_at_ms`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `autonomous_task_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `task_id` INTEGER NOT NULL,
                        `trigger_source` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `tools_used` TEXT NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_autonomous_task_logs_task_id_created_at` ON `autonomous_task_logs` (`task_id`, `created_at`)"
                )
            }
        }

        fun getInstance(context: Context): VoiceMemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VoiceMemoryDatabase::class.java,
                    "voice_memory.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
