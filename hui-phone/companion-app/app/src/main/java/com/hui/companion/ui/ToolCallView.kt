package com.hui.companion.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.hui.companion.R

/**
 * ToolCallView
 *
 * Implements prompt-kit's Tool component:
 * - Collapsible (Collapsible primitive)
 * - State icons: Loader2 (streaming), Settings (ready), CheckCircle (done), XCircle (error)
 * - State badges: Processing (blue), Ready (orange), Completed (green), Error (red)
 * - JSON input/output with copy functionality
 * - toolCallId display
 *
 * prompt-kit spec: border-border mt-3 overflow-hidden rounded-lg border
 * Trigger: bg-background h-auto w-full justify-between rounded-b-none px-3 py-2 font-normal
 * Content: bg-background space-y-3 p-3
 */
class ToolCallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var triggerButton: Button? = null
    private var contentContainer: LinearLayout? = null
    private var inputSection: LinearLayout? = null
    private var outputSection: LinearLayout? = null
    private var errorSection: LinearLayout? = null
    private var processingText: TextView? = null
    private var toolCallIdText: TextView? = null
    private var divider: View? = null

    private var isExpanded = false
    private var currentState = ToolState.INPUT_STREAMING

    init {
        initView()
    }

    private fun initView() {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_tool_card)

        val view = LayoutInflater.from(context).inflate(R.layout.item_tool_call, this, true)

        triggerButton = view.findViewById(R.id.toolCallTrigger)
        contentContainer = view.findViewById(R.id.toolCallContent)
        inputSection = view.findViewById(R.id.toolCallInputSection)
        outputSection = view.findViewById(R.id.toolCallOutputSection)
        errorSection = view.findViewById(R.id.toolCallErrorSection)
        processingText = view.findViewById(R.id.toolCallProcessing)
        toolCallIdText = view.findViewById(R.id.toolCallId)
        divider = view.findViewById(R.id.toolCallDivider)

        triggerButton?.setOnClickListener { toggle() }
    }

    fun bind(toolCall: ToolCallData) {
        currentState = toolCall.state
        updateTrigger(toolCall)
        
        if (toolCall.state == ToolState.INPUT_STREAMING) {
            processingText?.visibility = View.VISIBLE
            inputSection?.visibility = View.GONE
            outputSection?.visibility = View.GONE
            errorSection?.visibility = View.GONE
        } else {
            processingText?.visibility = View.GONE
            
            // Input
            if (toolCall.input != null && toolCall.input!!.isNotEmpty()) {
                inputSection?.visibility = View.VISIBLE
                val inputText = inputSection?.findViewById<TextView>(R.id.toolCallInput)
                inputText?.text = formatJson(toolCall.input!!)
            } else {
                inputSection?.visibility = View.GONE
            }

            // Output
            if (toolCall.output != null && toolCall.output!!.isNotEmpty()) {
                outputSection?.visibility = View.VISIBLE
                val outputText = outputSection?.findViewById<TextView>(R.id.toolCallOutput)
                outputText?.text = formatJson(toolCall.output!!)
            } else {
                outputSection?.visibility = View.GONE
            }

            // Error
            if (toolCall.state == ToolState.OUTPUT_ERROR && toolCall.errorText != null) {
                errorSection?.visibility = View.VISIBLE
                val errorText = errorSection?.findViewById<TextView>(R.id.toolCallError)
                errorText?.text = toolCall.errorText
            } else {
                errorSection?.visibility = View.GONE
            }

            // Tool call ID
            if (toolCall.toolCallId != null && toolCall.toolCallId!!.isNotBlank()) {
                divider?.visibility = View.VISIBLE
                toolCallIdText?.visibility = View.VISIBLE
                toolCallIdText?.text = context.getString(R.string.tool_call_id_format, toolCall.toolCallId)
            } else {
                divider?.visibility = View.GONE
                toolCallIdText?.visibility = View.GONE
            }
        }
    }

    private fun updateTrigger(toolCall: ToolCallData) {
        val state = toolCall.state
        val iconRes = when (state) {
            ToolState.INPUT_STREAMING -> R.drawable.ic_loader2
            ToolState.INPUT_AVAILABLE -> R.drawable.ic_settings
            ToolState.OUTPUT_AVAILABLE -> R.drawable.ic_check_circle
            ToolState.OUTPUT_ERROR -> R.drawable.ic_x_circle
        }

        val badgeText = when (state) {
            ToolState.INPUT_STREAMING -> context.getString(R.string.tool_state_streaming)
            ToolState.INPUT_AVAILABLE -> context.getString(R.string.tool_state_ready)
            ToolState.OUTPUT_AVAILABLE -> context.getString(R.string.tool_state_done)
            ToolState.OUTPUT_ERROR -> context.getString(R.string.tool_state_error)
        }

        val badgeColor = when (state) {
            ToolState.INPUT_STREAMING -> ContextCompat.getColor(context, R.color.pk_state_streaming_text)
            ToolState.INPUT_AVAILABLE -> ContextCompat.getColor(context, R.color.pk_state_ready_text)
            ToolState.OUTPUT_AVAILABLE -> ContextCompat.getColor(context, R.color.pk_state_done_text)
            ToolState.OUTPUT_ERROR -> ContextCompat.getColor(context, R.color.pk_state_error_text)
        }

        val badgeBgColor = when (state) {
            ToolState.INPUT_STREAMING -> ContextCompat.getColor(context, R.color.pk_state_streaming_bg)
            ToolState.INPUT_AVAILABLE -> ContextCompat.getColor(context, R.color.pk_state_ready_bg)
            ToolState.OUTPUT_AVAILABLE -> ContextCompat.getColor(context, R.color.pk_state_done_bg)
            ToolState.OUTPUT_ERROR -> ContextCompat.getColor(context, R.color.pk_state_error_bg)
        }

        triggerButton?.apply {
            // Icon (set compound drawable)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                ContextCompat.getDrawable(context, iconRes),
                null, null, null
            )
            
            // Text: tool name + badge
            text = "${toolCall.type}  $badgeText"
            setTextColor(ContextCompat.getColor(context, R.color.pk_foreground))
            
            // Badge span
            val spannable = android.text.SpannableString(text.toString())
            val badgeStart = toolCall.type.length + 2
            spannable.setSpan(
                android.text.style.BackgroundColorSpan(badgeBgColor),
                badgeStart,
                badgeStart + badgeText.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(badgeColor),
                badgeStart,
                badgeStart + badgeText.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.RelativeSizeSpan(0.75f),
                badgeStart,
                badgeStart + badgeText.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setText(spannable)
        }

        // Chevron rotation
        val chevron = triggerButton?.findViewById<ImageView>(R.id.toolCallChevron)
        chevron?.rotation = if (isExpanded) 180f else 0f
    }

    private fun toggle() {
        isExpanded = !isExpanded
        contentContainer?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        val chevron = triggerButton?.findViewById<ImageView>(R.id.toolCallChevron)
        chevron?.animate()?.rotation(if (isExpanded) 180f else 0f)?.duration = 200
    }

    private fun formatJson(map: Map<String, Any>): String {
        return map.entries.map { "${it.key}: ${formatValue(it.value)}" }.joinToString("\n")
    }

    private fun formatValue(value: Any): String {
        return when (value) {
            is Map<*, *> -> formatJson(value as Map<String, Any>)
            is List<*> -> value.joinToString(", ") { formatValue(it as Any) }
            is String -> "\"$value\""
            else -> value.toString()
        }
    }
}

/**
 * Tool call data model (matches prompt-kit ToolPart)
 */
data class ToolCallData(
    val type: String,
    val state: ToolState,
    val input: Map<String, Any>? = null,
    val output: Map<String, Any>? = null,
    val toolCallId: String? = null,
    val errorText: String? = null
)

enum class ToolState {
    INPUT_STREAMING,    // "input-streaming" - Loader2 icon, Processing badge
    INPUT_AVAILABLE,    // "input-available" - Settings icon, Ready badge
    OUTPUT_AVAILABLE,   // "output-available" - CheckCircle icon, Completed badge
    OUTPUT_ERROR        // "output-error" - XCircle icon, Error badge
}