package com.southpawin.companion.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.southpawin.companion.R
import com.southpawin.companion.ui.PromptInputLayout
import timber.log.Timber

/** AttachmentPicker
 *
 * Handles file attachment selection and preview chips in PromptInputActions.
 * prompt-kit's FileUpload component: integrated in PromptInputActions, preview chips.
 *
 * For v1: image/photo attachment via system picker. Selected file shows as a
 * removable preview chip in the input area.
 */
class AttachmentPicker constructor(
    private val context: Context,
    private val promptInputLayout: PromptInputLayout,
    private val onAttachmentSelected: (Uri) -> Unit
) {

    private var attachmentPreview: View? = null
    private var selectedUri: Uri? = null
    private val PICK_IMAGE_REQUEST = 1001
    private val imageLoader = ImageLoader.Builder(context).build()

    /** Call from Activity.onActivityResult to handle picker result. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK_IMAGE_REQUEST) return false
        if (resultCode == Activity.RESULT_OK && data != null) {
            val uri = data.data
            if (uri != null) {
                selectAttachment(uri)
            }
            return true
        }
        return false
    }

    /** Pick an image from the gallery. Call from Activity. */
    fun pickAttachment() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        val activity = context as? Activity
        activity?.startActivityForResult(intent, PICK_IMAGE_REQUEST)
            ?: Timber.w("AttachmentPicker: context is not an Activity, cannot pick")
    }

    private fun selectAttachment(uri: Uri) {
        selectedUri = uri
        showPreview(uri)
        onAttachmentSelected(uri)
        Timber.i("AttachmentPicker: selected %s", uri.toString())
    }

    private fun showPreview(uri: Uri) {
        val actionsContainer = promptInputLayout.getActionsContainer() ?: return

        // Remove existing preview if any
        attachmentPreview?.let { actionsContainer.removeView(it) }

        attachmentPreview = LayoutInflater.from(context)
            .inflate(R.layout.item_attachment_preview, actionsContainer, false)

        val thumbnail = attachmentPreview!!.findViewById<ImageView>(R.id.attachmentThumbnail)
        val filename = attachmentPreview!!.findViewById<TextView>(R.id.attachmentFilename)
        val removeBtn = attachmentPreview!!.findViewById<ImageButton>(R.id.attachmentRemove)

        // Load thumbnail with Coil
        imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(uri)
                .target { drawable ->
                    thumbnail.setImageDrawable(drawable)
                }
                .build()
        )

        // Get filename from URI
        val fileName = getFileName(uri)
        filename.text = fileName

        removeBtn.setOnClickListener {
            clearAttachment()
        }

        // Insert preview before send button
        actionsContainer.addView(attachmentPreview, actionsContainer.childCount - 1)
    }

    private fun clearAttachment() {
        selectedUri = null
        attachmentPreview?.let {
            val actionsContainer = promptInputLayout.getActionsContainer() ?: return
            actionsContainer.removeView(it)
        }
        attachmentPreview = null
    }

    private fun getFileName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    cursor.getString(columnIndex)
                } else {
                    uri.lastPathSegment ?: "attachment"
                }
            } ?: "attachment"
        } catch (e: Exception) {
            "attachment"
        }
    }

    val getSelectedUri: Uri? get() = selectedUri
}