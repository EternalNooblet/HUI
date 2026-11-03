package com.southpawin.companion.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.southpawin.companion.R

/**
 * PromptKitLoader
 *
 * Implements prompt-kit's Loader component with all 11 variants:
 * - circular, classic, pulse, pulse-dot, dots, typing, wave, bars, terminal, text-blink, text-shimmer, loading-dots
 * - Sizes: sm, md, lg
 * - Text support for text variants
 *
 * prompt-kit spec variants:
 * - circular: border-primary animate-spin rounded-full border-2 border-t-transparent
 * - classic: 12 bars spinning with fade
 * - pulse: expanding ring
 * - pulse-dot: pulsing dot
 * - dots: 3 bouncing dots
 * - typing: 3 dots typing animation
 * - wave: 5 bars wave animation
 * - bars: 3 bars wave animation
 * - terminal: ">" with blinking cursor
 * - text-blink: blinking text
 * - text-shimmer: gradient shimmer text
 * - loading-dots: text with animated dots
 */
class PromptKitLoader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Variant {
        CIRCULAR, CLASSIC, PULSE, PULSE_DOT, DOTS, TYPING, WAVE, BARS, TERMINAL, TEXT_BLINK, TEXT_SHIMMER, LOADING_DOTS
    }

    enum class Size { SM, MD, LG }

    private var variant = Variant.CIRCULAR
    private var size = Size.MD
    private var text = ""
    private var paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var animationProgress = 0f
    private var isAnimating = false

    private val primaryColor: Int
    private val textColor: Int

    init {
        primaryColor = ContextCompat.getColor(context, R.color.pk_primary)
        textColor = ContextCompat.getColor(context, R.color.pk_foreground)
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
    }

    fun setVariant(variant: Variant) {
        this.variant = variant
        resetAnimation()
        invalidate()
    }

    fun setSize(size: Size) {
        this.size = size
        resetAnimation()
        invalidate()
    }

    fun setText(text: String) {
        this.text = text
        invalidate()
    }

    fun start() {
        isAnimating = true
        startAnimation()
    }

    fun stop() {
        isAnimating = false
        animator?.cancel()
    }

    private fun resetAnimation() {
        animator?.cancel()
        if (isAnimating) startAnimation()
    }

    private fun startAnimation() {
        animator?.cancel()
        
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when (variant) {
                Variant.CIRCULAR -> 1000L
                Variant.CLASSIC -> 1200L
                Variant.PULSE -> 1500L
                Variant.PULSE_DOT -> 1200L
                Variant.DOTS -> 1400L
                Variant.TYPING -> 1000L
                Variant.WAVE -> 1000L
                Variant.BARS -> 1200L
                Variant.TERMINAL -> 1000L
                Variant.TEXT_BLINK -> 2000L
                Variant.TEXT_SHIMMER -> 4000L
                Variant.LOADING_DOTS -> 1400L
            }
            repeatCount = ValueAnimator.INFINITE
            repeatMode = when (variant) {
                Variant.PULSE, Variant.PULSE_DOT, Variant.TYPING, Variant.WAVE, Variant.BARS -> ValueAnimator.REVERSE
                else -> ValueAnimator.RESTART
            }
            interpolator = when (variant) {
                Variant.CLASSIC, Variant.DOTS, Variant.WAVE, Variant.BARS -> AccelerateDecelerateInterpolator()
                else -> LinearInterpolator()
            }
            addUpdateListener { animation ->
                animationProgress = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun getSizePx(): Float = when (size) {
        Size.SM -> 24f
        Size.MD -> 32f
        Size.LG -> 40f
    } * resources.displayMetrics.density

    private fun getBarHeight(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.3f
        Size.MD -> getSizePx() * 0.3f
        Size.LG -> getSizePx() * 0.3f
    }

    private fun getBarWidth(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.075f
        Size.MD -> getSizePx() * 0.05f
        Size.LG -> getSizePx() * 0.06f
    }

    private fun getSpacing(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.125f
        Size.MD -> getSizePx() * 0.1875f
        Size.LG -> getSizePx() * 0.25f
    }

    private fun getDotSize(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.1875f
        Size.MD -> getSizePx() * 0.25f
        Size.LG -> getSizePx() * 0.3125f
    }

    private fun getDotsSpacing(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.125f
        Size.MD -> getSizePx() * 0.125f
        Size.LG -> getSizePx() * 0.15625f
    }

    private fun getTypingDotSize(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.125f
        Size.MD -> getSizePx() * 0.1875f
        Size.LG -> getSizePx() * 0.25f
    }

    private fun getTypingSpacing(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.125f
        Size.MD -> getSizePx() * 0.125f
        Size.LG -> getSizePx() * 0.15625f
    }

    private fun getPulseDotSize(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.125f
        Size.MD -> getSizePx() * 0.25f
        Size.LG -> getSizePx() * 0.375f
    }

    private fun getWaveBarWidth(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.0625f
        Size.MD -> getSizePx() * 0.0625f
        Size.LG -> getSizePx() * 0.125f
    }

    private fun getWaveSpacing(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.0625f
        Size.MD -> getSizePx() * 0.0625f
        Size.LG -> getSizePx() * 0.125f
    }

    private fun getWaveHeights(): FloatArray = when (this.size) {
        Size.SM -> floatArrayOf(0.375f, 0.5625f, 0.75f, 0.5625f, 0.375f).map { it * getSizePx() }.toFloatArray()
        Size.MD -> floatArrayOf(0.5f, 0.75f, 1f, 0.75f, 0.5f).map { it * getSizePx() }.toFloatArray()
        Size.LG -> floatArrayOf(0.625f, 0.9375f, 1.25f, 0.9375f, 0.625f).map { it * getSizePx() }.toFloatArray()
    }

    private fun getBarsBarWidth(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.125f
        Size.MD -> getSizePx() * 0.1875f
        Size.LG -> getSizePx() * 0.25f
    }

    private fun getBarsSpacing(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.125f
        Size.MD -> getSizePx() * 0.1875f
        Size.LG -> getSizePx() * 0.25f
    }

    private fun getTerminalTextSize(): Float = when (this.size) {
        Size.SM -> 12f
        Size.MD -> 16f
        Size.LG -> 20f
    }

    private fun getDotsDotSize(): Float = when (this.size) {
        Size.SM -> getSizePx() * 0.1875f
        Size.MD -> getSizePx() * 0.25f
        Size.LG -> getSizePx() * 0.3125f
    }

    private fun getCircularSize(): Float = getSizePx()

    private fun getPulseMaxRadius(): Float = getSizePx() / 2f

    private fun getPulseStrokeWidth(): Float = (getSizePx() * 0.125f).toFloat()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        when (variant) {
            Variant.CIRCULAR -> drawCircular(canvas)
            Variant.CLASSIC -> drawClassic(canvas)
            Variant.PULSE -> drawPulse(canvas)
            Variant.PULSE_DOT -> drawPulseDot(canvas)
            Variant.DOTS -> drawDots(canvas)
            Variant.TYPING -> drawTyping(canvas)
            Variant.WAVE -> drawWave(canvas)
            Variant.BARS -> drawBars(canvas)
            Variant.TERMINAL -> drawTerminal(canvas)
            Variant.TEXT_BLINK -> drawTextBlink(canvas)
            Variant.TEXT_SHIMMER -> drawTextShimmer(canvas)
            Variant.LOADING_DOTS -> drawLoadingDots(canvas)
        }
    }

    private fun drawCircular(canvas: Canvas) {
        val size = getCircularSize()
        val strokeWidth = (size * 0.125f).toFloat().coerceAtLeast(2f)
        val radius = (size - strokeWidth) / 2f
        
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = primaryColor
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        
        // Draw the animated arc (spinning)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = primaryColor
        val sweepAngle = 270f
        canvas.drawArc(
            width / 2f - radius, height / 2f - radius,
            width / 2f + radius, height / 2f + radius,
            360f * animationProgress - 90f, sweepAngle, false, paint
        )
    }

    private fun drawClassic(canvas: Canvas) {
        val sizePx = getSizePx()
        val barHeight = getBarHeight()
        val barWidth = getBarWidth()
        
        paint.color = primaryColor
        paint.style = Paint.Style.FILL
        
        for (i in 0 until 12) {
            val angle = i * 30f + 360f * animationProgress
            val angleRad = Math.toRadians(angle.toDouble()).toFloat()
            val x = width.toFloat() / 2f + (sizePx * 0.25f) * kotlin.math.cos(angleRad)
            val y = height.toFloat() / 2f + (sizePx * 0.25f) * kotlin.math.sin(angleRad)
            val alpha = (1f - animationProgress * 0.5f) * (1f - i / 12f)
            paint.alpha = (255 * alpha).toInt()
            canvas.drawRect(
                x - barWidth / 2f, y - barHeight / 2f,
                x + barWidth / 2f, y + barHeight / 2f,
                paint
            )
        }
    }

    private fun drawPulse(canvas: Canvas) {
        val sizePx = getSizePx()
        val maxRadius = getPulseMaxRadius()
        val radius = maxRadius * animationProgress
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = getPulseStrokeWidth()
        paint.color = primaryColor
        paint.alpha = (255 * (1 - animationProgress)).toInt()
        canvas.drawCircle(width.toFloat() / 2f, height.toFloat() / 2f, radius, paint)
    }

    private fun drawPulseDot(canvas: Canvas) {
        val sizePx = getSizePx()
        val dotSize = getPulseDotSize()
        val scale = 0.5f + 0.5f * kotlin.math.sin((2 * Math.PI * animationProgress).toFloat())
        paint.color = primaryColor
        canvas.drawCircle(width.toFloat() / 2f, height.toFloat() / 2f, dotSize * scale, paint)
    }

    private fun drawDots(canvas: Canvas) {
        val sizePx = getSizePx()
        val dotSize = getDotsDotSize()
        val spacing = getDotsSpacing()
        
        paint.color = primaryColor
        for (i in 0 until 3) {
            val delay = i * 160
            val animProgress = (animationProgress * 1000 + delay) % 1400 / 1400f
            val offset = (1f - kotlin.math.cos(2 * Math.PI * animProgress)) * spacing * 2
            val x = (width.toFloat() / 2f + (i - 1) * (getDotsDotSize() + spacing)).toFloat()
            val y = (height.toFloat() / 2f - offset).toFloat()
            paint.alpha = 255
            canvas.drawCircle(x, y, getDotsDotSize(), paint)
        }
    }

    private fun drawTyping(canvas: Canvas) {
        val sizePx = getSizePx()
        val dotSize = getTypingDotSize()
        val spacing = getTypingSpacing()
        
        paint.color = primaryColor
        for (i in 0 until 3) {
            val delay = i * 250
            val animProgress = (animationProgress * 1000 + delay) % 1000 / 1000f
            val yOffset = (1f - kotlin.math.cos(2 * Math.PI * animProgress)) * spacing
            val x = (width.toFloat() / 2f + (i - 1) * (getTypingDotSize() + spacing)).toFloat()
            val y = (height.toFloat() / 2f - yOffset).toFloat()
            canvas.drawCircle(x, y, getTypingDotSize(), paint)
        }
    }

    private fun drawWave(canvas: Canvas) {
        val sizePx = getSizePx()
        val barWidth = getWaveBarWidth()
        val heights = getWaveHeights()
        val spacing = getWaveSpacing()
        
        paint.color = primaryColor
        for (i in 0 until 5) {
            val delay = i * 100
            val animProgress = (animationProgress * 1000 + delay) % 1000 / 1000f
            val barHeight = heights[i] * (0.5f + 0.5f * kotlin.math.sin(2 * Math.PI * animProgress))
            val x = width.toFloat() / 2f + (i - 2).toFloat() * (barWidth + spacing)
            val y = height.toFloat() / 2f
            canvas.drawRect(
                x - (barWidth / 2f).toFloat(), y - (barHeight / 2f).toFloat(),
                x + (barWidth / 2f).toFloat(), y + (barHeight / 2f).toFloat(),
                paint
            )
        }
    }

    private fun drawBars(canvas: Canvas) {
        val sizePx = getSizePx()
        val barWidth = getBarsBarWidth()
        val spacing = getBarsSpacing()
        
        paint.color = primaryColor
        for (i in 0 until 3) {
            val delay = i * 200
            val animProgress = (animationProgress * 1000 + delay) % 1200 / 1200f
            val barHeight = sizePx * (0.5f + 0.5f * kotlin.math.sin(2 * Math.PI * animProgress))
            val x = width.toFloat() / 2f + (i - 1).toFloat() * (barWidth + spacing)
            val y = height.toFloat() / 2f
            canvas.drawRect(
                x - (barWidth / 2f).toFloat(), y - (barHeight / 2f).toFloat(),
                x + (barWidth / 2f).toFloat(), y + (barHeight / 2f).toFloat(),
                paint
            )
        }
    }

    private fun drawTerminal(canvas: Canvas) {
        val textSize = getTerminalTextSize()
        paint.textSize = textSize
        paint.color = primaryColor
        paint.typeface = android.graphics.Typeface.MONOSPACE
        
        // Draw ">"
        canvas.drawText(">", width.toFloat() / 2f - 20, height.toFloat() / 2f + textSize / 3, paint)
        
        // Blinking cursor
        val cursorVisible = (animationProgress * 2) % 1f < 0.5f
        if (cursorVisible) {
            paint.style = Paint.Style.FILL
            canvas.drawRect(
                width.toFloat() / 2f + 2, height.toFloat() / 2f - textSize * 0.7f,
                width.toFloat() / 2f + 2 + textSize * 0.25f, height.toFloat() / 2f + textSize * 0.3f,
                paint
            )
        }
    }

    private fun drawTextBlink(canvas: Canvas) {
        val textToShow = if (text.isNotEmpty()) text else context.getString(R.string.loader_default)
        val textSize = getTerminalTextSize()
        paint.textSize = textSize
        paint.color = primaryColor
        paint.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
        
        val visible = (animationProgress * 2) % 1f < 0.5f
        if (visible) {
            val textWidth = paint.measureText(textToShow)
            canvas.drawText(textToShow, (width - textWidth) / 2f, height.toFloat() / 2f + textSize / 3, paint)
        }
    }

    private fun drawTextShimmer(canvas: Canvas) {
        val textToShow = if (text.isNotEmpty()) text else context.getString(R.string.loader_default)
        val textSize = getTerminalTextSize()
        paint.textSize = textSize
        paint.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
        
        val textWidth = paint.measureText(textToShow)
        val x = (width - textWidth) / 2f
        
        // Shimmer shader
        val shader = android.graphics.LinearGradient(
            -textWidth + 200f * animationProgress, 0f,
            textWidth + 200f * animationProgress, 0f,
            intArrayOf(ContextCompat.getColor(context, R.color.pk_muted_foreground),
                       ContextCompat.getColor(context, R.color.pk_foreground),
                       ContextCompat.getColor(context, R.color.pk_muted_foreground)),
            floatArrayOf(0.3f, 0.5f, 0.7f),
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawText(textToShow, x, height.toFloat() / 2f + textSize / 3, paint)
        paint.shader = null
    }

    private fun drawLoadingDots(canvas: Canvas) {
        val textToShow = if (text.isNotEmpty()) text else context.getString(R.string.loader_default)
        val textSize = getTerminalTextSize()
        paint.textSize = textSize
        paint.color = primaryColor
        paint.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
        
        val baseX = (width - paint.measureText(textToShow + "...")) / 2f
        val y = height.toFloat() / 2f + paint.textSize / 3
        
        // Base text
        canvas.drawText(textToShow, (width - paint.measureText(textToShow + "   ")) / 2f, y, paint)
        
        // Animated dots
        for (i in 0 until 3) {
            val delay = (i + 1) * 200
            val animProgress = (animationProgress * 1000 + delay) % 1400 / 1400f
            val alpha = if (animProgress < 0.5f) 1f else 0f
            paint.alpha = (255 * alpha).toInt()
            val dotX = (width + paint.measureText(textToShow)) / 2f + i * paint.measureText(".")
            canvas.drawText(".", dotX, y, paint)
        }
        paint.alpha = 255
    }
}