package com.opencanvas.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * The live stroke lives here while you draw: a grid of small 256 x 256 bitmaps, created only where
 * the stroke goes. Each frame the phone only re-sends the few tiles that changed to the GPU,
 * instead of the whole 16 MB canvas. When the stroke ends the tiles are merged into the layer.
 */
class TileSet(width: Int, height: Int) {
    val cols = TileGeom.cols(width)
    val rows = TileGeom.cols(height)

    private val bitmaps = arrayOfNulls<Bitmap>(cols * rows)
    private val canvases = arrayOfNulls<Canvas>(cols * rows)
    private val touched = BooleanArray(cols * rows)
    private val touchedList = ArrayList<Int>()

    val isEmpty: Boolean get() = touchedList.isEmpty()

    fun isTouched(tx: Int, ty: Int): Boolean = touched[ty * cols + tx]

    /** Marks a tile as in use (creating it if needed) and returns a canvas to draw on it. */
    fun touch(tx: Int, ty: Int): Canvas {
        val i = ty * cols + tx
        var c = canvases[i]
        if (c == null) {
            val b = Bitmap.createBitmap(TileGeom.T, TileGeom.T, Bitmap.Config.ARGB_8888)
            bitmaps[i] = b
            c = Canvas(b)
            canvases[i] = c
        }
        if (!touched[i]) {
            touched[i] = true
            touchedList.add(i)
        }
        return c
    }

    fun bitmap(tx: Int, ty: Int): Bitmap = bitmaps[ty * cols + tx]!!

    /** Empties the tiles that were used, ready for the next stroke. */
    fun clear() {
        for (i in touchedList) {
            bitmaps[i]?.eraseColor(Color.TRANSPARENT)
            touched[i] = false
        }
        touchedList.clear()
    }

    /** Draws every tile that is in use at its place on the canvas. */
    fun draw(canvas: Canvas, paint: Paint?) {
        for (i in touchedList) {
            val b = bitmaps[i] ?: continue
            canvas.drawBitmap(b, (i % cols) * TileGeom.T.toFloat(), (i / cols) * TileGeom.T.toFloat(), paint)
        }
    }

    fun forEachTouched(fn: (Int, Int, Bitmap) -> Unit) {
        for (i in touchedList) {
            val b = bitmaps[i] ?: continue
            fn(i % cols, i / cols, b)
        }
    }
}
