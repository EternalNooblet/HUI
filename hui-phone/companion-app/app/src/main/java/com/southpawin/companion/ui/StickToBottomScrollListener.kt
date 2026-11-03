package com.southpawin.companion.ui

import android.util.Log
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * StickToBottomScrollListener
 *
 * Replicates the behavior of `use-stick-to-bottom` library used in prompt-kit's ChatContainer.
 *
 * Key behaviors:
 * - `resize="smooth"`: When RecyclerView resizes (keyboard, new items), smoothly scroll to bottom if user was at bottom
 * - `initial="instant"`: On first layout, instantly scroll to bottom (no animation)
 * - `role="log"`: Announces new messages to accessibility services
 *
 * Algorithm (from use-stick-to-bottom):
 * 1. Track whether user is "at bottom" (within threshold of last item)
 * 2. On layout/scroll/resize: if was at bottom, scroll to new bottom
 * 3. Use smooth scroll for resize, instant for initial layout
 */
class StickToBottomScrollListener(
    private val recyclerView: RecyclerView,
    private val layoutManager: LinearLayoutManager,
    private val thresholdPx: Int = 100, // px threshold for "at bottom"
    private val onScrollCallback: ((Boolean) -> Unit)? = null // true = at bottom
) : RecyclerView.OnScrollListener() {

    private var isAtBottom = true
    private var isInitialLayout = true
    private var pendingSmoothScroll = false

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        super.onScrolled(recyclerView, dx, dy)
        updateAtBottomState()
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        super.onScrollStateChanged(recyclerView, newState)
        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
            updateAtBottomState()
        }
    }

    /**
     * Call when new items are inserted or RecyclerView size changes (keyboard, etc.)
     * Replicates `resize="smooth"` behavior.
     */
    fun onContentChanged(smooth: Boolean = true) {
        if (isInitialLayout) {
            // initial="instant" - scroll instantly on first layout
            scrollToBottomInstant()
            isInitialLayout = false
        } else if (isAtBottom) {
            // resize="smooth" - smooth scroll if user was at bottom
            if (smooth) {
                scrollToBottomSmooth()
            } else {
                scrollToBottomInstant()
            }
        }
        // If user scrolled up (not at bottom), don't auto-scroll
    }

    /**
     * Call when a new message arrives (appended to end).
     * If user was at bottom, scroll to show new message.
     */
    fun onNewMessage(smooth: Boolean = true) {
        if (isAtBottom) {
            if (smooth) {
                scrollToBottomSmooth()
            } else {
                scrollToBottomInstant()
            }
        }
    }

    private fun updateAtBottomState() {
        val lastVisiblePosition = layoutManager.findLastCompletelyVisibleItemPosition()
        val itemCount = layoutManager.itemCount
        val wasAtBottom = isAtBottom

        isAtBottom = (lastVisiblePosition >= itemCount - 1) || isNearBottom()

        if (wasAtBottom != isAtBottom) {
            onScrollCallback?.invoke(isAtBottom)
        }
    }

    private fun isNearBottom(): Boolean {
        val childCount = layoutManager.childCount
        if (childCount == 0) return true

        val lastChild = layoutManager.getChildAt(childCount - 1) ?: return true
        val lastChildBottom = lastChild.bottom
        val recyclerViewBottom = recyclerView.height - recyclerView.paddingBottom

        return (recyclerViewBottom - lastChildBottom) <= thresholdPx
    }

    private fun scrollToBottomInstant() {
        val itemCount = layoutManager.itemCount
        if (itemCount > 0) {
            recyclerView.scrollToPosition(itemCount - 1)
        }
    }

    private fun scrollToBottomSmooth() {
        val itemCount = layoutManager.itemCount
        if (itemCount > 0) {
            // Cancel any pending smooth scroll
            recyclerView.stopScroll()
            recyclerView.smoothScrollToPosition(itemCount - 1)
        }
    }

    /** Returns true if user is currently at bottom (for showing/hiding ScrollToBottomFab) */
    fun isUserAtBottom(): Boolean = isAtBottom
}