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
 * Implements prompt-kit TextShimmer (upstream components/prompt-kit/text-shimmer.tsx):
 * - bg-size-[200%_auto] bg-clip-text font-medium text-transparent
 * - animate-[shimmer_4s_infinite_linear], duration default 4s
 * - gradient: linear-gradient(to right, muted-foreground (50-spread)%, foreground 50%, muted-foreground (50+spread)%)
 * - spread default 20, clamped to 5..45
 *
 * Android: LinearGradient shader drawn left-aligned (inline text, not centered).
 * Shader is rebuilt every frame so the 200% sweep actually moves.
 */
class TextShimmerTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animationOffset = 0f // 0..200, % of textWidth traversed
    private var animationDuration = 4000L // 4s upstream default
    private var spread = 20
    private var isAnimating = false
    private var animationRunnable: Runnable? = null
    private var text: String = ""

    init {
        initView()
    }

    private fun initView() {
        // text-sm = 13sp (pk_text_sm). font-medium ≈ normal weight on API 27.
        textPaint.textSize = resources.getDimension(R.dimen.pk_text_sm)
        textPaint.color = ContextCompat.getColor(context, R.color.pk_foreground)
        textPaint.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.NORMAL)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (text.isEmpty()) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val tw = textPaint.measureText(text).toInt()
        val th = (textPaint.descent() - textPaint.ascent()).toInt()
        val pad = 2
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) measuredWidth
                else tw + pad
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) measuredHeight
                else th + pad
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    fun setText(text: String) {
        this.text = text
        requestLayout()
        invalidate()
    }

    fun setTextColor(color: Int) {
        textPaint.color = color
        invalidate()
    }

    fun setTextSize(sp: Float) {
        textPaint.textSize = sp * resources.displayMetrics.scaledDensity
        requestLayout()
    }

    fun setAnimationDuration(durationMs: Long) {
        animationDuration = durationMs
    }

    fun setSpread(spreadPercent: Int) {
        spread = spreadPercent.coerceIn(5, 45)
        invalidate()
    }

    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        animationRunnable = object : Runnable {
            override fun run() {
                if (!isAnimating) return
                // 4s per full 200% sweep (bg-size-[200%_auto]), linear.
                animationOffset = (animationOffset + 200f * 16f / animationDuration) % 200f
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
        if (text.isEmpty()) return
        val textWidth = textPaint.measureText(text)
        if (textWidth <= 0) return

        // Upstream: gradient stops at (50-spread)%, 50%, (50+spread)% over a
        // 200%-wide background sweep. Tile 200% so the band travels across text.
        val sweepPx = textWidth * 2f
        val offsetPx = animationOffset / 100f * textWidth
        val startColor = ContextCompat.getColor(context, R.color.pk_muted_foreground)
        val centerColor = ContextCompat.getColor(context, R.color.pk_foreground)
        val c = textWidth / 2f + offsetPx
        val half = textWidth * spread / 100f
        val shader = LinearGradient(
            c - half - textWidth / 2f, 0f,
            c + half + textWidth / 2f, 0f,
            intArrayOf(
                startColor,
                centerColor,
                startColor
            ),
            floatArrayOf(
                (c - half - textWidth / 2f) / sweepPx,
                0.5f,
                (c + half + textWidth / 2f) / sweepPx
            ),
            Shader.TileMode.REPEAT
        )
        textPaint.shader = shader
        val y = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, 0f, y, textPaint)
        textPaint.shader = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}