package com.yinu.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.Room
import com.yinu.database.MessageEntity
import com.yinu.database.YinuDatabase
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class RecordRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: YinuDatabase
    private lateinit var repository: RecordRepository
    private lateinit var sources: TestDocuments

    @Before
    fun openDatabase() {
        sources = TestDocuments()
        sources.attachInfo(context, ProviderInfo().apply {
            authority = "yinu.test"
            packageName = context.packageName
            applicationInfo = context.applicationInfo
        })
        ShadowContentResolver.registerProviderInternal("yinu.test", sources)
        database = Room.databaseBuilder(context, YinuDatabase::class.java, "record-test.db").build()
        repository = RecordRepository(context, database)
    }

    @After
    fun closeDatabase() {
        database.close()
        context.deleteDatabase("record-test.db")
        File(context.filesDir, "attachments").deleteRecursively()
    }

    @Test
    fun mergeCopiesAttachmentsAndFavoriteGroupsSurviveBackup() = runBlocking<Unit> {
        val file = File(context.cacheDir, "merge-fixture.txt").apply { writeText("附件内容") }
        val uri = sources.add("merge-fixture", file.name, "text/plain", file)
        repository.addText("正文甲", messageId = "merge-a")
        repository.importFiles(listOf(uri), messageId = "merge-b", sharedText = "正文乙")
        val original = checkNotNull(repository.record("merge-b")).attachments.single()
        repository.mergeRecords(listOf("merge-a", "merge-b"), "汇总", "merged")
        val merged = checkNotNull(repository.record("merged"))
        assertEquals("汇总\n\n正文甲\n\n正文乙", merged.content)
        assertEquals("附件内容", merged.attachments.single().localFile?.readText())
        assertTrue(original.id != merged.attachments.single().id)
        repository.removeLocalCopy(original.id)
        assertEquals("附件内容", merged.attachments.single().localFile?.readText())
        assertTrue(repository.setFavoriteGroup("merged", "项目资料"))
        assertEquals(listOf("项目资料"), repository.favoriteGroups.first())
        assertEquals(listOf("merged"), repository.search("", true, favoriteGroup = "项目资料").first().map { it.id })

        val backup = File(context.cacheDir, "merge-groups.zip")
        val backupUri = sources.add("merge-groups", backup.name, "application/zip", backup)
        repository.exportLocalBackup(backupUri)
        val databaseName = "restored-groups.db"
        val database = Room.databaseBuilder(context, YinuDatabase::class.java, databaseName).build()
        try {
            val restored = RecordRepository(context, database)
            restored.restoreLocalBackup(backupUri)
            assertEquals("项目资料", restored.record("merged")?.favoriteGroup)
            assertEquals(listOf("merged"), restored.search("", true, favoriteGroup = "项目资料").first().map { it.id })
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            file.delete()
            backup.delete()
        }
    }

    @Test
    fun galleryKeepsTimelineAndAttachmentOrderAndExcludesTrashAndRemovedCopies() = runBlocking {
        val fixture = File(context.cacheDir, "gallery-source").apply { writeText("fixture bytes") }
        fun image(id: String) = sources.add(id, "$id.png", "image/png", fixture)
        repository.importFiles(listOf(image("first"), sources.add("doc", "note.txt", "text/plain", fixture), image("second")), messageId = "bundle")
        repository.importFiles(listOf(image("removed")), messageId = "removed-message")
        repository.importFiles(listOf(image("trash")), messageId = "trash-message")
        val rows = repository.timeline.first()
        repository.removeLocalCopy(rows.single { it.id == "removed-message" }.attachments.single().id)
        repository.deleteRecord("trash-message")
        assertEquals(listOf("first.png", "second.png"), repository.imageGallery().map { it.fileName })
        fixture.delete()
        Unit
    }

    @Test
    fun unrelatedWritesDoNotRepublishPinnedRecordButPinChangesStillArrive() = runBlocking {
        repository.addText("已置顶", messageId = "pinned")
        repository.setPinned("pinned", true)
        val events = Channel<Record?>(Channel.UNLIMITED)
        val collector = launch { repository.pinned.collect { events.send(it) } }
        try {
            assertEquals("pinned", withTimeout(5_000) { events.receive() }?.id)
            repository.addText("另一条消息", messageId = "other")
            assertNull(withTimeoutOrNull(300) { events.receive() })
            repository.setPinned("other", true)
            assertEquals("other", withTimeout(5_000) { events.receive() }?.id)
        } finally { collector.cancelAndJoin(); events.close() }
    }

    @Test
    fun restoredExpiredRemindersAreNotReplayed() = runBlocking {
        repository.addText("过期提醒", messageId = "expired")
        database.records().putReminder(com.yinu.database.ReminderEntity("expired", System.currentTimeMillis() - 1_000))
        val zip = File(context.cacheDir, "expired-reminder.zip")
        val uri = sources.add("expired-reminder", zip.name, "application/zip", zip)
        repository.exportLocalBackup(uri)
        val otherDb = Room.databaseBuilder(context, YinuDatabase::class.java, "expired-restore.db").build()
        try {
            val other = RecordRepository(context, otherDb)
            other.restoreLocalBackup(uri)
            assertTrue(other.record("expired")!!.reminderDelivered)
            assertTrue(other.pendingReminders.first().isEmpty())
        } finally { otherDb.close(); context.deleteDatabase("expired-restore.db"); zip.delete() }
    }

    @Test
    fun legacyVersionOneBackupRestoresItsQuoteAsALink() = runBlocking {
        val zip = File(context.cacheDir, "legacy-backup.zip")
        val record = JSONObject().put("id", "legacy-reply").put("type", "TEXT").put("text", "旧备份内容")
            .put("created", 1).put("updated", 1).put("favorite", false).put("pinned", false)
            .put("quotedId", "missing-target").put("quotedText", "保留的旧引用摘要").put("files", org.json.JSONArray())
        val manifest = JSONObject().put("format", "yinu-local-backup").put("version", 1)
            .put("records", org.json.JSONArray().put(record))
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("manifest.json")); it.write(manifest.toString().toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("backup.complete")); it.closeEntry()
        }
        val uri = sources.add("legacy-backup", zip.name, "application/zip", zip)
        try {
            repository.restoreLocalBackup(uri)
            repository.restoreLocalBackup(uri)
            val restored = repository.record("legacy-reply")!!
            assertEquals(listOf(RecordQuote("missing-target", "保留的旧引用摘要")), restored.links)
            assertEquals(restored.quote, restored.links.single())
            assertEquals("记录尚未恢复或不存在", repository.linkState("missing-target"))
            assertEquals(1, repository.timeline.first().size)
        } finally { zip.delete() }
    }

    @Test
    fun versionFourQuoteMigratesIntoBidirectionalLinks() = runBlocking {
        database.close()
        val schema = JSONObject(File(checkNotNull(System.getProperty("yinu.schema.v4"))).readText()).getJSONObject("database")
        context.openOrCreateDatabase("record-test.db", 0, null).use { legacy ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) legacy.execSQL(setup.getString(i))
            legacy.execSQL("INSERT INTO messages(id,type,content,created_at,updated_at,is_favorite,is_deleted,sync_status) VALUES('old','TEXT','旧记录',1,1,0,0,'PENDING')")
            legacy.execSQL("INSERT INTO messages(id,type,content,created_at,updated_at,is_favorite,is_deleted,sync_status,quoted_message_id,quoted_summary) VALUES('reply','TEXT','旧引用',2,2,0,0,'PENDING','old','旧记录')")
            legacy.version = 4
        }
        reopen()
        assertEquals(listOf(RecordQuote("old", "旧记录")), repository.record("reply")!!.links)
        assertEquals("reply", repository.backlinks("old").first().single().id)
        repository.unlink("reply", "old")
        assertNull(repository.record("reply")!!.quote)
        assertTrue(repository.record("reply")!!.links.isEmpty())
    }

    @Test
    fun linkedRecordsAreBidirectionalAndKeepSummariesAcrossDeletionAndBackup() = runBlocking {
        repository.addText("第一份材料", messageId = "one")
        repository.addText("第二份材料", messageId = "two")
        val links = listOf(RecordQuote("one", "第一份材料"), RecordQuote("two", "第二份材料"))
        repository.addText("整理后的想法", messageId = "summary", links = links + links.first())
        reopen()
        assertEquals(links, repository.record("summary")!!.links)
        assertEquals("summary", repository.backlinks("one").first().single().id)
        val deletion = repository.deleteRecords(setOf("one"))
        assertEquals("记录已删除", repository.linkState("one"))
        assertEquals(links, repository.record("summary")!!.links)
        repository.restoreTrash(deletion)
        assertEquals("可查看", repository.linkState("one"))
        val sourceDeletion = repository.deleteRecords(setOf("summary"))
        assertTrue(repository.backlinks("one").first().isEmpty())
        repository.restoreTrash(sourceDeletion)
        assertEquals("summary", repository.backlinks("one").first().single().id)
        repository.unlink("summary", "two")
        assertEquals(listOf(links.first()), repository.record("summary")!!.links)

        val zip = File(context.cacheDir, "linked-backup.zip")
        val uri = sources.add("linked-backup", zip.name, "application/zip", zip)
        repository.exportLocalBackup(uri)
        val restoredDb = Room.databaseBuilder(context, YinuDatabase::class.java, "linked-restore.db").build()
        try {
            val restored = RecordRepository(context, restoredDb)
            restored.restoreLocalBackup(uri)
            restored.restoreLocalBackup(uri)
            assertEquals(listOf(links.first()), restored.record("summary")!!.links)
            assertEquals(1, restored.backlinks("one").first().size)
        } finally { restoredDb.close(); context.deleteDatabase("linked-restore.db"); zip.delete() }
    }

    @Test
    fun remindersRejectStaleDeliveryAndSurviveRestartDeletionAndBackup() = runBlocking {
        repository.addText("需要提醒的记录", messageId = "remind")
        val first = System.currentTimeMillis() + 3_600_000
        val replacement = first + 3_600_000
        repository.setReminder("remind", first)
        repository.setReminder("remind", replacement)
        reopen()
        assertEquals(replacement, repository.pendingReminders.first().single().atMillis)
        assertFalse(repository.claimReminder("remind", first, replacement + 1))
        assertFalse(repository.claimReminder("remind", replacement, first))
        val deletion = repository.deleteRecords(setOf("remind"))
        assertTrue(repository.pendingReminders.first().isEmpty())
        assertFalse(repository.claimReminder("remind", replacement, replacement + 1))
        repository.restoreTrash(deletion)
        assertEquals(replacement, repository.pendingReminders.first().single().atMillis)
        val zip = File(context.cacheDir, "reminder-backup.zip")
        val uri = sources.add("reminder-backup", zip.name, "application/zip", zip)
        repository.exportLocalBackup(uri)
        val otherDb = Room.databaseBuilder(context, YinuDatabase::class.java, "reminder-restore.db").build()
        try {
            val other = RecordRepository(context, otherDb)
            other.restoreLocalBackup(uri)
            assertEquals(replacement, other.pendingReminders.first().single().atMillis)
        } finally { otherDb.close(); context.deleteDatabase("reminder-restore.db"); zip.delete() }
        assertTrue(repository.claimReminder("remind", replacement, replacement + 1))
        assertFalse(repository.claimReminder("remind", replacement, replacement + 2))
        assertTrue(repository.pendingReminders.first().isEmpty())
        repository.releaseReminderClaim("remind", replacement, replacement + 2)
        assertTrue(repository.record("remind")!!.reminderDelivered)
        repository.releaseReminderClaim("remind", replacement, replacement + 1)
        assertFalse(repository.record("remind")!!.reminderDelivered)
        repository.cancelReminder("remind")
        assertNull(repository.record("remind")!!.reminderAt)
    }

    @Test
    fun textLinkAndFavoriteSurviveReopen() = runBlocking {
        repository.addText(" 周末看展 100% ")
        repository.addText("https://example.com/exhibit")
        val timeline = repository.timeline.first()
        assertEquals(listOf("周末看展 100%", "https://example.com/exhibit"), timeline.map { it.content })
        repository.toggleFavorite(timeline.first().id)
        reopen()

        assertEquals("周末看展 100%", repository.search("%", favoritesOnly = true).first().single().content)
        assertEquals("https://example.com/exhibit", repository.search("EXHIBIT", favoritesOnly = false).first().single().content)
        assertEquals(listOf(timeline.last().id, timeline.first().id), repository.search("", false).first().map { it.id })
        repository.deleteRecord(timeline.first().id)
        assertTrue(repository.search("周末", false).first().isEmpty())
    }

    @Test
    fun upgradesVersionOneAndPersistsQuoteAfterReopen() = runBlocking {
        database.close()
        val schemaFile = File(checkNotNull(System.getProperty("yinu.schema.v1")))
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        context.openOrCreateDatabase("record-test.db", 0, null).use { legacy ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) {
                    legacy.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) legacy.execSQL(setup.getString(i))
            legacy.execSQL(
                "INSERT INTO messages (id, type, content, created_at, updated_at, is_favorite, is_deleted, sync_status) " +
                    "VALUES ('original', 'TEXT', '以前的记录', 1, 1, 1, 0, 'PENDING')",
            )
            legacy.execSQL(
                "INSERT INTO messages (id, type, content, created_at, updated_at, is_favorite, is_deleted, sync_status) " +
                    "VALUES ('old-deletion', 'TEXT', '旧版已经删除', 1, 1, 0, 1, 'PENDING')",
            )
            legacy.version = 1
        }
        reopen()
        val original = repository.timeline.first().single()
        assertTrue(repository.trash.first().isEmpty())
        assertEquals("以前的记录", original.content)
        assertTrue(original.isFavorite)
        assertFalse(original.isPinned)
        assertNull(original.quote)

        val quote = RecordQuote(original.id, "以前的记录")
        repository.addText("新的记录", quote)
        reopen()
        assertEquals(quote, repository.timeline.first().last().quote)
        repository.deleteRecord(original.id)
        assertEquals(quote, repository.timeline.first().single().quote)
    }

    @Test
    fun pinSurvivesReopenAndCanBeReplacedRemovedOrDeleted() = runBlocking {
        repository.addText("第一条")
        repository.addText("第二条")
        val (first, second) = repository.timeline.first()
        repository.setPinned(first.id, true)
        reopen()
        assertEquals(first.id, repository.search("", false).first().single { it.isPinned }.id)

        repository.setPinned("missing", true)
        assertEquals(first.id, repository.timeline.first().single { it.isPinned }.id)
        repository.setPinned(second.id, true)
        assertEquals(listOf(first.id, second.id), repository.timeline.first().map { it.id })
        assertEquals(second.id, repository.timeline.first().single { it.isPinned }.id)

        repository.setPinned(second.id, false)
        assertFalse(repository.timeline.first().any { it.isPinned })
        repository.setPinned(second.id, true)
        repository.deleteRecord(second.id)
        assertFalse(repository.timeline.first().single().isPinned)
    }

    @Test
    fun localBackupRestoresIntoAnotherDatabaseAndRejectsIncompleteArchives() = runBlocking<Unit> {
        repository.addText("要备份的文字")
        val original = repository.timeline.first().single()
        val sourceFile = File(context.cacheDir, "original.txt").apply { writeText("附件原文") }
        repository.importFiles(
            listOf(sources.add("original", sourceFile.name, "text/plain", sourceFile)),
            quote = RecordQuote(original.id, "要备份的文字"),
            sharedText = "附件说明",
        )
        val attachmentRecord = repository.timeline.first().last()
        repository.setPinned(attachmentRecord.id, true)
        repository.toggleFavorite(attachmentRecord.id)

        val archive = File(context.cacheDir, "backup.zip")
        val archiveUri = sources.add("backup", archive.name, "application/zip", archive)
        assertEquals(2, repository.exportLocalBackup(archiveUri))
        val deletion = repository.deleteRecords(setOf(original.id, attachmentRecord.id))
        assertEquals(RestoreResult(0, 2), repository.restoreLocalBackup(archiveUri))
        assertTrue(repository.discardTrash(attachmentRecord.id, deletion.deletedAt))
        assertEquals(RestoreResult(0, 2), repository.restoreLocalBackup(archiveUri))

        val broken = File(context.cacheDir, "incomplete.zip")
        ZipInputStream(archive.inputStream()).use { input ->
            ZipOutputStream(broken.outputStream()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.name == "backup.complete") break
                    output.putNextEntry(ZipEntry(entry.name))
                    input.copyTo(output)
                    output.closeEntry()
                }
            }
        }
        val brokenUri = sources.add("broken", broken.name, "application/zip", broken)
        val targetName = "restored-record-test.db"
        val targetDatabase = Room.databaseBuilder(context, YinuDatabase::class.java, targetName).build()
        try {
            val target = RecordRepository(context, targetDatabase)
            target.addText("目标设备已有记录")
            val existing = target.timeline.first().single()
            target.setPinned(existing.id, true)

            try {
                target.restoreLocalBackup(brokenUri)
                fail("An archive without the completion entry must fail")
            } catch (_: IllegalArgumentException) {
                // Validation occurs before any records are inserted.
            }
            assertEquals(existing.id, target.timeline.first().single().id)
            assertFalse(File(context.filesDir, "attachments").walkTopDown().any { it.isFile })

            try {
                target.inspectLocalBackup(brokenUri)
                fail("An incomplete archive cannot be confirmed")
            } catch (_: IllegalArgumentException) {
                assertEquals(existing.id, target.timeline.first().single().id)
            }
            val preview = target.inspectLocalBackup(archiveUri)
            assertEquals(2, preview.records)
            assertEquals(1, preview.attachments)
            assertEquals(1, preview.storedAttachments)
            assertEquals(2, preview.newRecords)
            assertEquals(existing.id, target.timeline.first().single().id)
            assertFalse(File(context.filesDir, "attachments").walkTopDown().any { it.isFile })
            assertEquals(RestoreResult(2, 0), target.restoreLocalBackup(archiveUri, preview))
            val restored = target.timeline.first()
            assertEquals(existing.id, restored.single { it.isPinned }.id)
            val recovered = restored.single { it.id == attachmentRecord.id }
            assertTrue(recovered.isFavorite)
            assertEquals(attachmentRecord.createdAt, recovered.createdAt)
            assertEquals(attachmentRecord.quote, recovered.quote)
            assertEquals("附件说明", recovered.content)
            assertEquals("附件原文", recovered.attachments.single().localFile?.readText())
            assertEquals(RestoreResult(0, 2), target.restoreLocalBackup(archiveUri))
            assertEquals(3, target.timeline.first().size)
        } finally {
            targetDatabase.close()
            context.deleteDatabase(targetName)
        }
    }

    @Test
    fun localBackupRepairsMissingCopiesWithoutReplacingExistingData() = runBlocking {
        val files = listOf("removed", "missing", "retained").map { name ->
            val file = File(context.cacheDir, "$name.txt").apply { writeText(name) }
            sources.add(name, file.name, "text/plain", file)
        }
        repository.importFiles(files, sharedText = "附件记录")
        val original = repository.timeline.first().single()
        val (removed, missing, retained) = original.attachments
        val archive = File(context.cacheDir, "repair.zip")
        val archiveUri = sources.add("repair", archive.name, "application/zip", archive)
        assertEquals(1, repository.exportLocalBackup(archiveUri))

        repository.removeLocalCopy(removed.id)
        assertTrue(checkNotNull(missing.localFile).delete())
        repository.setPinned(original.id, true)
        repository.toggleFavorite(original.id)

        val preview = repository.inspectLocalBackup(archiveUri)
        assertEquals(0, preview.newRecords)
        assertEquals(2, preview.repairableAttachments)
        assertFalse(checkNotNull(missing.localFile).isFile)
        assertEquals(RestoreResult(0, 1, 2), repository.restoreLocalBackup(archiveUri, preview))
        val repaired = repository.timeline.first().single()
        assertEquals(original.id, repaired.id)
        assertEquals(original.content, repaired.content)
        assertTrue(repaired.isPinned)
        assertTrue(repaired.isFavorite)
        assertEquals(original.attachments.map { it.id }, repaired.attachments.map { it.id })
        assertEquals(listOf("removed", "missing", "retained"), repaired.attachments.map { it.localFile?.readText() })
        assertEquals(retained.localFile, repaired.attachments.last().localFile)
        assertEquals(RestoreResult(0, 1), repository.restoreLocalBackup(archiveUri))
        assertEquals(repaired, repository.timeline.first().single())
    }

    @Test
    fun restoreRejectsBackupReplacedAfterInspection() = runBlocking<Unit> {
        repository.addText("原始记录", messageId = "original")
        val archive = File(context.cacheDir, "replaced.zip")
        val uri = sources.add("replaced", archive.name, "application/zip", archive)
        repository.exportLocalBackup(uri)
        val preview = repository.inspectLocalBackup(uri)
        repository.addText("后来添加", messageId = "another")
        repository.exportLocalBackup(uri)

        try {
            repository.restoreLocalBackup(uri, preview)
            fail("A changed archive must be checked again")
        } catch (_: BackupChangedException) {
            assertEquals(listOf("original", "another"), repository.timeline.first().map { it.id })
        }
    }

    @Test
    fun backupReportsFileProgressAndCancelsBeforeCommit() = runBlocking {
        val source = File(context.cacheDir, "progress.txt").apply { writeText("backup progress") }
        repository.importFiles(listOf(sources.add("progress", source.name, "text/plain", source)))
        val attachment = repository.timeline.first().single().attachments.single()
        val archive = File(context.cacheDir, "progress.zip")
        val archiveUri = sources.add("progress-archive", archive.name, "application/zip", archive)
        val events = mutableListOf<BackupProgress>()
        assertEquals(1, repository.exportLocalBackup(archiveUri, events::add))
        val complete = BackupProgress.Transferring(1, 1, source.length(), source.length())
        assertEquals(complete, events.filterIsInstance<BackupProgress.Transferring>().last())
        assertEquals(BackupProgress.Finishing, events.last())

        repository.removeLocalCopy(attachment.id)
        try {
            repository.restoreLocalBackup(archiveUri) { if (it == BackupProgress.Finishing) throw CancellationException() }
            fail("Cancellation before the merge must abort the restore")
        } catch (_: CancellationException) {
            assertNull(repository.timeline.first().single().attachments.single().localFile)
            assertFalse(File(context.filesDir, "attachments").walkTopDown().any { it.isFile })
        }
        assertEquals(RestoreResult(0, 1, 1), repository.restoreLocalBackup(archiveUri))

        val cancelled = File(context.cacheDir, "cancelled.zip")
        val cancelledUri = sources.add("cancelled-archive", cancelled.name, "application/zip", cancelled)
        try {
            repository.exportLocalBackup(cancelledUri) { if (it == complete) throw CancellationException() }
            fail("Cancellation before the ZIP is finished must abort the export")
        } catch (_: CancellationException) {
            assertEquals(0L, cancelled.length())
        }
    }

    @Test
    fun recentTimelineUsesStableOrderAndRankAndEditPreservesRecordIdentity() = runBlocking {
        val dao = database.records()
        for (index in 0..60) {
            dao.add(MessageEntity("row-$index", "TEXT", "content-$index", 100L), emptyList())
        }
        repository.setPinned("row-1", true)
        repository.toggleFavorite("row-1")
        val initial = repository.recentTimeline(48).first()
        assertEquals((13..60).map { "row-$it" }, initial.records.map { it.id })
        assertTrue(initial.hasOlder)
        assertEquals("row-1", repository.pinned.first()?.id)
        assertEquals(60, repository.timelineRank("row-1"))
        assertEquals(61, repository.storageUsage.first().records)
        assertFalse(repository.editContent("row-1", "  "))
        assertTrue(repository.editContent("row-1", " https://example.com/updated "))
        val edited = repository.search("example.com/updated", false).first().single()
        assertEquals("row-1", edited.id)
        assertEquals(100L, edited.createdAt)
        assertTrue(edited.isPinned && edited.isFavorite)
        assertEquals(60, repository.timelineRank(edited.id))
        repository.deleteRecord(edited.id)
        assertFalse(repository.editContent(edited.id, "cannot resurrect"))
        assertNull(repository.timelineRank(edited.id))
    }

    @Test
    fun batchDeletionRetainsFilesAcrossRestartAndRestoresWithoutReplacingActivePin() = runBlocking {
        val source = File(context.cacheDir, "batch-source.txt").apply { writeText("attachment") }
        repository.importFiles(listOf(sources.add("1", "first.txt", "text/plain", source)))
        repository.importFiles(listOf(sources.add("2", "second.txt", "text/plain", source)))
        repository.importFiles(listOf(sources.add("3", "keep.txt", "text/plain", source)))
        val (first, second, kept) = repository.timeline.first()
        val firstFile = checkNotNull(first.attachments.single().localFile)
        val secondFile = checkNotNull(second.attachments.single().localFile)
        val keptFile = checkNotNull(kept.attachments.single().localFile)
        repository.setPinned(first.id, true)

        val deletion = repository.deleteRecords(setOf(first.id, second.id))
        reopen()
        repository.cleanOrphans()

        assertEquals(kept.id, repository.timeline.first().single().id)
        assertEquals(kept.id, repository.search("", false).first().single().id)
        assertEquals(setOf(first.id, second.id), repository.trash.first().map { it.record.id }.toSet())
        assertTrue(firstFile.isFile)
        assertTrue(secondFile.isFile)
        assertTrue(keptFile.isFile)
        repository.setPinned(kept.id, true)
        assertEquals(2, repository.restoreTrash(deletion))
        assertEquals(0, repository.restoreTrash(deletion))
        assertTrue(repository.trash.first().isEmpty())
        assertEquals(listOf(first.id, second.id, kept.id), repository.timeline.first().map { it.id })
        assertEquals(kept.id, repository.pinned.first()?.id)
        assertEquals("attachment", repository.timeline.first().first().attachments.single().localFile?.readText())
    }

    @Test
    fun managedAttachmentsSortBySizeAndCanBeSavedWithoutChangingTheOriginal() = runBlocking {
        val small = File(context.cacheDir, "small.txt").apply { writeText("a") }
        val large = File(context.cacheDir, "large.txt").apply { writeText("完整附件数据") }
        repository.importFiles(listOf(sources.add("small", small.name, "text/plain", small)))
        repository.importFiles(listOf(sources.add("large", large.name, "text/plain", large)))
        val entries = repository.managedAttachments.first()
        assertEquals(listOf(large.name, small.name), entries.map { it.attachment.fileName })

        val entry = entries.first()
        val saved = File(context.cacheDir, "saved.txt")
        val target = sources.add("saved", saved.name, "text/plain", saved)
        assertTrue(repository.saveAttachment(entry.attachment.id, target))
        assertArrayEquals(large.readBytes(), saved.readBytes())
        assertArrayEquals(large.readBytes(), checkNotNull(entry.attachment.localFile).readBytes())

        val deletion = repository.deleteRecord(entry.recordId)
        assertEquals(listOf(small.name), repository.managedAttachments.first().map { it.attachment.fileName })
        assertFalse(repository.saveAttachment(entry.attachment.id, target))
        assertEquals(1, repository.restoreTrash(deletion))
        assertEquals(entry.attachment.id, repository.managedAttachments.first().first().attachment.id)
    }

    @Test
    fun expiredTrashClearsPayloadButRetainsTheDeletedId() = runBlocking {
        val source = File(context.cacheDir, "expired.txt").apply { writeText("expired") }
        repository.importFiles(listOf(sources.add("expired", source.name, "text/plain", source)))
        val record = repository.timeline.first().single()
        val local = checkNotNull(record.attachments.single().localFile)
        val deletedAt = System.currentTimeMillis() - (TrashRetentionDays + 1) * 86_400_000L
        assertEquals(listOf(record.id), database.records().moveToTrash(listOf(record.id), deletedAt))
        assertEquals(0, repository.restoreTrash(TrashDeletion(listOf(record.id), deletedAt)))
        assertTrue(local.isFile)

        repository.cleanOrphans()
        assertFalse(local.exists())
        assertTrue(repository.trash.first().isEmpty())
        assertEquals(listOf(record.id), database.records().existingMessageIds(listOf(record.id)))
    }

    @Test
    fun dateSearchUsesLocalMidnightBoundariesAndCombinesWithOtherFilters() = runBlocking {
        val date = LocalDate.of(2024, 3, 10)
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dao = database.records()
        dao.add(MessageEntity("before", "TEXT", "会议前一日", start - 1), emptyList())
        dao.add(MessageEntity("start", "TEXT", "早上的会议", start, isFavorite = true), emptyList())
        dao.add(MessageEntity("end", "TEXT", "晚上的会议", end - 1), emptyList())
        dao.add(MessageEntity("after", "TEXT", "次日的会议", end, isFavorite = true), emptyList())

        assertEquals(listOf("end", "start"), repository.search("", false, date = date).first().map { it.id })
        assertEquals(listOf("start"), repository.search("会议", true, date = date).first().map { it.id })
    }

    @Test
    fun copiesFilesBeforeCommitAndRetainsMetadataWhenRemovingCopy() = runBlocking {
        val bytes = ByteArray(2 * 1024 * 1024 + 17) { (it % 113).toByte() }
        val original = File(context.cacheDir, "source.txt").apply { writeBytes(bytes) }
        val image = File(context.cacheDir, "second.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val first = sources.add("1", "展览清单.txt", "text/plain", original)
        val second = sources.add("2", "图片.png", "image/png", image)
        repository.importFiles(listOf(first, second))
        assertTrue(original.delete())
        assertTrue(image.delete())
        reopen()

        val record = repository.search("展览清单", false).first().single()
        assertEquals(listOf("展览清单.txt", "图片.png"), record.attachments.map { it.fileName })
        val firstCopy = checkNotNull(record.attachments.first().localFile)
        val secondCopy = checkNotNull(record.attachments.last().localFile)
        assertEquals(bytes.size.toLong(), record.attachments.first().fileSize)
        assertArrayEquals(bytes, firstCopy.readBytes())

        repository.removeLocalCopy(record.attachments.first().id)
        val afterRemoval = repository.timeline.first().single().attachments.first()
        assertEquals("展览清单.txt", afterRemoval.fileName)
        assertNull(afterRemoval.localFile)
        assertFalse(firstCopy.exists())
        assertTrue(secondCopy.isFile)

        val deletion = repository.deleteRecord(record.id)
        assertTrue(repository.timeline.first().isEmpty())
        assertTrue(secondCopy.isFile)
        assertTrue(repository.discardTrash(record.id, deletion.deletedAt))
        assertFalse(secondCopy.exists())
    }

    @Test
    fun categoryFiltersKeywordAndFavoritesWithinTheMatchingAttachmentType() = runBlocking {
        val source = File(context.cacheDir, "category-fixture").apply { writeText("fixture") }
        val image = sources.add("1", "海边.JPG", "application/octet-stream", source)
        val document = sources.add("2", "行程报告.pdf", "application/pdf", source)
        val archive = sources.add("3", "行程压缩.zip", "application/zip", source)
        val installer = sources.add("4", "行程工具.APK", "application/zip", source)
        val video = sources.add("5", "行程片段", "video/mp4", source)
        val audio = sources.add("6", "行程录音.flac", "application/octet-stream", source)
        repository.importFiles(listOf(image, document))
        repository.importFiles(listOf(archive, installer))
        repository.importFiles(listOf(video, audio))
        val timeline = repository.timeline.first()

        assertEquals(timeline[0].id, repository.search("", false, SearchCategory.IMAGE).first().single().id)
        assertEquals(timeline[0].id, repository.search("报告", false, SearchCategory.DOCUMENT).first().single().id)
        assertTrue(repository.search("报告", false, SearchCategory.IMAGE).first().isEmpty())
        assertTrue(repository.search("压缩", false, SearchCategory.INSTALLER).first().isEmpty())
        assertEquals(timeline[1].id, repository.search("压缩", false, SearchCategory.ARCHIVE).first().single().id)
        assertEquals(timeline[1].id, repository.search("工具", false, SearchCategory.INSTALLER).first().single().id)
        assertEquals(timeline[2].id, repository.search("片段", false, SearchCategory.VIDEO).first().single().id)
        assertEquals(timeline[2].id, repository.search("录音", false, SearchCategory.AUDIO).first().single().id)
        assertTrue(repository.search("海边", true, SearchCategory.IMAGE).first().isEmpty())
        repository.toggleFavorite(timeline[0].id)
        assertEquals(timeline[0].id, repository.search("海边", true, SearchCategory.IMAGE).first().single().id)
    }

    @Test
    fun partialImportRollsBackAndOrphanCleanupPreservesReferencedFiles() = runBlocking {
        val source = File(context.cacheDir, "valid.txt").apply { writeText("offline") }
        val valid = sources.add("1", "valid.txt", "text/plain", source)
        val missing = sources.add("2", "missing.txt", "text/plain", File(context.cacheDir, "missing.txt"))
        try {
            repository.importFiles(listOf(valid, missing))
            fail("Expected the second document to fail")
        } catch (_: FileNotFoundException) {
            // The first file was already copied before the second source failed.
        }
        val root = File(context.filesDir, "attachments")
        assertTrue(root.walkTopDown().none { it.isFile })
        assertTrue(repository.timeline.first().isEmpty())

        repository.importFiles(listOf(valid))
        val retained = repository.timeline.first().single().attachments.single().localFile
        val orphan = File(root, "orphan/content").apply {
            parentFile!!.mkdirs()
            writeText("abandoned")
        }
        repository.cleanOrphans()
        assertFalse(orphan.exists())
        assertNotNull(retained)
        assertEquals("offline", retained!!.readText())
    }

    private fun reopen() {
        database.close()
        database = Room.databaseBuilder(context, YinuDatabase::class.java, "record-test.db").build()
        repository = RecordRepository(context, database)
    }
}

private class TestDocuments : ContentProvider() {
    private data class Document(val name: String, val type: String, val file: File)

    private val documents = mutableMapOf<String, Document>()

    fun add(id: String, name: String, type: String, file: File): Uri {
        documents[id] = Document(name, type, file)
        return Uri.parse("content://yinu.test/$id")
    }

    private fun document(uri: Uri) = documents.getValue(uri.lastPathSegment!!)
    override fun onCreate() = true
    override fun getType(uri: Uri) = document(uri).type
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(document(uri).file, ParcelFileDescriptor.parseMode(mode))

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
        addRow(arrayOf(document(uri).name))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
