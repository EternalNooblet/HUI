package com.southpawin.companion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.southpawin.companion.R
import com.southpawin.companion.ui.ChatRecyclerView
import com.southpawin.companion.ui.StickToBottomScrollListener
import timber.log.Timber

/** StickToBottomFab
 *
 * Implements prompt-kit's ScrollButton component:
 * - FAB that appears when user scrolls up from bottom
 * - Taps to smoothly scroll to bottom
 * - Hidden when user is at bottom
 *
 * prompt-kit spec:
 * - ScrollButton: FAB "jump to bottom", shows when not at bottom
 */
class StickToBottomFab @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FloatingActionButton(context, attrs, defStyleAttr) {

    private var chatRecyclerView: ChatRecyclerView? = null
    private var stickToBottomListener: StickToBottomScrollListener? = null
    private var isVisible = false

    init {
        // FAB defaults
        setImageResource(R.drawable.ic_chevron_down)
        setSize(FloatingActionButton.SIZE_MINI)
        visibility = View.GONE
        setOnClickListener {
            chatRecyclerView?.scrollToBottomSmooth()
            Timber.d("StickToBottomFab: scroll to bottom")
        }
    }

    /** Attach to a ChatRecyclerView so the Fab reacts to scroll state. */
    fun attach(chatRecyclerView: ChatRecyclerView) {
        this.chatRecyclerView = chatRecyclerView
        stickToBottomListener = chatRecyclerView.getStickToBottomListener()

        // Listen to scroll state changes via the listener's callback
        // We poll on scroll events by wrapping the listener
        val original = stickToBottomListener
        // Use a simple approach: check on each scroll via a layout listener
        chatRecyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                updateVisibility()
            }
        })

        // Initial state
        updateVisibility()
    }

    private fun updateVisibility() {
        val atBottom = chatRecyclerView?.isUserAtBottom() ?: true
        if (atBottom && isVisible) {
            hideFab()
        } else if (!atBottom && !isVisible) {
            showFab()
        }
    }

    private fun showFab() {
        visibility = View.VISIBLE
        isVisible = true
        animate().alpha(1f).scaleY(1f).scaleX(1f).setDuration(200).start()
    }

    private fun hideFab() {
        animate().alpha(0f).scaleY(0f).scaleX(0f).setDuration(200).withEndAction {
            if (!isVisible) visibility = View.GONE
        }.start()
        isVisible = false
    }
}