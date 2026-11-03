package com.southpawin.companion.ui

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.southpawin.companion.R
import com.southpawin.companion.db.MessageEntity
import timber.log.Timber

/**
 * MessageViewHolder
 *
 * Implements prompt-kit's Message component structure:
 * - Message: flex gap-3 (Avatar + Content + Actions)
 * - MessageAvatar: h-8 w-8 shrink-0 (assistant only)
 * - MessageContent: rounded-lg p-2 text-foreground bg-secondary prose break-words whitespace-normal
 * - MessageActions: text-muted-foreground flex items-center gap-2 (tooltips on actions)
 * - MessageAction: TooltipProvider + TooltipTrigger + TooltipContent
 *
 * Supports multiple message types via MessageEntity fields:
 * - role="user": no avatar, content only
 * - role="assistant": avatar + content + actions
 * - role="tool": tool call card
 * - role="system": system banner (handled separately)
 * - reasoning: ChainOfThought steps
 */
class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {

    // Root views
    val messageRoot: LinearLayout = view.findViewById(R.id.messageRoot)
    val avatarInclude: View = view.findViewById(R.id.messageAvatarInclude)
    val contentInclude: View = view.findViewById(R.id.messageContentInclude)
    val actionsInclude: View = view.findViewById(R.id.messageActionsInclude)

    // Avatar
    val avatarView: ShapeableImageView = avatarInclude.findViewById(R.id.messageAvatar)

    // Content
    val contentContainer: LinearLayout = contentInclude.findViewById(R.id.messageContentContainer)
    val contentView: LinearLayout = contentInclude.findViewById(R.id.messageContent)
    val citationsContainer: LinearLayout = contentInclude.findViewById(R.id.messageCitations)
    val toolCallView: View = contentInclude.findViewById(R.id.messageToolCall)
    val reasoningView: View = contentInclude.findViewById(R.id.messageReasoning)

    // Actions
    val actionsContainer: LinearLayout = actionsInclude.findViewById(R.id.messageActions)

    companion object {
        fun create(parent: ViewGroup): MessageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message, parent, false)
            return MessageViewHolder(view)
        }
    }

    /**
     * Bind message entity to view holder.
     * Handles all prompt-kit message variations.
     */
    fun bind(
        entity: MessageEntity,
        context: Context,
        markdownRenderer: MarkdownRenderer,
        onActionClick: (MessageEntity, String) -> Unit
    ) {
        val role = entity.role
        val isUser = role == "user"
        val isAssistant = role == "assistant"
        val isTool = role == "tool"
        val hasReasoning = entity.reasoningJson != null && entity.reasoningJson!!.isNotBlank()

        // === Avatar (assistant only) ===
        avatarInclude.visibility = if (isAssistant) View.VISIBLE else View.GONE

        // === Content ===
        if (isTool) {
            // Tool call message - show tool card
            contentView.visibility = View.GONE
            citationsContainer.visibility = View.GONE
            toolCallView.visibility = View.VISIBLE
            bindToolCall(entity, context)
        } else if (hasReasoning) {
            // Reasoning message - show ChainOfThought
            contentView.visibility = View.GONE
            citationsContainer.visibility = View.GONE
            toolCallView.visibility = View.GONE
            reasoningView.visibility = View.VISIBLE
            bindReasoning(entity, context)
        } else {
            // Regular message (user or assistant) - render markdown
            contentView.visibility = View.VISIBLE
            citationsContainer.visibility = View.GONE
            toolCallView.visibility = View.GONE
            reasoningView.visibility = View.GONE
            renderMarkdownContent(entity.content, context, markdownRenderer)
        }

        // === Citations (Source chips) ===
        if (entity.citationsJson != null && entity.citationsJson!!.isNotBlank()) {
            bindCitations(entity.citationsJson!!, context)
        } else {
            citationsContainer.visibility = View.GONE
        }

        // === Actions (assistant only) ===
        actionsInclude.visibility = if (isAssistant) View.VISIBLE else View.GONE
        if (isAssistant) {
            bindActions(entity, context, onActionClick)
        }

        // === Accessibility ===
        messageRoot.contentDescription = context.getString(
            if (isUser) R.string.message_user else R.string.message_assistant,
            entity.content.take(100)
        )
    }

    private fun renderMarkdownContent(
        markdown: String,
        context: Context,
        markdownRenderer: MarkdownRenderer
    ) {
        contentView.removeAllViews()
        // MarkdownRenderer will populate contentView with rendered blocks
        markdownRenderer.render(markdown, contentView, context)
    }

    private fun bindToolCall(entity: MessageEntity, context: Context) {
        // Tool call view is already inflated in layout, populate it
        val toolCallsJson = entity.toolCallsJson ?: return
        // Parse JSON and populate toolCallView
        // For now, show placeholder
        val trigger = toolCallView.findViewById<Button>(R.id.toolCallTrigger)
        trigger.text = context.getString(R.string.tool_processing)
    }

    private fun bindReasoning(entity: MessageEntity, context: Context) {
        // Reasoning view is already inflated, populate with steps
        val reasoningJson = entity.reasoningJson ?: return
        // Parse JSON and populate reasoningView
        // For now, show placeholder
        val title = reasoningView.findViewById<TextView>(R.id.reasoningTitle)
        title.text = context.getString(R.string.reasoning_step)
    }

    private fun bindCitations(citationsJson: String, context: Context) {
        citationsContainer.visibility = View.VISIBLE
        citationsContainer.removeAllViews()
        // Parse citationsJson and create CitationChip for each
        // Placeholder for now
        val chip = LayoutInflater.from(context).inflate(R.layout.item_citation_chip, citationsContainer, false) as com.google.android.material.chip.Chip
        chip.text = "Source"
        citationsContainer.addView(chip)
    }

    private fun bindActions(
        entity: MessageEntity,
        context: Context,
        onActionClick: (MessageEntity, String) -> Unit
    ) {
        actionsContainer.removeAllViews()

        // Copy action
        val copyBtn = createActionButton(
            context,
            R.drawable.ic_content_copy,
            context.getString(R.string.copy_message),
            "copy"
        )
        copyBtn.setOnClickListener { onActionClick(entity, "copy") }
        actionsContainer.addView(copyBtn)

        // Feedback positive
        val feedbackPosBtn = createActionButton(
            context,
            R.drawable.ic_thumb_up,
            context.getString(R.string.feedback_positive),
            "feedback_positive"
        )
        feedbackPosBtn.setOnClickListener { onActionClick(entity, "feedback_positive") }
        actionsContainer.addView(feedbackPosBtn)

        // Feedback negative
        val feedbackNegBtn = createActionButton(
            context,
            R.drawable.ic_thumb_down,
            context.getString(R.string.feedback_negative),
            "feedback_negative"
        )
        feedbackNegBtn.setOnClickListener { onActionClick(entity, "feedback_negative") }
        actionsContainer.addView(feedbackNegBtn)
    }

    private fun createActionButton(
        context: Context,
        iconRes: Int,
        tooltip: String,
        actionId: String
    ): ImageView {
        val btn = ImageView(context)
        btn.setImageResource(iconRes)
        btn.setColorFilter(ContextCompat.getColor(context, R.color.pk_muted_foreground))
        val size = context.resources.getDimensionPixelSize(R.dimen.pk_space_6)
        btn.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            setMargins(0, 0, context.resources.getDimensionPixelSize(R.dimen.pk_space_1), 0)
        }
        btn.setPadding(
            context.resources.getDimensionPixelSize(R.dimen.pk_space_1),
            context.resources.getDimensionPixelSize(R.dimen.pk_space_1),
            context.resources.getDimensionPixelSize(R.dimen.pk_space_1),
            context.resources.getDimensionPixelSize(R.dimen.pk_space_1)
        )
        btn.background = ContextCompat.getDrawable(context, R.drawable.action_button_bg)
        btn.contentDescription = tooltip
        btn.tag = actionId
        // Tooltip: use TooltipCompat for API 26+
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            btn.setTooltipText(tooltip)
        }
        return btn
    }
}

/**
 * MarkdownRenderer
 *
 * Replicates prompt-kit's Markdown component:
 * - Uses marked (lexer) + react-markdown + remark-gfm + remark-breaks
 * - Splits markdown into blocks, memoizes per block
 * - Custom code component: inline code = bg-primary-foreground rounded-sm px-1 font-mono text-sm
 * - Code blocks delegated to CodeBlock component (Shiki)
 */
class MarkdownRenderer {

    // Block types from marked lexer
    private sealed class Block {
        data class Text(val content: String) : Block()
        data class Code(val language: String, val code: String) : Block()
        data class Heading(val level: Int, val content: String) : Block()
        data class ListBlock(val ordered: Boolean, val items: kotlin.collections.List<String>) : Block()
        data class Blockquote(val content: String) : Block()
        data class Table(val header: kotlin.collections.List<String>, val rows: kotlin.collections.List<kotlin.collections.List<String>>) : Block()
        data class HR(val dummy: Int = 0) : Block()
        data class Image(val alt: String, val url: String) : Block()
    }

    fun render(markdown: String, container: LinearLayout, context: Context) {
        val blocks = parseMarkdownIntoBlocks(markdown)
        blocks.forEach { block ->
            val view = createViewForBlock(block, context)
            container.addView(view)
        }
    }

    private fun parseMarkdownIntoBlocks(markdown: String): List<Block> {
        // Simple block parsing - in production, use a proper markdown lexer
        // This mirrors prompt-kit's marked.lexer() approach
        val blocks = mutableListOf<Block>()
        val lines = markdown.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    // Code block
                    val language = line.substring(3).trim().takeIf { it.isNotBlank() } ?: "plaintext"
                    val codeLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        codeLines.add(lines[i])
                        i++
                    }
                    blocks.add(Block.Code(language, codeLines.joinToString("\n")))
                }
                line.startsWith("#") -> {
                    // Heading
                    val level = line.takeWhile { it == '#' }.length
                    val content = line.substring(level).trim()
                    blocks.add(Block.Heading(level, content))
                }
                line.startsWith("> ") -> {
                    // Blockquote
                    val content = line.substring(2).trim()
                    blocks.add(Block.Blockquote(content))
                }
                line.matches(Regex("^!\\[.*\\]\\(.*\\)$")) -> {
                    // Image: ![alt](url)
                    val imageLine = line.trim()
                    val altStart = imageLine.indexOf("[") + 1
                    val altEnd = imageLine.indexOf("]")
                    val urlStart = imageLine.indexOf("(") + 1
                    val urlEnd = imageLine.indexOf(")")
                    val alt = imageLine.substring(altStart, altEnd)
                    val url = imageLine.substring(urlStart, urlEnd)
                    blocks.add(Block.Image(alt, url))
                }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> {
                    // Unordered list
                    val items = mutableListOf<String>()
                    while (i < lines.size && (lines[i].startsWith("- ") || lines[i].startsWith("* ") || lines[i].startsWith("+ "))) {
                        items.add(lines[i].substring(2))
                        i++
                    }
                    i--
                    blocks.add(Block.ListBlock(false, items))
                }
                line.matches(Regex("^\\d+\\.\\s")) -> {
                    // Ordered list
                    val items = mutableListOf<String>()
                    while (i < lines.size && lines[i].matches(Regex("^\\d+\\.\\s"))) {
                        items.add(lines[i].substringAfter(".").trim())
                        i++
                    }
                    i--
                    blocks.add(Block.ListBlock(true, items))
                }
                line.trim().isEmpty() -> {
                    // Skip empty lines (handled by breaks)
                }
                else -> {
                    // Paragraph / text
                    val textLines = mutableListOf<String>()
                    while (i < lines.size && !isBlockStart(lines[i])) {
                        textLines.add(lines[i])
                        i++
                    }
                    i--
                    blocks.add(Block.Text(textLines.joinToString("\n")))
                }
            }
            i++
        }
        return blocks
    }

    private fun isBlockStart(line: String): Boolean {
        return line.startsWith("```") ||
               line.startsWith("#") ||
               line.startsWith("> ") ||
               line.startsWith("- ") ||
               line.startsWith("* ") ||
               line.startsWith("+ ") ||
               line.matches(Regex("^\\d+\\.\\s")) ||
               line.matches(Regex("""^!\[.*\]\(.*\)$""")) ||
               line.trim().isEmpty()
    }

    private fun createViewForBlock(block: Block, context: Context): View {
        return when (block) {
            is Block.Text -> createTextBlock(block.content, context)
            is Block.Code -> createCodeBlock(block.language, block.code, context)
            is Block.Heading -> createHeading(block.level, block.content, context)
            is Block.ListBlock -> createList(block.ordered, block.items, context)
            is Block.Blockquote -> createBlockquote(block.content, context)
            is Block.Table -> createTable(block.header, block.rows, context)
            is Block.HR -> createHorizontalRule(context)
            is Block.Image -> createImageBlock(block.alt, block.url, context)
        }
    }

    private fun createTextBlock(text: String, context: Context): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.textSize = context.resources.getDimension(R.dimen.pk_text_base)
        tv.setTextColor(ContextCompat.getColor(context, R.color.pk_message_text))
        tv.movementMethod = LinkMovementMethod.getInstance()
        tv.setPadding(0, context.resources.getDimensionPixelSize(R.dimen.pk_space_1), 0, context.resources.getDimensionPixelSize(R.dimen.pk_space_1))
        // TODO: Handle inline markdown (bold, italic, links, inline code)
        return tv
    }

    private fun createCodeBlock(language: String, code: String, context: Context): View {
        // Delegate to CodeBlockView (Shiki-style highlighting)
        val view = CodeBlockView(context)
        view.bind(language, code)
        return view.getView()
    }

    private fun createHeading(level: Int, content: String, context: Context): TextView {
        val tv = TextView(context)
        tv.text = content
        tv.setTextColor(ContextCompat.getColor(context, R.color.pk_message_text))
        tv.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
        tv.setPadding(0, context.resources.getDimensionPixelSize(R.dimen.pk_space_2), 0, context.resources.getDimensionPixelSize(R.dimen.pk_space_1))
        tv.textSize = when (level) {
            1 -> context.resources.getDimension(R.dimen.pk_text_xl)
            2 -> context.resources.getDimension(R.dimen.pk_text_lg)
            3 -> context.resources.getDimension(R.dimen.pk_text_base)
            else -> context.resources.getDimension(R.dimen.pk_text_base)
        }
        return tv
    }

    private fun createList(ordered: Boolean, items: kotlin.collections.List<String>, context: Context): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.resources.getDimensionPixelSize(R.dimen.pk_space_3), 0, 0, 0)
        }
        items.forEachIndexed { index, item ->
            val itemView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val bullet = TextView(context).apply {
                    text = if (ordered) "${index + 1}." else "•"
                    textSize = context.resources.getDimension(R.dimen.pk_text_base)
                    setTextColor(ContextCompat.getColor(context, R.color.pk_message_text))
                    setPadding(0, 0, context.resources.getDimensionPixelSize(R.dimen.pk_space_2), 0)
                }
                val text = TextView(context).apply {
                    text = item
                    textSize = context.resources.getDimension(R.dimen.pk_text_base)
                    setTextColor(ContextCompat.getColor(context, R.color.pk_message_text))
                }
                addView(bullet)
                addView(text)
            }
            container.addView(itemView)
        }
        return container
    }

    private fun createBlockquote(content: String, context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                context.resources.getDimensionPixelSize(R.dimen.pk_space_3),
                context.resources.getDimensionPixelSize(R.dimen.pk_space_1),
                context.resources.getDimensionPixelSize(R.dimen.pk_space_3),
                context.resources.getDimensionPixelSize(R.dimen.pk_space_1)
            )
            ViewCompat.setBackground(this, ContextCompat.getDrawable(context, R.drawable.bg_blockquote))
        }
        val bar = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                context.resources.getDimensionPixelSize(R.dimen.pk_connector_bar_width),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(context, R.color.pk_primary))
        }
        val text = TextView(context).apply {
            text = content
            textSize = context.resources.getDimension(R.dimen.pk_text_base)
            setTextColor(ContextCompat.getColor(context, R.color.pk_muted_foreground))
            setPadding(context.resources.getDimensionPixelSize(R.dimen.pk_space_2), 0, 0, 0)
        }
        container.addView(bar)
        container.addView(text)
        return container
    }

    private fun createTable(header: kotlin.collections.List<String>, rows: kotlin.collections.List<kotlin.collections.List<String>>, context: Context): LinearLayout {
        // Simplified table - prompt-kit uses remark-gfm tables
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // TODO: Proper table layout
        val tv = TextView(context).apply {
            text = "Table: ${header.joinToString(", ")} | ${rows.size} rows"
            textSize = context.resources.getDimension(R.dimen.pk_text_sm)
            setTextColor(ContextCompat.getColor(context, R.color.pk_muted_foreground))
        }
        container.addView(tv)
        return container
    }

    private fun createHorizontalRule(context: Context): View {
        val view = View(context)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            1
        )
        view.setBackgroundColor(ContextCompat.getColor(context, R.color.pk_border))
        val params = view.layoutParams as LinearLayout.LayoutParams
        params.setMargins(0, context.resources.getDimensionPixelSize(R.dimen.pk_space_3), 0, context.resources.getDimensionPixelSize(R.dimen.pk_space_3))
        return view
    }

    private fun createImageBlock(alt: String, url: String, context: Context): View {
        val imageView = ImageMessageView(context)
        imageView.loadImage(url)
        return imageView
    }
}