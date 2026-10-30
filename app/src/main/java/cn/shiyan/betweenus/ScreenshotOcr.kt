package cn.shiyan.betweenus

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import cn.shiyan.core.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Tiled recognition keeps Chinese strokes readable even in very tall scrolling screenshots. */
class ScreenshotOcr(private val simplifyChinese: Boolean = false) {
    data class Result(val lines: List<OcrLine>, val width: Int, val height: Int)

    @Suppress(
        "DEPRECATION"
    ) // Region decoding supports API 26 and never allocates the full long image.
    suspend fun recognize(file: File, progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码图片，请选择原始 PNG、JPG 或 WebP 截图" }
        val orientation =
            ExifInterface(file)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val (width, height) =
            OcrGeometry.uprightSize(bounds.outWidth, bounds.outHeight, orientation)
        val tiles = OcrGeometry.tiles(width, height)
        val decoder =
            BitmapRegionDecoder.newInstance(file.path, false) ?: error("此图片无法分段解码，请另存为 PNG 或 JPG")
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val detections = mutableListOf<OcrLine>()
        try {
            for ((index, tile) in tiles.withIndex()) {
                currentCoroutineContext().ensureActive()
                progress(index + 1, tiles.size)
                val raw =
                    OcrGeometry.originalRect(
                        tile.crop,
                        bounds.outWidth,
                        bounds.outHeight,
                        orientation,
                    )
                val decoded =
                    decoder.decodeRegion(
                        Rect(raw.left, raw.top, raw.right, raw.bottom),
                        BitmapFactory.Options().apply {
                            inSampleSize = tile.sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inScaled = false
                        },
                    ) ?: error("读取截图第 ${index+1} 段失败")
                var upright: Bitmap? = null
                var opaque: Bitmap? = null
                var enlarged: Bitmap? = null
                try {
                    upright = orient(decoded, orientation)
                    if (isUniform(upright)) continue
                    // Transparent screenshots must be composited, not treated as black-background
                    // data.
                    val baseInput = prepare(upright).also { opaque = it }
                    // Short, near-square tiles avoid tiny glyphs in the layout detector.
                    // Enlarge regular phone screenshots using interpolation, not text substitution.
                    val input =
                        if (tile.sample == 1 && baseInput.width <= 1440) {
                            Bitmap.createScaledBitmap(
                                    baseInput,
                                    baseInput.width * 2,
                                    baseInput.height * 2,
                                    true,
                                )
                                .also { enlarged = it }
                        } else baseInput
                    val pending = recognizer.process(InputImage.fromBitmap(input, 0))
                    // ML Kit Tasks cannot be cancelled. Keep their bitmap alive until native OCR
                    // finishes, even if the surrounding import coroutine has been cancelled.
                    val result = withContext(NonCancellable) { pending.await() }
                    currentCoroutineContext().ensureActive()
                    val scaleX = tile.crop.width.toDouble() / input.width
                    val scaleY = tile.crop.height.toDouble() / input.height
                    for (block in result.textBlocks) for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val refined = ChineseScript.display(line.text, simplifyChinese)
                        val restored =
                            OcrLine(
                                refined,
                                tile.crop.left + (box.left * scaleX).toInt(),
                                tile.crop.top + (box.top * scaleY).toInt(),
                                tile.crop.left + (box.right * scaleX).toInt(),
                                tile.crop.top + (box.bottom * scaleY).toInt(),
                                line.text,
                            )
                        if (OcrGeometry.owns(tile, restored)) detections += restored
                    }
                } finally {
                    enlarged?.recycle()
                    opaque?.recycle()
                    if (upright != null && upright !== decoded) upright.recycle()
                    decoded.recycle()
                }
            }
        } finally {
            recognizer.close()
            decoder.recycle()
        }
        return Result(OcrGeometry.mergeDetections(detections), width, height)
    }

    /** Exact equality, not sampling: even a one-pixel stroke prevents skipping this region. */
    private fun isUniform(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val first = pixels[0]
        return pixels.all { it == first }
    }

    private fun prepare(bitmap: Bitmap): Bitmap {
        val gray = ColorMatrix().apply { setSaturation(0f) }
        gray.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1.5f,
                    0f,
                    0f,
                    0f,
                    -64f,
                    0f,
                    1.5f,
                    0f,
                    0f,
                    -64f,
                    0f,
                    0f,
                    1.5f,
                    0f,
                    -64f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )
            )
        )
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply {
                drawColor(Color.WHITE)
                drawBitmap(
                    bitmap,
                    0f,
                    0f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(gray)
                    },
                )
            }
        }
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation !in 2..8) return bitmap
        val values =
            when (orientation) {
                2 -> floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                3 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
                4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
                5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                6 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                7 -> floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
                else -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
            }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { setValues(values) },
            false,
        )
    }
}
