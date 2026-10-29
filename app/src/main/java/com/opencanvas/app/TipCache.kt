package com.opencanvas.app

import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** A brush tip as a chain of alpha masks from large to small, so small stamps stay crisp. */
class Tip(private val variants: List<List<Bitmap>>) {
    fun pick(variant: Int, d: Float): Bitmap {
        val levels = variants[variant.coerceIn(0, variants.size - 1)]
        var best = levels[0]
        for (b in levels) {
            if (b.width >= d) best = b else break
        }
        return best
    }
}

object TipCache {
    private val cache = object : LinkedHashMap<String, Tip>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tip>?): Boolean =
            size > 40
    }

    fun get(context: Context, spec: BrushSpec): Tip {
        val key = keyOf(spec)
        val hit = cache[key]
        if (hit != null) return hit
        val tip = build(context, spec)
        cache[key] = tip
        return tip
    }

    private fun q(v: Float): Int = (v * 50f).roundToInt()

    private fun keyOf(spec: BrushSpec): String = when (spec.tip) {
        TipKind.ROUND -> "r|" + q(spec.hardness)
        TipKind.GRAIN -> "g|" + q(spec.hardness) + "|" + q(spec.grain)
        TipKind.IMAGE -> "i|" + spec.tipFile + "|" + spec.invertTip
        else -> "k|" + spec.tip.name
    }

    private fun build(context: Context, spec: BrushSpec): Tip {
        val h = q(spec.hardness) / 50f
        val g = q(spec.grain) / 50f
        if (spec.tip == TipKind.IMAGE) {
            val file = spec.tipFile
            val mask = if (file != null) BrushStore.loadTipMask(context, file) else null
            if (mask == null) return Tip(listOf(toBitmaps(TipMath.round(TipMath.BASE, 0.9f))))
            val m = if (spec.invertTip) TipMath.invert(mask) else mask
            return Tip(listOf(toBitmaps(m)))
        }
        val list = ArrayList<List<Bitmap>>()
        for (v in 0 until spec.variants) {
            list.add(toBitmaps(TipMath.forKind(spec.tip, TipMath.BASE, h, g, v)))
        }
        return Tip(list)
    }

    private fun toBitmaps(base: ByteArray): List<Bitmap> {
        val list = ArrayList<Bitmap>()
        for (lv in TipMath.levels(base, TipMath.BASE)) {
            val bmp = Bitmap.createBitmap(lv.size, lv.size, Bitmap.Config.ALPHA_8)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(lv.data))
            list.add(bmp)
        }
        return list
    }
}
