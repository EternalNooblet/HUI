package com.southpawin.companion.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.southpawin.companion.R

/**
 * CitationChip
 *
 * Implements prompt-kit's Source component (HoverCard pattern):
 * - SourceTrigger: Chip with favicon + domain, rounded-full bg-muted
 * - SourceContent: HoverCardContent popup with favicon, title, description
 * - On long-press/hover: show popup with full citation details
 *
 * prompt-kit spec:
 * - Trigger: bg-muted text-muted-foreground hover:bg-muted-foreground/30 hover:text-primary
 * - Chip: inline-flex h-5 max-w-32 items-center gap-1 overflow-hidden rounded-full py-0 text-xs
 * - Favicon: https://www.google.com/s2/favicons?sz=64&domain_url={href}
 * - Content: w-80 p-0 shadow-xs, flex flex-col gap-2 p-3
 * - Content fields: domain (text-primary truncate text-sm), title (line-clamp-2 text-sm font-medium), description (text-muted-foreground line-clamp-2 text-sm)
 */
class CitationChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var chip: LinearLayout? = null
    private var faviconView: ImageView? = null
    private var domainText: TextView? = null
    private var citation: Citation? = null
    private var popupWindow: PopupWindow? = null

    init {
        initView()
    }

    private fun initView() {
        val view = LayoutInflater.from(context).inflate(R.layout.item_citation_chip, this, true)
        chip = view.findViewById(R.id.citationChipContainer)
        faviconView = view.findViewById(R.id.citationFavicon)
        domainText = view.findViewById(R.id.citationChipText)

        chip?.setOnClickListener {
            // Open URL in browser
            citation?.href?.let { url ->
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                context.startActivity(intent)
            }
        }

        chip?.setOnLongClickListener {
            showPopup()
            true
        }

        // Also show on hover (API 26+)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            chip?.setOnHoverListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_HOVER_ENTER -> showPopup()
                    android.view.MotionEvent.ACTION_HOVER_EXIT -> hidePopup()
                }
                true
            }
        }
    }

    fun bind(citationData: Citation) {
        citation = citationData
        
        // Set domain text
        val displayDomain = citationData.domain.replace("www.", "")
        domainText?.text = displayDomain

        // Load favicon
        val faviconUrl = "https://www.google.com/s2/favicons?sz=64&domain_url=${citationData.href}"
        loadFavicon(faviconUrl)
    }

    private fun loadFavicon(url: String) {
        // Simple implementation - in production use Coil/Glide
        // For now, use a placeholder
        faviconView?.setImageResource(R.drawable.ic_link)
        faviconView?.setColorFilter(ContextCompat.getColor(context, R.color.pk_muted_foreground))
    }

    private fun showPopup() {
        if (citation == null || popupWindow != null) return

        val contentView = LayoutInflater.from(context).inflate(R.layout.item_citation_popup, null)
        
        val popupFavicon = contentView.findViewById<ImageView>(R.id.citationPopupFavicon)
        val popupDomain = contentView.findViewById<TextView>(R.id.citationPopupDomain)
        val popupTitle = contentView.findViewById<TextView>(R.id.citationPopupTitle)
        val popupDescription = contentView.findViewById<TextView>(R.id.citationPopupDescription)

        val c = citation!!
        popupDomain?.text = c.domain.replace("www.", "")
        popupTitle?.text = c.title
        popupDescription?.text = c.description

        // Load favicon for popup
        val faviconUrl = "https://www.google.com/s2/favicons?sz=64&domain_url=${c.href}"
        // placeholder for now
        popupFavicon?.setImageResource(R.drawable.ic_link)
        popupFavicon?.setColorFilter(ContextCompat.getColor(context, R.color.pk_primary))

        popupWindow = PopupWindow(contentView, 
            context.resources.getDimensionPixelSize(R.dimen.pk_citation_popup_width),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
        
        popupWindow?.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_citation_popup))
        popupWindow?.setElevation(context.resources.getDimension(R.dimen.pk_elevation_3))
        popupWindow?.setOutsideTouchable(true)
        popupWindow?.setFocusable(true)

        // Show below the chip
        chip?.let { anchor ->
            popupWindow?.showAsDropDown(anchor, 0, context.resources.getDimensionPixelSize(R.dimen.pk_space_1))
        }
    }

    private fun hidePopup() {
        popupWindow?.dismiss()
        popupWindow = null
    }
}

/**
 * Citation data model (matches prompt-kit Source)
 */
data class Citation(
    val href: String,
    val title: String,
    val description: String,
    val domain: String = android.net.Uri.parse(href).host ?: ""
)