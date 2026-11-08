package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RadialGradient
import android.os.SystemClock
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.roundToInt

/** Native rendering of the cat silhouette and gold/blue palettes supplied in 猫头素材.zip. */
internal class CatHintView(context: Context) : View(context) {
    companion object {
        // Verbatim geometry of 猫头.svg, viewBox 0 0 70 50.
        private val face = Path().apply {
            moveTo(7f, 43f)
            cubicTo(3f, 39f, 3f, 32f, 6f, 25f)
            lineTo(9f, 18f); lineTo(12f, 8f)
            quadTo(14f, 3f, 18f, 7f); lineTo(24f, 14f)
            cubicTo(31f, 11f, 40f, 11f, 47f, 14f)
            lineTo(54f, 7f); quadTo(58f, 3f, 61f, 8f); lineTo(65f, 18f)
            cubicTo(69f, 24f, 71f, 32f, 68f, 39f)
            cubicTo(65f, 46f, 58f, 49f, 49f, 50f)
            lineTo(22f, 50f); cubicTo(15f, 50f, 10f, 48f, 7f, 43f); close()
        }
        internal fun paletteIndex(rank: Int, hasWeight: Boolean): Int =
            if (!hasWeight) 2 else rank.coerceIn(0, 2)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private var action = ""
    private var percent = ""
    private var palette = 2

    init { isClickable = false; isFocusable = false }

    fun bind(action: String, percent: String, rank: Int, hasWeight: Boolean): CatHintView {
        this.action = action
        this.percent = percent
        palette = paletteIndex(rank, hasWeight)
        contentDescription = "$action $percent"
        invalidate()
        return this
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        textPaint.textSize = 10f * resources.displayMetrics.scaledDensity
        val naturalWidth = (42f * density).roundToInt()
        setMeasuredDimension(resolveSize(naturalWidth, widthMeasureSpec),
            resolveSize((35f * density).roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gold = palette == 0
        val top = if (gold) 0xFFFFE47A.toInt() else 0xFF58AEEE.toInt()
        val bottom = if (gold) 0xFFFFD258.toInt() else 0xFF2F79BD.toInt()
        val edge = if (gold) 0xFFFFFBE4.toInt() else 0xFF9AD8FF.toInt()
        // Reserve space INSIDE the view for the crest, so Android clipping cannot hide it.
        // Size the cat against the chip width: the reference crest is prominent,
        // tucked into its upper-right edge rather than a tiny detached ornament.
        val crestW = w * 0.66f
        val crestScale = crestW / 76f
        val crestH = crestScale * 56f
        // Keep the existing body and text geometry while enlarging only the cat.
        val chipTop = h * 0.38f

        val rect = RectF(1f, chipTop, w - 1f, h - 1f)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, chipTop, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, h * 0.10f, h * 0.10f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = edge
        canvas.drawRoundRect(rect, h * 0.10f, h * 0.10f, paint)



        canvas.save()
        val crestLeft = w - crestW - w * 0.025f
        if (gold) {
            val pulse = (0.5 + 0.5 * kotlin.math.sin(SystemClock.uptimeMillis() * Math.PI * 2.0 / 2100.0)).toFloat()
            val cx = crestLeft + crestW / 2f
            val cy = crestH * 0.60f
            val radius = crestW * 0.80f
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(cx, cy, radius,
                intArrayOf(Color.argb((150 + 70 * pulse).toInt(),255,255,224),
                    Color.argb((80 + 65 * pulse).toInt(),255,192,54),Color.TRANSPARENT),
                floatArrayOf(0f,0.45f,1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx,cy,radius,paint)
            paint.shader = null
        }
        canvas.translate(crestLeft, 0f)
        // Leave room for the SVG's five-unit outline inside the crest bounds.
        canvas.scale(crestScale, crestScale)
        canvas.translate(3f, 3f)
        paint.style = Paint.Style.FILL
        paint.color = when (palette) {
            0 -> 0xFFF2A52D.toInt()
            1 -> 0xFF3E568B.toInt()
            else -> 0xFF53B6E2.toInt()
        }
        canvas.drawPath(face, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = if (gold) 5f else 2.5f
        paint.color = when (palette) {
            0 -> 0xFFFFFDF3.toInt()
            1 -> 0xFF75ACD5.toInt()
            else -> 0xFF8EDCFA.toInt()
        }
        canvas.drawPath(face, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(26f, 30f, 5.5f, paint)
        canvas.drawCircle(49f, 30f, 5.5f, paint)
        canvas.restore()

        textPaint.color = if (gold) 0xFF2B2C2E.toInt() else 0xFFF4FBFF.toInt()
        // One row: discard chips read "切 83" (integer probability, no tile name).
        val label = "$action $percent"
        val bodyHeight = (h - chipTop - 2f).coerceAtLeast(1f)
        textPaint.textSize = bodyHeight * 0.80f
        val labelWidth = textPaint.measureText(label)
        val availableWidth = (w - 8f).coerceAtLeast(1f)
        if (labelWidth > availableWidth) textPaint.textSize *= availableWidth / labelWidth
        val baselineOffset = -(textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, w / 2f, chipTop + bodyHeight * 0.5f + baselineOffset, textPaint)
        if (gold && isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateDelayed(40L)
    }
}
