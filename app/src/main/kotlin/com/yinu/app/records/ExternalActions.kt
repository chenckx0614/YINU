package com.yinu.app.records

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.yinu.app.MainActivity
import com.yinu.data.Attachment

internal fun copyText(context: Context, text: String, notify: (String) -> Unit) {
    try {
        val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText("一隅", text))
        if (android.os.Build.VERSION.SDK_INT < 33) notify("已复制")
    } catch (_: Exception) {
        notify("复制失败")
    }
}

internal fun openLink(context: Context, link: String, notify: (String) -> Unit) {
    try {
        val uri = Uri.parse(link)
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
        require(!uri.host.isNullOrBlank())
        context.startActivity(Intent(Intent.ACTION_VIEW, uri.normalizeScheme()))
    } catch (_: Exception) {
        notify("无法打开链接")
    }
}

internal fun sendAttachment(
    context: Context,
    attachment: Attachment,
    share: Boolean,
    notify: (String) -> Unit,
) {
    try {
        val file = attachment.localFile?.takeIf { it.isFile }
        if (file == null) {
            notify("本地文件不存在")
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file, attachment.fileName)
        val action = if (share) {
            Intent(Intent.ACTION_SEND).setType(attachment.mimeType).putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mimeType)
        }
        action.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        action.clipData = ClipData.newRawUri(attachment.fileName, uri)
        val chooser = Intent.createChooser(action, if (share) "分享文件" else "外部打开")
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
        context.startActivity(chooser)
    } catch (_: Exception) {
        notify(if (share) "无法分享文件" else "没有可用的应用打开此文件")
    }
}
