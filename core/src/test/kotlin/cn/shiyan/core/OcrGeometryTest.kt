package cn.shiyan.core

import org.junit.Assert.*
import org.junit.Test

class OcrGeometryTest {
    @Test
    fun longScreenshotDoesNotShrinkGlyphs() {
        val tiles = OcrGeometry.tiles(1200, 24000)
        assertTrue(tiles.size > 10)
        assertTrue(tiles.all { it.sample == 1 && it.crop.width == 1200 && it.crop.height <= 1024 })
        assertEquals(0, tiles.first().ownerTop)
        assertEquals(24000, tiles.last().ownerBottom)
        assertTrue(tiles.zipWithNext().all { (a, b) -> a.ownerBottom == b.ownerTop })
    }

    @Test
    fun everyScanlineBelongsToExactlyOneTile() {
        val tiles = OcrGeometry.tiles(1440, 20001)
        for (y in 0 until 20001) assertEquals(
            1,
            tiles.count { y >= it.ownerTop && y < it.ownerBottom },
        )
    }

    @Test
    fun paddingPreservesCrossBoundaryText() {
        val tiles = OcrGeometry.tiles(1080, 6000)
        val crossing = OcrLine("明天一起去公园", 50, 752, 500, 784)
        assertTrue(tiles[0].crop.bottom > crossing.bottom)
        assertTrue(tiles[1].crop.top < crossing.top)
        assertEquals(1, tiles.count { OcrGeometry.owns(it, crossing) })
    }

    @Test
    fun veryWideImageMemoryIsBoundedByWidthOnly() {
        val tiles = OcrGeometry.tiles(6000, 100000)
        assertTrue(
            tiles.all {
                it.sample == 4 &&
                    it.crop.width / it.sample <= 2048 &&
                    it.crop.height / it.sample <= 1024
            }
        )
    }

    @Test
    fun allExifOrientationsMapWholeImageToOriginal() {
        for (o in 1..8) {
            val (w, h) = OcrGeometry.uprightSize(1200, 9000, o)
            assertEquals(
                PixelRect(0, 0, 1200, 9000),
                OcrGeometry.originalRect(PixelRect(0, 0, w, h), 1200, 9000, o),
            )
        }
    }

    @Test
    fun rotatedAndMirroredCropsStayWithinEncodedRaster() {
        for (o in 1..8) {
            val (w, h) = OcrGeometry.uprightSize(1200, 9000, o)
            for (tile in OcrGeometry.tiles(w, h)) {
                val r = OcrGeometry.originalRect(tile.crop, 1200, 9000, o)
                assertTrue(r.left >= 0 && r.top >= 0 && r.right <= 1200 && r.bottom <= 9000)
                assertEquals(
                    tile.crop.width.toLong() * tile.crop.height,
                    r.width.toLong() * r.height,
                )
            }
        }
    }

    @Test
    fun repeatedPhrasesAtDifferentPositionsArePreserved() {
        val a = OcrLine("好的", 50, 100, 120, 130)
        val b = a.copy(top = 200, bottom = 230)
        assertEquals(listOf(a, b), OcrGeometry.mergeDetections(listOf(a, b)))
        assertEquals(
            listOf(a, b),
            OcrGeometry.mergeDetections(listOf(a, a.copy(top = 101, bottom = 131), b)),
        )
    }

    @Test
    fun traditionalOriginalIsNeverSilentlyConverted() {
        val line = OcrLine("謝謝你陪我聽音樂", 10, 20, 500, 70)
        assertEquals(line.text, OcrGeometry.mergeDetections(listOf(line)).single().text)
    }
}
