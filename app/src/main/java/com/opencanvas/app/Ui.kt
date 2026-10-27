package com.opencanvas.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

object Ui {
    val ACCENT = 0xFF0A84FF.toInt()
    val BUTTON = 0xFF3A3A3C.toInt()
    val PANEL = 0xF01C1C1E.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusPx: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radiusPx
        return d
    }

    fun chip(ctx: Context, label: String, hPad: Int = 10, onClick: () -> Unit): TextView {
        val t = TextView(ctx)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 13f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(ctx, hPad), dp(ctx, 8), dp(ctx, hPad), dp(ctx, 8))
        t.background = rounded(BUTTON, dp(ctx, 8).toFloat())
        t.setOnClickListener { onClick() }
        return t
    }

    fun setActive(ctx: Context, t: TextView, on: Boolean) {
        t.background = rounded(if (on) ACCENT else BUTTON, dp(ctx, 8).toFloat())
    }
}

/** Procreate-style vertical slider: value 1 at the top, 0 at the bottom. */
class VerticalSlider(context: Context) : View(context) {
    var onChange: ((Float) -> Unit)? = null
    var onFinish: (() -> Unit)? = null

    var value: Float = 0.5f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val d = context.resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC2B2B2B.toInt() }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6E6E6.toInt() }
    private val rect = RectF()
    private val knobH = 30f * d

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, w / 2f, w / 2f, trackPaint)
        val top = (1f - value) * (h - knobH)
        rect.set(4f * d, top + 2f * d, w - 4f * d, top + knobH - 2f * d)
        canvas.drawRoundRect(rect, 8f * d, 8f * d, knobPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val usable = height - knobH
                if (usable > 0f) {
                    value = 1f - ((e.y - knobH / 2f) / usable)
                    onChange?.invoke(value)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onFinish?.invoke()
        }
        return true
    }
}

/** Saturation/brightness square with a hue bar underneath. */
class ColorPickerView(context: Context) : View(context) {
    var onColor: ((Int) -> Unit)? = null
    var onFinish: (() -> Unit)? = null

    private val hsv = floatArrayOf(0f, 0f, 0f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val svRect = RectF()
    private val hueRect = RectF()
    private var mode = 0
    private val d = context.resources.displayMetrics.density
    private val hueColors = intArrayOf(
        0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
        0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt()
    )

    fun setColor(c: Int) {
        Color.colorToHSV(c, hsv)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = 6f * d
        val barH = 28f * d
        svRect.set(pad, pad, w - pad, h - pad * 2f - barH)
        hueRect.set(pad, h - pad - barH, w - pad, h - pad)
    }

    private fun marker(canvas: Canvas, x: Float, y: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * d
        paint.color = Color.BLACK
        canvas.drawCircle(x, y, 9f * d, paint)
        paint.strokeWidth = 1.5f * d
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 9f * d, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val corner = 8f * d
        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        paint.shader = LinearGradient(
            svRect.left, 0f, svRect.right, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(svRect, corner, corner, paint)
        paint.shader = LinearGradient(
            0f, svRect.top, 0f, svRect.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(svRect, corner, corner, paint)

        paint.shader = LinearGradient(
            hueRect.left, 0f, hueRect.right, 0f, hueColors, null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(hueRect, corner, corner, paint)
        paint.shader = null

        marker(canvas, svRect.left + hsv[1] * svRect.width(), svRect.top + (1f - hsv[2]) * svRect.height())
        marker(canvas, hueRect.left + hsv[0] / 360f * hueRect.width(), hueRect.centerY())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            mode = if (svRect.contains(e.x, e.y)) 1 else if (e.y >= hueRect.top - 8f * d) 2 else 0
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            if (mode == 1) {
                hsv[1] = ((e.x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
                hsv[2] = 1f - ((e.y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
            } else if (mode == 2) {
                hsv[0] = ((e.x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 359.9f
            }
            if (mode != 0) {
                invalidate()
                onColor?.invoke(Color.HSVToColor(hsv))
            }
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (mode != 0) onFinish?.invoke()
            mode = 0
        }
        return true
    }
}

/** Small preview of one layer for the layers panel. */
class LayerThumb(context: Context, private val layer: Layer) : View(context) {
    private val paper = Paint().apply { color = Color.WHITE }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect(0, 0, Document.SIZE, Document.SIZE)
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRect(dst, paper)
        canvas.drawBitmap(layer.bitmap, src, dst, bmp)
    }
}
