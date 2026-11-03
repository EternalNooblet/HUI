package com.hui.companion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.hui.companion.R

/**
 * ReasoningView
 *
 * Implements prompt-kit's ChainOfThought + Steps components:
 * - Collapsible steps (Collapsible primitive)
 * - Vertical connector bar (bg-primary/20 w-[2px])
 * - Circle indicator (size-2 fill-current)
 * - defaultOpen=true
 * - StepsTrigger supports leftIcon, swapIconOnHover
 *
 * prompt-kit spec:
 * - ChainOfThought: space-y-0
 * - ChainOfThoughtStep: Collapsible with data-last={isLast}
 * - Trigger: group text-muted-foreground hover:text-foreground flex cursor-pointer items-center justify-start gap-1 text-left text-sm
 * - Content: grid grid-cols-[min-content_minmax(0,1fr)] gap-x-4, connector bar (bg-primary/20 ml-1.75 h-full w-px)
 * - Circle: size-2 fill-current
 */
class ReasoningView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        initView()
    }

    private fun initView() {
        orientation = VERTICAL
    }

    fun bind(reasoning: ReasoningData) {
        removeAllViews()

        reasoning.steps.forEachIndexed { index, step ->
            val isLast = index == reasoning.steps.size - 1
            val stepView = createStepView(step, isLast)
            addView(stepView)
        }
    }

    private fun createStepView(step: ReasoningStep, isLast: Boolean): View {
        val view = LayoutInflater.from(context).inflate(R.layout.item_reasoning_step, this, false)

        val connectorTop = view.findViewById<View>(R.id.reasoningConnectorTop)
        val connectorBottom = view.findViewById<View>(R.id.reasoningConnectorBottom)
        val circle = view.findViewById<View>(R.id.reasoningCircle)
        val trigger = view.findViewById<LinearLayout>(R.id.reasoningTrigger)
        val titleText = view.findViewById<TextView>(R.id.reasoningTitle)
        val contentContainer = view.findViewById<LinearLayout>(R.id.reasoningContent)
        val contentText = view.findViewById<TextView>(R.id.reasoningText)

        // Connector visibility
        connectorTop?.visibility = if (isLast) View.GONE else View.VISIBLE
        connectorBottom?.visibility = if (isLast) View.GONE else View.VISIBLE

        // Title
        titleText?.text = step.title

        // Content (initially expanded per prompt-kit defaultOpen=true)
        contentText?.text = step.content
        if (!step.isOpen) {
            contentContainer?.visibility = View.GONE
        }

        // Trigger click
        trigger?.setOnClickListener {
            val isVisible = contentContainer?.visibility == View.VISIBLE
            contentContainer?.visibility = if (isVisible) View.GONE else View.VISIBLE
            val chevron = trigger?.findViewById<View>(R.id.reasoningChevron)
            chevron?.rotation = if (isVisible) 0f else 180f
        }

        // Circle color (primary)
        circle?.setBackgroundColor(ContextCompat.getColor(context, R.color.pk_primary))

        return view
    }
}

/**
 * Reasoning step data model (matches prompt-kit ChainOfThoughtStep)
 */
data class ReasoningStep(
    val id: String,
    val title: String,        // ChainOfThoughtTrigger content
    val content: String,      // ChainOfThoughtItem content
    val isLast: Boolean = false,
    val isOpen: Boolean = true  // defaultOpen=true
)

/**
 * Full reasoning data container
 */
data class ReasoningData(
    val steps: List<ReasoningStep>
)