package com.opencanvas.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class LayerMeta(
    val id: String,
    val name: String,
    val opacity: Float,
    val visible: Boolean,
    val locked: Boolean,
    val blend: String
)

/** What is kept in project.json. The pixels live in one PNG per layer next to it. */
class ProjectMeta(
    val id: String,
    var name: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val created: Long,
    var modified: Long,
    var layers: List<LayerMeta>,
    var activeIndex: Int,
    var layerCounter: Int
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("version", 1)
        o.put("id", id)
        o.put("name", name)
        o.put("width", width)
        o.put("height", height)
        o.put("dpi", dpi)
        o.put("created", created)
        o.put("modified", modified)
        o.put("activeIndex", activeIndex)
        o.put("layerCounter", layerCounter)
        val arr = JSONArray()
        for (l in layers) {
            val lo = JSONObject()
            lo.put("id", l.id)
            lo.put("name", l.name)
            lo.put("opacity", l.opacity.toDouble())
            lo.put("visible", l.visible)
            lo.put("locked", l.locked)
            lo.put("blend", l.blend)
            arr.put(lo)
        }
        o.put("layers", arr)
        return o.toString()
    }

    companion object {
        fun fromJson(text: String): ProjectMeta {
            val o = JSONObject(text)
            val arr = o.getJSONArray("layers")
            val layers = ArrayList<LayerMeta>()
            for (i in 0 until arr.length()) {
                val lo = arr.getJSONObject(i)
                layers.add(
                    LayerMeta(
                        lo.getString("id"), lo.getString("name"),
                        lo.optDouble("opacity", 1.0).toFloat(),
                        lo.optBoolean("visible", true), lo.optBoolean("locked", false),
                        lo.optString("blend", "NORMAL")
                    )
                )
            }
            return ProjectMeta(
                o.getString("id"), o.optString("name", "Untitled"),
                o.getInt("width"), o.getInt("height"),
                o.optInt("dpi", CanvasSizes.DEFAULT_DPI),
                o.optLong("created", 0L), o.optLong("modified", 0L),
                layers, o.optInt("activeIndex", 0), o.optInt("layerCounter", layers.size)
            )
        }
    }
}

/**
 * A copy of what needs saving, taken on the main thread so the background save never walks a list
 * that the user is changing at the same moment (deleting a layer, say).
 */
class SaveSnapshot(
    val width: Int,
    val height: Int,
    val layers: List<Layer>,
    val props: List<LayerProps>,
    val activeIndex: Int,
    val layerCounter: Int
) {
    companion object {
        fun of(doc: Document): SaveSnapshot {
            val ls = ArrayList(doc.layers)
            return SaveSnapshot(
                doc.width, doc.height, ls, ls.map { it.props() },
                ls.indexOf(doc.active).coerceAtLeast(0), doc.layerCounter
            )
        }
    }
}

/**
 * Saves and opens drawings in the app's private storage:
 * files/projects/<id>/project.json, thumb.png and layer_<layerId>.png
 * Files are written to a temporary name and then renamed, so a crash or kill mid-save
 * never leaves a broken file behind.
 */
class ProjectStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "projects").apply { mkdirs() }
    private val saveLock = Any()

    fun dir(id: String) = File(root, id)
    fun thumbFile(id: String) = File(dir(id), "thumb.png")

    fun list(): List<ProjectMeta> {
        val out = ArrayList<ProjectMeta>()
        for (d in root.listFiles() ?: emptyArray()) {
            val m = readMeta(d.name) ?: continue
            out.add(m)
        }
        out.sortByDescending { it.modified }
        return out
    }

    fun readMeta(id: String): ProjectMeta? {
        return try {
            val f = File(dir(id), "project.json")
            if (!f.exists()) null else ProjectMeta.fromJson(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun create(name: String, w: Int, h: Int, dpi: Int): ProjectMeta {
        val id = java.util.UUID.randomUUID().toString().substring(0, 12)
        val now = System.currentTimeMillis()
        val first = LayerMeta(java.util.UUID.randomUUID().toString().substring(0, 8), "Layer 1", 1f, true, false, "NORMAL")
        val meta = ProjectMeta(id, name, w, h, dpi, now, now, listOf(first), 0, 1)
        dir(id).mkdirs()
        writeText(File(dir(id), "project.json"), meta.toJson())
        return meta
    }

    fun rename(id: String, name: String) {
        val m = readMeta(id) ?: return
        m.name = name
        writeText(File(dir(id), "project.json"), m.toJson())
    }

    fun delete(id: String) {
        synchronized(saveLock) { dir(id).deleteRecursively() }
    }

    fun duplicate(id: String, newName: String): ProjectMeta? {
        val m = readMeta(id) ?: return null
        val newId = java.util.UUID.randomUUID().toString().substring(0, 12)
        val src = dir(id)
        val dst = dir(newId)
        dst.mkdirs()
        synchronized(saveLock) {
            for (f in src.listFiles() ?: emptyArray()) {
                if (f.name.endsWith(".tmp")) continue
                f.copyTo(File(dst, f.name), overwrite = true)
            }
        }
        val now = System.currentTimeMillis()
        val copy = ProjectMeta(newId, newName, m.width, m.height, m.dpi, now, now, m.layers, m.activeIndex, m.layerCounter)
        writeText(File(dst, "project.json"), copy.toJson())
        return copy
    }

    /** Makes an empty Document of the right size with the saved layer settings. Pixels come later. */
    fun prepare(meta: ProjectMeta): Document {
        val doc = Document(meta.width, meta.height, false)
        val layers = meta.layers.map { lm ->
            val l = Layer(lm.name, meta.width, meta.height, lm.id)
            l.opacity = lm.opacity
            l.visible = lm.visible
            l.locked = lm.locked
            l.blend = try { Blend.valueOf(lm.blend) } catch (e: Exception) { Blend.NORMAL }
            l
        }
        doc.setLayers(layers, meta.activeIndex, meta.layerCounter)
        return doc
    }

    /** Reads the layer PNGs into the document. Slow, so call it off the main thread. */
    fun loadPixels(meta: ProjectMeta, doc: Document) {
        val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        for (layer in doc.layers) {
            val f = File(dir(meta.id), "layer_${layer.id}.png")
            if (f.exists()) {
                try {
                    val opts = BitmapFactory.Options()
                    opts.inMutable = true
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888
                    opts.inBitmap = layer.bitmap
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                } catch (e: Exception) {
                    // The saved size did not match; draw it in instead.
                    val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    if (bmp != null) {
                        Canvas(layer.bitmap).drawBitmap(bmp, 0f, 0f, copyPaint)
                        bmp.recycle()
                    }
                }
            }
            layer.dirty = false
        }
    }

    /** Writes changed layers, the settings and a thumbnail. Call off the main thread. */
    fun save(meta: ProjectMeta, snap: SaveSnapshot) {
        synchronized(saveLock) {
            val d = dir(meta.id)
            if (!d.exists()) return  // deleted while open
            val layers = snap.layers
            for (layer in layers) {
                val f = File(d, "layer_${layer.id}.png")
                if (layer.dirty || !f.exists()) {
                    layer.dirty = false
                    val tmp = File(d, "layer_${layer.id}.png.tmp")
                    FileOutputStream(tmp).use { out ->
                        layer.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    if (!tmp.renameTo(f)) {
                        f.delete()
                        tmp.renameTo(f)
                    }
                }
            }
            val keep = layers.map { "layer_${it.id}.png" }.toSet()
            for (f in d.listFiles() ?: emptyArray()) {
                if (f.name.startsWith("layer_") && f.name !in keep) f.delete()
            }

            val (tw, th) = CanvasSizes.fitInside(snap.width, snap.height, 384)
            val thumb = Flatten.render(layers, snap.width, snap.height, tw, th)
            val tt = File(d, "thumb.png.tmp")
            FileOutputStream(tt).use { out -> thumb.compress(Bitmap.CompressFormat.PNG, 100, out) }
            thumb.recycle()
            val tf = thumbFile(meta.id)
            if (!tt.renameTo(tf)) {
                tf.delete()
                tt.renameTo(tf)
            }

            meta.layers = layers.indices.map { i ->
                val p = snap.props[i]
                LayerMeta(layers[i].id, p.name, p.opacity, p.visible, p.locked, p.blend.name)
            }
            meta.activeIndex = snap.activeIndex
            meta.layerCounter = snap.layerCounter
            meta.modified = System.currentTimeMillis()
            writeText(File(d, "project.json"), meta.toJson())
        }
    }

    private fun writeText(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
