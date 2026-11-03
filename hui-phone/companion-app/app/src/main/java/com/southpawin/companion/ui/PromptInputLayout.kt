package com.southpawin.companion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.southpawin.companion.R

/**
 * PromptInputLayout
 *
 * Implements prompt-kit's PromptInput component:
 * - rounded-3xl border p-2 shadow-xs
 * - bg-background (--background)
 * - border-input (--border)
 * - Contains: PromptInputTextarea + PromptInputActions
 */
class PromptInputLayout constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    private var textarea: AutoSizingEditText? = null
    private var actionsContainer: LinearLayout? = null
    private var onSendCallback: (String) -> Unit = {}
    private var onAttachmentClickCallback: () -> Unit = {}

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    init {
        initView()
    }

    private fun initView() {
        // prompt-kit: rounded-3xl border p-2 shadow-xs
        radius = context.resources.getDimension(R.dimen.pk_radius_3xl)
        setStrokeWidth(1)
        setStrokeColor(ContextCompat.getColor(context, R.color.pk_input_border))
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.pk_input_bg))
        // shadow-xs
        cardElevation = context.resources.getDimension(R.dimen.pk_shadow_xs)
        setPreventCornerOverlap(true)
        setClipToOutline(true)

        // Padding: p-2
        setPadding(
            context.resources.getDimensionPixelSize(R.dimen.pk_space_2),
            context.resources.getDimensionPixelSize(R.dimen.pk_space_2),
            context.resources.getDimensionPixelSize(R.dimen.pk_space_2),
            context.resources.getDimensionPixelSize(R.dimen.pk_space_2)
        )

        // Inflate layout
        val view = LayoutInflater.from(context).inflate(R.layout.layout_prompt_input, this, true)

        textarea = view.findViewById(R.id.promptInputTextarea)
        actionsContainer = view.findViewById(R.id.promptInputActions)

        // Overlay placeholder (30sp) visible only when textarea empty
        val placeholder = view.findViewById<View>(R.id.promptInputPlaceholder)
        val updatePlaceholder = {
            val empty = textarea?.text.isNullOrEmpty()
            placeholder?.visibility = if (empty) View.VISIBLE else View.GONE
        }
        updatePlaceholder()
        textarea?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updatePlaceholder() }
        })

        // Configure textarea
        textarea?.apply {
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    sendMessage()
                    true
                } else false
            }
        }

        // Wire Send button click
        val sendBtn = actionsContainer?.findViewById<View>(R.id.promptInputSendBtn)
        sendBtn?.setOnClickListener {
            sendMessage()
        }
    }

    fun setOnSendListener(callback: (String) -> Unit) {
        onSendCallback = callback
    }

    fun setOnAttachmentClickListener(callback: () -> Unit) {
        onAttachmentClickCallback = callback
        // Find attachment button in actions and set click listener
        actionsContainer?.findViewById<View>(R.id.promptInputAttachmentBtn)?.setOnClickListener {
            onAttachmentClickCallback()
        }
    }

    fun setOnVoiceClickListener(callback: () -> Unit) {
        // Find voice button in actions and set click listener
        actionsContainer?.findViewById<View>(R.id.promptInputVoiceBtn)?.setOnClickListener {
            callback()
        }
    }

    fun getVoiceButton(): android.widget.ImageButton? {
        return actionsContainer?.findViewById(R.id.promptInputVoiceBtn)
    }

    fun getText(): String = textarea?.text.toString()

    fun setText(text: String) {
        textarea?.setText(text)
    }

    fun clearText() {
        textarea?.setText("")
    }

    fun getTextarea(): AutoSizingEditText? = textarea

    fun getActionsContainer(): LinearLayout? = actionsContainer

    private fun sendMessage() {
        val text = textarea?.text.toString().trim()
        if (text.isNotEmpty()) {
            onSendCallback(text)
            clearText()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        textarea?.isEnabled = enabled
        actionsContainer?.isEnabled = enabled
        alpha = if (enabled) 1f else 0.6f
    }
}