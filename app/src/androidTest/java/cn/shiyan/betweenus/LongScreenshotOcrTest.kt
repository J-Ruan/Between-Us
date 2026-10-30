package cn.shiyan.betweenus

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shiyan.core.ChatParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic regression fixtures, not private chats and not product screenshots. */
@RunWith(AndroidJUnit4::class)
class LongScreenshotOcrTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val phrases =
        listOf("今天一起听音乐", "明天我们去公园", "记得带上雨伞", "谢谢你一直陪着我", "周末一起去看电影", "早点休息晚安", "下次见面一起吃饭")
    private val baselines = listOf(150, 1545, 3100, 6000, 9000, 12000, 15900)

    private fun fixture(dark: Boolean = false, height: Int = 16000): File {
        val image = Bitmap.createBitmap(1080, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(if (dark) Color.rgb(20, 20, 20) else Color.rgb(237, 237, 237))
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) Color.WHITE else Color.rgb(25, 25, 25)
                textSize = 32f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
        val bubble = Paint().apply { color = if (dark) Color.rgb(48, 48, 48) else Color.WHITE }
        phrases
            .zip(if (height == 16000) baselines else phrases.indices.map { 150 + it * 280 })
            .forEachIndexed { i, (value, y) ->
                val x = if (i % 2 == 0) 90f else 590f
                canvas.drawRoundRect(
                    x - 20,
                    y - 44f,
                    x + text.measureText(value) + 20,
                    y + 18f,
                    12f,
                    12f,
                    bubble,
                )
                canvas.drawText(value, x, y.toFloat(), text)
            }
        val file = File(context.cacheDir, "long-ocr-${uid()}.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        return file
    }

    @Test
    fun longSimplifiedScreenshotRetainsChineseAndBoundaryMessage() = runBlocking {
        val file = fixture()
        try {
            val result =
                ScreenshotOcr(simplifyChinese = true).recognize(file) { segment, total ->
                    println("Synthetic fixture OCR segment=$segment/$total")
                }
            val normalized = result.lines.map { ChatParser.normalize(it.text) }
            println("Synthetic fixture native OCR: ${result.lines.map { it.originalText }}")
            for (phrase in phrases) assertTrue(
                "Long screenshot phrase missing: $phrase; OCR: $normalized",
                normalized.any { it.contains(phrase) },
            )
            assertEquals(1080, result.width)
            assertEquals(16000, result.height)
            assertTrue(result.lines.filter { it.text.contains("晚安") }.all { it.top > 11500 })
            assertEquals(1, result.lines.count { it.text.contains("明天我们去公园") })
            // Reproduce the previous path's destructive resize on the SAME image.
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            val old = BitmapFactory.decodeFile(file.path, options)
            val recognizer =
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
                val text = recognizer.process(InputImage.fromBitmap(old, 0)).await().text
                val oldHits =
                    phrases.count {
                        ChineseScript.display(ChatParser.normalize(text), true).contains(it)
                    }
                assertTrue(
                    "Regression fixture did not expose original downsampling bug",
                    oldHits < phrases.size,
                )
                println(
                    "Long OCR regression: tiled=${phrases.size}/${phrases.size}; old longest-edge downsample=$oldHits/${phrases.size}"
                )
            } finally {
                recognizer.close()
                old.recycle()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun darkSimplifiedLongScreenshotUsesRealBundledRecognizer() = runBlocking {
        val file = fixture(true)
        try {
            val text =
                ScreenshotOcr(simplifyChinese = true).recognize(file).lines.joinToString("") {
                    ChatParser.normalize(it.text)
                }
            for (phrase in phrases) assertTrue(
                "Dark long screenshot phrase missing: $phrase",
                text.contains(phrase),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun transparentImageCompositedOnWhite() = runBlocking {
        val image = Bitmap.createBitmap(800, 240, Bitmap.Config.ARGB_8888)
        Canvas(image)
            .drawText(
                "今天一起听音乐",
                40f,
                120f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 50f
                },
            )
        val file = File(context.cacheDir, "transparent-${uid()}.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        try {
            assertTrue(ScreenshotOcr().recognize(file).lines.any { it.text.contains("音乐") })
        } finally {
            file.delete()
        }
    }

    @Test
    fun recognizeAgainPreservesUserMessagesAndCreatesDrafts() = runBlocking {
        val app = context.applicationContext as ShiyanApp
        app.db.clearAllTables()
        val image = Bitmap.createBitmap(900, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(
            "今天一起听音乐",
            40f,
            120f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 50f
            },
        )
        val file = File(context.cacheDir, "rerecognize-${uid()}.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        try {
            val asset = ScreenshotAsset(path = file.path, hash = uid())
            val corrected = Message(assetId = asset.id, text = "用户校对后保存的内容", confirmed = true)
            val oldDraft = Message(assetId = asset.id, text = "旧的错误识别结果", confirmed = false)
            val dao = app.db.dao()
            dao.put(asset)
            dao.put(listOf(corrected, oldDraft))
            ImportOcr(app, dao).recognizeAgain(asset.id) {}
            assertEquals(corrected, dao.byIds(listOf(corrected.id)).single())
            assertEquals(oldDraft, dao.byIds(listOf(oldDraft.id)).single())
            assertTrue(
                dao.page().any {
                    it.id != corrected.id &&
                        it.id != oldDraft.id &&
                        !it.confirmed &&
                        it.text.contains("音乐")
                }
            )
        } finally {
            app.db.clearAllTables()
            file.delete()
        }
        Unit
    }

    @Test
    fun fineSimplifiedChineseGlyphsOnChatBubbles() = runBlocking {
        val file = fixture(height = 2200)
        try {
            val lines =
                ScreenshotOcr(simplifyChinese = true).recognize(file).lines.map {
                    ChatParser.normalize(it.text)
                }
            for (phrase in phrases) assertTrue(
                "Fine Chinese glyph mismatch: $phrase; OCR: $lines",
                lines.any { it.contains(phrase) },
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun explicitSimplificationNeverChangesRawText() {
        val raw = "謝謝你陪我聽音樂"
        assertEquals(raw, ChineseScript.display(raw, false))
        assertEquals("谢谢你陪我听音乐", ChineseScript.display(raw, true))
        val parsed =
            ChatParser.parse(
                    listOf(
                        cn.shiyan.core.OcrLine(
                            ChineseScript.display(raw, true),
                            10,
                            20,
                            600,
                            70,
                            originalText = raw,
                        )
                    ),
                    1000,
                )
                .single()
        assertEquals(raw, parsed.originalText)
        assertEquals("谢谢你陪我听音乐", parsed.text)
        // Script conversion cannot repair a different character, e.g. 天 misread as 夭.
        assertEquals("明夭", ChineseScript.display("明夭", true))
    }

    @Test
    fun blankScreenshotContainsNoInventedText() = runBlocking {
        val image = Bitmap.createBitmap(1080, 2000, Bitmap.Config.ARGB_8888)
        Canvas(image).drawColor(Color.WHITE)
        val file = File(context.cacheDir, "blank-${uid()}.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        try {
            assertTrue(ScreenshotOcr().recognize(file).lines.isEmpty())
        } finally {
            file.delete()
        }
    }
}
