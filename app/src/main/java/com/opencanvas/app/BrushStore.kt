package com.opencanvas.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Keeps the user's own brushes on the phone (brushes.json plus tip images). */
class BrushStore(private val context: Context) {

    val custom = ArrayList<BrushSpec>()
    private val file = File(context.filesDir, "brushes.json")

    init {
        load()
    }

    fun all(): List<BrushSpec> = Brushes.ALL + custom

    fun find(id: String): BrushSpec? = all().firstOrNull { it.id == id }

    fun newId(): String = "c" + java.lang.Long.toString(System.currentTimeMillis(), 36)

    fun save(spec: BrushSpec) {
        val s = spec.copy(builtin = false)
        val i = custom.indexOfFirst { it.id == s.id }
        if (i >= 0) custom[i] = s else custom.add(s)
        persist()
    }

    fun delete(id: String) {
        val spec = custom.firstOrNull { it.id == id } ?: return
        custom.remove(spec)
        val tf = spec.tipFile
        if (tf != null && custom.none { it.tipFile == tf }) {
            File(tipDir(context), tf).delete()
        }
        persist()
    }

    /** Reads a picture the user picked and stores it as a brush tip. Returns the tip file name. */
    fun importTip(uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            val mask = TipMath.maskFromArgb(px, w, h, TipMath.BASE)
            saveMask(mask)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveMask(mask: ByteArray): String? {
        val n = TipMath.BASE
        val px = IntArray(n * n)
        for (i in px.indices) {
            px[i] = ((mask[i].toInt() and 0xFF) shl 24) or 0x00FFFFFF
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        val name = "tip_" + System.currentTimeMillis() + ".png"
        FileOutputStream(File(tipDir(context), name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return name
    }

    private fun toJson(s: BrushSpec): JSONObject {
        val o = JSONObject()
        o.put("id", s.id)
        o.put("name", s.name)
        o.put("tip", s.tip.name)
        o.put("tipFile", s.tipFile ?: JSONObject.NULL)
        o.put("invertTip", s.invertTip)
        o.put("spacing", s.spacing.toDouble())
        o.put("hardness", s.hardness.toDouble())
        o.put("flow", s.flow.toDouble())
        o.put("sizePressure", s.sizePressure.toDouble())
        o.put("flowPressure", s.flowPressure.toDouble())
        o.put("sizeJitter", s.sizeJitter.toDouble())
        o.put("angle", s.angle.toDouble())
        o.put("angleJitter", s.angleJitter.toDouble())
        o.put("followStroke", s.followStroke)
        o.put("roundness", s.roundness.toDouble())
        o.put("scatter", s.scatter.toDouble())
        o.put("grain", s.grain.toDouble())
        o.put("smoothing", s.smoothing.toDouble())
        return o
    }

    private fun fromJson(o: JSONObject): BrushSpec {
        val d = BrushSpec(id = "", name = "")
        fun f(key: String, def: Float): Float = o.optDouble(key, def.toDouble()).toFloat()
        val kind = try {
            TipKind.valueOf(o.optString("tip", "ROUND"))
        } catch (e: Exception) {
            TipKind.ROUND
        }
        return BrushSpec(
            id = o.getString("id"),
            name = o.optString("name", "Custom"),
            tip = kind,
            tipFile = if (o.isNull("tipFile")) null else o.getString("tipFile"),
            invertTip = o.optBoolean("invertTip", false),
            spacing = f("spacing", d.spacing),
            hardness = f("hardness", d.hardness),
            flow = f("flow", d.flow),
            sizePressure = f("sizePressure", d.sizePressure),
            flowPressure = f("flowPressure", d.flowPressure),
            sizeJitter = f("sizeJitter", d.sizeJitter),
            angle = f("angle", d.angle),
            angleJitter = f("angleJitter", d.angleJitter),
            followStroke = o.optBoolean("followStroke", false),
            roundness = f("roundness", d.roundness),
            scatter = f("scatter", d.scatter),
            grain = f("grain", d.grain),
            smoothing = f("smoothing", d.smoothing),
            builtin = false
        )
    }

    private fun persist() {
        val arr = JSONArray()
        for (s in custom) arr.put(toJson(s))
        file.writeText(arr.toString())
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                custom.add(fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            // a damaged file just means no custom brushes; built-ins still work
        }
    }

    companion object {
        fun tipDir(context: Context): File {
            val d = File(context.filesDir, "tips")
            if (!d.exists()) d.mkdirs()
            return d
        }

        fun loadTipMask(context: Context, name: String): ByteArray? {
            return try {
                val bmp = BitmapFactory.decodeFile(File(tipDir(context), name).absolutePath) ?: return null
                val w = bmp.width
                val h = bmp.height
                if (w != TipMath.BASE || h != TipMath.BASE) {
                    bmp.recycle()
                    return null
                }
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                bmp.recycle()
                val out = ByteArray(w * h)
                for (i in px.indices) out[i] = (px[i] ushr 24).toByte()
                out
            } catch (e: Exception) {
                null
            }
        }
    }
}
