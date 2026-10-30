package cn.shiyan.betweenus

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.room.withTransaction
import cn.shiyan.core.*
import java.io.*
import java.util.zip.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.*

class BackupExport(private val app: ShiyanApp) {
    private val tables =
        listOf(
            "Conversation",
            "Participant",
            "ScreenshotAsset",
            "Message",
            "ImportTask",
            "MemoryEvent",
            "StoryChapter",
            "StoryMessageReference",
            "FavoriteQuote",
            "Report",
            "ModelProfile",
            "MemorySummary",
            "MemoryProcessingTask",
            "MemoryIndex",
            "SummaryDependency",
            "MessageRevision",
            "TaskMessage",
        )

    suspend fun backup(password: CharArray): ByteArray =
        withContext(Dispatchers.IO) {
            val json = JSONObject().put("version", 1)
            val data = JSONObject()
            app.db.withTransaction {
                for (table in tables.filter { it != "ModelProfile" }) {
                    val rows = JSONArray()
                    app.db.query("SELECT * FROM `$table`", null).use { c ->
                        while (c.moveToNext()) {
                            val row = JSONObject()
                            for (i in 0 until c.columnCount) row.put(
                                c.getColumnName(i),
                                if (c.isNull(i)) JSONObject.NULL
                                else
                                    when (c.getType(i)) {
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                        else -> c.getString(i)
                                    },
                            )
                            rows.put(row)
                        }
                    }
                    data.put(table, rows)
                }
            }
            json.put("tables", data)
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(json.toString().toByteArray())
                zip.closeEntry()
                for (asset in app.db.dao().assets()) {
                    val f = File(asset.path)
                    require(f.isFile) { "截图资源缺失，无法创建完整备份" }
                    require(out.size() + f.length() < 128_000_000) { "测试版备份限制为 128 MB，请缩小数据量" }
                    zip.putNextEntry(ZipEntry("screenshots/${asset.id}.img"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            require(out.size() < 128_000_000) { "备份过大" }
            EncryptedBackup.encrypt(out.toByteArray(), password)
        }

    suspend fun restore(bytes: ByteArray, password: CharArray) =
        withContext(Dispatchers.IO) {
            require(bytes.size <= 128_000_100) { "备份过大" }
            val plain = EncryptedBackup.decrypt(bytes, password)
            val staging = File(app.cacheDir, "restore-${uid()}").apply { mkdirs() }
            try {
                var size = 0L
                var entries = 0
                ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= 10000) { "备份条目过多" }
                        require(
                            entry.name == "data.json" ||
                                Regex("screenshots/[a-fA-F0-9-]+\\.img").matches(entry.name)
                        ) {
                            "备份路径无效"
                        }
                        val file = File(staging, entry.name)
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { out ->
                            val b = ByteArray(65536)
                            while (true) {
                                val n = zip.read(b)
                                if (n < 0) break
                                size += n
                                require(size <= 256_000_000) { "解压后备份过大" }
                                out.write(b, 0, n)
                            }
                        }
                    }
                }
                val json = JSONObject(File(staging, "data.json").readText())
                require(json.getInt("version") == 1) { "不支持此备份版本" }
                val data = json.getJSONObject("tables")
                // Require an empty library: never silently overwrite existing private memories.
                require(app.db.dao().page(limit = 1).isEmpty() && app.db.dao().assets().isEmpty()) {
                    "请先备份并清空当前数据，再恢复；恢复不会自动覆盖现有回忆"
                }
                val target = File(app.filesDir, "screenshots").apply { mkdirs() }
                val copied = mutableListOf<File>()
                try {
                    app.db.withTransaction {
                        for (table in tables.filter { it != "ModelProfile" }) {
                            val rows = data.getJSONArray(table)
                            for (i in 0 until rows.length()) {
                                val row = rows.getJSONObject(i)
                                if (table == "ScreenshotAsset") {
                                    val id = row.getString("id")
                                    require(id.matches(Regex("[a-fA-F0-9-]+")))
                                    val source = File(staging, "screenshots/$id.img")
                                    require(source.isFile) { "备份缺失截图" }
                                    val file = File(target, "$id.img")
                                    require(!file.exists())
                                    source.copyTo(file)
                                    copied += file
                                    row.put("path", file.path)
                                }
                                if (
                                    table == "MemoryProcessingTask" &&
                                        row.getString("status") in listOf("处理中", "排队")
                                )
                                    row.put("status", "已暂停")
                                val columns = mutableListOf<String>()
                                app.db.query("PRAGMA table_info(`$table`)", null).use { c ->
                                    while (c.moveToNext()) columns +=
                                        c.getString(c.getColumnIndexOrThrow("name"))
                                }
                                require(row.keys().asSequence().toSet() == columns.toSet()) {
                                    "备份表结构不匹配"
                                }
                                val values =
                                    columns
                                        .map { if (row.isNull(it)) null else row.get(it) }
                                        .toTypedArray()
                                app.db.openHelper.writableDatabase.execSQL(
                                    "INSERT INTO `$table` (${columns.joinToString(","){"`$it`"}}) VALUES (${columns.joinToString(","){"?"}})",
                                    values,
                                )
                            }
                        }
                    }
                } catch (t: Throwable) {
                    copied.forEach { it.delete() }
                    throw t
                }
            } finally {
                staging.deleteRecursively()
            }
        }

    fun html(chapters: List<StoryChapter>, messages: List<Message>): ByteArray {
        val body =
            chapters.joinToString("") {
                "<section><h2>${htmlEscape(it.title)}</h2><p>${htmlEscape(it.body).replace("\n","<br>")}</p></section>"
            }
        val raw =
            messages
                .filter { it.favorite }
                .joinToString("") {
                    "<blockquote>${htmlEscape(it.sender)}：${htmlEscape(it.text)}</blockquote>"
                }
        return "<!doctype html><html lang=\"zh\"><meta charset=\"utf-8\"><title>拾言 · 我们的故事</title><style>body{max-width:46em;margin:3em auto;padding:1.5em;background:#fcf7ef;color:#433c38;font:18px/1.9 sans-serif}h1,h2{font-weight:500}section{break-before:page}p{overflow-wrap:anywhere}</style><h1>拾言 · 我们的故事</h1><p>把聊天记录，写成我们的故事。</p>$body$raw</html>"
            .toByteArray()
    }

    fun pdf(chapters: List<StoryChapter>): ByteArray {
        val doc = PdfDocument()
        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 16f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
        var number = 1
        fun pages(text: String) {
            val layout =
                StaticLayout.Builder.obtain(text, 0, text.length, paint, 500)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(6f, 1f)
                    .build()
            var start = 0
            while (start < layout.lineCount) {
                val top = layout.getLineTop(start)
                var end = start
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= 720) end++
                if (end == start) end++
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, number++).create())
                page.canvas.save()
                page.canvas.clipRect(45, 55, 550, 775)
                page.canvas.translate(45f, 55f - top)
                layout.draw(page.canvas)
                page.canvas.restore()
                page.canvas.drawText("拾言 · ${number-1}", 45f, 810f, paint)
                doc.finishPage(page)
                start = end
            }
        }
        pages("拾言\n\n把聊天记录，写成我们的故事。\n\n目录\n" + chapters.joinToString("\n") { it.title })
        chapters.forEach { pages(it.title + "\n\n" + it.body) }
        return ByteArrayOutputStream()
            .also {
                doc.writeTo(it)
                doc.close()
            }
            .toByteArray()
    }

    fun card(title: String, text: String, background: Int): ByteArray {
        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    if (Color.luminance(background) < 0.4f) Color.WHITE else Color.rgb(55, 48, 46)
                textSize = 36f
            }
        val value = "$title\n\n$text\n\n拾言 · Between Us"
        val layout =
            StaticLayout.Builder.obtain(value, 0, value.length, paint, 900)
                .setLineSpacing(12f, 1f)
                .build()
        require(layout.height < 12000) { "卡片过长，请减少内容" }
        val bitmap = Bitmap.createBitmap(1080, layout.height + 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        canvas.translate(90f, 90f)
        layout.draw(canvas)
        return ByteArrayOutputStream()
            .also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                bitmap.recycle()
            }
            .toByteArray()
    }
}
