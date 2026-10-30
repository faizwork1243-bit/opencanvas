package com.opencanvas.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.roundToInt

/** Draws the visible layers onto white paper, at full size or scaled down (for thumbnails). */
object Flatten {
    fun render(layers: List<Layer>, srcW: Int, srcH: Int, outW: Int, outH: Int): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        if (outW != srcW || outH != srcH) c.scale(outW / srcW.toFloat(), outH / srcH.toFloat())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (layer in layers) {
            if (!layer.visible) continue
            paint.alpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
            paint.xfermode = layer.blend.xfermode
            c.drawBitmap(layer.bitmap, 0f, 0f, paint)
        }
        return out
    }

    fun thumbnail(doc: Document, box: Int): Bitmap {
        val (w, h) = CanvasSizes.fitInside(doc.width, doc.height, box)
        return render(doc.layers, doc.width, doc.height, w, h)
    }
}
