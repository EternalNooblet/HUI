package com.southpawin.companion.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.southpawin.companion.R

/**
 * TextShimmerTextView
 *
 * Implements prompt-kit's TextShimmer component:
 * - CSS gradient: linear-gradient(to right, var(--muted-foreground) X%, var(--foreground) 50%, var(--muted-foreground) Y%)
 * - bg-size-[200%] bg-clip-text text-transparent animate-[shimmer_4s_infinite_linear]
 * - spread parameter controls the width of the highlight (default 20%)
 *
 * prompt-kit spec:
 * - dynamicSpread = clamp(spread, 5, 45)
 * - backgroundImage: linear-gradient(to right, muted-foreground (50-dynamicSpread)%, foreground 50%, muted-foreground (50+dynamicSpread)%)
 * - animationDuration: 4s (configurable)
 */
class TextShimmerTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shimmerShader: LinearGradient? = null
    private var animationOffset = 0f
    private var animationDuration = 4000L // 4s default
    private var spread = 20 // default spread 20%
    private var isAnimating = false
    private var animationRunnable: Runnable? = null

    init {
        initView()
    }

    private fun initView() {
        // Default text appearance
        textPaint.textSize = 16f
        textPaint.color = ContextCompat.getColor(context, R.color.pk_foreground)
        textPaint.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
        
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setText(text: String) {
        // Store text for drawing
        tag = text
        invalidate()
        updateShader()
    }

    fun setTextColor(color: Int) {
        textPaint.color = color
        updateShader()
    }

    fun setTextSize(sp: Float) {
        textPaint.textSize = sp * resources.displayMetrics.scaledDensity
        updateShader()
        invalidate()
    }

    fun setAnimationDuration(durationMs: Long) {
        animationDuration = durationMs
    }

    fun setSpread(spreadPercent: Int) {
        spread = spreadPercent.coerceIn(5, 45)
        updateShader()
    }

    fun startAnimation() {
        isAnimating = true
        animationRunnable = object : Runnable {
            override fun run() {
                if (!isAnimating) return
                animationOffset = (animationOffset + 2f) % 200f // 200% width
                invalidate()
                postDelayed(this, 16) // ~60fps
            }
        }
        post(animationRunnable!!)
    }

    fun stopAnimation() {
        isAnimating = false
        animationRunnable?.let { removeCallbacks(it) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val text = tag as? String ?: return
        if (text.isEmpty()) return

        // Draw shimmer effect
        val textWidth = textPaint.measureText(text)
        val x = (width - textWidth) / 2f
        val y = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f

        // Apply shader for shimmer
        if (shimmerShader != null) {
            textPaint.shader = shimmerShader
        }

        canvas.drawText(text, x, y, textPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()
    }

    private fun updateShader() {
        val text = tag as? String ?: return
        if (text.isEmpty()) return

        val textWidth = textPaint.measureText(text)
        if (textWidth <= 0) return

        // prompt-kit: linear-gradient(to right, muted-foreground (50-spread)%, foreground 50%, muted-foreground (50+spread)%)
        // Android shader coordinates are in pixels, so we map 0-100% to 0-textWidth
        val spreadPx = textWidth * spread / 100f
        val centerPx = textWidth / 2f
        val startColor = ContextCompat.getColor(context, R.color.pk_muted_foreground)
        val centerColor = ContextCompat.getColor(context, R.color.pk_foreground)
        val endColor = ContextCompat.getColor(context, R.color.pk_muted_foreground)

        shimmerShader = LinearGradient(
            -textWidth + animationOffset * textWidth / 100f, 0f, // Start off-screen for smooth entry
            textWidth * 2f + animationOffset * textWidth / 100f, 0f, // End off-screen
            intArrayOf(startColor, centerColor, startColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}