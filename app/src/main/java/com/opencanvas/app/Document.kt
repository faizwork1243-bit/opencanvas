package com.opencanvas.app

import android.graphics.Bitmap
import android.graphics.Paint
import kotlin.math.roundToInt

/** One undoable step. Every change to the document goes through a Command. */
interface Command {
    fun redo(doc: Document)
    fun undo(doc: Document)

    /** Memory held by this step, so the history can stay within a budget. */
    val bytes: Long get() = 0L

    fun release() {}
}

/** A stroke, stored as the pixels of the touched rectangle before and after. */
class PatchCmd(
    private val layer: Layer,
    private val x: Int,
    private val y: Int,
    private val before: Bitmap,
    private val after: Bitmap
) : Command {
    override fun redo(doc: Document) = layer.putPatch(x, y, after)

    override fun undo(doc: Document) = layer.putPatch(x, y, before)

    override val bytes: Long get() = (before.byteCount + after.byteCount).toLong()

    override fun release() {
        before.recycle()
        after.recycle()
    }
}

class ClearLayerCmd(private val layer: Layer, private val saved: Bitmap) : Command {
    override fun redo(doc: Document) = layer.clear()

    override fun undo(doc: Document) = layer.putPatch(0, 0, saved)

    override val bytes: Long get() = saved.byteCount.toLong()

    override fun release() {
        saved.recycle()
    }
}

class AddLayerCmd(private val index: Int, private val layer: Layer) : Command {
    override fun redo(doc: Document) {
        doc.layers.add(index.coerceAtMost(doc.layers.size), layer)
        doc.active = layer
    }

    override fun undo(doc: Document) {
        val i = doc.layers.indexOf(layer)
        doc.layers.remove(layer)
        doc.fixActive(i - 1)
    }
}

class DeleteLayerCmd(private val index: Int, private val layer: Layer) : Command {
    override fun redo(doc: Document) {
        doc.layers.remove(layer)
        doc.fixActive(index - 1)
    }

    override fun undo(doc: Document) {
        doc.layers.add(index.coerceAtMost(doc.layers.size), layer)
        doc.active = layer
    }

    override val bytes: Long get() = layer.bitmap.byteCount.toLong()
}

class MoveLayerCmd(private val from: Int, private val to: Int) : Command {
    override fun redo(doc: Document) {
        val l = doc.layers.removeAt(from)
        doc.layers.add(to, l)
    }

    override fun undo(doc: Document) {
        val l = doc.layers.removeAt(to)
        doc.layers.add(from, l)
    }
}

/** Merges a layer into the one below it. */
class MergeDownCmd(
    private val upperIndex: Int,
    private val upper: Layer,
    private val lower: Layer,
    private val lowerBefore: Bitmap
) : Command {
    private val paint = Paint()

    override fun redo(doc: Document) {
        paint.alpha = (upper.opacity * 255f).roundToInt().coerceIn(0, 255)
        paint.xfermode = upper.blend.xfermode
        lower.canvas.drawBitmap(upper.bitmap, 0f, 0f, paint)
        lower.dirty = true
        doc.layers.remove(upper)
        doc.active = lower
    }

    override fun undo(doc: Document) {
        lower.putPatch(0, 0, lowerBefore)
        doc.layers.add(upperIndex.coerceAtMost(doc.layers.size), upper)
        doc.active = upper
    }

    override val bytes: Long get() = (lowerBefore.byteCount + upper.bitmap.byteCount).toLong()

    override fun release() {
        lowerBefore.recycle()
    }
}

class PropsCmd(
    private val layer: Layer,
    private val before: LayerProps,
    private val after: LayerProps
) : Command {
    override fun redo(doc: Document) = layer.restore(after)

    override fun undo(doc: Document) = layer.restore(before)
}

class Document(val width: Int, val height: Int, createFirstLayer: Boolean = true) {
    val layers = ArrayList<Layer>()
    lateinit var active: Layer

    /** How many layers this canvas size allows. */
    val maxLayers: Int = CanvasSizes.maxLayers(width, height)
    var onChanged: (() -> Unit)? = null

    private var counter = 1
    private val undoStack = ArrayList<Command>()
    private val redoStack = ArrayList<Command>()
    private var undoBytes = 0L

    init {
        if (createFirstLayer) {
            active = Layer("Layer 1", width, height)
            layers.add(active)
        }
    }

    /** Used when opening a saved project: swaps in the loaded layers and clears the history. */
    fun setLayers(list: List<Layer>, activeIndex: Int, layerCounter: Int) {
        layers.clear()
        layers.addAll(list)
        active = layers[activeIndex.coerceIn(0, layers.size - 1)]
        counter = layerCounter
        for (c in undoStack) c.release()
        for (c in redoStack) c.release()
        undoStack.clear()
        redoStack.clear()
        undoBytes = 0L
    }

    val layerCounter: Int get() = counter

    fun changed() {
        onChanged?.invoke()
    }

    /** Records a step that has already been applied. */
    fun push(c: Command) {
        undoStack.add(c)
        undoBytes += c.bytes
        for (r in redoStack) r.release()
        redoStack.clear()
        while (undoStack.size > 1 &&
            (undoStack.size > MAX_HISTORY || undoBytes > MAX_HISTORY_BYTES)
        ) {
            val old = undoStack.removeAt(0)
            undoBytes -= old.bytes
            old.release()
        }
    }

    /** Applies a step and records it. */
    private fun run(c: Command) {
        c.redo(this)
        push(c)
        changed()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val c = undoStack.removeAt(undoStack.lastIndex)
        undoBytes -= c.bytes
        c.undo(this)
        redoStack.add(c)
        changed()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val c = redoStack.removeAt(redoStack.lastIndex)
        c.redo(this)
        undoStack.add(c)
        undoBytes += c.bytes
        changed()
    }

    fun fixActive(preferred: Int) {
        if (!layers.contains(active)) {
            active = layers[preferred.coerceIn(0, layers.size - 1)]
        }
    }

    fun select(layer: Layer) {
        if (layers.contains(layer)) {
            active = layer
            changed()
        }
    }

    fun addLayer(): Boolean {
        if (layers.size >= maxLayers) return false
        counter++
        run(AddLayerCmd(layers.indexOf(active) + 1, Layer("Layer $counter", width, height)))
        return true
    }

    fun duplicateActive(): Boolean {
        if (layers.size >= maxLayers) return false
        val src = active
        run(AddLayerCmd(layers.indexOf(src) + 1, src.duplicate(src.name + " copy")))
        return true
    }

    fun deleteActive(): Boolean {
        if (layers.size <= 1) return false
        run(DeleteLayerCmd(layers.indexOf(active), active))
        return true
    }

    /** delta +1 moves the layer up the stack, -1 moves it down. */
    fun moveActive(delta: Int): Boolean {
        val from = layers.indexOf(active)
        val to = from + delta
        if (to < 0 || to >= layers.size) return false
        run(MoveLayerCmd(from, to))
        return true
    }

    /** Merges the active layer into the one beneath it. Returns false if that isn't possible. */
    fun mergeDown(): Boolean {
        val i = layers.indexOf(active)
        if (i <= 0) return false
        val upper = layers[i]
        val lower = layers[i - 1]
        if (!upper.visible) return false
        run(MergeDownCmd(i, upper, lower, lower.snapshot()))
        return true
    }

    fun clearActive() {
        val l = active
        run(ClearLayerCmd(l, l.snapshot()))
    }

    fun setProps(layer: Layer, after: LayerProps) {
        val before = layer.props()
        if (before == after) return
        run(PropsCmd(layer, before, after))
    }

    /** For live slider drags: the change is already applied, just record it. */
    fun commitProps(layer: Layer, before: LayerProps) {
        val after = layer.props()
        if (before == after) return
        push(PropsCmd(layer, before, after))
        changed()
    }

    companion object {
        const val MAX_HISTORY = 300
        const val MAX_HISTORY_BYTES = 200L * 1024L * 1024L
    }
}
