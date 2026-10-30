package com.opencanvas.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The start screen: every saved drawing as a card, plus a New drawing button. */
class GalleryActivity : Activity() {
    private lateinit var store: ProjectStore
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var loadToken = 0

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF1E1E1E.toInt())

        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0xFF111111.toInt())
        bar.setPadding(dp(16), dp(10), dp(12), dp(10))
        val title = TextView(this)
        title.text = "Gallery"
        title.setTextColor(Color.WHITE)
        title.textSize = 20f
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Ui.chip(this, "+ New drawing", 14) { newDrawing() })
        page.addView(bar)

        scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(10), dp(10), dp(10), dp(24))
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun newDrawing() {
        NewDrawingDialog.show(this) { name, w, h, dpi ->
            val meta = store.create(name, w, h, dpi)
            open(meta.id)
        }
    }

    private fun open(id: String) {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra(MainActivity.EXTRA_PROJECT, id)
        startActivity(i)
    }

    private class Card(val meta: ProjectMeta, val thumb: Bitmap?)

    private fun reload() {
        val token = ++loadToken
        Thread {
            val cards = store.list().map { m ->
                val f = store.thumbFile(m.id)
                val bmp = if (f.exists()) {
                    try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Throwable) { null }
                } else null
                Card(m, bmp)
            }
            runOnUiThread { if (token == loadToken) show(cards) }
        }.start()
    }

    private fun show(cards: List<Card>) {
        content.removeAllViews()
        if (cards.isEmpty()) {
            val t = TextView(this)
            t.text = "No drawings yet.\nTap \"+ New drawing\" to start."
            t.setTextColor(0xFFAAAAAA.toInt())
            t.textSize = 16f
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(120), 0, 0)
            content.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        val screenDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val columns = (screenDp / 170f).toInt().coerceIn(2, 6)
        var row: LinearLayout? = null
        for ((i, c) in cards.withIndex()) {
            if (i % columns == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(5), dp(5), dp(5), dp(5))
            row!!.addView(card(c), lp)
        }
        val missing = (columns - cards.size % columns) % columns
        for (k in 0 until missing) {
            row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).also { it.setMargins(dp(5), 0, dp(5), 0) })
        }
    }

    private fun card(c: Card): View {
        val m = c.meta
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = Ui.rounded(0xFF2C2C2E.toInt(), dp(12).toFloat())
        box.setPadding(dp(8), dp(8), dp(8), dp(8))
        box.setOnClickListener { open(m.id) }

        val frame = FrameLayout(this)
        frame.setBackgroundColor(0xFF3A3A3C.toInt())
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        if (c.thumb != null) img.setImageBitmap(c.thumb) else img.setBackgroundColor(Color.WHITE)
        frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = m.name
        name.setTextColor(Color.WHITE)
        name.textSize = 14f
        name.setSingleLine()
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        val sub = TextView(this)
        val ago = DateUtils.getRelativeTimeSpanString(
            m.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        sub.text = "${m.width} x ${m.height} - $ago"
        sub.setTextColor(0xFF9A9A9E.toInt())
        sub.textSize = 11f
        sub.setSingleLine()
        sub.ellipsize = android.text.TextUtils.TruncateAt.END
        texts.addView(name)
        texts.addView(sub)
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val dots = IconView(this, IconView.DOTS)
        dots.setOnClickListener { cardMenu(dots, m) }
        line.addView(dots, LinearLayout.LayoutParams(dp(32), dp(32)))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        box.addView(line, lp)
        return box
    }

    private fun cardMenu(anchor: View, m: ProjectMeta) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add("Rename")
        pm.menu.add("Duplicate")
        pm.menu.add("Delete")
        pm.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Rename" -> rename(m)
                "Duplicate" -> duplicate(m)
                "Delete" -> confirmDelete(m)
            }
            true
        }
        pm.show()
    }

    private fun rename(m: ProjectMeta) {
        val box = EditText(this)
        box.setText(m.name)
        box.setSingleLine()
        box.setSelectAllOnFocus(true)
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ ->
                val nm = box.text.toString().trim()
                if (nm.isNotEmpty()) {
                    Thread { store.rename(m.id, nm); runOnUiThread { reload() } }.start()
                }
            }
            .show()
    }

    private fun duplicate(m: ProjectMeta) {
        Toast.makeText(this, "Duplicating...", Toast.LENGTH_SHORT).show()
        Thread {
            store.duplicate(m.id, m.name + " copy")
            runOnUiThread { reload() }
        }.start()
    }

    private fun confirmDelete(m: ProjectMeta) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${m.name}\"?")
            .setMessage("This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                Thread { store.delete(m.id); runOnUiThread { reload() } }.start()
            }
            .show()
    }
}
