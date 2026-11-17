package com.yinu.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.yinu.database.AttachmentEntity
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class AttachmentStore(private val root: File, private val resolver: ContentResolver) {
    suspend fun copy(uri: Uri, messageId: String, position: Int): AttachmentEntity {
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
            ?.trim()?.takeIf { it.isNotEmpty() } ?: "未命名文件"
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }
        val reportedType = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val mimeType = reportedType?.takeUnless { it.isBlank() || it == "application/octet-stream" }
            ?: extension?.let(MimeTypeMap.getSingleton()::getMimeTypeFromExtension)
            ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val relativePath = "$id/content" + extension?.let { ".$it" }.orEmpty()
        val destination = file(relativePath)
        if (!destination.parentFile!!.mkdirs()) throw IOException("Cannot create attachment directory")

        var copied = false
        try {
            val size = resolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    val total = input.copyCancellable(output)
                    output.fd.sync()
                    total
                }
            } ?: throw IOException("Cannot open attachment")
            val attachment = AttachmentEntity(id, messageId, fileName, mimeType, size, relativePath, position = position)
            copied = true
            return attachment
        } finally {
            if (!copied) remove(relativePath)
        }
    }

    suspend fun duplicate(attachment: AttachmentEntity, messageId: String, position: Int): AttachmentEntity {
        val original = attachment.localPath?.let(::file)?.takeIf { it.isFile }
        return copyLocal(original, attachment, messageId, position)
    }

    suspend fun importRecording(source: File, attachment: AttachmentEntity): AttachmentEntity =
        copyLocal(source, attachment, attachment.messageId, attachment.position)

    private suspend fun copyLocal(original: File?, attachment: AttachmentEntity, messageId: String, position: Int): AttachmentEntity {
        val id = UUID.randomUUID().toString()
        val copy = attachment.copy(id = id, messageId = messageId, position = position,
            localPath = null, remotePath = null, backupStatus = "LOCAL_ONLY")
        if (original == null) return copy
        val extension = attachment.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }
        val path = "$id/content" + extension?.let { ".$it" }.orEmpty()
        val destination = file(path)
        check(destination.parentFile!!.mkdirs())
        var success = false
        try {
            val size = original.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyCancellable(output).also { output.fd.sync() } }
            }
            success = true
            return copy.copy(localPath = path, fileSize = size)
        } finally {
            if (!success) remove(path)
        }
    }

    suspend fun save(source: File, destination: Uri) {
        source.inputStream().use { input ->
            val output = resolver.openOutputStream(destination, "wt") ?: throw IOException("Cannot create output file")
            try {
                output.use { input.copyCancellable(it) }
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    runCatching { resolver.openOutputStream(destination, "wt")?.close() }
                }
                throw error
            }
        }
    }

    fun file(relativePath: String): File = File(root, relativePath)

    fun remove(relativePath: String) {
        val path = file(relativePath)
        path.delete()
        path.parentFile?.delete()
    }

    fun removeOrphans(retainedPaths: Set<String>) {
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { directory ->
            directory.listFiles().orEmpty().forEach { child ->
                if (child.isFile && "${directory.name}/${child.name}" !in retainedPaths) child.delete()
            }
            directory.delete() // Succeeds only when the directory is empty.
        }
    }
}

private suspend fun InputStream.copyCancellable(output: OutputStream): Long {
    val jobContext = currentCoroutineContext()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        jobContext.ensureActive()
        val read = read(buffer)
        if (read < 0) return total
        output.write(buffer, 0, read)
        total += read
    }
}
