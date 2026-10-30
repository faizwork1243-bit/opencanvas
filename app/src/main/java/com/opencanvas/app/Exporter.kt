package com.opencanvas.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Base64

enum class ExportFormat(
    val label: String,
    val detail: String,
    val ext: String,
    val mime: String,
    val isImage: Boolean
) {
    PNG("PNG", "Lossless, keeps full quality", "png", "image/png", true),
    JPG("JPG", "Smaller file, white background", "jpg", "image/jpeg", true),
    WEBP("WebP", "Small file, good quality", "webp", "image/webp", true),
    PDF("PDF", "One page, sized from the canvas dpi", "pdf", "application/pdf", false),
    SVG("SVG", "Picture embedded in an SVG file (not vector)", "svg", "image/svg+xml", false),
    ORA("Layered (ORA)", "Keeps all layers; opens in Krita, GIMP", "ora", "application/octet-stream", false),
    ZIP("Layered (ZIP)", "Same layers as a plain .zip: unpack to get each layer as a PNG", "zip", "application/zip", false)
}

/** What the background export needs, captured on the main thread so it cannot change underneath. */
class ExportJob(
    val name: String,
    val format: ExportFormat,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val flat: Bitmap,
    val layers: List<Layer>,
    val props: List<LayerProps>
)

object Exporter {
    const val JPG_QUALITY = 95
    const val WEBP_QUALITY = 95

    /** Runs the export and returns a readable location, or null if it failed. Call off the main thread. */
    fun run(context: Context, job: ExportJob): String? {
        return try {
            save(context, job.name, job.format) { out -> write(job, out) }
        } catch (e: Throwable) {
            null
        }
    }

    private fun write(job: ExportJob, out: OutputStream) {
        when (job.format) {
            ExportFormat.PNG -> job.flat.compress(Bitmap.CompressFormat.PNG, 100, out)
            ExportFormat.JPG -> job.flat.compress(Bitmap.CompressFormat.JPEG, JPG_QUALITY, out)
            ExportFormat.WEBP -> job.flat.compress(webpFormat(), WEBP_QUALITY, out)
            ExportFormat.PDF -> writePdf(job, out)
            ExportFormat.SVG -> writeSvg(job, out)
            ExportFormat.ORA, ExportFormat.ZIP -> writeOra(job, out)
        }
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    private fun writePdf(job: ExportJob, out: OutputStream) {
        val dpi = if (job.dpi > 0) job.dpi else CanvasSizes.DEFAULT_DPI
        val pw = Math.max(1, Math.round(job.width * 72f / dpi))
        val ph = Math.max(1, Math.round(job.height * 72f / dpi))
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            val c = page.canvas
            c.scale(pw / job.width.toFloat(), ph / job.height.toFloat())
            c.drawBitmap(job.flat, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page)
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private fun writeSvg(job: ExportJob, out: OutputStream) {
        val png = ByteArrayOutputStream()
        job.flat.compress(Bitmap.CompressFormat.PNG, 100, png)
        val b64 = Base64.getEncoder().encodeToString(png.toByteArray())
        val svg = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
            "width=\"${job.width}\" height=\"${job.height}\" viewBox=\"0 0 ${job.width} ${job.height}\">\n" +
            "<image width=\"${job.width}\" height=\"${job.height}\" xlink:href=\"data:image/png;base64,$b64\"/>\n" +
            "</svg>\n"
        out.write(svg.toByteArray(Charsets.UTF_8))
    }

    private fun writeOra(job: ExportJob, out: OutputStream) {
        val oraLayers = job.layers.indices.map { i ->
            val p = job.props[i]
            OraLayer(p.name, p.opacity, p.visible, OraFormat.compositeOp(p.blend), "data/layer$i.png")
        }
        OraFormat.write(
            out, job.width, job.height, oraLayers,
            writeLayerPng = { i, o -> job.layers[i].bitmap.compress(Bitmap.CompressFormat.PNG, 100, o) },
            writeMerged = { o -> job.flat.compress(Bitmap.CompressFormat.PNG, 100, o) },
            writeThumb = { o ->
                val (tw, th) = CanvasSizes.fitInside(job.width, job.height, 256)
                val t = Bitmap.createScaledBitmap(job.flat, tw, th, true)
                t.compress(Bitmap.CompressFormat.PNG, 100, o)
                if (t !== job.flat) t.recycle()
            }
        )
    }

    private fun save(context: Context, name: String, fmt: ExportFormat, writer: (OutputStream) -> Unit): String? {
        val fileName = "$name.${fmt.ext}"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            values.put(MediaStore.MediaColumns.MIME_TYPE, fmt.mime)
            val folder = if (fmt.isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Carnival")
            val collection = if (fmt.isImage) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val uri = context.contentResolver.insert(collection, values) ?: return null
            try {
                val os = context.contentResolver.openOutputStream(uri) ?: return null
                os.use { writer(it) }
            } catch (e: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
            "$folder/Carnival/$fileName"
        } else {
            val type = if (fmt.isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
            val dir = context.getExternalFilesDir(type) ?: return null
            val f = File(dir, fileName)
            FileOutputStream(f).use { writer(it) }
            f.absolutePath
        }
    }
}
