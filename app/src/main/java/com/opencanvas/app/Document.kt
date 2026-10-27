package com.opencanvas.app

/** One undoable step. Every change to the document goes through a Command. */
interface Command {
    fun redo(doc: Document)
    fun undo(doc: Document)
}

class StrokeCmd(private val layer: Layer, private val stroke: Stroke) : Command {
    override fun redo(doc: Document) {
        layer.strokes.add(stroke)
        StrokeRenderer.render(layer.canvas, stroke)
    }

    override fun undo(doc: Document) {
        layer.strokes.remove(stroke)
        layer.rebuild()
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

class ClearLayerCmd(private val layer: Layer, private val saved: List<Stroke>) : Command {
    override fun redo(doc: Document) {
        layer.strokes.clear()
        layer.rebuild()
    }

    override fun undo(doc: Document) {
        layer.strokes.addAll(saved)
        layer.rebuild()
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

class Document {
    val layers = ArrayList<Layer>()
    var active: Layer = Layer("Layer 1")
    var onChanged: (() -> Unit)? = null

    private var counter = 1
    private val undoStack = ArrayList<Command>()
    private val redoStack = ArrayList<Command>()

    init {
        layers.add(active)
    }

    fun changed() {
        onChanged?.invoke()
    }

    /** Records a step that has already been applied. */
    fun push(c: Command) {
        undoStack.add(c)
        redoStack.clear()
        while (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
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
        c.undo(this)
        redoStack.add(c)
        changed()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val c = redoStack.removeAt(redoStack.lastIndex)
        c.redo(this)
        undoStack.add(c)
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
        if (layers.size >= MAX_LAYERS) return false
        counter++
        run(AddLayerCmd(layers.indexOf(active) + 1, Layer("Layer $counter")))
        return true
    }

    fun duplicateActive(): Boolean {
        if (layers.size >= MAX_LAYERS) return false
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

    fun clearActive() {
        val l = active
        if (l.strokes.isEmpty()) return
        run(ClearLayerCmd(l, ArrayList(l.strokes)))
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
        const val SIZE = 2048
        const val MAX_LAYERS = 8
        const val MAX_HISTORY = 300
    }
}
