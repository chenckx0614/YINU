package com.yinu.database

import androidx.room.Dao
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.OnConflictStrategy
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [MessageEntity::class, AttachmentEntity::class, RecordLinkEntity::class, ReminderEntity::class],
    version = 6,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = QuoteLinksMigration::class), AutoMigration(from = 5, to = 6)],
)
abstract class YinuDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
}

class QuoteLinksMigration : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("""INSERT OR IGNORE INTO record_links(source_id, target_id, summary, position)
            SELECT id, quoted_message_id, quoted_summary, 0 FROM messages
            WHERE quoted_message_id IS NOT NULL AND quoted_summary IS NOT NULL AND is_deleted = 0""")
    }
}

data class AttachmentRepair(val original: AttachmentEntity, val restoredPath: String)
data class DiscardedTrash(val discarded: Boolean, val paths: List<String>)

data class BackupMerge(
    val insertedRecords: Int,
    val repairedAttachments: Int,
    val retainedPaths: Set<String>,
)

@Dao
abstract class RecordDao {
    @Query("""SELECT attachments.* FROM attachments JOIN messages ON messages.id = attachments.message_id
        WHERE messages.is_deleted = 0 AND attachments.local_path IS NOT NULL AND attachments.mime_type LIKE 'image/%'
        ORDER BY messages.created_at ASC, messages.rowid ASC, attachments.position ASC""")
    abstract suspend fun imageGallery(): List<AttachmentEntity>

    @Transaction
    @Query(
        """
        SELECT * FROM messages WHERE is_deleted = 0
        ORDER BY created_at ASC, rowid ASC
        """,
    )
    abstract fun observeTimeline(): Flow<List<MessageWithAttachments>>

    @Transaction
    @Query("SELECT * FROM messages WHERE is_deleted = 0 ORDER BY created_at DESC, rowid DESC LIMIT :limit")
    abstract fun observeRecentTimeline(limit: Int): Flow<List<MessageWithAttachments>>

    @Transaction
    @Query("SELECT * FROM messages WHERE is_deleted = 0 AND is_pinned = 1 LIMIT 1")
    abstract fun observePinned(): Flow<MessageWithAttachments?>

    @Query(
        """
        SELECT 1 + (
            SELECT COUNT(*) FROM messages AS newer
            WHERE newer.is_deleted = 0 AND (
                newer.created_at > target.created_at OR
                (newer.created_at = target.created_at AND newer.rowid > target.rowid)
            )
        ) FROM messages AS target WHERE target.id = :id AND target.is_deleted = 0
        """,
    )
    abstract suspend fun timelineRank(id: String): Int?

    @Query("SELECT COUNT(*) FROM messages WHERE is_deleted = 0")
    abstract fun observeRecordCount(): Flow<Int>

    @Query(
        """
        SELECT attachments.local_path FROM attachments JOIN messages ON messages.id = attachments.message_id
        WHERE messages.is_deleted = 0 AND attachments.local_path IS NOT NULL
        """,
    )
    abstract fun observeLocalFiles(): Flow<List<String>>

    @Query(
        """
        SELECT attachments.* FROM attachments JOIN messages ON messages.id = attachments.message_id
        WHERE messages.is_deleted = 0 AND attachments.local_path IS NOT NULL
        ORDER BY attachments.file_size DESC, attachments.file_name COLLATE NOCASE, attachments.id
        """,
    )
    abstract fun observeManagedAttachments(): Flow<List<AttachmentEntity>>

    @Transaction
    @Query("SELECT * FROM messages WHERE is_deleted = 1 AND deleted_at IS NOT NULL ORDER BY deleted_at DESC, rowid DESC")
    abstract fun observeTrash(): Flow<List<MessageWithAttachments>>

    @Transaction
    @Query(
        """
        SELECT * FROM messages WHERE is_deleted = 0
          AND (:favoritesOnly = 0 OR is_favorite = 1)
          AND (:favoriteGroup IS NULL OR favorite_group = :favoriteGroup)
          AND (:dayStart IS NULL OR (created_at >= :dayStart AND created_at < :dayEnd))
          AND (:query = '' OR instr(lower(coalesce(content, '')), lower(:query)) > 0
            OR EXISTS (
                SELECT 1 FROM attachments
                WHERE message_id = messages.id AND instr(lower(file_name), lower(:query)) > 0
            ))
        ORDER BY created_at DESC, rowid DESC
        """,
    )
    abstract fun observeSearch(
        query: String,
        favoritesOnly: Boolean,
        dayStart: Long?,
        dayEnd: Long?,
        favoriteGroup: String?,
    ): Flow<List<MessageWithAttachments>>

    @Insert
    protected abstract suspend fun insertMessage(message: MessageEntity)

    @Insert
    protected abstract suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Transaction
    open suspend fun add(message: MessageEntity, attachments: List<AttachmentEntity>, links: List<RecordLinkEntity> = emptyList()) {
        insertMessage(message)
        if (attachments.isNotEmpty()) insertAttachments(attachments)
        if (links.isNotEmpty()) insertLinks(links)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLinks(links: List<RecordLinkEntity>)

    @Transaction
    @Query("SELECT * FROM messages WHERE id = :id AND is_deleted = 0")
    abstract suspend fun activeRecord(id: String): MessageWithAttachments?

    @Transaction
    @Query("SELECT * FROM messages WHERE id = :id AND is_deleted = 0")
    abstract fun observeRecord(id: String): Flow<MessageWithAttachments?>

    @Query("DELETE FROM record_links WHERE source_id = :id AND target_id = :target")
    protected abstract suspend fun deleteLink(id: String, target: String)

    @Query("UPDATE messages SET quoted_message_id = CASE WHEN quoted_message_id = :target THEN NULL ELSE quoted_message_id END, quoted_summary = CASE WHEN quoted_message_id = :target THEN NULL ELSE quoted_summary END, updated_at = max(updated_at + 1, :now), sync_status = 'PENDING' WHERE id = :id AND is_deleted = 0")
    protected abstract suspend fun clearLegacyQuote(id: String, target: String, now: Long)

    @Transaction
    open suspend fun unlink(id: String, target: String, now: Long) {
        deleteLink(id, target)
        clearLegacyQuote(id, target, now)
    }

    @Query("SELECT is_deleted FROM messages WHERE id = :id")
    abstract suspend fun deletedState(id: String): Boolean?

    @Transaction
    @Query("SELECT * FROM messages WHERE is_deleted = 0 AND id IN (SELECT source_id FROM record_links WHERE target_id = :id) ORDER BY created_at DESC")
    abstract fun observeBacklinks(id: String): Flow<List<MessageWithAttachments>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putReminder(reminder: ReminderEntity)

    @Transaction
    open suspend fun putActiveReminder(reminder: ReminderEntity): Boolean {
        if (activeRecord(reminder.messageId) == null) return false
        putReminder(reminder)
        return true
    }

    @Query("DELETE FROM reminders WHERE message_id = :id")
    abstract suspend fun cancelReminder(id: String)

    @Query("SELECT reminders.* FROM reminders JOIN messages ON messages.id = reminders.message_id WHERE messages.is_deleted = 0 AND reminders.delivered_at IS NULL")
    abstract fun observePendingReminders(): Flow<List<ReminderEntity>>

    @Transaction
    @Query("SELECT * FROM messages WHERE is_deleted = 0 AND id IN (SELECT message_id FROM reminders) ORDER BY (SELECT at_millis FROM reminders WHERE message_id = messages.id) ASC")
    abstract fun observeReminderRecords(): Flow<List<MessageWithAttachments>>

    @Query("UPDATE reminders SET delivered_at = :now WHERE message_id = :id AND at_millis = :at AND at_millis <= :now AND delivered_at IS NULL AND EXISTS (SELECT 1 FROM messages WHERE id = :id AND is_deleted = 0)")
    abstract suspend fun claimReminder(id: String, at: Long, now: Long): Int

    @Query("UPDATE reminders SET delivered_at = NULL WHERE message_id = :id AND at_millis = :at AND delivered_at = :claimedAt")
    abstract suspend fun releaseReminderClaim(id: String, at: Long, claimedAt: Long)

    @Query("SELECT id FROM messages WHERE id IN (:ids)")
    abstract suspend fun existingMessageIds(ids: List<String>): List<String>

    @Query(
        """
        SELECT attachments.* FROM attachments JOIN messages ON messages.id = attachments.message_id
        WHERE attachments.id IN (:ids) AND messages.is_deleted = 0
        """,
    )
    abstract suspend fun activeAttachments(ids: List<String>): List<AttachmentEntity>

    @Query(
        """
        UPDATE attachments SET local_path = :newPath
        WHERE id = :id AND message_id = :messageId AND local_path IS :oldPath AND file_size = :fileSize
            AND EXISTS (SELECT 1 FROM messages WHERE id = :messageId AND is_deleted = 0)
        """,
    )
    protected abstract suspend fun repairAttachment(
        id: String,
        messageId: String,
        oldPath: String?,
        newPath: String,
        fileSize: Long,
    ): Int

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE is_pinned = 1 AND is_deleted = 0)")
    protected abstract suspend fun hasPinnedMessage(): Boolean

    @Transaction
    open suspend fun restoreBackup(rows: List<MessageWithAttachments>, repairs: List<AttachmentRepair>): BackupMerge {
        val existing = rows.map { it.message.id }.chunked(400).flatMap { existingMessageIds(it) }.toSet()
        val retainedPaths = mutableSetOf<String>()
        var inserted = 0
        var pinned = hasPinnedMessage()
        for (row in rows) {
            val message = row.message
            val attachments = row.attachments
            if (message.id in existing) continue
            val restorePin = message.isPinned && !pinned
            insertMessage(message.copy(isPinned = restorePin))
            if (attachments.isNotEmpty()) insertAttachments(attachments)
            if (row.links.isNotEmpty()) insertLinks(row.links)
            row.reminders.firstOrNull()?.let { putReminder(it) }
            pinned = pinned || restorePin
            inserted++
            attachments.mapNotNullTo(retainedPaths) { it.localPath }
        }
        var repaired = 0
        for ((original, path) in repairs) {
            if (repairAttachment(original.id, original.messageId, original.localPath, path, original.fileSize) == 1) {
                retainedPaths += path
                repaired++
            }
        }
        return BackupMerge(inserted, repaired, retainedPaths)
    }

    @Query("SELECT DISTINCT favorite_group FROM messages WHERE is_deleted = 0 AND is_favorite = 1 AND favorite_group IS NOT NULL ORDER BY favorite_group")
    abstract fun favoriteGroups(): Flow<List<String>>

    @Query("UPDATE messages SET is_favorite = 1, favorite_group = :group, updated_at = max(updated_at + 1, :now), sync_status = 'PENDING' WHERE id = :id AND is_deleted = 0")
    abstract suspend fun setFavoriteGroup(id: String, group: String?, now: Long): Int

    @Query("UPDATE messages SET is_favorite = NOT is_favorite, favorite_group = NULL, updated_at = max(updated_at + 1, :now), sync_status = 'PENDING' WHERE id = :id AND is_deleted = 0")
    abstract suspend fun toggleFavorite(id: String, now: Long)

    @Query(
        """
        UPDATE messages SET content = :content, updated_at = max(updated_at + 1, :now), sync_status = 'PENDING',
            type = CASE WHEN type IN ('TEXT', 'LINK') THEN :textType ELSE type END
        WHERE id = :id AND is_deleted = 0 AND (
            :content IS NOT NULL OR EXISTS (SELECT 1 FROM attachments WHERE message_id = :id)
        )
        """,
    )
    abstract suspend fun editContent(id: String, content: String?, textType: String, now: Long): Int

    @Query(
        """
        UPDATE messages SET is_pinned = CASE WHEN id = :id THEN 1 ELSE 0 END
        WHERE is_deleted = 0 AND (is_pinned = 1 OR id = :id)
          AND EXISTS (SELECT 1 FROM messages AS target WHERE target.id = :id AND target.is_deleted = 0)
        """,
    )
    abstract suspend fun pinMessage(id: String)

    @Query("UPDATE messages SET is_pinned = 0 WHERE id = :id AND is_pinned = 1 AND is_deleted = 0")
    abstract suspend fun unpinMessage(id: String)

    @Query("SELECT id FROM messages WHERE id IN (:ids) AND is_deleted = 0")
    protected abstract suspend fun activeMessageIds(ids: List<String>): List<String>

    @Query("UPDATE messages SET is_deleted = 1, deleted_at = :now, updated_at = max(updated_at + 1, :now), sync_status = 'PENDING' WHERE id IN (:ids) AND is_deleted = 0")
    protected abstract suspend fun markDeleted(ids: List<String>, now: Long)

    @Transaction
    open suspend fun moveToTrash(ids: List<String>, now: Long): List<String> {
        val moved = mutableListOf<String>()
        // Keep the database changes atomic while staying below SQLite's bound parameter limit.
        for (chunk in ids.chunked(400)) {
            moved += activeMessageIds(chunk)
            markDeleted(chunk, now)
        }
        return moved
    }

    @Query("SELECT id FROM messages WHERE id IN (:ids) AND is_deleted = 1 AND deleted_at = :deletedAt ORDER BY rowid DESC")
    protected abstract suspend fun trashIds(ids: List<String>, deletedAt: Long): List<String>

    @Query(
        """
        UPDATE messages SET is_deleted = 0, deleted_at = NULL, updated_at = max(updated_at + 1, :now), sync_status = 'PENDING',
            is_pinned = CASE WHEN EXISTS(SELECT 1 FROM messages WHERE is_pinned = 1 AND is_deleted = 0)
                THEN 0 ELSE is_pinned END
        WHERE id = :id AND is_deleted = 1 AND deleted_at = :deletedAt
        """,
    )
    protected abstract suspend fun restoreMessage(id: String, deletedAt: Long, now: Long): Int

    @Transaction
    open suspend fun restoreTrash(ids: List<String>, deletedAt: Long, now: Long): Int {
        var count = 0
        for (chunk in ids.chunked(400)) {
            for (id in trashIds(chunk, deletedAt)) count += restoreMessage(id, deletedAt, now)
        }
        return count
    }

    @Query("SELECT local_path FROM attachments WHERE message_id IN (:ids) AND local_path IS NOT NULL")
    protected abstract suspend fun attachmentPaths(ids: List<String>): List<String>

    @Query("DELETE FROM attachments WHERE message_id IN (:ids)")
    protected abstract suspend fun deleteAttachmentRows(ids: List<String>)

    @Query(
        """
        UPDATE messages SET deleted_at = NULL, content = NULL, is_pinned = 0,
            quoted_message_id = NULL, quoted_summary = NULL
        WHERE id IN (:ids) AND is_deleted = 1
        """,
    )
    protected abstract suspend fun clearTrashPayload(ids: List<String>)

    @Query("SELECT id FROM messages WHERE is_deleted = 1 AND deleted_at <= :cutoff")
    protected abstract suspend fun expiredTrashIds(cutoff: Long): List<String>

    @Transaction
    open suspend fun discardTrash(id: String, deletedAt: Long): DiscardedTrash {
        val ids = trashIds(listOf(id), deletedAt)
        return DiscardedTrash(ids.isNotEmpty(), purge(ids))
    }

    @Transaction
    open suspend fun purgeExpiredTrash(cutoff: Long): List<String> = purge(expiredTrashIds(cutoff))

    private suspend fun purge(ids: List<String>): List<String> {
        val paths = mutableListOf<String>()
        for (chunk in ids.chunked(400)) {
            paths += attachmentPaths(chunk)
            deleteAttachmentRows(chunk)
            // Keep the deleted ID so restoring an older ZIP cannot recreate this record.
            clearTrashPayload(chunk)
            for (id in chunk) {
                cancelReminder(id)
                deleteOutgoingLinks(id)
            }
        }
        return paths
    }

    @Query("DELETE FROM record_links WHERE source_id = :id")
    protected abstract suspend fun deleteOutgoingLinks(id: String)

    @Query(
        """
        SELECT attachments.local_path FROM attachments JOIN messages ON messages.id = attachments.message_id
        WHERE attachments.id = :id AND messages.is_deleted = 0
        """,
    )
    protected abstract suspend fun attachmentPath(id: String): String?

    @Query("UPDATE attachments SET local_path = NULL WHERE id = :id")
    protected abstract suspend fun clearAttachmentPath(id: String)

    @Query(
        """
        UPDATE messages SET updated_at = :now WHERE is_deleted = 0 AND
        id = (SELECT message_id FROM attachments WHERE id = :attachmentId)
        """,
    )
    protected abstract suspend fun touchAttachmentMessage(attachmentId: String, now: Long)

    @Transaction
    open suspend fun removeLocalCopy(id: String, now: Long): String? {
        val path = attachmentPath(id) ?: return null
        clearAttachmentPath(id)
        touchAttachmentMessage(id, now)
        return path
    }

    @Query("SELECT local_path FROM attachments WHERE local_path IS NOT NULL")
    abstract suspend fun retainedPaths(): List<String>

    @Transaction
    @Query("SELECT * FROM messages WHERE sync_status != 'SYNCED' ORDER BY updated_at, id LIMIT :limit")
    abstract suspend fun pendingSyncRecords(limit: Int): List<MessageWithAttachments>

    @Query("UPDATE messages SET sync_status = 'SYNCED' WHERE id = :id AND updated_at = :updatedAt")
    abstract suspend fun acknowledgeSync(id: String, updatedAt: Long)

    @Transaction
    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun syncRecord(id: String): MessageWithAttachments?

    @Upsert
    protected abstract suspend fun upsertSyncMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSyncAttachments(attachments: List<AttachmentEntity>)

    @Transaction
    open suspend fun applySyncedRecord(row: MessageWithAttachments, local: MessageWithAttachments?) {
        val message = row.message
        val old = local?.message
        if (message.isDeleted) {
            val tombstone = old?.copy(isDeleted = true, updatedAt = message.updatedAt, syncStatus = "SYNCED",
                deletedAt = if (old.isDeleted) old.deletedAt else System.currentTimeMillis()) ?: message
            upsertSyncMessage(tombstone)
            cancelReminder(message.id)
            return
        }
        val preservePin = old?.isPinned == true && (!old.isDeleted || !hasPinnedMessage())
        upsertSyncMessage(message.copy(isPinned = preservePin))
        insertSyncAttachments(row.attachments)
        deleteOutgoingLinks(message.id)
        insertLinks(row.links)
    }
}
