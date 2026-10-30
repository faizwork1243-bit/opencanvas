package com.opencanvas.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A ready-made canvas size. dpi is only used to size PDF pages. */
class SizePreset(val label: String, val width: Int, val height: Int, val dpi: Int) {
    val isPaper: Boolean get() = dpi != 0 && label.startsWith("A")
    fun rotated() = SizePreset(label, height, width, dpi)
}

/**
 * Canvas size rules, kept free of Android types so they can be tested.
 * Every layer is a full-size 32-bit bitmap, so the number of layers is limited by memory.
 */
object CanvasSizes {
    const val MIN_SIDE = 64
    const val MAX_SIDE = 8192
    const val MAX_PIXELS = 16_777_216L          // 4096 x 4096
    const val LAYER_BUDGET_BYTES = 256L * 1024L * 1024L
    const val MAX_LAYERS = 8
    const val DEFAULT_DPI = 150

    val presets = listOf(
        SizePreset("Square 2048", 2048, 2048, DEFAULT_DPI),
        SizePreset("Square 3072", 3072, 3072, DEFAULT_DPI),
        SizePreset("Phone 1080 x 1920", 1080, 1920, DEFAULT_DPI),
        SizePreset("HD 1920 x 1080", 1920, 1080, DEFAULT_DPI),
        SizePreset("A5 (300 dpi)", 1748, 2480, 300),
        SizePreset("A4 (150 dpi)", 1240, 1754, 150),
        SizePreset("A4 (300 dpi)", 2480, 3508, 300),
        SizePreset("A3 (150 dpi)", 1754, 2480, 150),
        SizePreset("A3 (200 dpi)", 2339, 3307, 200)
    )

    fun layerBytes(w: Int, h: Int): Long = w.toLong() * h.toLong() * 4L

    /** How many layers fit in the memory budget for this canvas size (1..8). */
    fun maxLayers(w: Int, h: Int): Int {
        val fit = (LAYER_BUDGET_BYTES / layerBytes(w, h)).toInt()
        return fit.coerceIn(1, MAX_LAYERS)
    }

    /** Brings any width/height into the allowed range, keeping the shape if it has to shrink. */
    fun clamp(w: Int, h: Int): Pair<Int, Int> {
        var cw = w.coerceIn(MIN_SIDE, MAX_SIDE)
        var ch = h.coerceIn(MIN_SIDE, MAX_SIDE)
        if (cw.toLong() * ch > MAX_PIXELS) {
            val f = sqrt(MAX_PIXELS.toDouble() / (cw.toDouble() * ch.toDouble()))
            cw = max(MIN_SIDE, (cw * f).toInt())
            ch = max(MIN_SIDE, (ch * f).toInt())
            while (cw.toLong() * ch > MAX_PIXELS) {
                if (cw >= ch) cw-- else ch--
            }
        }
        return Pair(cw, ch)
    }

    fun isValid(w: Int, h: Int): Boolean =
        w in MIN_SIDE..MAX_SIDE && h in MIN_SIDE..MAX_SIDE && w.toLong() * h <= MAX_PIXELS

    /** Size of a thumbnail that fits in a box, keeping the shape. */
    fun fitInside(w: Int, h: Int, box: Int): Pair<Int, Int> {
        val s = min(box.toFloat() / w, box.toFloat() / h)
        return Pair(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
    }

    fun megapixels(w: Int, h: Int): String = String.format(java.util.Locale.US, "%.1f MP", w.toLong() * h / 1_000_000.0)
}
