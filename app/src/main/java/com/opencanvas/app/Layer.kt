package com.opencanvas.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

enum class Blend(val label: String, mode: PorterDuff.Mode) {
    NORMAL("Normal", PorterDuff.Mode.SRC_OVER),
    MULTIPLY("Multiply", PorterDuff.Mode.MULTIPLY),
    SCREEN("Screen", PorterDuff.Mode.SCREEN),
    OVERLAY("Overlay", PorterDuff.Mode.OVERLAY),
    DARKEN("Darken", PorterDuff.Mode.DARKEN),
    LIGHTEN("Lighten", PorterDuff.Mode.LIGHTEN),
    ADD("Add", PorterDuff.Mode.ADD);

    val xfermode = PorterDuffXfermode(mode)
}

data class LayerProps(
    val name: String,
    val opacity: Float,
    val visible: Boolean,
    val locked: Boolean,
    val blend: Blend
)

class Layer(
    var name: String,
    val width: Int,
    val height: Int,
    /** Stable id, used for the layer's file when the project is saved. */
    val id: String = java.util.UUID.randomUUID().toString().substring(0, 8)
) {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    /** True when the pixels have changed since the layer was last saved. */
    @Volatile var dirty = true

    var opacity = 1f
    var visible = true
    var locked = false
    var blend = Blend.NORMAL

    private val patchPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    fun props() = LayerProps(name, opacity, visible, locked, blend)

    fun restore(p: LayerProps) {
        name = p.name
        opacity = p.opacity
        visible = p.visible
        locked = p.locked
        blend = p.blend
    }

    fun clear() {
        bitmap.eraseColor(Color.TRANSPARENT)
        dirty = true
    }

    /** A full copy of the pixels, used to undo "clear layer". */
    fun snapshot(): Bitmap {
        val copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(copy).drawBitmap(bitmap, 0f, 0f, patchPaint)
        return copy
    }

    /** Replaces a rectangle of the layer with the given pixels (used by undo and redo). */
    fun putPatch(x: Int, y: Int, patch: Bitmap) {
        canvas.drawBitmap(patch, x.toFloat(), y.toFloat(), patchPaint)
        dirty = true
    }

    fun duplicate(newName: String): Layer {
        val c = Layer(newName, width, height)
        c.canvas.drawBitmap(bitmap, 0f, 0f, patchPaint)
        c.restore(props().copy(name = newName))
        return c
    }
}
