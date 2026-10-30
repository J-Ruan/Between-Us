package cn.shiyan.betweenus

import android.content.Context
import android.net.Uri
import cn.shiyan.core.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*

class ImportOcr(private val context: Context, private val dao: MemoryDao) {
    /** Existing messages remain intact; the new result is a separate, unconfirmed draft. */
    suspend fun recognizeAgain(assetId: String, progress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val asset = dao.assets().firstOrNull { it.id == assetId } ?: error("原图不存在")
            val result =
                ScreenshotOcr(
                        simplifyChinese =
                            context
                                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                                .getBoolean("ocrSimplified", false)
                    )
                    .recognize(File(asset.path)) { segment, total ->
                        progress("重新识别原图 · 分段 $segment/$total")
                    }
            val parsed = ChatParser.parse(result.lines, result.width)
            require(parsed.isNotEmpty()) { "没有识别到文字，请检查原图是否清晰" }
            val now = System.currentTimeMillis()
            dao.put(
                parsed.mapIndexed { index, p ->
                    Message(
                        assetId = asset.id,
                        text = p.text,
                        originalText = p.originalText,
                        sender = p.side,
                        date = p.date,
                        time = p.time,
                        position = now + index,
                    )
                }
            )
            progress("重新识别完成：已添加 ${parsed.size} 条待校对消息。原有消息已保留，请核对后删除旧的错误结果。")
        }

    suspend fun import(uris: List<Uri>, progress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val task = ImportTask(total = uris.size)
            dao.put(task)
            var previous = emptyList<Parsed>()
            val errors = mutableListOf<String>()
            try {
                for ((index, uri) in uris.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    progress("正在识别 ${index+1}/${uris.size}")
                    try {
                        val id = uid()
                        val file = File(context.filesDir, "screenshots/$id.img")
                        file.parentFile!!.mkdirs()
                        try {
                            context.contentResolver.openInputStream(uri)!!.use { input ->
                                file.outputStream().use { output ->
                                    val b = ByteArray(65536)
                                    var size = 0L
                                    while (true) {
                                        val n = input.read(b)
                                        if (n < 0) break
                                        size += n
                                        require(size < 50_000_000) { "图片超过 50 MB" }
                                        output.write(b, 0, n)
                                    }
                                }
                            }
                            val hash =
                                file.inputStream().use { input ->
                                    val md = MessageDigest.getInstance("SHA-256")
                                    val b = ByteArray(65536)
                                    while (true) {
                                        val n = input.read(b)
                                        if (n < 0) break
                                        md.update(b, 0, n)
                                    }
                                    md.digest().joinToString("") { "%02x".format(it) }
                                }
                            if (dao.assetHash(hash) != null) {
                                file.delete()
                                continue
                            }
                            val result =
                                ScreenshotOcr(
                                        simplifyChinese =
                                            context
                                                .getSharedPreferences(
                                                    "settings",
                                                    Context.MODE_PRIVATE,
                                                )
                                                .getBoolean("ocrSimplified", false)
                                    )
                                    .recognize(file) { segment, total ->
                                        progress(
                                            "正在识别第 ${index+1}/${uris.size} 张 · 分段 $segment/$total"
                                        )
                                    }
                            val parsed = ChatParser.parse(result.lines, result.width)
                            require(parsed.isNotEmpty()) { "没有识别到文字" }
                            val overlap = ChatParser.overlap(previous, parsed)
                            dao.put(ScreenshotAsset(id, file.path, hash))
                            dao.put(
                                parsed.mapIndexed { i, p ->
                                    Message(
                                        assetId = id,
                                        text = p.text,
                                        originalText = p.originalText,
                                        sender = p.side,
                                        date = p.date,
                                        time = p.time,
                                        position = System.currentTimeMillis() + i,
                                    )
                                }
                            )
                            previous = parsed
                            if (overlap > 0) progress("检测到 $overlap 条可能重叠的消息，已保留供人工校对")
                        } catch (t: Throwable) {
                            file.delete()
                            throw t
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        errors += "第 ${index+1} 张：${e.message}"
                    }
                    dao.put(task.copy(processed = index + 1))
                }
                dao.put(
                    task.copy(
                        processed = uris.size,
                        status = if (errors.isEmpty()) "已完成" else "部分失败",
                        error = errors.joinToString("\n"),
                    )
                )
                progress(if (errors.isEmpty()) "导入完成，请逐条校对后确认" else errors.joinToString("\n"))
            } catch (c: CancellationException) {
                dao.put(task.copy(status = "已取消"))
                throw c
            }
        }
}
