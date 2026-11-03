package com.southpawin.companion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.southpawin.companion.R

/**
 * ThinkingBarView
 *
 * Implements prompt-kit's ThinkingBar component:
 * - TextShimmer on "Thinking" text
 * - Optional stop button (dotted border-b text-sm)
 * - Chevron icon (clickable, triggers onClick)
 *
 * prompt-kit spec:
 * - flex w-full items-center justify-between
 * - TextShimmer on text (font-medium)
 * - onClick: button with TextShimmer + ChevronRight
 * - onStop: button with dotted border-b text-sm "Answer now"
 */
class ThinkingBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var shimmerText: TextShimmerTextView? = null
    private var stopButton: Button? = null
    private var clickButton: Button? = null
    private var onStopListener: (() -> Unit)? = null
    private var onClickListener: (() -> Unit)? = null

    init {
        initView()
    }

    private fun initView() {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // prompt-kit: flex w-full items-center justify-between
        setPadding(0, 0, 0, 0)

        val view = LayoutInflater.from(context).inflate(R.layout.layout_thinking_bar, this, true)

        shimmerText = view.findViewById(R.id.thinkingShimmerText)
        stopButton = view.findViewById(R.id.thinkingStopBtn)
        clickButton = view.findViewById(R.id.thinkingClickBtn)

        // Default text
        shimmerText?.setText(context.getString(R.string.thinking_default))
        shimmerText?.startAnimation()

        // Stop button (dotted border-b)
        stopButton?.apply {
            setTextColor(ContextCompat.getColor(context, R.color.pk_muted_foreground))
            setBackground(null)
            setPadding(context.resources.getDimensionPixelSize(R.dimen.pk_space_2), 0, 
                       context.resources.getDimensionPixelSize(R.dimen.pk_space_2), 0)
            visibility = View.GONE
        }

        clickButton?.setOnClickListener { onClickListener?.invoke() }
        stopButton?.setOnClickListener { onStopListener?.invoke() }
    }

    fun setText(text: String) {
        shimmerText?.setText(text)
    }

    fun setOnStopListener(listener: () -> Unit) {
        onStopListener = listener
        stopButton?.visibility = View.VISIBLE
    }

    fun setOnClickListener(listener: () -> Unit) {
        onClickListener = listener
        clickButton?.visibility = View.VISIBLE
    }

    fun setStopButtonText(text: String) {
        stopButton?.text = text
    }

    fun start() {
        shimmerText?.startAnimation()
        visibility = View.VISIBLE
    }

    fun stop() {
        shimmerText?.stopAnimation()
        visibility = View.GONE
    }
}