package cn.shiyan.betweenus

import android.content.pm.PackageManager
import android.graphics.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntegrationTest {
    private val app
        get() =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as ShiyanApp

    @Test
    fun bundledChineseOcr() = runBlocking {
        val image = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.WHITE)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 70f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
        canvas.drawText("今天一起听音乐", 80f, 150f, paint)
        val ocr = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = ocr.process(InputImage.fromBitmap(image, 0)).await()
            assertTrue(
                "Bundled OCR did not recognize Chinese: ${result.text}",
                result.text.contains("音乐"),
            )
            assertTrue(result.textBlocks.flatMap { it.lines }.all { it.boundingBox != null })
        } finally {
            ocr.close()
            image.recycle()
        }
    }

    @Test
    fun encryptedDatabaseBackupRestoreAndExports() = runBlocking {
        app.db.clearAllTables()
        val file = File(app.filesDir, "screenshots/${uid()}.img")
        file.parentFile!!.mkdirs()
        val image = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val asset =
            ScreenshotAsset(
                path = file.path,
                hash =
                    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                        "%02x".format(it)
                    },
            )
        val message = Message(assetId = asset.id, text = "今天听音乐", confirmed = true, favorite = true)
        val chapter = StoryChapter(title = "音乐", body = "今天听音乐 [${message.id}]")
        val dao = app.db.dao()
        dao.put(asset)
        dao.put(listOf(message))
        app.repository.chapter(chapter, listOf(message.id))
        val summary =
            MemorySummary(
                taskId = "task",
                level = 0,
                ordinal = 0,
                startDate = null,
                endDate = null,
                text = "提到了音乐",
                sourceIds = message.id,
            )
        dao.put(summary)
        dao.deps(listOf(SummaryDependency(message.id, summary.id)))
        val exporter = BackupExport(app)
        val archive = exporter.backup("testing-only-password".toCharArray())
        assertFalse(String(archive, Charsets.ISO_8859_1).contains(message.text))
        app.db.clearAllTables()
        file.delete()
        exporter.restore(archive, "testing-only-password".toCharArray())
        assertEquals(message, dao.byIds(listOf(message.id)).single())
        assertEquals(summary, dao.level("task", 0).single())
        assertTrue(File(dao.assets().single().path).isFile)
        val html = String(exporter.html(listOf(chapter), listOf(message)))
        assertTrue(html.contains("音乐"))
        assertFalse(html.contains("https://"))
        val pdf = exporter.pdf(listOf(chapter))
        assertTrue(String(pdf.take(4).toByteArray()).startsWith("%PDF"))
        val png = exporter.card("音乐", message.text, Color.WHITE)
        assertNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
        // SQLCipher file must not expose the plaintext SQLite header.
        val header =
            app.getDatabasePath("shiyan.db").inputStream().use {
                ByteArray(16).also { b -> it.read(b) }
            }
        assertFalse(String(header).startsWith("SQLite format 3"))
        app.db.clearAllTables()
        File(app.filesDir, "screenshots").deleteRecursively()
        Unit
    }

    @Test
    fun startsWithoutModelAndHasNoInternetPermission() {
        assertFalse(app.ai.available)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            app.checkSelfPermission("android.permission.INTERNET"),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }
}
