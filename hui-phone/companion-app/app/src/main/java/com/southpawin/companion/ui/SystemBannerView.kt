package com.southpawin.companion.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.southpawin.companion.R
import timber.log.Timber

/** SystemBannerView
 *
 * Implements prompt-kit's SystemMessage component:
 * - Top-of-chat dismissible banner
 * - Variants: info / warning / error (color-coded icon + text)
 * - Dismissible via X button (callback)
 *
 * prompt-kit spec:
 * - SystemMessage: top banner, dismissible, variants (info/warning/error)
 */
class SystemBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Variant { INFO, WARNING, ERROR }

    private var bannerCard: View? = null
    private var bannerIcon: ImageView? = null
    private var bannerText: TextView? = null
    private var dismissButton: ImageButton? = null
    private var onDismiss: (() -> Unit)? = null
    private var isVisible = false
    private var dismissHandler: Handler? = null
    private var dismissRunnable: Runnable? = null

    /** Delay before auto-dismiss (ms). Null = never auto-dismiss. */
    private var dismissDelayMs: Long? = null

    init {
        orientation = VERTICAL
        visibility = View.GONE

        val view = LayoutInflater.from(context).inflate(R.layout.item_system_banner, this, true)

        bannerCard = view.findViewById(R.id.systemBannerCard)
        bannerIcon = view.findViewById(R.id.systemBannerIcon)
        bannerText = view.findViewById(R.id.systemBannerText)
        dismissButton = view.findViewById(R.id.systemBannerDismiss)

        dismissButton?.setOnClickListener {
            cancelDismissTimer()
            hide()
            onDismiss?.invoke()
        }
    }

    /** Show the banner with a message and variant. Optionally auto-dismiss after delayMs. */
    fun show(message: String, variant: Variant = Variant.INFO, dismissDelayMs: Long? = null) {
        cancelDismissTimer()
        this.dismissDelayMs = dismissDelayMs
        bannerText?.text = message
        setVariantColors(variant)
        visibility = View.VISIBLE
        isVisible = true
        Timber.i("SystemBanner shown: %s (variant=%s)", message, variant)
        if (dismissDelayMs != null && dismissDelayMs > 0) {
            startDismissTimer(dismissDelayMs)
        }
    }

    /** Hide the banner. */
    fun hide() {
        cancelDismissTimer()
        visibility = View.GONE
        isVisible = false
    }

    fun isBannerShown(): Boolean = isVisible

    fun setOnDismissListener(listener: () -> Unit) {
        onDismiss = listener
    }

    private fun startDismissTimer(delayMs: Long) {
        cancelDismissTimer()
        dismissHandler = Handler(Looper.getMainLooper())
        val runnable = Runnable { hide() }
        dismissRunnable = runnable
        dismissHandler?.postDelayed(runnable, delayMs)
    }

    private fun cancelDismissTimer() {
        val r = dismissRunnable
        if (r != null) {
            dismissHandler?.removeCallbacks(r)
        }
        dismissHandler = null
        dismissRunnable = null
    }

    override fun onDetachedFromWindow() {
        cancelDismissTimer()
        super.onDetachedFromWindow()
    }

    private fun setVariantColors(variant: Variant) {
        val context = context ?: return
        val color = when (variant) {
            Variant.INFO  -> R.color.pk_primary
            Variant.WARNING -> R.color.pk_state_ready_text
            Variant.ERROR -> R.color.pk_state_error_text
        }
        val tint = androidx.core.content.ContextCompat.getColor(context, color)
        // ERROR gets a red X-circle, not the white donut / green check:
        // a green circle on "Disconnected from server" reads as success.
        val iconRes = when (variant) {
            Variant.ERROR -> R.drawable.ic_x_circle
            else -> R.drawable.ic_notification
        }
        bannerIcon?.setImageResource(iconRes)
        bannerIcon?.setColorFilter(tint)
        dismissButton?.setImageResource(
            if (variant == Variant.ERROR) R.drawable.ic_x_circle else R.drawable.ic_check_circle
        )
        dismissButton?.setColorFilter(tint)
    }
}