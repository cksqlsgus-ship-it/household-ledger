package com.myledger.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.text.NumberFormat
import java.util.Locale

data class PieSlice(
    val label: String,
    val value: Long,
    val color: Int
)

class PieChartView(
    context: Context,
    private val slices: List<PieSlice>,
    private val holeColor: Int,
    private val primaryTextColor: Int,
    private val secondaryTextColor: Int
) : View(context) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = holeColor
    }
    private val sliceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val centerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryTextColor
        textAlign = Paint.Align.CENTER
    }
    private val centerValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryTextColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) * 0.44f
        val innerRadius = radius * 0.54f
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        val total = slices.sumOf { it.value }
        if (total <= 0L) {
            arcPaint.color = Color.parseColor("#3A3A3C")
            canvas.drawCircle(cx, cy, radius, arcPaint)
            canvas.drawCircle(cx, cy, innerRadius, holePaint)
            centerTitlePaint.textSize = radius * 0.16f
            canvas.drawText("표시할 데이터 없음", cx, cy + (radius * 0.05f), centerTitlePaint)
            return
        }

        val gapAngle = if (slices.size > 1) 1.5f else 0f
        var startAngle = -90f
        sliceTextPaint.textSize = radius * 0.13f

        for (slice in slices) {
            val rawSweep = (slice.value.toFloat() / total.toFloat()) * 360f
            val sweep = (rawSweep - gapAngle).coerceAtLeast(1f)
            arcPaint.color = slice.color
            canvas.drawArc(oval, startAngle, sweep, true, arcPaint)

            if (rawSweep >= 26f) {
                val midRad = Math.toRadians((startAngle + rawSweep / 2f).toDouble())
                val textR = (radius + innerRadius) / 2f
                val tx = cx + (textR * Math.cos(midRad)).toFloat()
                val ty = cy + (textR * Math.sin(midRad)).toFloat() + (sliceTextPaint.textSize * 0.35f)
                val pctInt = ((slice.value.toDouble() / total.toDouble()) * 100).toInt()
                canvas.drawText("$pctInt%", tx, ty, sliceTextPaint)
            }
            startAngle += rawSweep
        }

        canvas.drawCircle(cx, cy, innerRadius, holePaint)
        val nf = NumberFormat.getNumberInstance(Locale.KOREA)
        centerTitlePaint.textSize = radius * 0.13f
        centerValuePaint.textSize = radius * 0.16f

        canvas.drawText("선택 합계", cx, cy - (radius * 0.05f), centerTitlePaint)
        canvas.drawText("${nf.format(total)}원", cx, cy + (radius * 0.14f), centerValuePaint)
    }
}
