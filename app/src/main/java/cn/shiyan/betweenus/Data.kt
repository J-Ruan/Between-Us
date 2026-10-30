package cn.shiyan.betweenus

import android.content.Context
import androidx.room.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow

fun uid(): String = UUID.randomUUID().toString()

@Entity
data class Conversation(@PrimaryKey val id: String = "default", val title: String = "我们的回忆")

@Entity
data class Participant(@PrimaryKey val id: String, val name: String, val avatar: String? = null)

@Entity(
    indices = [Index("assetId"), Index("date")],
    foreignKeys =
        [
            ForeignKey(
                entity = ScreenshotAsset::class,
                parentColumns = ["id"],
                childColumns = ["assetId"],
                onDelete = ForeignKey.SET_NULL,
            )
        ],
)
data class Message(
    @PrimaryKey val id: String = uid(),
    val assetId: String? = null,
    val text: String,
    val originalText: String = text,
    val sender: String = "待确认",
    val date: String? = null,
    val time: String? = null,
    val position: Long = System.currentTimeMillis(),
    val confirmed: Boolean = false,
    val favorite: Boolean = false,
    val version: Int = 1,
)

@Entity(indices = [Index(value = ["hash"], unique = true)])
data class ScreenshotAsset(
    @PrimaryKey val id: String = uid(),
    val path: String,
    val hash: String,
    val importedAt: Long = System.currentTimeMillis(),
)

@Entity
data class ImportTask(
    @PrimaryKey val id: String = uid(),
    val total: Int,
    val processed: Int = 0,
    val status: String = "处理中",
    val error: String? = null,
)

@Entity
data class MemoryEvent(
    @PrimaryKey val id: String = uid(),
    val title: String,
    val description: String,
    val date: String? = null,
    val messageIds: String,
    val verified: Boolean = false,
    val keywords: String = "",
)

@Entity
data class StoryChapter(
    @PrimaryKey val id: String = uid(),
    val title: String,
    val body: String,
    val position: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val generated: Boolean = false,
)

@Entity(
    primaryKeys = ["chapterId", "messageId"],
    foreignKeys =
        [
            ForeignKey(
                entity = StoryChapter::class,
                parentColumns = ["id"],
                childColumns = ["chapterId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = Message::class,
                parentColumns = ["id"],
                childColumns = ["messageId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("messageId")],
)
data class StoryMessageReference(val chapterId: String, val messageId: String)

@Entity(
    foreignKeys =
        [
            ForeignKey(
                entity = Message::class,
                parentColumns = ["id"],
                childColumns = ["messageId"],
                onDelete = ForeignKey.CASCADE,
            )
        ]
)
data class FavoriteQuote(@PrimaryKey val messageId: String, val note: String = "")

@Entity
data class Report(
    @PrimaryKey val id: String = uid(),
    val title: String,
    val startDate: String?,
    val endDate: String?,
    val body: String,
)

@Entity
data class ModelProfile(
    @PrimaryKey val id: String = "qwen",
    val name: String = "Qwen3-0.6B",
    val size: Long,
    val sha256: String,
    val status: String,
    val context: Int = 4096,
)

@Entity(indices = [Index("taskId"), Index("status")])
data class MemorySummary(
    @PrimaryKey val id: String = uid(),
    val taskId: String,
    val level: Int,
    val ordinal: Int,
    val startDate: String?,
    val endDate: String?,
    val text: String,
    val sourceIds: String,
    val parentIds: String = "",
    val keywords: String = "",
    val eventIds: String = "",
    val modelVersion: String = "Qwen3-0.6B/LiteRT-LM-0.16.1",
    val tokenizerVersion: String = "UTF8-byte-upper-bound-v1",
    val sourceDataVersion: Int = 1,
    val generatedAt: Long = System.currentTimeMillis(),
    val status: String = "待审核",
)

@Entity
data class MemoryProcessingTask(
    @PrimaryKey val id: String = uid(),
    val type: String = "摘要",
    val startDate: String? = null,
    val endDate: String? = null,
    val total: Int,
    val processed: Int = 0,
    val batch: Int = 0,
    val status: String = "排队",
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(primaryKeys = ["keyword", "summaryId"])
data class MemoryIndex(val keyword: String, val summaryId: String)

@Entity(primaryKeys = ["sourceMessageId", "summaryId"], indices = [Index("summaryId")])
data class SummaryDependency(
    val sourceMessageId: String,
    val summaryId: String,
    val dependencyType: String = "原文",
)

@Entity(primaryKeys = ["taskId", "messageId"])
data class TaskMessage(
    val taskId: String,
    val messageId: String,
    val version: Int,
    val position: Long,
)

@Entity
data class MessageRevision(
    @PrimaryKey val id: String = uid(),
    val messageId: String,
    val oldText: String,
    val changedAt: Long = System.currentTimeMillis(),
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM Message ORDER BY date, position") fun messages(): Flow<List<Message>>

    @Query(
        "SELECT * FROM Message WHERE (:query = '' OR text LIKE '%' || :query || '%' OR sender LIKE '%' || :query || '%') AND (:start IS NULL OR date >= :start) AND (:end IS NULL OR date <= :end) ORDER BY date,position LIMIT :limit OFFSET :offset"
    )
    suspend fun page(
        query: String = "",
        start: String? = null,
        end: String? = null,
        limit: Int = 200,
        offset: Int = 0,
    ): List<Message>

    @Query(
        "SELECT COUNT(*) FROM Message WHERE confirmed = 1 AND (:start IS NULL OR date >= :start) AND (:end IS NULL OR date <= :end)"
    )
    suspend fun count(start: String?, end: String?): Int

    @Query(
        "SELECT * FROM Message WHERE confirmed=1 AND (:start IS NULL OR date >= :start) AND (:end IS NULL OR date <= :end) ORDER BY date, position LIMIT :limit OFFSET :offset"
    )
    suspend fun confirmedPage(start: String?, end: String?, limit: Int, offset: Int): List<Message>

    @Query(
        "INSERT INTO TaskMessage(taskId,messageId,version,position) SELECT :taskId,id,version,position FROM Message WHERE confirmed=1 AND (:start IS NULL OR date >= :start) AND (:end IS NULL OR date <= :end)"
    )
    suspend fun snapshot(taskId: String, start: String?, end: String?)

    @Query(
        "SELECT Message.* FROM Message INNER JOIN TaskMessage ON Message.id=TaskMessage.messageId WHERE TaskMessage.taskId=:taskId ORDER BY Message.date, TaskMessage.position LIMIT :limit OFFSET :offset"
    )
    suspend fun taskPage(taskId: String, limit: Int, offset: Int): List<Message>

    @Query(
        "SELECT COUNT(*) FROM TaskMessage LEFT JOIN Message ON TaskMessage.messageId=Message.id WHERE TaskMessage.taskId=:taskId AND (Message.id IS NULL OR Message.version != TaskMessage.version)"
    )
    suspend fun changedSources(taskId: String): Int

    @Query("SELECT * FROM Message WHERE id IN (:ids) ORDER BY date,position")
    suspend fun byIds(ids: List<String>): List<Message>

    @Query("SELECT * FROM StoryChapter ORDER BY position") fun chapters(): Flow<List<StoryChapter>>

    @Query("SELECT * FROM MemoryEvent ORDER BY date DESC") fun events(): Flow<List<MemoryEvent>>

    @Query("SELECT * FROM MemorySummary ORDER BY generatedAt DESC")
    fun summaries(): Flow<List<MemorySummary>>

    @Query("SELECT * FROM MemoryProcessingTask ORDER BY updatedAt DESC")
    fun tasks(): Flow<List<MemoryProcessingTask>>

    @Query("SELECT * FROM ScreenshotAsset") suspend fun assets(): List<ScreenshotAsset>

    @Query("SELECT * FROM ScreenshotAsset WHERE hash=:hash LIMIT 1")
    suspend fun assetHash(hash: String): ScreenshotAsset?

    @Query("SELECT * FROM MemoryProcessingTask WHERE id=:id")
    suspend fun task(id: String): MemoryProcessingTask?

    @Query("SELECT * FROM MemoryEvent") suspend fun allEvents(): List<MemoryEvent>

    @Query("SELECT * FROM MemorySummary WHERE id=:id LIMIT 1")
    suspend fun summary(id: String): MemorySummary?

    @Query("SELECT * FROM MemorySummary WHERE taskId=:taskId AND level=:level ORDER BY ordinal")
    suspend fun level(taskId: String, level: Int): List<MemorySummary>

    @Query(
        "SELECT * FROM MemorySummary WHERE text LIKE '%' || :query || '%' OR keywords LIKE '%' || :query || '%' LIMIT 100"
    )
    suspend fun searchSummary(query: String): List<MemorySummary>

    @Query("SELECT * FROM ModelProfile LIMIT 1") fun model(): Flow<ModelProfile?>

    @Query("DELETE FROM ModelProfile") suspend fun clearModel()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(messages: List<Message>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(asset: ScreenshotAsset)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(task: ImportTask)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(chapter: StoryChapter)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(event: MemoryEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(summary: MemorySummary)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(task: MemoryProcessingTask)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(model: ModelProfile)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(revision: MessageRevision)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun refs(refs: List<StoryMessageReference>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun deps(refs: List<SummaryDependency>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun indexes(indexes: List<MemoryIndex>)

    @Query(
        "UPDATE Message SET text=:text,sender=:sender,date=:date,time=:time,confirmed=1,version=version+1 WHERE id=:id"
    )
    suspend fun edit(id: String, text: String, sender: String, date: String?, time: String?)

    @Query("UPDATE Message SET favorite=NOT favorite WHERE id=:id") suspend fun favorite(id: String)

    @Query(
        "UPDATE MemorySummary SET status='待更新' WHERE id IN (SELECT summaryId FROM SummaryDependency WHERE sourceMessageId=:id)"
    )
    suspend fun invalidate(id: String)

    @Query("UPDATE MemoryEvent SET verified=0 WHERE messageIds LIKE '%' || :id || '%'")
    suspend fun invalidateEvents(id: String)

    @Query("DELETE FROM Message WHERE id=:id") suspend fun deleteMessage(id: String)

    @Query("DELETE FROM StoryChapter WHERE id=:id") suspend fun deleteChapter(id: String)

    @Query("DELETE FROM MemoryEvent WHERE id=:id") suspend fun deleteEvent(id: String)
}

@Database(
    entities =
        [
            Conversation::class,
            Participant::class,
            Message::class,
            ScreenshotAsset::class,
            ImportTask::class,
            MemoryEvent::class,
            StoryChapter::class,
            StoryMessageReference::class,
            FavoriteQuote::class,
            Report::class,
            ModelProfile::class,
            MemorySummary::class,
            MemoryProcessingTask::class,
            MemoryIndex::class,
            SummaryDependency::class,
            MessageRevision::class,
            TaskMessage::class,
        ],
    version = 1,
    exportSchema = true,
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        fun open(context: Context): MemoryDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, MemoryDatabase::class.java, "shiyan.db")
                .openHelperFactory(
                    net.zetetic.database.sqlcipher.SupportOpenHelperFactory(DbKey.load(context))
                )
                .build()
        }
    }
}

class MemoryRepository(val db: MemoryDatabase) {
    val dao = db.dao()

    suspend fun edit(m: Message, text: String, sender: String, date: String?, time: String?) =
        db.withTransaction {
            dao.put(MessageRevision(messageId = m.id, oldText = m.text))
            dao.edit(m.id, text, sender, date, time)
            dao.invalidate(m.id)
            dao.invalidateEvents(m.id)
        }

    suspend fun delete(m: Message) =
        db.withTransaction {
            dao.invalidate(m.id)
            dao.invalidateEvents(m.id)
            dao.deleteMessage(m.id)
        }

    suspend fun chapter(chapter: StoryChapter, ids: List<String>) =
        db.withTransaction {
            require(dao.byIds(ids).size == ids.distinct().size) { "引用原文不存在" }
            dao.put(chapter)
            dao.refs(ids.distinct().map { StoryMessageReference(chapter.id, it) })
        }
}
