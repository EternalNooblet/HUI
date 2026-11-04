package com.hui.companion.ui

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.hui.companion.R
import com.hui.companion.util.Config
import timber.log.Timber

/** ImageMessageView
 *
 * Implements prompt-kit's Image component:
 * - Displays an image in a chat message bubble
 * - Uses Coil for image loading (placeholder + error states)
 * - Can be standalone or embedded in markdown rendering
 *
 * prompt-kit spec:
 * - Image: Coil in markdown or standalone
 */
class ImageMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var imageView: ImageView? = null
    private var progressBar: ProgressBar? = null
    private var errorText: TextView? = null
    // History images are mostly local content URIs (direct mode: the echo
    // markdown points at the phone copy). Plain OkHttp covers any http(s)
    // sources — the gateway is plain ws/http on LAN now, no pinned cert.
    private val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .okHttpClient { okhttp3.OkHttpClient() }
            .build()
    }

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER
        setPadding(
            context.resources.getDimensionPixelSize(R.dimen.pk_space_2),
            context.resources.getDimensionPixelSize(R.dimen.pk_space_1),
            context.resources.getDimensionPixelSize(R.dimen.pk_space_2),
            context.resources.getDimensionPixelSize(R.dimen.pk_space_1)
        )

        val view = LayoutInflater.from(context).inflate(R.layout.item_image_message, this, true)
        imageView = view.findViewById(R.id.imageMessageImage)
        progressBar = view.findViewById(R.id.imageMessageProgress)
        errorText = view.findViewById(R.id.imageMessageError)

        hideError()
    }

    /** Load an image from a URL or file path using Coil. Relative server
     *  paths (``/uploads/x.jpg`` from chat markdown) are resolved against
     *  the configured server URL. */
    fun loadImage(url: String) {
        showLoading()
        hideError()
        val resolved = Config.resolveImageUrl(url)
        Timber.i("ImageMessageView: loading %s", resolved)

        imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(resolved)
                .target { drawable ->
                    hideLoading()
                    imageView?.setImageDrawable(drawable)
                    imageView?.visibility = View.VISIBLE
                }
                .listener(
                    onError = { _, result ->
                        hideLoading()
                        showError("Image failed to load")
                        Timber.e(result.throwable, "ImageMessageView: failed to load %s", resolved)
                    }
                )
                .build()
        )
    }

    /** Load an image from a Uri (e.g., selected attachment). */
    fun loadImageUri(uri: Uri) {
        showLoading()
        hideError()

        imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(uri)
                .target { drawable ->
                    hideLoading()
                    imageView?.setImageDrawable(drawable)
                    imageView?.visibility = View.VISIBLE
                }
                .listener(
                    onError = { _, result ->
                        hideLoading()
                        showError("Image failed to load")
                        Timber.e(result.throwable, "ImageMessageView: failed to load %s", uri)
                    }
                )
                .build()
        )
    }

    private fun showLoading() {
        progressBar?.visibility = View.VISIBLE
        imageView?.visibility = View.GONE
        errorText?.visibility = View.GONE
    }

    private fun hideLoading() {
        progressBar?.visibility = View.GONE
    }

    private fun showError(message: String) {
        errorText?.text = message
        errorText?.visibility = View.VISIBLE
        imageView?.visibility = View.GONE
    }

    private fun hideError() {
        errorText?.visibility = View.GONE
    }
}