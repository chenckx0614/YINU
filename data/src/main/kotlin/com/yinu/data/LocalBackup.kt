package com.yinu.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.yinu.database.AttachmentEntity
import com.yinu.database.AttachmentRepair
import com.yinu.database.MessageEntity
import com.yinu.database.MessageWithAttachments
import com.yinu.database.RecordDao
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RestoreResult(val restored: Int, val skipped: Int, val repairedAttachments: Int = 0)

data class BackupPreview(
    val records: Int,
    val attachments: Int,
    val storedAttachments: Int,
    val newRecords: Int,
    val repairableAttachments: Int,
    internal val fingerprint: String,
)

class BackupChangedException : IOException("Backup contents changed after inspection")

sealed interface BackupProgress {
    data object Preparing : BackupProgress
    data object Finishing : BackupProgress
    data class Transferring(
        val filesDone: Int,
        val filesTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) : BackupProgress
}

class BackupSpaceException : IOException("Insufficient space for backup")

private const val ManifestEntry = "manifest.json"
private const val CompleteEntry = "backup.complete"
private const val Format = "yinu-local-backup"
private const val FormatVersion = 2
private const val ManifestLimit = 16 * 1024 * 1024

internal class LocalBackup(
    private val context: Context,
    private val dao: RecordDao,
    private val store: AttachmentStore,
) {
    suspend fun export(destination: Uri, records: List<MessageWithAttachments>, onProgress: (BackupProgress) -> Unit): Int {
        val snapshot = records.map { record ->
            record.copy(attachments = record.attachments.sortedBy { it.position }.map { attachment ->
                attachment.copy(localPath = attachment.localPath?.takeIf { store.file(it).isFile })
            })
        }
        val manifest = encode(snapshot).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= ManifestLimit) { "Backup manifest too large" }
        val sizes = snapshot.asSequence().flatMap { it.attachments }
            .filter { it.localPath != null }.map { it.fileSize }
        val meter = BackupMeter(sizes, onProgress)
        currentCoroutineContext().ensureActive()
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: throw IOException("Cannot open backup destination")
        val buffer = ByteArray(64 * 1024)
        try {
            ZipOutputStream(output.buffered()).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry(ManifestEntry))
                zip.write(manifest)
                zip.closeEntry()
                snapshot.forEachIndexed { row, record ->
                    record.attachments.forEachIndexed { index, attachment ->
                        currentCoroutineContext().ensureActive()
                        attachment.localPath?.let { path ->
                            zip.putNextEntry(ZipEntry(fileEntry(row, index)))
                            store.file(path).inputStream().use { copyExactly(it, zip, attachment.fileSize, buffer, meter) }
                            zip.closeEntry()
                            meter.finishFile()
                        }
                    }
                }
                onProgress(BackupProgress.Finishing)
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(ZipEntry(CompleteEntry))
                zip.closeEntry()
            }
        } catch (error: Exception) {
            // The destination was opened for this export. Clear the partial archive if the provider permits it.
            withContext(NonCancellable) {
                runCatching { context.contentResolver.openOutputStream(destination, "wt")?.close() }
            }
            throw error
        }
        return snapshot.size
    }

    suspend fun inspect(source: Uri): BackupPreview {
        val input = context.contentResolver.openInputStream(source) ?: throw IOException("Cannot open backup")
        val buffer = ByteArray(64 * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        return ZipInputStream(input.buffered()).use { zip ->
            val manifest = readRecords(zip, buffer, digest)
            val plan = planRestore(manifest)
            val meter = BackupMeter(manifest.storedSizes()) {}
            var attachments = 0
            var stored = 0
            var repairable = 0
            manifest.forEachIndexed { row, record ->
                record.files.forEachIndexed { index, file ->
                    currentCoroutineContext().ensureActive()
                    attachments++
                    if (file.stored) {
                        stored++
                        if (plan.missingCopy(file.attachment) != null) repairable++
                        require(zip.nextEntry?.name == fileEntry(row, index)) { "Missing attachment entry" }
                        copyExactly(zip, null, file.attachment.fileSize, buffer, meter, digest)
                    }
                }
            }
            requireComplete(zip)
            BackupPreview(manifest.size, attachments, stored, manifest.size - plan.existing.size, repairable, digest.fingerprint())
        }
    }

    suspend fun restore(source: Uri, preview: BackupPreview?, onProgress: (BackupProgress) -> Unit): RestoreResult {
        val staged = mutableSetOf<String>()
        val repairs = mutableListOf<AttachmentRepair>()
        var committed = false
        try {
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("Cannot open backup")
            val buffer = ByteArray(64 * 1024)
            val (rows, total) = ZipInputStream(input.buffered()).use { zip ->
                val digest = preview?.let { MessageDigest.getInstance("SHA-256") }
                val manifest = readRecords(zip, buffer, digest)
                val meter = BackupMeter(manifest.storedSizes(), onProgress)
                val plan = planRestore(manifest)
                val additions = mutableListOf<MessageWithAttachments>()
                manifest.forEachIndexed { row, record ->
                    currentCoroutineContext().ensureActive()
                    val newRecord = record.message.id !in plan.existing
                    val files = record.files.mapIndexed { index, file ->
                        val attachment = file.attachment
                        if (!file.stored) return@mapIndexed attachment
                        require(zip.nextEntry?.name == fileEntry(row, index)) { "Missing attachment entry" }
                        val missingCopy = plan.missingCopy(attachment)
                        if (!newRecord && missingCopy == null) {
                            copyExactly(zip, null, attachment.fileSize, buffer, meter, digest)
                            meter.finishFile()
                            return@mapIndexed attachment
                        }
                        val path = stagePath(attachment)
                        staged += path
                        val destination = store.file(path)
                        if (!destination.parentFile!!.mkdirs()) throw IOException("Cannot stage attachment")
                        destination.outputStream().use {
                            copyExactly(zip, it, attachment.fileSize, buffer, meter, digest)
                            it.fd.sync()
                        }
                        meter.finishFile()
                        if (missingCopy != null) repairs += AttachmentRepair(missingCopy, path)
                        attachment.copy(localPath = path)
                    }
                    if (newRecord) additions += MessageWithAttachments(record.message, files, record.links, record.reminders)
                }
                requireComplete(zip)
                if (preview != null && digest?.fingerprint() != preview.fingerprint) throw BackupChangedException()
                additions to manifest.size
            }
            onProgress(BackupProgress.Finishing)
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                val merged = dao.restoreBackup(rows, repairs)
                committed = true
                staged.forEach { if (it !in merged.retainedPaths) store.remove(it) }
                RestoreResult(merged.insertedRecords, total - merged.insertedRecords, merged.repairedAttachments)
            }
        } finally {
            if (!committed) staged.forEach(store::remove)
        }
    }

    private suspend fun planRestore(manifest: List<BackupRecord>): RestorePlan {
        val existing = manifest.map { it.message.id }.chunked(400)
            .flatMap { dao.existingMessageIds(it) }.toSet()
        val candidates = manifest.asSequence().filter { it.message.id in existing }
            .flatMap { it.files }.filter { it.stored }.map { it.attachment.id }.toList()
        val missing = candidates.chunked(400).flatMap { dao.activeAttachments(it) }
            .filter { it.localPath?.let { path -> store.file(path).isFile } != true }
            .associateBy { it.id }
        return RestorePlan(existing, missing)
    }

    private fun stagePath(attachment: AttachmentEntity): String {
        if (attachment.fileSize > context.filesDir.usableSpace) throw BackupSpaceException()
        val extension = attachment.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }
        return "${UUID.randomUUID()}/content" + extension?.let { ".$it" }.orEmpty()
    }
}

private data class RestorePlan(val existing: Set<String>, val missing: Map<String, AttachmentEntity>) {
    fun missingCopy(attachment: AttachmentEntity) = missing[attachment.id]?.takeIf {
        it.messageId == attachment.messageId && it.fileSize == attachment.fileSize
    }
}

private fun List<BackupRecord>.storedSizes() = asSequence().flatMap { it.files }
    .filter { it.stored }.map { it.attachment.fileSize }

private suspend fun readRecords(zip: ZipInputStream, buffer: ByteArray, digest: MessageDigest?): List<BackupRecord> {
    require(zip.nextEntry?.name == ManifestEntry) { "Not a YINU backup" }
    return decode(readManifest(zip, buffer, digest))
}

private fun requireComplete(zip: ZipInputStream) {
    require(zip.nextEntry?.name == CompleteEntry && zip.read() == -1 && zip.nextEntry == null) {
        "Incomplete or invalid backup"
    }
}

private fun MessageDigest.fingerprint(): String = android.util.Base64.encodeToString(digest(), android.util.Base64.NO_WRAP)

private fun fileEntry(row: Int, index: Int) = "attachments/$row/$index"

private class BackupMeter(sizes: Sequence<Long>, private val onProgress: (BackupProgress) -> Unit) {
    private var totalBytes = 0L
    private var totalFiles = 0
    private var doneBytes = 0L
    private var doneFiles = 0
    private var lastUpdate = 0L

    init {
        for (size in sizes) {
            totalBytes = Math.addExact(totalBytes, size)
            totalFiles++
        }
        report()
    }

    fun copied(count: Int) {
        doneBytes += count
        if (SystemClock.elapsedRealtime() - lastUpdate >= 120) report()
    }

    fun finishFile() {
        doneFiles++
        report()
    }

    private fun report() {
        lastUpdate = SystemClock.elapsedRealtime()
        onProgress(BackupProgress.Transferring(doneFiles, totalFiles, doneBytes, totalBytes))
    }
}

private suspend fun copyExactly(
    input: InputStream,
    output: OutputStream?,
    expected: Long,
    buffer: ByteArray,
    meter: BackupMeter,
    digest: MessageDigest? = null,
) {
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (count.toLong() > expected - total) throw ZipException("Attachment size mismatch")
        output?.write(buffer, 0, count)
        digest?.update(buffer, 0, count)
        total += count
        meter.copied(count)
    }
    if (total != expected) throw ZipException("Attachment is incomplete")
}

private suspend fun readManifest(zip: ZipInputStream, buffer: ByteArray, digest: MessageDigest?): JSONObject {
    val bytes = ByteArrayOutputStream()
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = zip.read(buffer)
        if (count < 0) break
        require(bytes.size() + count <= ManifestLimit) { "Backup manifest too large" }
        bytes.write(buffer, 0, count)
        digest?.update(buffer, 0, count)
    }
    return JSONObject(bytes.toString("UTF-8"))
}

private fun encode(records: List<MessageWithAttachments>): JSONObject = JSONObject().apply {
    put("format", Format)
    put("version", FormatVersion)
    put("records", JSONArray().apply {
        records.forEach { row ->
            val record = row.message
            val attachments = row.attachments
            put(JSONObject().apply {
                put("id", record.id)
                put("type", record.type)
                put("text", record.content ?: JSONObject.NULL)
                put("created", record.createdAt)
                put("updated", record.updatedAt)
                put("favorite", record.isFavorite)
                put("favoriteGroup", record.favoriteGroup ?: JSONObject.NULL)
                put("pinned", record.isPinned)
                put("quotedId", record.quotedMessageId ?: JSONObject.NULL)
                put("quotedText", record.quotedSummary ?: JSONObject.NULL)
                put("links", JSONArray().apply {
                    row.links.sortedBy { it.position }.forEach { link ->
                        put(JSONObject().put("id", link.targetId).put("summary", link.summary))
                    }
                })
                row.reminders.firstOrNull()?.let { reminder ->
                    put("reminderAt", reminder.atMillis)
                    put("reminderDelivered", reminder.deliveredAt ?: JSONObject.NULL)
                }
                put("files", JSONArray().apply {
                    attachments.forEach { attachment ->
                        put(JSONObject().apply {
                            put("id", attachment.id)
                            put("name", attachment.fileName)
                            put("mime", attachment.mimeType)
                            put("size", attachment.fileSize)
                            put("position", attachment.position)
                            put("stored", attachment.localPath != null)
                        })
                    }
                })
            })
        }
    })
}

private data class BackupFile(val attachment: AttachmentEntity, val stored: Boolean)
private data class BackupRecord(val message: MessageEntity, val files: List<BackupFile>,
    val links: List<com.yinu.database.RecordLinkEntity>, val reminders: List<com.yinu.database.ReminderEntity>)

private fun decode(manifest: JSONObject): List<BackupRecord> {
    require(manifest.getString("format") == Format && manifest.getInt("version") in 1..FormatVersion) {
        "Unsupported backup format"
    }
    val ids = mutableSetOf<String>()
    val attachmentIds = mutableSetOf<String>()
    val records = manifest.getJSONArray("records")
    return List(records.length()) { index ->
        val row = records.getJSONObject(index)
        val id = row.getString("id")
        require(id.isNotBlank() && id.length <= 128 && ids.add(id)) { "Invalid record ID" }
        val message = MessageEntity(
            id = id,
            type = row.getString("type"),
            content = row.nullableString("text"),
            createdAt = row.getLong("created"),
            updatedAt = row.getLong("updated"),
            isFavorite = row.getBoolean("favorite"),
            favoriteGroup = row.nullableString("favoriteGroup")?.trim()?.take(32)?.ifBlank { null },
            quotedMessageId = row.nullableString("quotedId"),
            quotedSummary = row.nullableString("quotedText"),
            isPinned = row.getBoolean("pinned"),
        )
        val files = row.getJSONArray("files")
        val linked = row.optJSONArray("links")
        require(linked == null || linked.length() <= 200) { "Too many linked records" }
        val links = if (linked == null) listOfNotNull(message.quotedMessageId?.let { target ->
            message.quotedSummary?.let { com.yinu.database.RecordLinkEntity(id, target, it, 0) }
        }) else List(linked.length()) { position ->
            val entry = linked.getJSONObject(position)
            val target = entry.getString("id")
            require(target.isNotBlank() && target.length <= 128 && target != id)
            com.yinu.database.RecordLinkEntity(id, target, entry.getString("summary").take(240), position)
        }.distinctBy { it.targetId }
        val reminders = if (row.has("reminderAt")) {
            val at = row.getLong("reminderAt")
            require(at > 0)
            val delivered = if (!row.isNull("reminderDelivered")) row.getLong("reminderDelivered")
                else if (at <= System.currentTimeMillis()) System.currentTimeMillis() else null
            listOf(com.yinu.database.ReminderEntity(id, at, delivered))
        } else emptyList()
        BackupRecord(message, List(files.length()) { fileIndex ->
            val entry = files.getJSONObject(fileIndex)
            val fileId = entry.getString("id")
            val size = entry.getLong("size")
            require(fileId.isNotBlank() && fileId.length <= 128 && attachmentIds.add(fileId) && size >= 0) {
                "Invalid attachment"
            }
            BackupFile(
                attachment = AttachmentEntity(
                    id = fileId,
                    messageId = id,
                    fileName = entry.getString("name"),
                    mimeType = entry.getString("mime"),
                    fileSize = size,
                    localPath = null,
                    position = entry.getInt("position"),
                ),
                stored = entry.getBoolean("stored"),
            )
        }, links, reminders)
    }
}

private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else getString(key)
