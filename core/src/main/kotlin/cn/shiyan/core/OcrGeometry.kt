package cn.shiyan.core

/** Pixel-edge coordinates, not pixel centers. Original screenshots are never resized on disk. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width
        get() = right - left

    val height
        get() = bottom - top
}

data class OcrTile(val crop: PixelRect, val ownerTop: Int, val ownerBottom: Int, val sample: Int)

object OcrGeometry {
    fun uprightSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        if (orientation in 5..8) height to width else width to height

    /** Bound memory by WIDTH, never by the height of a scrolling screenshot. */
    fun tiles(width: Int, height: Int): List<OcrTile> {
        require(width > 0 && height > 0)
        var sample = 1
        while ((width + sample - 1) / sample > 2048) sample *= 2
        val stride = 768 * sample
        val padding = 128 * sample
        return (0 until height step stride).map { top ->
            val end = minOf(height, top + stride)
            OcrTile(
                PixelRect(0, maxOf(0, top - padding), width, minOf(height, end + padding)),
                top,
                end,
                sample,
            )
        }
    }

    /** Convert an upright crop back to the encoded raster, including mirrored EXIF orientations. */
    fun originalRect(rect: PixelRect, width: Int, height: Int, orientation: Int): PixelRect {
        fun inverse(x: Int, y: Int): Pair<Int, Int> =
            when (orientation) {
                2 -> width - x to y
                3 -> width - x to height - y
                4 -> x to height - y
                5 -> y to x
                6 -> y to height - x
                7 -> width - y to height - x
                8 -> width - y to x
                else -> x to y
            }
        val corners =
            listOf(
                inverse(rect.left, rect.top),
                inverse(rect.right, rect.top),
                inverse(rect.left, rect.bottom),
                inverse(rect.right, rect.bottom),
            )
        return PixelRect(
            corners.minOf { it.first },
            corners.minOf { it.second },
            corners.maxOf { it.first },
            corners.maxOf { it.second },
        )
    }

    /** A word crossing a tile boundary is read with padding, then assigned to just one core. */
    fun owns(tile: OcrTile, line: OcrLine): Boolean {
        val center = (line.top.toLong() + line.bottom) / 2.0
        return center >= tile.ownerTop && center < tile.ownerBottom
    }

    /** Only merge two OCR detections of the SAME pixel region, never repeated chat phrases. */
    fun mergeDetections(lines: List<OcrLine>): List<OcrLine> {
        val result = mutableListOf<OcrLine>()
        for (line in lines.sortedWith(compareBy({ it.top }, { it.left }))) {
            val duplicate =
                result
                    .asReversed()
                    .takeWhile { it.bottom >= line.top - 32 }
                    .any { old ->
                        val overlapWidth =
                            (minOf(old.right, line.right) - maxOf(old.left, line.left))
                                .coerceAtLeast(0)
                        val overlapHeight =
                            (minOf(old.bottom, line.bottom) - maxOf(old.top, line.top))
                                .coerceAtLeast(0)
                        val minArea =
                            minOf(
                                (old.right - old.left).toLong() * (old.bottom - old.top),
                                (line.right - line.left).toLong() * (line.bottom - line.top),
                            )
                        ChatParser.normalize(old.text) == ChatParser.normalize(line.text) &&
                            minArea > 0 &&
                            overlapWidth.toLong() * overlapHeight > minArea * .65
                    }
            if (!duplicate) result += line
        }
        return result
    }
}
