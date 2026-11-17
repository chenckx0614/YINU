package com.yinu.app.records

import androidx.compose.ui.graphics.Color

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.haooz.chedule.ui.basic.ShortcutMenuItem
import com.kyant.capsule.ContinuousRoundedRectangle
import com.yinu.data.Attachment
import com.yinu.designsystem.YinuMenuIcons
import com.yinu.data.Record
import com.yinu.data.RecordQuote
import com.yinu.data.TrashRetentionDays
import com.yinu.data.SearchCategory
import com.yinu.data.webLinks

private val bubbleShape = RoundedCornerShape(topStart = 12.dp, topEnd = 4.dp, bottomEnd = 12.dp, bottomStart = 12.dp)
private val searchItemShape = ContinuousRoundedRectangle(14.dp)

// Menu and preview anchors only need their current bounds when an action opens.
internal class RecordAnchor {
    var coordinates: LayoutCoordinates? = null

    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInRoot()?.takeUnless { it.isEmpty }
}

@Composable
internal fun RecordBubble(
    record: Record,
    viewModel: RecordsViewModel,
    onPreview: (AttachmentPreviewTarget) -> Unit,
    onLocateQuote: (String) -> Unit,
    category: SearchCategory? = null,
    inSearch: Boolean = false,
    bubbleModifier: Modifier = Modifier,
    animateSend: Boolean = false,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val menus = LocalRecordMenus.current
    val deleteDialog = LocalRecordDeleteDialog.current
    val selection = LocalRecordSelection.current
    val selecting = selection.active
    val selected by remember(selection, record.id) { derivedStateOf { record.id in selection.selected } }
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val voicePlayback = LocalVoicePlayback.current
    val voiceSeconds = remember(record.id) { viewModel.pendingBubbleMotion(record.id)?.voice?.seconds }
    val hiddenAttachment = LocalHiddenPreviewAttachment.current
    val itemShape = if (inSearch) searchItemShape else bubbleShape
    var motionAnchor by remember(record.id) { mutableStateOf(Rect.Zero) }
    val motion = if (animateSend && !inSearch) messageFlight(record.id, viewModel) { motionAnchor } else Modifier
    val tracksFlight = motion != Modifier

    fun confirmDelete(attachment: Attachment?) {
        deleteDialog.open(DeletePrompt(
            title = if (attachment == null) "删除这条记录？" else "移除本地副本？",
            summary = if (attachment == null) "记录和附件将移到「最近删除」，可在 ${TrashRetentionDays} 天内恢复。"
                else "记录会保留文件名和大小。需要找回文件时，可导入包含此附件的备份。",
            confirmLabel = if (attachment == null) "删除" else "移除",
            onConfirm = {
                if (attachment == null) viewModel.deleteRecord(record.id)
                else viewModel.removeLocalCopy(attachment.id)
            },
        ))
    }

    fun open(attachment: Attachment, origin: () -> Rect?, thumbnail: Painter?) {
        if (attachment.localFile == null) {
            viewModel.notify("本地副本已移除")
        } else if (attachment.canPreview) {
            onPreview(AttachmentPreviewTarget(attachment, origin, thumbnail, inSearch))
        } else {
            sendAttachment(context, attachment, share = false, viewModel::notify)
        }
    }

    fun showActions(
        anchor: Rect,
        attachment: Attachment?,
        openFile: () -> Unit = {},
        bubble: @Composable () -> Unit,
    ) {
        val icons = YinuMenuIcons
        val items = buildList {
            if (attachment != null) {
                if (attachment.localFile != null) {
                    add(ShortcutMenuItem(icons.open, if (attachment.isVoiceRecording) "播放语音" else "打开文件", openFile))
                    add(ShortcutMenuItem(icons.share, "分享文件", {
                        sendAttachment(context, attachment, share = true, viewModel::notify)
                    }))
                }
                add(ShortcutMenuItem(icons.copy, "复制文件名", {
                    copyText(context, attachment.fileName, viewModel::notify)
                }))
                if (attachment.localFile != null) {
                    add(ShortcutMenuItem(icons.removeCopy, "移除本地副本", { confirmDelete(attachment) }))
                }
            }
            record.content?.let { content ->
                add(ShortcutMenuItem(icons.copy, "复制文字", { copyText(context, content, viewModel::notify) }))
                if (attachment == null && content.isNotBlank()) {
                    add(ShortcutMenuItem(icons.ai, "AI 整理", {
                        focus.clearFocus(force = true)
                        menus.showOrganize(listOf(record), viewModel) {}
                    }))
                    add(ShortcutMenuItem(icons.search, "提取信息", {
                        focus.clearFocus(force = true)
                        menus.showInformation(content, viewModel::notify)
                    }))
                }
                if (attachment == null) {
                    add(ShortcutMenuItem(icons.selectText, "选择文字", {
                        menus.selectText(content) { selected -> copyText(context, selected, viewModel::notify) }
                    }))
                }
            }
            add(ShortcutMenuItem(icons.edit, if (attachment == null) "编辑记录" else "编辑说明", {
                focus.clearFocus(force = true)
                menus.editRecord(record) { text -> viewModel.editRecord(record.id, text) }
            }))
            add(ShortcutMenuItem(icons.reply, "引用", {
                viewModel.setQuote(RecordQuote(record.id, record.previewSummary(attachment)))
            }))
            add(ShortcutMenuItem(icons.multiSelect, "多选", {
                focus.clearFocus(force = true)
                selection.start(record)
            }))
            add(ShortcutMenuItem(icons.calendar, "设置提醒", { focus.clearFocus(force = true); menus.showReminder(record, viewModel) }))
            add(ShortcutMenuItem(icons.link, "关联记录", { focus.clearFocus(force = true); menus.showRelations(record, viewModel, onLocateQuote) }))
            add(ShortcutMenuItem(icons.pin, if (record.isPinned) "取消置顶" else "置顶", {
                viewModel.setPinned(record.id, !record.isPinned)
            }))
            add(ShortcutMenuItem(
                if (record.isFavorite) icons.unfavorite else icons.favorite,
                if (record.isFavorite) "取消收藏" else "收藏",
                { viewModel.toggleFavorite(record.id) },
            ))
            if (record.isFavorite) add(ShortcutMenuItem(icons.file, "收藏分组", { menus.showFavoriteGroup(record, viewModel) }))
            add(ShortcutMenuItem(icons.delete, "删除记录", { confirmDelete(null) }))
        }
        menus.open(RecordMenu.Message(anchor, items, bubble))
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selecting && selected) colors.primary.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(12.dp))
            .then(
            if (selecting) Modifier.toggleable(
                value = selected,
                enabled = !selection.deleting,
                role = Role.Checkbox,
                interactionSource = null,
                indication = null,
                onValueChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    selection.toggle(record)
                },
            ) else Modifier,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (inSearch) Alignment.Start else Alignment.End),
        verticalAlignment = Alignment.Top,
    ) {
        if (inSearch && selecting) {
            Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) { RecordSelectionMark(selected) }
        }
        Column(
            modifier = (if (inSearch && selecting) Modifier.weight(1f)
                else Modifier.fillMaxWidth(if (inSearch) 1f else 0.81f)).then(bubbleModifier).then(motion),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = if (inSearch) Alignment.Start else Alignment.End,
        ) {
            record.content?.let { content ->
                val linkedText = remember(content, colors.primary, context, viewModel, haptics) {
                    buildAnnotatedString {
                        append(content)
                        webLinks(content).forEach { (range, url) ->
                            addLink(
                                LinkAnnotation.Clickable(
                                    tag = url,
                                    styles = TextLinkStyles(
                                        style = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
                                    ),
                                    linkInteractionListener = {
                                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        openLink(context, url, viewModel::notify)
                                    },
                                ),
                                range.first,
                                range.last + 1,
                            )
                        }
                    }
                }
                val anchor = remember { RecordAnchor() }
                val plainText = remember(content) { AnnotatedString(content) }
                val showMenu = {
                    showActions(anchor.bounds() ?: Rect.Zero, null) {
                        MessageText(plainText, inSearch = inSearch, menuPreview = true)
                    }
                }
                MessageText(
                    if (selecting) plainText else linkedText,
                    if (selecting) Modifier else Modifier.onGloballyPositioned {
                        anchor.coordinates = it
                        if (tracksFlight) motionAnchor = it.boundsInRoot()
                    }
                        .clip(itemShape).combinedClickable(
                            interactionSource = null,
                            indication = null,
                            onClick = {},
                            onLongClick = showMenu,
                            onLongClickLabel = "消息操作",
                        ),
                    inSearch = inSearch,
                )
            }
            val attachments = remember(record.attachments, category) {
                if (category == null) record.attachments else record.attachments.filter(category::matches)
            }
            attachments.forEach { attachment ->
                key(attachment.id) {
                    val anchor = remember { RecordAnchor() }
                    var thumbnail by remember(attachment.localFile) { mutableStateOf<Painter?>(null) }
                    val isVoice = attachment.isVoiceRecording
                    val openFile = {
                        val file = attachment.localFile
                        if (isVoice && file != null && voicePlayback != null) voicePlayback.toggle(file)
                        else open(attachment, anchor::bounds, thumbnail)
                    }
                    val attachmentModifier = Modifier.onGloballyPositioned {
                            anchor.coordinates = it
                            if (tracksFlight && !isVoice && record.content == null && attachment.id == attachments.firstOrNull()?.id) {
                                motionAnchor = it.boundsInRoot()
                            }
                        }
                            .then(if (isVoice) Modifier else Modifier.clip(itemShape))
                            .then(
                                if (selecting) Modifier else Modifier.combinedClickable(
                                    interactionSource = null,
                                    indication = null,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        openFile()
                                    },
                                    onLongClick = {
                                        showActions(anchor.bounds() ?: Rect.Zero, attachment, openFile) {
                                            if (isVoice) VoiceBubble(attachment, voiceSeconds, Modifier, trailing = !inSearch)
                                            else AttachmentBubble(attachment, inSearch = inSearch)
                                        }
                                    },
                                    onLongClickLabel = if (isVoice) "语音操作" else "文件操作",
                                ),
                            )
                            .then(
                                if (!attachment.isImage && hiddenAttachment == attachment.id) Modifier.graphicsLayer { alpha = 0f }
                                else Modifier,
                            )
                    if (isVoice) {
                        VoiceBubble(attachment, voiceSeconds, attachmentModifier, trailing = !inSearch,
                            onPlayBounds = if (tracksFlight && voiceSeconds != null) { bounds -> motionAnchor = bounds } else null)
                    } else {
                        AttachmentBubble(attachment, modifier = attachmentModifier,
                            onThumbnail = { thumbnail = it }, inSearch = inSearch)
                    }
                }
            }
            record.quote?.let {
                QuoteSnippet(
                    it,
                    onLocate = { onLocateQuote(it.messageId) },
                    modifier = Modifier.fillMaxWidth(),
                    interactive = !selecting,
                )
            }
            val additionalLinks = remember(record.links, record.quote?.messageId) {
                record.links.filter { it.messageId != record.quote?.messageId }
            }
            additionalLinks.forEach { link ->
                QuoteSnippet(link, onLocate = { onLocateQuote(link.messageId) },
                    modifier = Modifier.fillMaxWidth(), interactive = !selecting)
            }
            record.reminderAt?.let { at ->
                val label = if (record.reminderDelivered) "提醒已结束"
                    else if (at <= System.currentTimeMillis()) "时间已到 · 未触发"
                    else "提醒 · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(at))
                Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            if (record.isFavorite) {
                Text("已收藏", style = MaterialTheme.typography.labelSmall, color = colors.primary)
            }
        }
        if (!inSearch) {
            Box(
                Modifier.size(30.dp).then(
                    if (selecting) Modifier else Modifier.background(colors.primaryContainer, RoundedCornerShape(8.dp)),
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (selecting) RecordSelectionMark(selected)
                else Text("┐", color = colors.primary)
            }
        }
    }
}

internal fun Record.previewSummary(attachment: Attachment? = null): String {
    val file = attachment ?: attachments.firstOrNull()
    val summary = if (file == null) content.orEmpty()
        else "[${if (file.isImage) "图片" else "文件"}] ${file.fileName}"
    return summary.take(240)
}

@Composable
private fun MessageText(
    content: AnnotatedString,
    modifier: Modifier = Modifier,
    inSearch: Boolean = false,
    menuPreview: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    if (inSearch) {
        val container = if (menuPreview) modifier.background(colors.surface, searchItemShape) else modifier
        Text(
            content, container.padding(horizontal = if (menuPreview) 12.dp else 0.dp, vertical = 4.dp),
            color = colors.onSurface,
            fontSize = 17.sp,
        )
        return
    }
    Surface(modifier, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, shape = bubbleShape) {
        Text(content, Modifier.padding(horizontal = 13.dp, vertical = 10.dp))
    }
}

@Composable
private fun AttachmentBubble(
    attachment: Attachment,
    modifier: Modifier = Modifier,
    onThumbnail: (Painter?) -> Unit = {},
    inSearch: Boolean = false,
) {
    val shape = if (inSearch) searchItemShape else bubbleShape
    if (attachment.isImage && attachment.localFile != null) {
        var imageFailed by remember(attachment.id, attachment.localFile) { mutableStateOf(false) }
        if (!imageFailed) {
            val hideThumbnail = LocalHiddenPreviewAttachment.current == attachment.id
            AsyncImage(
                model = attachment.localFile,
                contentDescription = attachment.fileName,
                modifier = modifier.clip(shape).fillMaxWidth().height(156.dp)
                    .graphicsLayer { alpha = if (hideThumbnail) 0f else 1f },
                contentScale = ContentScale.Crop,
                onSuccess = { onThumbnail(it.painter) },
                onError = {
                    onThumbnail(null)
                    imageFailed = true
                },
            )
            return
        }
    }
    val colors = MaterialTheme.colorScheme
    val fill = if (inSearch) colors.onSurface.copy(alpha = 0.06f) else colors.surface
    Box(modifier.fillMaxWidth().background(fill, shape)) {
        AttachmentCardContent(attachment, inSearch)
    }
}

@Composable
internal fun AttachmentCardContent(
    attachment: Attachment,
    inSearch: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val configuration = LocalConfiguration.current
    val sizeLabel = remember(attachment.fileSize, attachment.localFile, configuration) {
        if (attachment.localFile == null) "本地副本已移除"
        else Formatter.formatShortFileSize(context, attachment.fileSize)
    }
    val extensionLabel = remember(attachment.fileName, configuration) {
        attachment.fileName.substringAfterLast('.', "").uppercase().take(4).ifBlank { "文件" }
    }
    Row(
        modifier.padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(attachment.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                sizeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Box(
            Modifier.size(38.dp).background(
                if (inSearch) colors.surface else colors.primaryContainer,
                if (inSearch) searchItemShape else RoundedCornerShape(8.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                extensionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
            )
        }
    }
}
