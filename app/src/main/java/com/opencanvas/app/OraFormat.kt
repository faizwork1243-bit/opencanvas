package com.opencanvas.app

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One layer as written into a layered file. Plain data, so the format code can be tested. */
class OraLayer(
    val name: String,
    val opacity: Float,
    val visible: Boolean,
    val op: String,
    val file: String
)

/**
 * OpenRaster (.ora): a ZIP with the layers as PNG files plus a small XML stack.
 * Krita, GIMP, MyPaint and others open it; renaming it to .zip lets you unpack the layer PNGs.
 */
object OraFormat {
    fun compositeOp(b: Blend): String = when (b) {
        Blend.NORMAL -> "svg:src-over"
        Blend.MULTIPLY -> "svg:multiply"
        Blend.SCREEN -> "svg:screen"
        Blend.OVERLAY -> "svg:overlay"
        Blend.DARKEN -> "svg:darken"
        Blend.LIGHTEN -> "svg:lighten"
        Blend.ADD -> "svg:plus"
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** layers are given bottom to top; the file lists the top layer first. */
    fun stackXml(w: Int, h: Int, layers: List<OraLayer>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<image w=\"$w\" h=\"$h\" version=\"0.0.1\">\n<stack>\n")
        for (l in layers.reversed()) {
            sb.append("<layer name=\"").append(escape(l.name)).append("\" src=\"").append(l.file)
                .append("\" opacity=\"").append(String.format(java.util.Locale.US, "%.3f", l.opacity))
                .append("\" visibility=\"").append(if (l.visible) "visible" else "hidden")
                .append("\" composite-op=\"").append(l.op)
                .append("\" x=\"0\" y=\"0\"/>\n")
        }
        sb.append("</stack>\n</image>\n")
        return sb.toString()
    }

    /** The "mimetype" entry must come first and must not be compressed. */
    fun writeMimetype(zip: ZipOutputStream) {
        val bytes = "image/openraster".toByteArray(Charsets.US_ASCII)
        val e = ZipEntry("mimetype")
        e.method = ZipEntry.STORED
        e.size = bytes.size.toLong()
        e.compressedSize = bytes.size.toLong()
        val crc = CRC32()
        crc.update(bytes)
        e.crc = crc.value
        zip.putNextEntry(e)
        zip.write(bytes)
        zip.closeEntry()
    }

    /** Writes the whole file. writeLayerPng(i, out) and the two image writers produce PNG bytes. */
    fun write(
        out: OutputStream,
        w: Int,
        h: Int,
        layers: List<OraLayer>,
        writeLayerPng: (Int, OutputStream) -> Unit,
        writeMerged: (OutputStream) -> Unit,
        writeThumb: (OutputStream) -> Unit
    ) {
        val zip = ZipOutputStream(out)
        writeMimetype(zip)
        zip.putNextEntry(ZipEntry("stack.xml"))
        zip.write(stackXml(w, h, layers).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for ((i, l) in layers.withIndex()) {
            zip.putNextEntry(ZipEntry(l.file))
            writeLayerPng(i, NoClose(zip))
            zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry("mergedimage.png"))
        writeMerged(NoClose(zip))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("Thumbnails/thumbnail.png"))
        writeThumb(NoClose(zip))
        zip.closeEntry()
        zip.finish()
    }

    /** Lets image encoders write into the zip without closing it. */
    private class NoClose(private val o: OutputStream) : OutputStream() {
        override fun write(b: Int) = o.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = o.write(b, off, len)
        override fun flush() = o.flush()
        override fun close() {}
    }
}
