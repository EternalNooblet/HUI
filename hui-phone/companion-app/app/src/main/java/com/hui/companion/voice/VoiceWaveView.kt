package com.hui.companion.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.hui.companion.R
import kotlin.math.min

class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val backgroundPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface)
    }

    private var rmsHistory = FloatArray(60) { 0f }
    private var writeIndex = 0
    private var maxRms = 0.001f
    private val DECAY_FACTOR = 0.95f
    private val BAR_COUNT = 60
    private val BAR_SPACING_DP = 2
    private val barSpacingPx: Int

    init {
        val density = resources.displayMetrics.density
        barSpacingPx = (BAR_SPACING_DP * density).toInt()
        setWillNotDraw(false)
    }

    fun feedRms(rms: Float) {
        rmsHistory[writeIndex] = rms.coerceAtMost(1.0f)
        writeIndex = (writeIndex + 1) % BAR_COUNT
        maxRms = maxRms * DECAY_FACTOR + rms * (1 - DECAY_FACTOR)
        if (maxRms < 0.001f) maxRms = 0.001f
        postInvalidateOnAnimation()
    }

    fun reset() {
        rmsHistory = FloatArray(BAR_COUNT) { 0f }
        writeIndex = 0
        maxRms = 0.001f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2
        val availableWidth = width - barSpacingPx * (BAR_COUNT - 1)
        val barWidth = availableWidth / BAR_COUNT

        canvas.drawRect(0f, 0f, width, height, backgroundPaint)

        for (i in 0 until BAR_COUNT) {
            val idx = (writeIndex - 1 - i + BAR_COUNT) % BAR_COUNT
            val normalized = rmsHistory[idx] / maxRms
            val barHeight = (normalized * height * 0.9f / 2).coerceAtMost(height / 2)
            val x = i * (barWidth + barSpacingPx)
            val rect = Rect(
                x.toInt(),
                (centerY - barHeight).toInt(),
                (x + barWidth).toInt(),
                (centerY + barHeight).toInt()
            )
            canvas.drawRect(rect, barPaint)
        }
    }
}