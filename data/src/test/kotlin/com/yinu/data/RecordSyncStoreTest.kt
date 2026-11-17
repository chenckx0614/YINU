package com.yinu.data

import androidx.room.Room
import com.yinu.database.AttachmentEntity
import com.yinu.database.MessageEntity
import com.yinu.database.YinuDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class RecordSyncStoreTest {
    private suspend fun withDatabase(block: suspend (YinuDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), YinuDatabase::class.java).build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun mergesDevicesAndKeepsLocalFilesAndDeletion() = runBlocking {
        withDatabase { first ->
            withDatabase { second ->
                val a = RecordSyncStore(first)
                val b = RecordSyncStore(second)
                val firstDao = first.records()
                val secondDao = second.records()
                firstDao.add(MessageEntity("a", "TEXT", "第一台的记录", 1),
                    listOf(AttachmentEntity("asset", "a", "文件.txt", "text/plain", 64,
                        localPath = "local/a", position = 0)))
                secondDao.add(MessageEntity("b", "TEXT", "第二台离线的记录", 2), emptyList())
                val original = a.pending(20)
                val secondary = b.pending(20)
                assertFalse(original.single().payload.toString().contains("local/a"))
                a.acknowledge(original)
                b.acknowledge(secondary)
                a.apply(secondary)
                b.apply(original)

                assertEquals("第二台离线的记录", firstDao.activeRecord("b")?.message?.content)
                assertNull(secondDao.activeRecord("a")?.attachments?.single()?.localPath)
                secondDao.setFavoriteGroup("a", "已收藏", 3)
                val favorite = b.pending(20)
                b.acknowledge(favorite)
                a.apply(favorite)
                assertEquals("已收藏", firstDao.activeRecord("a")?.message?.favoriteGroup)
                assertEquals("local/a", firstDao.activeRecord("a")?.attachments?.single()?.localPath)

                firstDao.moveToTrash(listOf("a"), 5)
                val deleted = a.pending(20)
                assertFalse(deleted.single().payload.has("content"))
                a.acknowledge(deleted)
                secondDao.editContent("a", "较旧的离线编辑", "TEXT", 4)
                b.apply(deleted)
                assertNull(secondDao.activeRecord("a"))
                assertTrue(b.pending(20).isEmpty())
                assertEquals("较旧的离线编辑", secondDao.syncRecord("a")?.message?.content)

                firstDao.restoreTrash(listOf("a"), 5, 6)
                val restored = a.pending(20)
                b.apply(restored)
                assertEquals("第一台的记录", secondDao.activeRecord("a")?.message?.content)
            }
        }
    }

    @Test
    fun acknowledgingAnInFlightVersionKeepsNewEditPending() = runBlocking {
        withDatabase { database ->
            val dao = database.records()
            val sync = RecordSyncStore(database)
            dao.add(MessageEntity("id", "TEXT", "发送中的版本", 1), emptyList())
            val sent = sync.pending(20)
            dao.editContent("id", "发送期间修改的内容", "TEXT", 1)
            sync.acknowledge(sent)
            sync.apply(sent)
            assertEquals("发送期间修改的内容", sync.pending(20).single().payload.getString("content"))
        }
    }
}
