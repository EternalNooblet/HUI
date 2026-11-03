package com.hui.companion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.hui.companion.R

/**
 * PromptInputActions
 *
 * Implements prompt-kit's PromptInputActions + PromptInputAction:
 * - flex items-center gap-2
 * - Each action: TooltipProvider + TooltipTrigger (ImageButton) + TooltipContent
 * - Actions: Attachment (paperclip), Send (arrow)
 */
class PromptInputActions constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var attachmentButton: ImageButton? = null
    private var voiceButton: ImageButton? = null
    private var sendButton: ImageButton? = null
    private var onAttachmentClick: (() -> Unit)? = null
    private var onVoiceClick: (() -> Unit)? = null
    private var onSendClick: (() -> Unit)? = null

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    init {
        initView()
    }

    private fun initView() {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // prompt-kit: flex items-center gap-2
        setPadding(0, 0, 0, 0)

        val view = LayoutInflater.from(context).inflate(R.layout.layout_prompt_input_actions, this, true)

        attachmentButton = view.findViewById(R.id.promptInputAttachmentBtn)
        voiceButton = view.findViewById(R.id.promptInputVoiceBtn)
        sendButton = view.findViewById(R.id.promptInputSendBtn)

        attachmentButton?.setOnClickListener {
            onAttachmentClick?.invoke()
        }

        voiceButton?.setOnClickListener {
            onVoiceClick?.invoke()
        }

        sendButton?.setOnClickListener {
            onSendClick?.invoke()
        }

        // Set tooltips (API 26+)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            attachmentButton?.tooltipText = context.getString(R.string.prompt_input_attachment_tooltip)
            voiceButton?.tooltipText = context.getString(R.string.prompt_input_voice_tooltip)
            sendButton?.tooltipText = context.getString(R.string.prompt_input_send_tooltip)
        }
    }

    fun setOnAttachmentClickListener(callback: () -> Unit) {
        onAttachmentClick = callback
    }

    fun setOnVoiceClickListener(callback: () -> Unit) {
        onVoiceClick = callback
    }

    fun setOnSendClickListener(callback: () -> Unit) {
        onSendClick = callback
    }

    fun getAttachmentButton(): ImageButton? = attachmentButton
    fun getVoiceButton(): ImageButton? = voiceButton
    fun getSendButton(): ImageButton? = sendButton

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        attachmentButton?.isEnabled = enabled
        voiceButton?.isEnabled = enabled
        sendButton?.isEnabled = enabled
        attachmentButton?.alpha = if (enabled) 1f else 0.5f
        voiceButton?.alpha = if (enabled) 1f else 0.5f
        sendButton?.alpha = if (enabled) 1f else 0.5f
    }
}