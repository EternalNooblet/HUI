package com.hui.companion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.hui.companion.R

/**
 * ThinkingBarView — prompt-kit ThinkingBar (upstream components/prompt-kit/thinking-bar.tsx)
 *
 * Upstream:
 *  div.flex.w-full.items-center.justify-between
 *  - onClick ? button.flex.items-center.gap-1.text-sm.hover:opacity-80
 *      (TextShimmer.font-medium + ChevronRight.size-4.text-muted-foreground)
 *    : TextShimmer.cursor-default.font-medium (no chevron)
 *  - onStop ? button.text-sm.text-muted-foreground.border-b.border-dotted
 *      (default label "Answer now")
 *
 * Follows upstream branching exactly: chevron only when clickable, stop
 * button only when a listener is set.
 */
class ThinkingBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var shimmerText: TextShimmerTextView? = null
    private var dots: ThinkingDotsView? = null
    private var chevron: View? = null
    private var stopButton: View? = null
    private var clickButton: View? = null
    private var onStopListener: (() -> Unit)? = null
    private var onClickListener: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        initView()
    }

    private fun initView() {
        val view = LayoutInflater.from(context).inflate(R.layout.layout_thinking_bar, this, true)

        shimmerText = view.findViewById(R.id.thinkingShimmerText)
        dots = view.findViewById(R.id.thinkingDots)
        chevron = view.findViewById(R.id.thinkingChevron)
        stopButton = view.findViewById(R.id.thinkingStopBtn)
        clickButton = view.findViewById(R.id.thinkingClickBtn)

        shimmerText?.setText(context.getString(R.string.thinking_default))

        // Upstream branching — no onClick at rest: plain shimmer text, no chevron.
        chevron?.visibility = View.GONE
        stopButton?.visibility = View.GONE

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
        chevron?.visibility = View.VISIBLE
    }

    fun setStopButtonText(text: String) {
        (stopButton as? android.widget.TextView)?.text = text
    }

    fun start() {
        shimmerText?.startAnimation()
        dots?.start()
        visibility = View.VISIBLE
    }

    fun stop() {
        shimmerText?.stopAnimation()
        dots?.stop()
        visibility = View.GONE
    }
}
