package com.southpawin.companion.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.southpawin.companion.R
import kotlin.math.abs
import kotlin.math.min

class CircularTalkButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), GestureDetector.OnGestureListener {

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
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt() // White 50% opacity for hints
        textAlign = Paint.Align.CENTER
        textSize = 10f * resources.displayMetrics.density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color = 0xFFFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val textRect = Rect()
    private var isPressed = false
    private var buttonText = "Talk"
    private var currentMode = TalkMode.VOICE
    private var connectionState = ConnectionState.CONNECTED
    private var modeChangeListener: OnModeChangeListener? = null

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FFFFFF.toInt() // White ripple with 20% opacity
    }
    private var rippleRadius = 0f
    private var rippleAnimating = false

    private val gestureDetector: GestureDetector
    private val touchSlop: Int

    // Swipe detection
    private var swipeStartX = 0f
    private var isSwiping = false
    private val SWIPE_THRESHOLD_DP = 24
    private val swipeThresholdPx: Int

    init {
        isClickable = true
        setOnClickListener { performClick() }

        val density = resources.displayMetrics.density
        swipeThresholdPx = (SWIPE_THRESHOLD_DP * density).toInt()
        touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

        gestureDetector = GestureDetector(context, this)
        gestureDetector.setIsLongpressEnabled(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width.toFloat(), height.toFloat()) / 2f
        val innerRadius = radius - accentPaint.strokeWidth / 2f

        // Draw background circle
        canvas.drawCircle(cx, cy, innerRadius, if (isPressed) pressedPaint else backgroundPaint)

        // Draw connection accent ring
        when (connectionState) {
            ConnectionState.CONNECTED -> accentPaint.color = ContextCompat.getColor(context, R.color.connected)
            ConnectionState.CONNECTING -> accentPaint.color = ContextCompat.getColor(context, R.color.connecting)
            ConnectionState.DISCONNECTED -> accentPaint.color = ContextCompat.getColor(context, R.color.disconnected)
        }
        canvas.drawCircle(cx, cy, innerRadius, accentPaint)

        // Draw ripple if animating
        if (rippleAnimating) {
            canvas.drawCircle(cx, cy, rippleRadius.coerceAtMost(innerRadius), ripplePaint)
        }

        // Draw mode icon (mic or chat bubble)
        drawModeIcon(canvas, cx, cy, radius * 0.35f)

        // Draw text centered
        textPaint.getTextBounds(buttonText, 0, buttonText.length, textRect)
        val textX = cx
        val textY = cy + textRect.height() / 2f
        canvas.drawText(buttonText, textX, textY, textPaint)

        // Draw swipe hints (subtle arrows on sides)
        drawSwipeHints(canvas, cx, cy, radius)
    }

    private fun drawModeIcon(canvas: Canvas, cx: Float, cy: Float, iconSize: Float) {
        val halfSize = iconSize / 2f
        val strokeWidth = iconPaint.strokeWidth

        when (currentMode) {
            TalkMode.VOICE -> {
                // Draw microphone icon
                val micRect = android.graphics.RectF(
                    cx - halfSize, cy - halfSize,
                    cx + halfSize, cy + halfSize * 0.6f
                )
                // Mic body
                canvas.drawRoundRect(micRect, halfSize * 0.3f, halfSize * 0.3f, iconPaint)
                // Mic stand
                val standTop = cy + halfSize * 0.6f
                val standBottom = cy + halfSize * 1.1f
                canvas.drawLine(cx, standTop, cx, standBottom, iconPaint)
                // Mic base
                canvas.drawLine(cx - halfSize * 0.6f, standBottom, cx + halfSize * 0.6f, standBottom, iconPaint)
            }
            TalkMode.CHAT -> {
                // Draw chat bubble icon
                val bubblePath = Path()
                val bubbleWidth = halfSize * 1.6f
                val bubbleHeight = halfSize * 1.2f
                val tailHeight = halfSize * 0.5f
                val cornerRadius = halfSize * 0.2f

                bubblePath.moveTo(cx - bubbleWidth / 2f + cornerRadius, cy - bubbleHeight / 2f)
                bubblePath.lineTo(cx + bubbleWidth / 2f - cornerRadius, cy - bubbleHeight / 2f)
                bubblePath.quadTo(cx + bubbleWidth / 2f, cy - bubbleHeight / 2f,
                                cx + bubbleWidth / 2f, cy - bubbleHeight / 2f + cornerRadius)
                bubblePath.lineTo(cx + bubbleWidth / 2f, cy + bubbleHeight / 2f - cornerRadius - tailHeight)
                bubblePath.quadTo(cx + bubbleWidth / 2f, cy + bubbleHeight / 2f - tailHeight,
                                cx + bubbleWidth / 2f - cornerRadius, cy + bubbleHeight / 2f - tailHeight)
                // Tail
                bubblePath.lineTo(cx + halfSize * 0.3f, cy + bubbleHeight / 2f)
                bubblePath.lineTo(cx, cy + bubbleHeight / 2f + tailHeight)
                bubblePath.lineTo(cx - halfSize * 0.2f, cy + bubbleHeight / 2f)
                bubblePath.lineTo(cx - bubbleWidth / 2f + cornerRadius, cy + bubbleHeight / 2f - tailHeight)
                bubblePath.quadTo(cx - bubbleWidth / 2f, cy + bubbleHeight / 2f - tailHeight,
                                cx - bubbleWidth / 2f, cy + bubbleHeight / 2f - cornerRadius - tailHeight)
                bubblePath.lineTo(cx - bubbleWidth / 2f, cy - bubbleHeight / 2f + cornerRadius)
                bubblePath.quadTo(cx - bubbleWidth / 2f, cy - bubbleHeight / 2f,
                                cx - bubbleWidth / 2f + cornerRadius, cy - bubbleHeight / 2f)
                bubblePath.close()

                canvas.drawPath(bubblePath, iconPaint)
            }
        }
    }

    private fun drawSwipeHints(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val hintOffset = radius * 1.3f
        val arrowSize = radius * 0.25f

        // Left hint (swipe left for voice)
        if (currentMode == TalkMode.CHAT) {
            val leftX = cx - hintOffset
            val leftY = cy
            canvas.drawText("<", leftX, leftY + hintPaint.textSize / 3f, hintPaint)
        }

        // Right hint (swipe right for chat)
        if (currentMode == TalkMode.VOICE) {
            val rightX = cx + hintOffset
            val rightY = cy
            canvas.drawText(">", rightX, rightY + hintPaint.textSize / 3f, hintPaint)
        }
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

    fun setMode(mode: TalkMode) {
        if (currentMode != mode) {
            currentMode = mode
            buttonText = when (mode) {
                TalkMode.VOICE -> if (isPressed) "Send" else "Talk"
                TalkMode.CHAT -> "Send"
            }
            invalidate()
            modeChangeListener?.invoke(mode)
        }
    }

    fun getMode(): TalkMode = currentMode

    fun setConnectionState(state: ConnectionState) {
        connectionState = state
        invalidate()
    }

    fun setOnModeChangeListener(listener: OnModeChangeListener) {
        modeChangeListener = listener
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

    // GestureDetector.OnGestureListener
    override fun onDown(e: MotionEvent): Boolean {
        swipeStartX = e.x
        isSwiping = false
        return true
    }

    override fun onShowPress(e: MotionEvent) {}

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        return false // performClick handles tap
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        val deltaX = e2.x - swipeStartX
        if (abs(deltaX) > swipeThresholdPx && !isSwiping) {
            isSwiping = true
            if (deltaX > 0 && currentMode == TalkMode.VOICE) {
                setMode(TalkMode.CHAT)
            } else if (deltaX < 0 && currentMode == TalkMode.CHAT) {
                setMode(TalkMode.VOICE)
            }
        }
        return true
    }

    override fun onLongPress(e: MotionEvent) {}

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        if (!isSwiping && abs(velocityX) > 500f) {
            if (velocityX > 0 && currentMode == TalkMode.VOICE) {
                setMode(TalkMode.CHAT)
                return true
            } else if (velocityX < 0 && currentMode == TalkMode.CHAT) {
                setMode(TalkMode.VOICE)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }
}