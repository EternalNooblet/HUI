package com.hui.companion.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import coil.ImageLoader
import coil.request.ImageRequest
import com.hui.companion.R
import timber.log.Timber

/** AttachmentPicker
 *
 * Mirrors prompt-kit's FileUpload component:
 * - FileUpload multiple=true (default): ACTION_PICK with
 *   EXTRA_ALLOW_MULTIPLE, accumulates picks instead of replacing.
 * - FileUploadTrigger: the paperclip in PromptInputActions.
 * - Preview chips: rendered ABOVE the textarea (FileUpload + PromptInput
 *   composition), each with thumbnail, filename, uploading indicator,
 *   and a remove button. Removing a chip removes that attachment's
 *   pending state — the old code left AttachmentState set, so a
 *   "removed" image was still sent.
 *
 * Upload itself lives in MainActivity (it owns the network stack);
 * this picker only selects, previews, and reports add/remove.
 */
class AttachmentPicker constructor(
    private val context: Context,
    private val promptInputLayout: PromptInputLayout,
    private val onAttachmentSelected: (Uri) -> Unit,
    private val onAttachmentRemoved: (String) -> Unit = {}
) {

    private val chipsById = linkedMapOf<String, View>()
    private val PICK_IMAGE_REQUEST = 1001
    private val imageLoader = ImageLoader.Builder(context).build()

    /** Call from Activity.onActivityResult to handle picker result. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK_IMAGE_REQUEST) return false
        if (resultCode == Activity.RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            // Multi-select: ClipData carries all picks (FileUpload multiple=true)
            val clip = data.clipData
            if (clip != null && clip.itemCount > 0) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            } else {
                data.data?.let { uris.add(it) }
            }
            uris.forEach { selectAttachment(it) }
            return true
        }
        return false
    }

    /** Pick images from the gallery. Call from Activity.
     *
     * Uses ACTION_GET_CONTENT (DocumentsUI) for multi-select — the old
     * ACTION_PICK + CATEGORY_OPENABLE combination matches NO activity on
     * devices whose gallery (Gallery3D) declares no OPENABLE category,
     * which crashed with ActivityNotFoundException. Falls back to
     * ACTION_PICK, then to a toast if no handler exists at all. */
    fun pickAttachment() {
        val activity = context as? Activity
        if (activity == null) {
            Timber.w("AttachmentPicker: context is not an Activity, cannot pick")
            return
        }
        val candidates = listOf(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        )
        for (intent in candidates) {
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                activity.startActivityForResult(intent, PICK_IMAGE_REQUEST)
                return
            } catch (e: android.content.ActivityNotFoundException) {
                Timber.w(e, "AttachmentPicker: no handler for %s, trying next", intent.action)
            }
        }
        android.widget.Toast.makeText(
            context, "No photo picker found on this device", android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun selectAttachment(uri: Uri) {
        // Persistable read permission so the URI survives the upload copy
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            Timber.d("AttachmentPicker: no persistable permission for %s", uri)
        }
        showPreview(uri)
        onAttachmentSelected(uri)
        Timber.i("AttachmentPicker: selected %s", uri.toString())
    }

    private fun showPreview(uri: Uri) {
        val container = promptInputLayout.getPreviewContainer() ?: return
        val id = uri.toString()
        if (chipsById.containsKey(id)) return

        val chip = try {
            LayoutInflater.from(context)
                .inflate(R.layout.item_attachment_preview, container, false)
        } catch (e: Exception) {
            Timber.e(e, "AttachmentPicker: failed to inflate preview, skipping chip")
            return
        }

        val thumbnail = chip.findViewById<ImageView>(R.id.attachmentThumbnail)
        val filename = chip.findViewById<TextView>(R.id.attachmentFilename)
        val removeBtn = chip.findViewById<ImageButton>(R.id.attachmentRemove)
        val progress = chip.findViewById<ProgressBar>(R.id.attachmentProgress)

        imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(uri)
                .target { drawable -> thumbnail.setImageDrawable(drawable) }
                .build()
        )

        filename.text = getFileName(uri)
        progress.visibility = View.VISIBLE
        removeBtn.setOnClickListener { removeChip(id) }

        chipsById[id] = chip
        container.addView(chip)
        container.visibility = View.VISIBLE
    }

    /** Toggle the uploading indicator for a chip (PromptInput isLoading). */
    fun setUploading(uri: Uri, uploading: Boolean) {
        val chip = chipsById[uri.toString()] ?: return
        chip.findViewById<ProgressBar>(R.id.attachmentProgress)
            .visibility = if (uploading) View.VISIBLE else View.GONE
    }

    /** Remove one chip and its pending attachment (id = uri string). */
    fun removeChip(id: String) {
        val chip = chipsById.remove(id) ?: return
        promptInputLayout.getPreviewContainer()?.removeView(chip)
        updateContainerVisibility()
        onAttachmentRemoved(id)
    }

    /** Clear all chips after a successful send. */
    fun clearAll() {
        chipsById.values.toList().forEach { chip ->
            promptInputLayout.getPreviewContainer()?.removeView(chip)
        }
        chipsById.clear()
        updateContainerVisibility()
    }

    private fun updateContainerVisibility() {
        val container = promptInputLayout.getPreviewContainer() ?: return
        container.visibility = if (chipsById.isEmpty()) View.GONE else View.VISIBLE
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

    val getSelectedUris: List<Uri> get() = chipsById.keys.map { Uri.parse(it) }
}
