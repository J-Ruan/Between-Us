package cn.shiyan.betweenus

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: MemoryDatabase

    @Before
    fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    MemoryDatabase::class.java,
                )
                .build()
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun crudReferenceAndInvalidation() = runBlocking {
        val dao = db.dao()
        val repo = MemoryRepository(db)
        val message = Message(text = "明天一起听音乐", confirmed = true)
        dao.put(listOf(message))
        assertEquals(message, dao.byIds(listOf(message.id)).single())
        val summary =
            MemorySummary(
                taskId = "task",
                level = 0,
                ordinal = 0,
                startDate = null,
                endDate = null,
                text = "提到音乐",
                sourceIds = message.id,
            )
        dao.put(summary)
        dao.deps(listOf(SummaryDependency(message.id, summary.id)))
        val chapter = StoryChapter(title = "音乐", body = "明天一起听音乐 [${message.id}]")
        repo.chapter(chapter, listOf(message.id))
        repo.edit(message, "明天听音乐", "我", null, null)
        assertEquals("待更新", dao.level("task", 0).single().status)
        assertEquals("明天一起听音乐", dao.byIds(listOf(message.id)).single().originalText)
        repo.delete(message)
        assertTrue(dao.byIds(listOf(message.id)).isEmpty())
        db.query("SELECT count(*) FROM StoryMessageReference", null).use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun checkpointSurvivesReload() = runBlocking {
        val task = MemoryProcessingTask(total = 10000, processed = 40, batch = 1, status = "已暂停")
        db.dao().put(task)
        assertEquals(40, db.dao().task(task.id)!!.processed)
        val batch =
            MemorySummary(
                id = "stable-batch-id",
                taskId = task.id,
                level = 0,
                ordinal = 0,
                startDate = null,
                endDate = null,
                text = "真实摘要测试夹具",
                sourceIds = "id",
            )
        db.dao().put(batch)
        db.dao().put(batch)
        assertEquals(1, db.dao().level(task.id, 0).size)
    }

    @Test
    fun tenThousandPersistAndPageWithoutLoss() = runBlocking {
        db.dao()
            .put(
                (0 until 10000).map {
                    Message(id = "m$it", text = "聊天$it", confirmed = true, position = it.toLong())
                }
            )
        val ids = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val page = db.dao().confirmedPage(null, null, 40, offset)
            if (page.isEmpty()) break
            ids += page.map { it.id }
            offset += page.size
        }
        assertEquals(10000, ids.size)
    }
}
