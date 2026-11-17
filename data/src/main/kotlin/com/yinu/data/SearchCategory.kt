package com.yinu.data

import java.util.Locale

enum class SearchCategory(
    private val extensions: Set<String>,
    private val mimePrefix: String? = null,
    private val mimeTypes: Set<String> = emptySet(),
) {
    DOCUMENT(
        extensions = setOf(
            "pdf", "txt", "md", "rtf", "doc", "docx", "docm", "xls", "xlsx", "xlsm",
            "ppt", "pptx", "pptm", "csv", "tsv", "odt", "ods", "odp", "wps", "et", "dps", "epub",
        ),
        mimePrefix = "text/",
        mimeTypes = setOf(
            "application/pdf", "application/x-pdf", "application/msword", "application/rtf",
            "application/epub+zip", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
        ),
    ),
    IMAGE(
        extensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "tif", "tiff", "dng", "ico"),
        mimePrefix = "image/",
    ),
    VIDEO(
        extensions = setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "mpeg", "mpg", "ts", "flv", "wmv"),
        mimePrefix = "video/",
    ),
    AUDIO(
        extensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr", "wma", "aiff", "m4b", "ape"),
        mimePrefix = "audio/",
    ),
    ARCHIVE(
        extensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "tbz", "zst"),
        mimeTypes = setOf(
            "application/zip", "application/x-zip", "application/x-zip-compressed",
            "application/vnd.rar", "application/x-rar-compressed", "application/x-7z-compressed",
            "application/x-tar", "application/gzip", "application/x-gzip", "application/x-bzip2",
            "application/x-xz", "application/zstd",
        ),
    ),
    INSTALLER(
        extensions = setOf("apk", "apks", "xapk", "apkm"),
        mimeTypes = setOf("application/vnd.android.package-archive"),
    );

    companion object {
        private val byExtension by lazy {
            buildMap {
                SearchCategory.entries.forEach { category ->
                    category.extensions.forEach { if (!containsKey(it)) put(it, category) }
                }
            }
        }
    }

    fun matches(attachment: Attachment): Boolean = matches(attachment.fileName, attachment.mimeType)

    internal fun matches(fileName: String, mime: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        // Container extensions distinguish e.g. APKs and Office files labeled application/zip.
        val extensionCategory = byExtension[extension]
        if (extensionCategory != null) return this == extensionCategory
        val mimeType = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        return mimeType in mimeTypes ||
            mimePrefix?.let(mimeType::startsWith) == true ||
            (this == DOCUMENT && mimeType.startsWith("application/vnd.openxmlformats-officedocument.")) ||
            (this == DOCUMENT && mimeType.startsWith("application/vnd.oasis.opendocument."))
    }
}
