package com.yinu.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "messages",
    indices = [Index(value = ["is_deleted", "created_at"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val type: String,
    val content: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "user_id") val userId: String? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "favorite_group") val favoriteGroup: String? = null,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean = false,
    @ColumnInfo(name = "sync_status") val syncStatus: String = "PENDING",
    @ColumnInfo(name = "quoted_message_id") val quotedMessageId: String? = null,
    @ColumnInfo(name = "quoted_summary") val quotedSummary: String? = null,
    @ColumnInfo(name = "is_pinned", defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class,
        parentColumns = ["id"],
        childColumns = ["message_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("message_id")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "local_path") val localPath: String?,
    @ColumnInfo(name = "remote_path") val remotePath: String? = null,
    val checksum: String? = null,
    @ColumnInfo(name = "backup_status") val backupStatus: String = "LOCAL_ONLY",
    val position: Int,
)

data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "message_id")
    val attachments: List<AttachmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "source_id")
    val links: List<RecordLinkEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "message_id")
    val reminders: List<ReminderEntity> = emptyList(),
)

@Entity(
    tableName = "record_links",
    primaryKeys = ["source_id", "target_id"],
    indices = [Index("target_id")],
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"],
        childColumns = ["source_id"], onDelete = ForeignKey.CASCADE)],
)
data class RecordLinkEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    val summary: String,
    val position: Int,
)

@Entity(tableName = "reminders", foreignKeys = [ForeignKey(entity = MessageEntity::class,
    parentColumns = ["id"], childColumns = ["message_id"], onDelete = ForeignKey.CASCADE)])
data class ReminderEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "at_millis") val atMillis: Long,
    @ColumnInfo(name = "delivered_at") val deliveredAt: Long? = null,
)
