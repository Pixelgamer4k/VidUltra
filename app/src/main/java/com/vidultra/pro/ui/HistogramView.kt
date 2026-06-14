package com.vidultra.pro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.vidultra.pro.R

/**
 * Lightweight RGB + luma histogram view.
 * Feed luma / r / g / b channel arrays (256 bins) via [update].
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paintRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.histogram_red)
        style = Paint.Style.FILL
        alpha = 180
    }
    private val paintGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.histogram_green)
        style = Paint.Style.FILL
        alpha = 180
    }
    private val paintBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.histogram_blue)
        style = Paint.Style.FILL
        alpha = 180
    }
    private val paintLuma = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.histogram_luma)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 220
    }

    private val clipRect = RectF()
    private var luma = IntArray(256)
    private var red = IntArray(256)
    private var green = IntArray(256)
    private var blue = IntArray(256)
    private var maxValue = 1

    fun update(luma: IntArray, red: IntArray, green: IntArray, blue: IntArray) {
        this.luma = luma
        this.red = red
        this.green = green
        this.blue = blue
        maxValue = maxOf(
            luma.maxOrNull() ?: 1,
            red.maxOrNull() ?: 1,
            green.maxOrNull() ?: 1,
            blue.maxOrNull() ?: 1,
            1
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        clipRect.set(0f, 0f, w, h)
        canvas.clipRect(clipRect)

        val binWidth = w / 256f
        drawChannel(canvas, red, paintRed, binWidth, h)
        drawChannel(canvas, green, paintGreen, binWidth, h)
        drawChannel(canvas, blue, paintBlue, binWidth, h)
        drawLine(canvas, luma, paintLuma, binWidth, h)
    }

    private fun drawChannel(canvas: Canvas, data: IntArray, paint: Paint, binW: Float, h: Float) {
        for (i in data.indices) {
            val value = data[i].toFloat() / maxValue
            val barHeight = value * h
            val left = i * binW
            val right = left + binW
            canvas.drawRect(left, h - barHeight, right, h, paint)
        }
    }

    private fun drawLine(canvas: Canvas, data: IntArray, paint: Paint, binW: Float, h: Float) {
        var prevX = 0f
        var prevY = h - (data[0].toFloat() / maxValue * h)
        for (i in 1 until data.size) {
            val x = i * binW
            val y = h - (data[i].toFloat() / maxValue * h)
            canvas.drawLine(prevX, prevY, x, y, paint)
            prevX = x
            prevY = y
        }
    }
}
