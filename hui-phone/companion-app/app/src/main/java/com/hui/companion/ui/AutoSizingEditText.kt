package com.hui.companion.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.widget.TextViewCompat
import com.hui.companion.R

/**
 * AutoSizingEditText
 *
 * Implements prompt-kit's PromptInputTextarea behavior:
 * - Autosizing height based on content
 * - min-h-[44px] (minimum height)
 * - maxHeight configurable (default 240px like prompt-kit)
 * - Single line initially, expands as user types
 * - Enter to send (IME_ACTION_SEND), Shift+Enter for new line
 * - No border, transparent background, no shadow
 */
class AutoSizingEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private var maxHeightPx: Int = 240 // prompt-kit default maxHeight = 240
    private var minHeightPx: Int = 44  // prompt-kit min-h-[44px]
    private var isInitialized = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        initView()
    }

    private fun initView() {
        // prompt-kit Textarea styles:
        // min-h-[44px] w-full resize-none border-none bg-transparent shadow-none
        // outline-none focus-visible:ring-0 focus-visible:ring-offset-0
        // text-primary placeholder:text-muted-foreground
        setBackground(null)
        setHintTextColor(android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(context, R.color.pk_muted_foreground)
        ))
        setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.pk_foreground))
        // text-base 16sp (sp setter = scales with user font setting)
        textSize = 16f
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        setHorizontallyScrolling(false)
        setVerticalScrollBarEnabled(false)
        setCursorVisible(false)
        setInputType(android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        imeOptions = EditorInfo.IME_ACTION_SEND
        setSingleLine(false)
        setLines(1)
        setMaxLines(10) // Reasonable max

        // Ensure the EditText can receive focus and touch events (API 27 fix)
        setFocusable(true)
        setFocusableInTouchMode(true)
        setClickable(true)
        setLongClickable(true)

        // text-base 16sp, same as placeholder — no autosize shrink
        TextViewCompat.setAutoSizeTextTypeWithDefaults(this, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)

        // Text watcher for height adjustment
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adjustHeight()
                updateCursor()
            }
        })

        // Set minimum height
        minHeightPx = context.resources.getDimensionPixelSize(R.dimen.pk_input_min_height)
        super.setMinimumHeight(minHeightPx)

        // Convert maxHeight from dp to px
        maxHeightPx = (240 * context.resources.displayMetrics.density).toInt()

        // Initial height adjustment, and no cursor until user focuses for typing
        post {
            adjustHeight()
            clearFocus()
            setCursorVisible(false)
        }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        updateCursor()
    }

    private fun updateCursor() {
        setCursorVisible(hasFocus() && !text.isNullOrEmpty())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!isInitialized) {
            isInitialized = true
            // Initial height adjustment after first measure
            post { adjustHeight() }
        }
    }

    /**
     * Adjust height based on content, respecting min/max bounds.
     * Mirrors prompt-kit's adjustHeight logic:
     * el.style.height = "auto"
     * el.style.height = `min(${el.scrollHeight}px, ${maxHeight})`
     */
    fun adjustHeight() {
        // Reset height to auto to measure content
        setHeight(0)
        var measuredHeight = 0

        // Force layout to get accurate scrollHeight equivalent
        val paddingVertical = paddingTop + paddingBottom
        val desiredHeight = layout.height - paddingVertical

        // Calculate based on line count
        val lineCount = lineCount
        val lineHeight = if (lineCount > 0) paint.fontMetricsInt.descent - paint.fontMetricsInt.ascent else 0
        val contentHeight = (lineCount * lineHeight) + paddingVertical

        val targetHeight = contentHeight.coerceAtLeast(minHeightPx).coerceAtMost(maxHeightPx)

        if (measuredHeight != targetHeight) {
            layoutParams.height = targetHeight
            requestLayout()
        }
    }

    /** Set max height in dp (public API) */
    fun setMaxHeightDp(dp: Int) {
        maxHeightPx = (dp * context.resources.displayMetrics.density).toInt()
        adjustHeight()
    }

    /** Set min height in dp (public API) */
    fun setMinHeightDp(dp: Int) {
        minHeightPx = (dp * context.resources.displayMetrics.density).toInt()
        super.setMinimumHeight(minHeightPx)
        adjustHeight()
    }
}