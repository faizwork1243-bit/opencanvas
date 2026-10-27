package com.opencanvas.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

class Layer(var name: String) {
    val bitmap: Bitmap = Bitmap.createBitmap(Document.SIZE, Document.SIZE, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val strokes = ArrayList<Stroke>()

    var opacity = 1f
    var visible = true
    var locked = false
    var blend = Blend.NORMAL

    fun props() = LayerProps(name, opacity, visible, locked, blend)

    fun restore(p: LayerProps) {
        name = p.name
        opacity = p.opacity
        visible = p.visible
        locked = p.locked
        blend = p.blend
    }

    /** Re-draws every stroke from scratch. Slow on huge histories; replaced by tiles in the GPU phase. */
    fun rebuild() {
        bitmap.eraseColor(Color.TRANSPARENT)
        for (s in strokes) StrokeRenderer.render(canvas, s)
    }

    fun duplicate(newName: String): Layer {
        val copy = Layer(newName)
        copy.strokes.addAll(strokes)
        copy.restore(props().copy(name = newName))
        copy.rebuild()
        return copy
    }
}
