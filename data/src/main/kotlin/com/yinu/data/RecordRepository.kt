package com.yinu.data

import android.content.Context
import android.net.Uri
import com.yinu.database.AttachmentEntity
import com.yinu.database.MessageEntity
import com.yinu.database.MessageWithAttachments
import com.yinu.database.YinuDatabase
import com.yinu.database.RecordLinkEntity
import com.yinu.database.ReminderEntity
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TimelineWindow(val records: List<Record>, val hasOlder: Boolean)

data class StorageUsage(val records: Int, val files: Int, val bytes: Long)

data class ManagedAttachment(val recordId: String, val attachment: Attachment)

data class TrashRecord(val record: Record, val deletedAt: Long)

data class TrashDeletion(val ids: List<String>, val deletedAt: Long)

const val TrashRetentionDays = 30
private const val TrashRetentionMillis = TrashRetentionDays * 24 * 60 * 60 * 1_000L

class RecordRepository(context: Context, database: YinuDatabase) {
    private val dao = database.records()
    private val attachmentAuthority = "${context.packageName}.attachments"
    private val store = AttachmentStore(File(context.filesDir, "attachments"), context.contentResolver)
    private val fileMutations = Mutex()
    private val backup = LocalBackup(context, dao, store)

    val timeline: Flow<List<Record>> = dao.observeTimeline().distinctUntilChanged()
        .map { rows -> rows.map { it.toRecord() } }.flowOn(Dispatchers.Default)

    fun recentTimeline(limit: Int): Flow<TimelineWindow> = dao.observeRecentTimeline(limit + 1).distinctUntilChanged().map { rows ->
        TimelineWindow(rows.take(limit).asReversed().map { it.toRecord() }, hasOlder = rows.size > limit)
    }.flowOn(Dispatchers.Default)

    val pinned: Flow<Record?> = dao.observePinned().distinctUntilChanged().map { it?.toRecord() }.flowOn(Dispatchers.Default)

    suspend fun timelineRank(id: String): Int? = dao.timelineRank(id)
    suspend fun imageGallery(): List<Attachment> = withContext(Dispatchers.IO) {
        dao.imageGallery().map { it.toAttachment() }
    }
    val pendingReminders = dao.observePendingReminders().distinctUntilChanged()
    val reminderRecords = dao.observeReminderRecords().distinctUntilChanged()
        .map { it.map { row -> row.toRecord() } }.flowOn(Dispatchers.Default)
    suspend fun record(id: String): Record? = dao.activeRecord(id)?.toRecord()
    fun observeRecord(id: String): Flow<Record?> = dao.observeRecord(id).distinctUntilChanged()
        .map { it?.toRecord() }.flowOn(Dispatchers.Default)
    suspend fun unlink(id: String, target: String) = dao.unlink(id, target, System.currentTimeMillis())
    suspend fun linkState(id: String): String = when (dao.deletedState(id)) {
        false -> "可查看"
        true -> "记录已删除"
        null -> "记录尚未恢复或不存在"
    }
    fun backlinks(id: String): Flow<List<Record>> = dao.observeBacklinks(id).distinctUntilChanged()
        .map { it.map { row -> row.toRecord() } }.flowOn(Dispatchers.Default)
    suspend fun setReminder(id: String, at: Long) {
        require(at > System.currentTimeMillis()) { "Reminder must be in the future" }
        require(dao.putActiveReminder(ReminderEntity(id, at)))
    }
    suspend fun cancelReminder(id: String) = dao.cancelReminder(id)
    suspend fun claimReminder(id: String, at: Long, now: Long): Boolean = dao.claimReminder(id, at, now) == 1
    suspend fun releaseReminderClaim(id: String, at: Long, claimedAt: Long) = dao.releaseReminderClaim(id, at, claimedAt)

    val storageUsage: Flow<StorageUsage> = combine(dao.observeRecordCount(), dao.observeLocalFiles()) { count, paths ->
        var files = 0
        var bytes = 0L
        for (path in paths) {
            currentCoroutineContext().ensureActive()
            val file = store.file(path)
            if (!file.isFile) continue
            files++
            bytes += file.length()
        }
        StorageUsage(count, files, bytes)
    }.flowOn(Dispatchers.IO)

    val managedAttachments: Flow<List<ManagedAttachment>> = dao.observeManagedAttachments().map { rows ->
        rows.mapNotNull { row ->
            currentCoroutineContext().ensureActive()
            val attachment = row.toAttachment()
            if (attachment.localFile?.isFile == true) ManagedAttachment(row.messageId, attachment) else null
        }
    }.flowOn(Dispatchers.IO)

    val trash: Flow<List<TrashRecord>> = dao.observeTrash()
        .map { rows ->
            val cutoff = System.currentTimeMillis() - TrashRetentionMillis
            rows.mapNotNull { row ->
                val deletedAt = row.message.deletedAt ?: return@mapNotNull null
                if (deletedAt > cutoff) TrashRecord(row.toRecord(), deletedAt) else null
            }
        }.onStart { purgeExpiredTrash() }.flowOn(Dispatchers.IO)

    suspend fun exportLocalBackup(destination: Uri, onProgress: (BackupProgress) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        fileMutations.withLock { backup.export(destination, dao.observeTimeline().first(), onProgress) }
    }

    suspend fun inspectLocalBackup(source: Uri): BackupPreview = withContext(Dispatchers.IO) {
        fileMutations.withLock { backup.inspect(source) }
    }

    suspend fun restoreLocalBackup(source: Uri, preview: BackupPreview? = null, onProgress: (BackupProgress) -> Unit = {}): RestoreResult = withContext(Dispatchers.IO) {
        fileMutations.withLock { backup.restore(source, preview, onProgress) }
    }

    suspend fun saveAttachment(id: String, destination: Uri): Boolean = withContext(Dispatchers.IO) {
        require(destination.scheme == "content" && destination.authority != attachmentAuthority)
        fileMutations.withLock {
            val path = dao.activeAttachments(listOf(id)).singleOrNull()?.localPath ?: return@withLock false
            val source = store.file(path).takeIf { it.isFile } ?: return@withLock false
            store.save(source, destination)
            true
        }
    }

    fun search(
        query: String,
        favoritesOnly: Boolean,
        category: SearchCategory? = null,
        date: LocalDate? = null,
        favoriteGroup: String? = null,
    ): Flow<List<Record>> {
        val keyword = query.trim()
        val zone = ZoneId.systemDefault()
        val dayStart = date?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val dayEnd = date?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        return dao.observeSearch(keyword, favoritesOnly, dayStart, dayEnd, favoriteGroup).distinctUntilChanged().map { rows ->
            rows.mapNotNull { row ->
                if (category != null) {
                    val matchesContent = keyword.isEmpty() || row.message.content?.contains(keyword, ignoreCase = true) == true
                    val matchesAttachment = row.attachments.any { attachment ->
                        category.matches(attachment.fileName, attachment.mimeType) &&
                            (matchesContent || attachment.fileName.contains(keyword, ignoreCase = true))
                    }
                    if (!matchesAttachment) return@mapNotNull null
                }
                row.toRecord()
            }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun addText(input: String, quote: RecordQuote? = null, messageId: String = newId(), links: List<RecordQuote> = emptyList()) = withContext(Dispatchers.IO) {
        val text = input.trim()
        require(text.isNotEmpty())
        val row = newMessage(messageId, textType(text), text, quote)
        val linked = linkRows(messageId, quote, links)
        dao.add(row, emptyList(), linked)
    }

    suspend fun importFiles(
        uris: List<Uri>,
        quote: RecordQuote? = null,
        messageId: String = newId(),
        sharedText: String? = null,
        links: List<RecordQuote> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty())
        fileMutations.withLock {
            val attachments = mutableListOf<AttachmentEntity>()
            var committed = false
            try {
                uris.distinct().forEachIndexed { index, uri -> attachments += store.copy(uri, messageId, index) }
                val type = if (attachments.all { it.mimeType.startsWith("image/") }) "IMAGE" else "FILE"
                // Finish the commit once copying succeeds, so cancellation cannot delete referenced files.
                withContext(NonCancellable) {
                    dao.add(newMessage(messageId, type, sharedText, quote), attachments, linkRows(messageId, quote, links))
                    committed = true
                }
            } finally {
                if (!committed) attachments.mapNotNull { it.localPath }.forEach(store::remove)
            }
        }
    }

    val favoriteGroups = dao.favoriteGroups().distinctUntilChanged()

    suspend fun setFavoriteGroup(id: String, group: String?): Boolean =
        dao.setFavoriteGroup(id, group?.trim()?.take(32)?.ifBlank { null }, System.currentTimeMillis()) > 0

    suspend fun saveVoice(file: File, caption: String, messageId: String) = withContext(Dispatchers.IO) {
        fileMutations.withLock {
            require(file.isFile && file.length() > 0)
            val recording = AttachmentEntity("recording", messageId, "语音记录.m4a", "audio/mp4", file.length(), null, position = 0)
            val attachment = store.importRecording(file, recording)
            commitCopies(newMessage(messageId, "FILE", caption.trim().ifEmpty { null }, null), listOf(attachment))
        }
    }

    suspend fun mergeRecords(ids: List<String>, caption: String, messageId: String) = withContext(Dispatchers.IO) {
        fileMutations.withLock {
            require(ids.distinct().size >= 2)
            val rows = ids.distinct().map { checkNotNull(dao.activeRecord(it)) }.sortedBy { it.message.createdAt }
            val text = (listOf(caption.trim()) + rows.mapNotNull { it.message.content })
                .filter { it.isNotBlank() }.joinToString("\n\n").ifEmpty { null }
            val copies = mutableListOf<AttachmentEntity>()
            try {
                rows.flatMap { it.attachments.sortedBy(AttachmentEntity::position) }.forEachIndexed { index, file ->
                    copies += store.duplicate(file, messageId, index)
                }
            } catch (error: Throwable) {
                copies.mapNotNull { it.localPath }.forEach(store::remove)
                throw error
            }
            val type = when {
                copies.isEmpty() -> textType(checkNotNull(text))
                copies.all { it.mimeType.startsWith("image/") } -> "IMAGE"
                else -> "FILE"
            }
            commitCopies(newMessage(messageId, type, text, null), copies)
        }
    }

    private suspend fun commitCopies(message: MessageEntity, copies: List<AttachmentEntity>) {
        var committed = false
        try {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { dao.add(message, copies); committed = true }
        } finally {
            if (!committed) copies.mapNotNull { it.localPath }.forEach(store::remove)
        }
    }

    suspend fun toggleFavorite(id: String) = dao.toggleFavorite(id, System.currentTimeMillis())

    suspend fun editContent(id: String, input: String): Boolean {
        val text = input.trim()
        return dao.editContent(id, text.ifEmpty { null }, textType(text), System.currentTimeMillis()) > 0
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        if (pinned) dao.pinMessage(id) else dao.unpinMessage(id)
    }

    suspend fun deleteRecord(id: String) = deleteRecords(setOf(id))

    suspend fun deleteRecords(ids: Set<String>): TrashDeletion = withContext(Dispatchers.IO) {
        fileMutations.withLock {
            val now = System.currentTimeMillis()
            TrashDeletion(dao.moveToTrash(ids.toList(), now), now)
        }
    }

    suspend fun restoreTrash(deletion: TrashDeletion): Int = withContext(Dispatchers.IO) {
        fileMutations.withLock {
            val now = System.currentTimeMillis()
            if (deletion.deletedAt <= now - TrashRetentionMillis) 0
            else dao.restoreTrash(deletion.ids, deletion.deletedAt, now)
        }
    }

    suspend fun discardTrash(id: String, deletedAt: Long): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        fileMutations.withLock {
            val discarded = dao.discardTrash(id, deletedAt)
            discarded.paths.forEach(store::remove)
            discarded.discarded
        }
    }

    private suspend fun purgeExpiredTrash() = withContext(Dispatchers.IO + NonCancellable) {
        fileMutations.withLock { purgeExpiredTrashLocked() }
    }

    suspend fun removeLocalCopy(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        fileMutations.withLock { dao.removeLocalCopy(id, System.currentTimeMillis())?.let(store::remove) }
    }

    suspend fun cleanOrphans() = withContext(Dispatchers.IO) {
        fileMutations.withLock {
            purgeExpiredTrashLocked()
            store.removeOrphans(dao.retainedPaths().toSet())
        }
    }

    private suspend fun purgeExpiredTrashLocked() {
        dao.purgeExpiredTrash(System.currentTimeMillis() - TrashRetentionMillis).forEach(store::remove)
    }

    private fun MessageWithAttachments.toRecord() = Record(
        id = message.id,
        content = message.content,
        createdAt = message.createdAt,
        isFavorite = message.isFavorite,
        favoriteGroup = message.favoriteGroup,
        attachments = attachments.sortedBy { it.position }.map { it.toAttachment() },
        quote = message.quotedMessageId?.let { id -> message.quotedSummary?.let { RecordQuote(id, it) } },
        isPinned = message.isPinned,
        links = links.sortedBy { it.position }.map { RecordQuote(it.targetId, it.summary) },
        reminderAt = reminders.firstOrNull()?.atMillis,
        reminderDelivered = reminders.firstOrNull()?.deliveredAt != null,
    )

    private fun AttachmentEntity.toAttachment() =
        Attachment(id, fileName, mimeType, fileSize, localPath?.let(store::file), backupStatus == "BACKED_UP")

    private fun newMessage(id: String, type: String, content: String?, quote: RecordQuote?) = MessageEntity(
        id = id,
        type = type,
        content = content,
        createdAt = System.currentTimeMillis(),
        quotedMessageId = quote?.messageId,
        quotedSummary = quote?.summary,
    )

    private fun newId() = UUID.randomUUID().toString()

    private fun linkRows(id: String, quote: RecordQuote?, links: List<RecordQuote>): List<RecordLinkEntity> {
        val unique = (listOfNotNull(quote) + links).distinctBy { it.messageId }.filter { it.messageId != id }
        require(unique.size <= 200)
        return unique.mapIndexed { index, link ->
            RecordLinkEntity(id, link.messageId, link.summary.take(240), index)
        }
    }

    private fun textType(text: String) = if (webLinks(text).singleOrNull()?.second == text) "LINK" else "TEXT"
}
