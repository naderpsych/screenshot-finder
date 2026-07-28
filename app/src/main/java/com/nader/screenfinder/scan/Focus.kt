package com.nader.screenfinder.scan

/**
 * Decides what a screenshot is actually about.
 * A feed screenshot usually holds one whole post plus leftovers of its neighbours;
 * the leftovers stay searchable but must not decide the category.
 */
object Focus {
    /** phone chrome: clock and battery on top, navigation bar at the bottom */
    private const val TOP_CHROME = 0.05f
    private const val BOTTOM_CHROME = 0.06f
    private const val MIN_PHOTO = 0.14f

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    data class Result(
        /** text that carries the subject - used for categories */
        val focusText: String,
        /** the picture inside the screenshot, if there is a clear one */
        val crop: Box?,
        val blocksInFocus: Int
    )

    fun analyze(w: Int, h: Int, blocks: List<Ocr.Block>): Result {
        if (w <= 0 || h <= 0) return Result("", null, 0)
        val top = (h * TOP_CHROME).toInt()
        val bottom = (h - h * BOTTOM_CHROME).toInt()

        val body = blocks.filter { it.bottom > top && it.top < bottom && it.text.isNotBlank() }
        if (body.isEmpty()) {
            return Result("", Box(0, top, w, bottom), 0)
        }

        // weight by how central a block is, and distrust anything cut off at the edges
        val mid = (top + bottom) / 2f
        val span = (bottom - top).coerceAtLeast(1)
        val scored = body.map { b ->
            val center = (b.top + b.bottom) / 2f
            var weight = 1f - (Math.abs(center - mid) / span) * 1.6f
            if (b.top <= top + 4 || b.bottom >= bottom - 4) weight -= 0.35f
            weight += ((b.bottom - b.top).toFloat() / span) * 0.5f
            b to weight
        }
        val best = scored.maxOf { it.second }
        val keep = scored.filter { it.second >= maxOf(0.25f, best - 0.45f) }
        val focusText = keep.sortedBy { it.first.top }.joinToString("\n") { it.first.text }

        // the tallest text free band inside the body is the picture
        val covered = BooleanArray(h)
        for (b in body) for (y in b.top.coerceIn(0, h - 1)..b.bottom.coerceIn(0, h - 1)) covered[y] = true
        var bestStart = -1
        var bestLen = 0
        var start = -1
        for (y in top until bottom) {
            if (!covered[y]) {
                if (start < 0) start = y
            } else {
                if (start >= 0 && y - start > bestLen) {
                    bestLen = y - start
                    bestStart = start
                }
                start = -1
            }
        }
        if (start >= 0 && bottom - start > bestLen) {
            bestLen = bottom - start
            bestStart = start
        }
        val crop = if (bestLen >= h * MIN_PHOTO) Box(0, bestStart, w, bestStart + bestLen)
        else Box(0, top, w, bottom)

        return Result(focusText, crop, keep.size)
    }
}
