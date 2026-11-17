package com.yinu.app.records

import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yinu.app.IncomingShare
import com.yinu.data.BackupProgress
import com.yinu.data.BackupChangedException
import com.yinu.data.BackupPreview
import com.yinu.data.BackupSpaceException
import com.yinu.data.ManagedAttachment
import com.yinu.data.Record
import com.yinu.data.RecordQuote
import com.yinu.data.RecordRepository
import com.yinu.data.SearchCategory
import com.yinu.data.StorageUsage
import com.yinu.data.TrashDeletion
import com.yinu.data.TrashRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.EOFException
import java.io.FileNotFoundException
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipException
import javax.inject.Inject
import com.yinu.app.reminders.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONException

internal sealed interface RecordsState {
    data object Idle : RecordsState
    data object Loading : RecordsState
    data object Failed : RecordsState
    data class Loaded(val records: List<Record>, val window: Int = 0, val hasOlder: Boolean = false) : RecordsState
}

internal data class SearchDestination(val category: SearchCategory?) : java.io.Serializable

private const val TimelineBatch = 48

internal data class BackupStatus(
    val restoring: Boolean,
    val progress: BackupProgress = BackupProgress.Preparing,
    val cancelling: Boolean = false,
) {
    val canCancel: Boolean get() = !cancelling && progress != BackupProgress.Finishing
}

internal data class RecordNotice(val message: String, val undo: TrashDeletion? = null)

internal sealed interface LibraryState<out T> {
    data object Loading : LibraryState<Nothing>
    data object Failed : LibraryState<Nothing>
    data class Loaded<T>(val entries: List<T>) : LibraryState<T>
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class RecordsViewModel @Inject constructor(
    private val repository: RecordRepository,
    private val savedState: SavedStateHandle,
    private val reminderScheduler: ReminderScheduler,
) : ViewModel() {
    val draft = savedState.getStateFlow("draft", "")
    val quote = savedState.getStateFlow<RecordQuote?>("quote", null)
    val links = savedState.getStateFlow("links", arrayListOf<RecordQuote>())
    val quickAction = savedState.getStateFlow<String?>("quickAction", null)
    val externalLocation = savedState.getStateFlow<String?>("externalLocation", null)
    fun prepareQuickAction(action: String) { if (quickAction.value == null && !importing.value) savedState["quickAction"] = action }
    fun consumeQuickAction() { savedState["quickAction"] = null }
    fun requestLocation(id: String) { savedState["externalLocation"] = id }
    fun consumeLocation() { savedState["externalLocation"] = null }
    fun setLinks(records: List<Record>): Boolean {
        if ((records.map { it.id } + listOfNotNull(quote.value?.messageId)).distinct().size > 200) { notify("引用与关联合计最多 200 条记录"); return false }
        savedState["links"] = ArrayList(records.distinctBy { it.id }.map { RecordQuote(it.id, it.previewSummary()) })
        return true
    }
    fun removeLink(id: String) { savedState["links"] = ArrayList(links.value.filter { it.messageId != id }) }
    fun backlinks(id: String) = repository.backlinks(id)
    fun observeRecord(id: String) = repository.observeRecord(id)
    suspend fun imageGallery() = repository.imageGallery()
    fun unlink(id: String, target: String) = launchOperation("移除关联失败") { repository.unlink(id, target) }
    suspend fun linkedRecord(id: String) = repository.record(id)
    suspend fun linkState(id: String) = repository.linkState(id)
    suspend fun reminderRecord(id: String) = repository.record(id)
    suspend fun setReminder(id: String, at: Long) {
        repository.setReminder(id, at)
        reminderScheduler.restore()
    }
    fun refreshReminders() = launchOperation("提醒调度失败，请稍后重试") { reminderScheduler.restore() }
    suspend fun cancelReminder(id: String) { repository.cancelReminder(id); reminderScheduler.cancelDisplayed(listOf(id)) }
    val query = savedState.getStateFlow("query", "")
    val searchDestination = savedState.getStateFlow<SearchDestination?>("searchDestination", null)
    val searchDay = savedState.getStateFlow<Long?>("searchDay", null)

    private val reload = MutableStateFlow(0)
    private val timelineWindow = MutableStateFlow(TimelineBatch)
    private val sendingState = MutableStateFlow(false)
    private val importingState = MutableStateFlow(false)
    private val backupState = MutableStateFlow<BackupStatus?>(null)
    private var backupJob: Job? = null
    val backupStatus = backupState.asStateFlow()
    private val dissolvingState = MutableStateFlow<Set<String>>(emptySet())
    val dissolving = dissolvingState.asStateFlow()
    private val pendingBubbleAnimations = linkedMapOf<String, PendingMessageMotion>()
    var composerOrigin: MessageOrigin? = null
    var attachmentOrigin: MessageOrigin? = null
    val sending = sendingState.asStateFlow()
    val importing = importingState.asStateFlow()
    private val notices = Channel<RecordNotice>(Channel.BUFFERED)
    private val incomingShares = Channel<IncomingShare>(16)
    private val shareQueue = MutableStateFlow<List<IncomingShare>>(emptyList())
    val pendingShares = shareQueue.asStateFlow()
    val favoriteGroups = repository.favoriteGroups

    fun stageIncomingShare(share: IncomingShare) {
        if (shareQueue.value.size >= 16) notify("待保存的分享过多，请稍后重试")
        else shareQueue.update { it + share }
    }

    fun finishIncomingShare(share: IncomingShare, note: String?) {
        if (shareQueue.value.firstOrNull() !== share) return
        shareQueue.update { it.drop(1) }
        if (note != null) {
            val text = listOfNotNull(note.trim().ifEmpty { null }, share.text).joinToString("\n\n").ifEmpty { null }
            saveIncomingShare(share.copy(text = text))
        }
    }

    suspend fun mergeRecords(ids: List<String>, caption: String) {
        addOutgoingRecord(origin = null) { id -> repository.mergeRecords(ids, caption, id) }
    }

    suspend fun saveVoice(file: java.io.File, caption: String, origin: MessageOrigin?, flight: VoiceFlight?) {
        addOutgoingRecord(attachment = true, origin = origin, voice = flight) { id ->
            repository.saveVoice(file, caption, id)
        }
    }

    suspend fun saveVoiceTranscript(text: String) {
        addOutgoingRecord(origin = null) { id -> repository.addText(text, messageId = id) }
    }

    suspend fun saveAiSummary(text: String, sources: List<Record>) {
        val references = sources.map { RecordQuote(it.id, it.previewSummary()) }
        addOutgoingRecord(origin = null) { id -> repository.addText(text, messageId = id, links = references) }
    }

    suspend fun setFavoriteGroup(id: String, group: String?) = repository.setFavoriteGroup(id, group)
    val messages = notices.receiveAsFlow()

    val timeline = reload.flatMapLatest {
        timelineWindow.flatMapLatest { limit ->
            repository.recentTimeline(limit)
                .map<_, RecordsState> { RecordsState.Loaded(it.records, window = limit, hasOlder = it.hasOlder) }
                .catch { emit(RecordsState.Failed) }
        }.onStart { emit(RecordsState.Loading) }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecordsState.Loading)

    val pinned = repository.pinned.catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val storageUsage = repository.storageUsage.catch<StorageUsage?> { emit(null) }
    val managedAttachments = reload.flatMapLatest { repository.managedAttachments.toLibraryState<ManagedAttachment>() }
    val trash = reload.flatMapLatest { repository.trash.toLibraryState<TrashRecord>() }
    val reminderRecords = repository.reminderRecords.toLibraryState<Record>()
    private val savingAttachmentState = MutableStateFlow<String?>(null)
    val savingAttachment = savingAttachmentState.asStateFlow()

    val results = observeSearch()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordsState.Idle)

    fun observeSearch(category: SearchCategory? = null, onlyFavorites: Boolean = false, favoriteGroup: String? = null): Flow<RecordsState> =
        combine(query, searchDay, reload) { text, day, _ ->
            if (text.isBlank() && !onlyFavorites && category == null && day == null) flowOf(RecordsState.Idle)
            else repository.search(text, onlyFavorites, category, day?.let(LocalDate::ofEpochDay), favoriteGroup).toUiState()
        }.flatMapLatest { it }

    init {
        viewModelScope.launch {
            for (share in incomingShares) {
                try {
                    addOutgoingRecord(origin = null) { id ->
                        if (share.files.isEmpty()) {
                            repository.addText(checkNotNull(share.text), messageId = id)
                        } else {
                            repository.importFiles(share.files, messageId = id, sharedText = share.text)
                        }
                    }
                    notify("已保存到时间线")
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    notify("分享保存失败，请检查文件权限或存储空间后重试")
                }
            }
        }
        viewModelScope.launch {
            try {
                repository.cleanOrphans()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The next launch can retry cleanup. Record reads report their own errors.
            }
        }
    }

    fun updateDraft(text: String) { savedState["draft"] = text }
    fun setQuote(value: RecordQuote?) {
        if ((links.value.map { it.messageId } + listOfNotNull(value?.messageId)).distinct().size > 200) {
            notify("引用与关联合计最多 200 条记录")
        } else savedState["quote"] = value
    }
    fun updateQuery(text: String) { savedState["query"] = text }
    fun openSearchCategory(type: SearchCategory) { savedState["searchDestination"] = SearchDestination(type) }
    fun openFavorites() { savedState["searchDestination"] = SearchDestination(null) }
    fun closeSearchDestination() { savedState["searchDestination"] = null }
    fun selectSearchDate(date: LocalDate?) { savedState["searchDay"] = date?.toEpochDay() }
    fun retry() { reload.update { it + 1 } }

    fun loadOlder(window: Int) {
        val current = timeline.value as? RecordsState.Loaded ?: return
        if (current.hasOlder && current.window == window) {
            timelineWindow.compareAndSet(window, window + TimelineBatch)
        }
    }

    fun returnToLatest() {
        timelineWindow.value = TimelineBatch
    }

    suspend fun includeInTimeline(id: String): Int? {
        val current = timeline.value as? RecordsState.Loaded
        if (current != null && current.records.any { it.id == id }) return current.window
        val rank = repository.timelineRank(id) ?: return null
        // A target absent from the current emission also needs a fresh query if its rank
        // fits the window, for example when a search result arrived before timeline invalidation.
        val required = maxOf((current?.window ?: 0) + 1, rank + TimelineBatch / 2)
        timelineWindow.update { maxOf(it, required) }
        return required
    }

    suspend fun editRecord(id: String, text: String): Boolean = repository.editContent(id, text)

    fun notify(message: String) {
        notices.trySend(RecordNotice(message))
    }

    fun saveIncomingShare(share: IncomingShare) {
        if (incomingShares.trySend(share).isFailure) notify("待保存的分享过多，请稍后重试")
    }

    fun exportBackup(destination: Uri) = runBackup(restoring = false) { progress ->
        val count = repository.exportLocalBackup(destination, progress)
        "已导出 $count 条记录"
    }

    suspend fun inspectBackup(source: Uri) = repository.inspectLocalBackup(source)

    fun restoreBackup(source: Uri, preview: BackupPreview) = runBackup(restoring = true) { progress ->
        val result = repository.restoreLocalBackup(source, preview, progress)
        "新增 ${result.restored} 条记录，补回 ${result.repairedAttachments} 个附件"
    }

    fun cancelBackup() {
        while (true) {
            val status = backupState.value?.takeIf { it.canCancel } ?: return
            if (backupState.compareAndSet(status, status.copy(cancelling = true))) {
                backupJob?.cancel()
                return
            }
        }
    }

    private fun runBackup(restoring: Boolean, action: suspend ((BackupProgress) -> Unit) -> String) {
        if (!backupState.compareAndSet(null, BackupStatus(restoring))) return
        backupJob = viewModelScope.launch {
            try {
                val result = action { progress ->
                    backupState.update { status ->
                        status?.let { if (it.cancelling) it else it.copy(progress = progress) }
                    }
                    if (backupState.value?.cancelling == true) throw CancellationException()
                }
                notify(result)
            } catch (error: CancellationException) {
                if (backupState.value?.cancelling == true) notify(if (restoring) "已取消恢复" else "已取消导出")
                throw error
            } catch (error: Exception) {
                notify(backupError(error, restoring))
            } finally {
                backupState.value = null
            }
        }
    }

    private fun backupError(error: Exception, restoring: Boolean): String {
        val operation = if (restoring) "恢复" else "导出"
        val outOfSpace = error is BackupSpaceException || generateSequence<Throwable>(error) { it.cause }
            .any { it is ErrnoException && it.errno == OsConstants.ENOSPC }
        return when {
            error is BackupChangedException -> "备份文件已变化，请重新检查后恢复"
            outOfSpace -> "${operation}失败：存储空间不足"
            error is SecurityException || error is FileNotFoundException -> "${operation}失败：无法访问文件或保存位置"
            restoring && (error is ZipException || error is EOFException || error is JSONException ||
                error is IllegalArgumentException || error is ArithmeticException) -> "恢复失败：备份已损坏或格式不支持"
            !restoring && error is IllegalArgumentException -> "导出失败：备份记录索引过大"
            else -> "${operation}失败：读取或写入中断，请重试"
        }
    }

    fun hasPendingBubbleAnimation(id: String) = id in pendingBubbleAnimations
    fun pendingBubbleMotion(id: String) = pendingBubbleAnimations[id]

    fun consumeBubbleAnimation(id: String) {
        pendingBubbleAnimations.remove(id)
    }

    private suspend fun addOutgoingRecord(attachment: Boolean = false, origin: MessageOrigin? = if (attachment) attachmentOrigin else composerOrigin,
        voice: VoiceFlight? = null, write: suspend (String) -> Unit) {
        val id = UUID.randomUUID().toString()
        // Mark the ID before Room publishes it. Old rows never enter this set.
        if (pendingBubbleAnimations.size == 16) pendingBubbleAnimations.remove(pendingBubbleAnimations.keys.first())
        pendingBubbleAnimations[id] = PendingMessageMotion(origin, attachment, voice = voice)
        try {
            write(id)
            pendingBubbleAnimations[id]?.let { pendingBubbleAnimations[id] = it.copy(committedAt = android.os.SystemClock.uptimeMillis()) }
        } catch (error: Exception) {
            consumeBubbleAnimation(id)
            throw error
        }
    }

    fun send() {
        val text = draft.value
        if (text.isBlank() || importingState.value || !sendingState.compareAndSet(expect = false, update = true)) return
        val quoted = quote.value
        val linked = links.value
        launchOperation("发送失败，请重试") {
            try {
                addOutgoingRecord { id ->
                    repository.addText(text, quoted, messageId = id, links = linked)
                }
                if (draft.value == text) updateDraft("")
                if (quote.value === quoted) setQuote(null)
                if (links.value === linked) savedState["links"] = arrayListOf<RecordQuote>()
            } finally {
                sendingState.value = false
            }
        }
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty() || sendingState.value || !importingState.compareAndSet(expect = false, update = true)) return
        val quoted = quote.value
        val linked = links.value
        launchOperation("导入失败，请检查文件权限或存储空间后重试") {
            try {
                addOutgoingRecord(attachment = true) { id -> repository.importFiles(uris, quoted, messageId = id, links = linked) }
                if (quote.value === quoted) setQuote(null)
                if (links.value === linked) savedState["links"] = arrayListOf<RecordQuote>()
            } finally {
                importingState.value = false
            }
        }
    }

    fun toggleFavorite(id: String) = launchOperation("收藏操作失败，请重试") {
        repository.toggleFavorite(id)
    }

    fun setPinned(id: String, pinned: Boolean) = launchOperation("置顶操作失败，请重试") {
        repository.setPinned(id, pinned)
    }

    fun deleteRecord(id: String) = deleteRecords(setOf(id)) {}

    fun deleteRecords(ids: Set<String>, onFinished: (Boolean) -> Unit) = launchOperation("删除失败，请重试") {
        val pending = ids - dissolvingState.value
        dissolvingState.update { it + pending }
        var deleted = false
        try {
            if (pending.isEmpty()) return@launchOperation
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) delay(RecordDissolveMillis.toLong())
            val deletion = repository.deleteRecords(pending)
            reminderScheduler.cancelDisplayed(deletion.ids)
            if (deletion.ids.isEmpty()) return@launchOperation
            deleted = true
            notices.trySend(RecordNotice("已移到最近删除（${deletion.ids.size} 条）", undo = deletion))
            withTimeoutOrNull(600) {
                timeline.first { state ->
                    state is RecordsState.Loaded && state.records.none { it.id in pending }
                }
            }
        } finally {
            dissolvingState.update { it - pending }
            onFinished(deleted)
        }
    }

    fun restoreTrash(deletion: TrashDeletion) = launchOperation("恢复记录失败，请重试") {
        val count = repository.restoreTrash(deletion)
        notify(if (count > 0) "已恢复 ${count} 条记录" else "这些记录已不在最近删除")
    }

    fun discardTrash(entry: TrashRecord) = launchOperation("彻底删除失败，请重试") {
        val discarded = repository.discardTrash(entry.record.id, entry.deletedAt)
        notify(if (discarded) "已彻底删除记录" else "这条记录已不在最近删除")
    }

    fun saveAttachment(id: String, destination: Uri) {
        if (!savingAttachmentState.compareAndSet(null, id)) return
        launchOperation("另存失败，请检查存储空间或选择其他位置") {
            try {
                notify(if (repository.saveAttachment(id, destination)) "附件已另存" else "本地附件已不存在")
            } finally {
                savingAttachmentState.value = null
            }
        }
    }

    fun removeLocalCopy(id: String) = launchOperation("移除本地副本失败，请重试") {
        repository.removeLocalCopy(id)
    }

    private fun launchOperation(errorMessage: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("YinuRecords", errorMessage, error)
                notify(errorMessage)
            }
        }
    }

    private fun Flow<List<Record>>.toUiState(): Flow<RecordsState> =
        map<List<Record>, RecordsState> { RecordsState.Loaded(it) }
            .onStart { emit(RecordsState.Loading) }
            .catch { emit(RecordsState.Failed) }

    private fun <T> Flow<List<T>>.toLibraryState(): Flow<LibraryState<T>> =
        map<List<T>, LibraryState<T>> { LibraryState.Loaded(it) }
            .onStart { emit(LibraryState.Loading) }
            .catch { emit(LibraryState.Failed) }
}
