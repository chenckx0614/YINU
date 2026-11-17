package com.yinu.data

import androidx.room.withTransaction
import com.yinu.database.AttachmentEntity
import com.yinu.database.MessageEntity
import com.yinu.database.MessageWithAttachments
import com.yinu.database.RecordLinkEntity
import com.yinu.database.YinuDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class SyncChange(val id: String, val updatedAt: Long, val digest: String, val payload: JSONObject) {
    fun toJson(): JSONObject = JSONObject().put("record_id", id).put("updated_at_ms", updatedAt)
        .put("digest", digest).put("payload", payload)
}

class RecordSyncStore(private val database: YinuDatabase) {
    private val dao = database.records()

    suspend fun pending(limit: Int): List<SyncChange> = dao.pendingSyncRecords(limit).map(::encodeSyncChange)

    suspend fun acknowledge(changes: List<SyncChange>) = database.withTransaction {
        changes.forEach { dao.acknowledgeSync(it.id, it.updatedAt) }
    }

    suspend fun apply(changes: List<SyncChange>) {
        val rows = changes.map { change -> change to decodeSyncChange(change) }
        database.withTransaction {
            for ((change, row) in rows) {
                val local = dao.syncRecord(change.id)
                if (local != null) {
                    val current = encodeSyncChange(local)
                    if (current.digest == change.digest && current.updatedAt == change.updatedAt) {
                        dao.acknowledgeSync(change.id, change.updatedAt)
                        continue
                    }
                    if (local.message.syncStatus != "SYNCED" &&
                        (current.updatedAt > change.updatedAt ||
                            current.updatedAt == change.updatedAt && current.digest > change.digest)
                    ) continue
                }
                dao.applySyncedRecord(row, local)
            }
        }
    }
}

private fun encodeSyncChange(row: MessageWithAttachments): SyncChange {
    val message = row.message
    val payload = JSONObject().put("created", message.createdAt).put("deleted", message.isDeleted)
    if (!message.isDeleted) {
        payload.put("type", message.type).put("content", message.content)
            .put("favorite", message.isFavorite).put("group", message.favoriteGroup)
            .put("quote_id", message.quotedMessageId).put("quote_text", message.quotedSummary)
        payload.put("attachments", JSONArray(row.attachments.sortedBy { it.position }.map {
            JSONObject().put("id", it.id).put("name", it.fileName).put("mime", it.mimeType)
                .put("size", it.fileSize).put("position", it.position)
        }))
        payload.put("links", JSONArray(row.links.sortedBy { it.position }.map {
            JSONObject().put("id", it.targetId).put("summary", it.summary).put("position", it.position)
        }))
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
    return SyncChange(message.id, message.updatedAt, digest, payload)
}

private fun decodeSyncChange(change: SyncChange): MessageWithAttachments {
    val payload = change.payload
    require(change.id.length in 1..128 && change.updatedAt >= 0)
    val deleted = payload.getBoolean("deleted")
    val message = MessageEntity(
        id = change.id,
        type = if (deleted) "TEXT" else payload.getString("type"),
        content = if (deleted) null else payload.optionalText("content"),
        createdAt = payload.getLong("created"),
        updatedAt = change.updatedAt,
        isFavorite = !deleted && payload.getBoolean("favorite"),
        favoriteGroup = if (deleted) null else payload.optionalText("group"),
        isDeleted = deleted,
        syncStatus = "SYNCED",
        quotedMessageId = if (deleted) null else payload.optionalText("quote_id"),
        quotedSummary = if (deleted) null else payload.optionalText("quote_text"),
    )
    if (deleted) return MessageWithAttachments(message, emptyList())
    val attachments = payload.getJSONArray("attachments").objects(2000).map {
        AttachmentEntity(it.getString("id"), change.id, it.getString("name"), it.getString("mime"),
            it.getLong("size").also { size -> require(size >= 0) }, localPath = null, position = it.getInt("position"))
    }
    val links = payload.getJSONArray("links").objects(200).map {
        RecordLinkEntity(change.id, it.getString("id"), it.getString("summary"), it.getInt("position"))
    }
    return MessageWithAttachments(message, attachments, links)
}

private fun JSONArray.objects(max: Int): List<JSONObject> {
    require(length() <= max)
    return List(length()) { getJSONObject(it) }
}

private fun JSONObject.optionalText(name: String): String? = if (isNull(name)) null else getString(name)
