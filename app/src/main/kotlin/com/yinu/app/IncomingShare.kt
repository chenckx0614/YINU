package com.yinu.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal data class IncomingShare(val text: String?, val files: List<Uri>, val requestId: String = java.util.UUID.randomUUID().toString()) {
    companion object {
        fun accepts(action: String?) = action == Intent.ACTION_SEND ||
            action == Intent.ACTION_SEND_MULTIPLE || action == Intent.ACTION_VIEW

        fun from(intent: Intent, privateAuthority: String): IncomingShare? {
            fun isExternalContent(uri: Uri): Boolean =
                uri.scheme == ContentResolver.SCHEME_CONTENT &&
                    !uri.authority.isNullOrEmpty() && uri.authority?.substringAfter('@') != privateAuthority

            if (intent.action == Intent.ACTION_VIEW) {
                val file = intent.data?.takeIf(::isExternalContent) ?: return null
                return IncomingShare(text = null, files = listOf(file))
            }
            if (!accepts(intent.action)) return null

            val multiple = intent.action == Intent.ACTION_SEND_MULTIPLE
            val clipItems = intent.clipData?.let { clip -> List(clip.itemCount, clip::getItemAt) }.orEmpty()
            val streams: List<*> = if (multiple) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: emptyList<Uri>()
            } else {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
            val files = (streams.filterIsInstance<Uri>() + clipItems.mapNotNull { it.uri })
                .filter(::isExternalContent)
                .distinct()

            val extraText = if (multiple) {
                intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.joinToString("\n")
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            } else {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            }
            val text = extraText?.trim()?.takeIf(String::isNotEmpty)
                ?: clipItems.mapNotNull { item ->
                    item.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
                        ?: item.uri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
                }.distinct().joinToString("\n").takeIf(String::isNotEmpty)
            return if (text == null && files.isEmpty()) null else IncomingShare(text, files)
        }
    }
}
