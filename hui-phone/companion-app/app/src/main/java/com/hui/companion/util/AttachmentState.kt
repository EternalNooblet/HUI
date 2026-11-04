package com.hui.companion.util

/**
 * Pending image attachments waiting to be sent with the next chat message.
 *
 * Mirrors prompt-kit's FileUpload model: files are collected locally
 * (onFilesAdded), can be removed individually, and are only attached
 * to the outgoing message on submit. Images are uploaded immediately
 * on pick (server needs an /uploads URL), so an entry appears here
 * only once its upload succeeded; [uploadingCount] tracks in-flight
 * uploads so send can be gated until they finish (PromptInput isLoading).
 */
object AttachmentState {
    data class Pending(val id: String, val url: String, val name: String)

    private val pending = mutableListOf<Pending>()
    private val listeners = mutableListOf<() -> Unit>()

    var uploadingCount: Int = 0
        private set

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    private fun notifyChanged() { listeners.toList().forEach { it() } }

    fun add(id: String, url: String, name: String) {
        pending.removeAll { it.id == id }
        pending.add(Pending(id, url, name))
        notifyChanged()
    }

    fun remove(id: String) {
        if (pending.removeAll { it.id == id }) notifyChanged()
    }

    fun setUploading(delta: Int) {
        uploadingCount = (uploadingCount + delta).coerceAtLeast(0)
        notifyChanged()
    }

    fun peek(): List<Pending> = pending.toList()

    /** Consume all pending attachments. Call ONLY after send is validated
     *  (authenticated + connected) — consuming earlier loses the images
     *  when the send is blocked. */
    fun takeAll(): List<Pending> {
        val taken = pending.toList()
        pending.clear()
        notifyChanged()
        return taken
    }

    fun restore(items: List<Pending>) {
        pending.addAll(items.filter { p -> pending.none { it.id == p.id } })
        notifyChanged()
    }

    fun clear() {
        pending.clear()
        notifyChanged()
    }

    fun hasReady(): Boolean = pending.isNotEmpty()
    fun isUploading(): Boolean = uploadingCount > 0
}
