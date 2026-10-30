package com.opencanvas.app

import kotlin.math.max
import kotlin.math.min

/**
 * Tile arithmetic, kept free of Android types so it can be tested.
 * The live stroke is drawn into small 256 x 256 tiles instead of one big bitmap, so the phone only
 * has to re-send the few tiles that changed to the GPU on each frame.
 */
object TileGeom {
    const val T = 256

    fun cols(width: Int): Int = (width + T - 1) / T

    /**
     * Calls fn(tx, ty, ix0, iy0, ix1, iy1) for every tile that the canvas-space rectangle
     * [x0, x1) x [y0, y1) overlaps. (ix0..ix1, iy0..iy1) is the overlap in canvas coordinates.
     */
    inline fun forPieces(
        x0: Int, y0: Int, x1: Int, y1: Int, cols: Int, rows: Int,
        fn: (Int, Int, Int, Int, Int, Int) -> Unit
    ) {
        val ax0 = max(x0, 0)
        val ay0 = max(y0, 0)
        val ax1 = min(x1, cols * T)
        val ay1 = min(y1, rows * T)
        if (ax1 <= ax0 || ay1 <= ay0) return
        val tx0 = ax0 / T
        val tx1 = (ax1 - 1) / T
        val ty0 = ay0 / T
        val ty1 = (ay1 - 1) / T
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                fn(
                    tx, ty,
                    max(ax0, tx * T), max(ay0, ty * T),
                    min(ax1, (tx + 1) * T), min(ay1, (ty + 1) * T)
                )
            }
        }
    }
}
