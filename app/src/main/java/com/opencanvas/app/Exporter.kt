package com.opencanvas.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object Exporter {
    /** Returns a human-readable location, or null if saving failed. */
    fun savePng(context: Context, bmp: Bitmap, name: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues()
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Carnival"
                )
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                "Pictures/Carnival/$name.png"
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
                val f = File(dir, "$name.png")
                FileOutputStream(f).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                f.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
}
