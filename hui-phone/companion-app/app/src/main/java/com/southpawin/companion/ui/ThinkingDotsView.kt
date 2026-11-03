package com.southpawin.companion.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.southpawin.companion.R

/**
 * ThinkingDotsView — dots filling from end of shimmer text to edge.
 *
 * Prompt-kit basis: Loader dots/typing variants (size md = 8dp dot, space-x-1,
 * 160ms stagger, ~1.4s cycle). This bar stretches that pattern across the
 * available width: dots appear left-to-right in sequence, then the cycle
 * restarts. Color is mutated foreground, matching the shimmer's base color.
 */
class ThinkingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var progress = 0f // 0..1 across one cycle
    private var isAnimating = false

    private val dotColor: Int
        get() = ContextCompat.getColor(context, R.color.pk_muted_foreground)

    init {
        paint.style = Paint.Style.FILL
    }

    private fun dotSpacingPx() = 8f * resources.displayMetrics.density
    private fun dotRadiusPx() = 2f * resources.displayMetrics.density

    private fun dotCount(): Int {
        val spacing = dotSpacingPx()
        if (spacing <= 0) return 0
        return maxOf(3, (width / spacing).toInt())
    }

    fun start() {
        if (isAnimating) return
        isAnimating = true
        val cycleMs = dotCount().coerceAtLeast(3) * 60L + 700L
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = cycleMs
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun stop() {
        isAnimating = false
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = dotCount()
        if (n == 0) return
        // Appear sequentially: first fraction fills left-to-right, hold, then
        // one quick fade before the infinite restart. Matches "dots appear".
        val fillFrac = 0.5f
        val holdFrac = 0.85f
        val reveal = progress / fillFrac // 0..>1 during fill
        val fadingOut = progress > holdFrac
        val fadeAlpha = if (fadingOut) ((1f - progress) / (1f - holdFrac)) else 1f

        val spacing = dotSpacingPx()
        val r = dotRadiusPx()
        val cy = height / 2f
        val startX = spacing / 2f + r

        for (i in 0 until n) {
            val t = reveal * n - i // 0..1 for dot i (negative = not yet)
            if (t <= 0f) continue
            val a = (t.coerceAtMost(1f) * fadeAlpha * 255).toInt()
            paint.color = dotColor
            paint.alpha = a
            // Dots are drawn as circles; the cycle fade makes all reset together.
            val cx = startX + i * spacing
            canvas.drawCircle(cx, cy, r, paint)
        }
        paint.alpha = 255
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
