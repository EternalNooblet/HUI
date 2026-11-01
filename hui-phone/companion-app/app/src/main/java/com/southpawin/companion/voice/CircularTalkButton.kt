package com.southpawin.companion.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.southpawin.companion.R
import kotlin.math.min

class CircularTalkButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF1565C0.toInt() // Darker blue for pressed state
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt() // White text
        textAlign = Paint.Align.CENTER
        textSize = 16f * resources.displayMetrics.density
    }
    private val textRect = Rect()
    private var isPressed = false
    private var buttonText = "Talk"

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FFFFFF.toInt() // White ripple with 20% opacity
    }
    private var rippleRadius = 0f
    private var rippleAnimating = false

    init {
        isClickable = true
        setOnClickListener { performClick() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width.toFloat(), height.toFloat()) / 2f

        // Draw background circle
        canvas.drawCircle(cx, cy, radius, if (isPressed) pressedPaint else backgroundPaint)

        // Draw ripple if animating
        if (rippleAnimating) {
            canvas.drawCircle(cx, cy, rippleRadius, ripplePaint)
        }

        // Draw text centered
        textPaint.getTextBounds(buttonText, 0, buttonText.length, textRect)
        val textX = cx
        val textY = cy + textRect.height() / 2f
        canvas.drawText(buttonText, textX, textY, textPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    fun setButtonText(text: String) {
        buttonText = text
        invalidate()
    }

    fun setPressedState(pressed: Boolean) {
        isPressed = pressed
        invalidate()
    }

    fun startRipple() {
        rippleRadius = 0f
        rippleAnimating = true
        animateRipple()
    }

    private fun animateRipple() {
        rippleRadius += 8f * resources.displayMetrics.density
        if (rippleRadius < min(width.toFloat(), height.toFloat()) / 2f) {
            invalidate()
            postDelayed(::animateRipple, 16)
        } else {
            rippleAnimating = false
            rippleRadius = 0f
        }
    }

    override fun performClick(): Boolean {
        startRipple()
        return super.performClick()
    }
}