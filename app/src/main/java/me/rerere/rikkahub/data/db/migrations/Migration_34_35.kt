package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_34_35"

/**
 * memory_consolidation 的主键从 (conversation_id) 换成 (conversation_id, memory_id)。
 *
 * 群聊里每个成员都要往自己的记忆库整理，进度必须按记忆库分开记，
 * 否则一个成员整理完就把整条会话的进度推进了，其他人再也轮不到。
 * 改主键要重建表，Room 的自动迁移做不到，这里手写。
 *
 * 旧数据只有一份进度、没有记忆库信息：按会话所属助手补上（没开全局记忆时记忆库就是助手 id）。
 * 正好开了全局记忆的旧进度会对不上，代价是这批会话多整理一次，不会丢内容。
 */
val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 34 to 35 (memory_consolidation memory scope)")
        DatabaseMigrationTracker.onMigrationStart(34, 35)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_consolidation_new` (" +
                    "`conversation_id` TEXT NOT NULL, " +
                    "`memory_id` TEXT NOT NULL, " +
                    "`consolidated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`conversation_id`, `memory_id`), " +
                    "FOREIGN KEY(`conversation_id`) REFERENCES `conversationentity`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO `memory_consolidation_new` " +
                    "(`conversation_id`, `memory_id`, `consolidated_at`) " +
                    "SELECT m.`conversation_id`, " +
                    "COALESCE((SELECT c.`assistant_id` FROM `conversationentity` AS c " +
                    "WHERE c.`id` = m.`conversation_id`), m.`conversation_id`), " +
                    "m.`consolidated_at` FROM `memory_consolidation` AS m"
            )
            db.execSQL("DROP TABLE IF EXISTS `memory_consolidation`")
            db.execSQL("ALTER TABLE `memory_consolidation_new` RENAME TO `memory_consolidation`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_memory_consolidation_conversation_id` " +
                    "ON `memory_consolidation` (`conversation_id`)"
            )
            Log.i(TAG, "migrate: migrate from 34 to 35 success")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
