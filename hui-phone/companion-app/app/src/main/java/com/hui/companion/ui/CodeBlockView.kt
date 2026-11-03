package com.hui.companion.ui

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.hui.companion.R

/**
 * CodeBlockView
 *
 * Renders code blocks with Shiki-style highlighting (github-light theme).
 * prompt-kit spec: rounded-xl border-border bg-card text-[13px] overflow-x-auto
 * [&>pre]:px-4 [&>pre]:py-4
 *
 * Since Shiki (JavaScript) doesn't run on Android, we use a lightweight
 * syntax highlighter for common languages. For full Shiki parity,
 * server-side pre-highlighting (Python shiki) → send HTML to app is preferred.
 */
class CodeBlockView(private val context: Context) {

    private val container: LinearLayout
    private val codeTextView: TextView

    init {
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_code_block)
            setPadding(
                context.resources.getDimensionPixelSize(R.dimen.pk_code_padding_horizontal),
                context.resources.getDimensionPixelSize(R.dimen.pk_code_padding_vertical),
                context.resources.getDimensionPixelSize(R.dimen.pk_code_padding_horizontal),
                context.resources.getDimensionPixelSize(R.dimen.pk_code_padding_vertical)
            )
            // Horizontal scroll for overflow-x-auto
            isHorizontalScrollBarEnabled = true
        }

        val langBadge = TextView(context).apply {
            textSize = context.resources.getDimension(R.dimen.pk_text_xs)
            setTextColor(ContextCompat.getColor(context, R.color.pk_muted_foreground))
            setPadding(0, 0, 0, context.resources.getDimensionPixelSize(R.dimen.pk_space_1))
        }
        container.addView(langBadge)

        codeTextView = TextView(context).apply {
            textSize = context.resources.getDimension(R.dimen.pk_text_code)
            setTextColor(ContextCompat.getColor(context, R.color.pk_code_foreground))
            typeface = android.graphics.Typeface.MONOSPACE
            setHorizontallyScrolling(true)
            // textIsSelectable = true  // API 27+
        }
        container.addView(codeTextView)
    }

    fun getView(): View = container

    fun bind(language: String, code: String) {
        val langBadge = container.getChildAt(0) as TextView
        langBadge.text = if (language.isNotBlank()) language else "plaintext"

        // Apply syntax highlighting
        val highlighted = highlightCode(code, language)
        codeTextView.text = highlighted
    }

    /**
     * Lightweight syntax highlighter for common languages.
     * Matches github-light theme colors approximately.
     */
    private fun highlightCode(code: String, language: String): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(code)
        val lang = language.lowercase()

        // Keywords per language
        val keywords = when (lang) {
            "kotlin", "kt" -> kotlinKeywords()
            "java" -> javaKeywords()
            "javascript", "js", "typescript", "ts", "tsx" -> jsKeywords()
            "python", "py" -> pythonKeywords()
            "rust", "rs" -> rustKeywords()
            "go" -> goKeywords()
            "json" -> jsonKeywords()
            "yaml", "yml" -> yamlKeywords()
            "markdown", "md" -> markdownKeywords()
            "bash", "sh", "shell" -> bashKeywords()
            "sql" -> sqlKeywords()
            "html", "xml" -> htmlKeywords()
            "css" -> cssKeywords()
            "dockerfile", "docker" -> dockerKeywords()
            else -> emptyList<String>()
        }

        // Apply highlighting
        for (kw in keywords) {
            highlightKeyword(ssb, kw, 0xFFE06C75.toInt()) // github-light: red for keywords
        }

        // Strings (green)
        highlightRegex(ssb, "\"([^\"\\\\]|\\\\.)*\"", 0xFF98C379.toInt())
        highlightRegex(ssb, "'([^'\\\\]|\\\\.)*'", 0xFF98C379.toInt())
        highlightRegex(ssb, "`([^`\\\\]|\\\\.)*`", 0xFF98C379.toInt()) // template literals / backticks

        // Comments (gray)
        highlightRegex(ssb, "//.*", 0xFF7F848E.toInt())
        highlightRegex(ssb, "/\\*[\\s\\S]*?\\*/", 0xFF7F848E.toInt())
        highlightRegex(ssb, "#.*", 0xFF7F848E.toInt())

        // Numbers (purple)
        highlightRegex(ssb, "\\b\\d+(\\.\\d+)?\\b", 0xFFD19A66.toInt())

        // Function calls (blue)
        highlightRegex(ssb, "\\b[a-zA-Z_][a-zA-Z0-9_]*\\s*(?=\\()", 0xFF61AFEF.toInt())

        // Types/Classes (yellow)
        highlightRegex(ssb, "\\b[A-Z][a-zA-Z0-9_]*\\b", 0xFFE5C07B.toInt())

        return ssb
    }

    private fun highlightKeyword(ssb: SpannableStringBuilder, keyword: String, color: Int) {
        val pattern = "\\b$keyword\\b".toRegex()
        for (match in pattern.findAll(ssb)) {
            ssb.setSpan(
                ForegroundColorSpan(color),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun highlightRegex(ssb: SpannableStringBuilder, regex: String, color: Int) {
        val pattern = regex.toRegex()
        for (match in pattern.findAll(ssb)) {
            ssb.setSpan(
                ForegroundColorSpan(color),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    // Language keyword sets (github-light theme categories)
    private fun kotlinKeywords(): List<String> = listOf(
        "val", "var", "fun", "class", "interface", "object", "data", "sealed", "enum",
        "when", "if", "else", "for", "while", "do", "try", "catch", "finally", "throw",
        "return", "break", "continue", "in", "is", "as", "by", "lazy", "lateinit",
        "open", "abstract", "override", "final", "private", "public", "protected", "internal",
        "companion", "init", "constructor", "field", "property", "get", "set",
        "true", "false", "null", "this", "super", "it",
        "Int", "String", "Boolean", "Double", "Float", "Long", "Byte", "Short", "Char",
        "List", "Map", "Set", "MutableList", "MutableMap", "MutableSet",
        "Unit", "Nothing", "Any", "Array", "Pair", "Triple"
    )

    private fun javaKeywords(): List<String> = listOf(
        "public", "private", "protected", "static", "final", "abstract", "class", "interface",
        "extends", "implements", "new", "this", "super", "return", "if", "else", "for", "while",
        "do", "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw",
        "throws", "import", "package", "instanceof", "new", "void", "int", "long", "double", "float",
        "boolean", "char", "byte", "short", "String", "Integer", "Long", "Double", "Float",
        "Boolean", "Character", "Array", "List", "Map", "Set", "Collection", "Stream"
    )

    private fun jsKeywords(): List<String> = listOf(
        "const", "let", "var", "function", "async", "await", "class", "extends", "constructor",
        "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
        "try", "catch", "finally", "throw", "return", "import", "export", "from", "as",
        "typeof", "instanceof", "in", "of", "new", "this", "super", "yield",
        "true", "false", "null", "undefined", "NaN", "Infinity",
        "console", "log", "error", "warn", "Promise", "then", "catch", "finally",
        "String", "Number", "Boolean", "Object", "Array", "Map", "Set", "Date", "RegExp"
    )

    private fun pythonKeywords(): List<String> = listOf(
        "def", "class", "if", "elif", "else", "for", "while", "try", "except", "finally",
        "raise", "return", "yield", "break", "continue", "pass", "import", "from", "as",
        "with", "as", "lambda", "in", "is", "not", "and", "or", "True", "False", "None",
        "self", "super", "staticmethod", "classmethod", "property",
        "str", "int", "float", "bool", "list", "dict", "set", "tuple", "bytes"
    )

    private fun rustKeywords(): List<String> = listOf(
        "fn", "let", "mut", "struct", "enum", "impl", "trait", "mod", "use", "pub", "crate",
        "if", "else", "match", "loop", "while", "for", "break", "continue", "return",
        "async", "await", "move", "ref", "mut", "Box", "Vec", "String", "Option", "Result",
        "Some", "None", "Ok", "Err", "true", "false"
    )

    private fun goKeywords(): List<String> = listOf(
        "func", "var", "const", "type", "struct", "interface", "package", "import",
        "if", "else", "for", "range", "switch", "case", "default", "break", "continue",
        "return", "go", "defer", "select", "chan", "make", "new", "nil", "true", "false"
    )

    private fun jsonKeywords(): List<String> = listOf(
        "true", "false", "null"
    )

    private fun yamlKeywords(): List<String> = listOf(
        "true", "false", "null", "yes", "no", "on", "off"
    )

    private fun markdownKeywords(): List<String> = listOf(
        "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "code", "pre", "ul", "ol", "li",
        "strong", "em", "a", "img", "table", "thead", "tbody", "tr", "th", "td"
    )

    private fun bashKeywords(): List<String> = listOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "local", "export", "source", "alias", "true", "false"
    )

    private fun sqlKeywords(): List<String> = listOf(
        "SELECT", "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "ON", "AS",
        "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "INDEX",
        "ALTER", "DROP", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "NOT", "NULL", "UNIQUE",
        "DEFAULT", "AUTO_INCREMENT", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET"
    )

    private fun htmlKeywords(): List<String> = listOf(
        "div", "span", "p", "a", "img", "ul", "ol", "li", "table", "tr", "td", "th",
        "thead", "tbody", "form", "input", "button", "select", "option", "textarea",
        "header", "footer", "main", "section", "article", "nav", "aside"
    )

    private fun cssKeywords(): List<String> = listOf(
        "color", "background", "margin", "padding", "border", "display", "position",
        "flex", "grid", "width", "height", "font", "size", "weight", "family"
    )

    private fun dockerKeywords(): List<String> = listOf(
        "FROM", "RUN", "COPY", "ADD", "CMD", "ENTRYPOINT", "WORKDIR", "EXPOSE",
        "ENV", "ARG", "VOLUME", "USER", "LABEL", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK"
    )
}

/**
 * Extension for MarkdownRenderer to use CodeBlockView
 */
fun MarkdownRenderer.createCodeBlockView(language: String, code: String, context: Context): View {
    val view = CodeBlockView(context)
    view.bind(language, code)
    return view.getView()
}