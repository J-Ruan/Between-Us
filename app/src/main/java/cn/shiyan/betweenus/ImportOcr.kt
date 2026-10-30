package cn.shiyan.betweenus

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import cn.shiyan.core.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class ImportOcr(private val context: Context, private val dao: MemoryDao) {
    suspend fun import(uris: List<Uri>, progress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val task = ImportTask(total = uris.size)
            dao.put(task)
            val recognizer =
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
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
                            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.path, opt)
                            require(opt.outWidth > 0 && opt.outHeight > 0) { "无法解码图片" }
                            opt.inJustDecodeBounds = false
                            opt.inSampleSize = 1
                            while (maxOf(opt.outWidth, opt.outHeight) / opt.inSampleSize > 3000) opt
                                .inSampleSize *= 2
                            val bitmap = BitmapFactory.decodeFile(file.path, opt) ?: error("无法读取图片")
                            val rotation =
                                when (
                                    ExifInterface(file)
                                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                                ) {
                                    6 -> 90
                                    3 -> 180
                                    8 -> 270
                                    else -> 0
                                }
                            val result =
                                try {
                                    recognizer
                                        .process(InputImage.fromBitmap(bitmap, rotation))
                                        .await()
                                } finally {
                                    bitmap.recycle()
                                }
                            val width =
                                if (rotation == 90 || rotation == 270)
                                    opt.outHeight / opt.inSampleSize
                                else opt.outWidth / opt.inSampleSize
                            val lines =
                                result.textBlocks.flatMap { block ->
                                    block.lines.mapNotNull { line ->
                                        line.boundingBox?.let {
                                            OcrLine(line.text, it.left, it.top, it.right, it.bottom)
                                        }
                                    }
                                }
                            val parsed = ChatParser.parse(lines, width)
                            require(parsed.isNotEmpty()) { "没有识别到文字" }
                            val overlap = ChatParser.overlap(previous, parsed)
                            dao.put(ScreenshotAsset(id, file.path, hash))
                            dao.put(
                                parsed.mapIndexed { i, p ->
                                    Message(
                                        assetId = id,
                                        text = p.text,
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
            } finally {
                recognizer.close()
            }
        }
}
