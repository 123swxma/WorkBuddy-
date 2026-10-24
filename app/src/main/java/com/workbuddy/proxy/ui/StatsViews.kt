package com.workbuddy.proxy.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * 迷你趋势线：折线 + 向下渐隐的面积填充。
 * 数据点按最大值归一化，全 0 时画一条贴底的平线。
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values: List<Float> = emptyList()
    private var strokeColor: Int = Color.parseColor("#4FC3F7")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val fillPath = Path()

    fun setData(data: List<Float>, color: Int) {
        values = data
        strokeColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val pts = if (values.size < 2) {
            // 少于两个点画一条贴底的平线，避免空白
            listOf(0f, 0f)
        } else values

        var max = 0f
        for (v in pts) if (v > max) max = v
        val scale = if (max <= 0f) 0f else 1f

        val padTop = dp(2f)
        val padBottom = dp(2f)
        val usable = h - padTop - padBottom
        val stepX = w / (pts.size - 1).coerceAtLeast(1)

        path.reset()
        fillPath.reset()
        for (i in pts.indices) {
            val x = stepX * i
            val ratio = if (max <= 0f) 0f else (pts[i] / max) * scale
            val y = padTop + usable * (1f - ratio)
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, padTop, 0f, h,
            withAlpha(strokeColor, 0x40), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        linePaint.color = strokeColor
        canvas.drawPath(path, linePaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

/**
 * 成功率环形：底环 + 进度弧 + 中心百分比。
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var percent = 0f
    private var ringColor = Color.parseColor("#2E7D32")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#33808080")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#8B9BA8")
    }
    private val rect = RectF()

    fun setPercent(value: Float, color: Int) {
        percent = value.coerceIn(0f, 100f)
        ringColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0) return

        val stroke = size * 0.10f
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        arcPaint.color = ringColor

        val inset = stroke / 2f + size * 0.02f
        rect.set(
            (width - size) / 2f + inset,
            (height - size) / 2f + inset,
            (width + size) / 2f - inset,
            (height + size) / 2f - inset
        )

        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        if (percent > 0f) {
            canvas.drawArc(rect, -90f, 360f * percent / 100f, false, arcPaint)
        }

        textPaint.textSize = size * 0.20f
        textPaint.color = ringColor
        val cy = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("${percent.toInt()}%", width / 2f, cy, textPaint)

        subPaint.textSize = size * 0.10f
        canvas.drawText("成功", width / 2f, height / 2f + size * 0.22f, subPaint)
    }
}
