package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 一个对话在**某个记忆库**里的整理进度：[consolidatedAt] 及之前的消息已经整理进这个记忆库。
 *
 * 按记忆库分开记：群聊里每个成员都往自己的记忆库里整理，进度必须各算各的，
 * 否则 A 整理完就把 B、C 的进度一起推进了。
 *
 * 单独成表而不是放在 ConversationEntity 上：对话每次保存都会整行重写，会把进度冲掉。
 */
@Entity(
    tableName = "memory_consolidation",
    primaryKeys = ["conversation_id", "memory_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversation_id")],
)
data class MemoryConsolidationEntity(
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("memory_id")
    val memoryId: String,
    @ColumnInfo("consolidated_at")
    val consolidatedAt: Long,
)
