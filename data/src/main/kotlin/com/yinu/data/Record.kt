package com.yinu.data

import java.io.File
import java.io.Serializable
import java.util.Locale

data class Record(
    val id: String,
    val content: String?,
    val createdAt: Long,
    val isFavorite: Boolean,
    val attachments: List<Attachment>,
    val quote: RecordQuote? = null,
    val isPinned: Boolean = false,
    val links: List<RecordQuote> = emptyList(),
    val reminderAt: Long? = null,
    val reminderDelivered: Boolean = false,
    val favoriteGroup: String? = null,
)

data class RecordQuote(val messageId: String, val summary: String) : Serializable

data class Attachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val localFile: File?,
    val isBackedUp: Boolean,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isPdf: Boolean get() = mimeType == "application/pdf"
    val isDocx: Boolean get() = fileName.endsWith(".docx", ignoreCase = true) ||
        mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    val isPlainText: Boolean get() = mimeType in PlainTextTypes ||
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in PlainTextExtensions
    val isVideo: Boolean get() = SearchCategory.VIDEO.matches(this)
    val isAudio: Boolean get() = SearchCategory.AUDIO.matches(this)
    val canPreview: Boolean get() = isImage || isPdf || isDocx || isPlainText || isVideo || isAudio
}

private val PlainTextTypes = setOf("text/plain", "text/markdown", "text/x-markdown")
private val PlainTextExtensions = setOf("txt", "md", "markdown", "log")

private val webUrl = Regex("""https?://[^\s<>"'，。；！？、）》】]+""", RegexOption.IGNORE_CASE)

fun webLinks(text: String): List<Pair<IntRange, String>> = webUrl.findAll(text).mapNotNull { match ->
    var url = match.value.trimEnd('.', ',', ';', '!', '?')
    while (url.endsWith(')') && url.count { it == ')' } > url.count { it == '(' }) {
        url = url.dropLast(1)
    }
    if (url.substringAfter("://").substringBefore('/').substringBefore('?').isBlank()) {
        null
    } else {
        (match.range.first until match.range.first + url.length) to url
    }
}.toList()
