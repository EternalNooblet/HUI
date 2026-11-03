package com.southpawin.companion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * ChatRecyclerView
 *
 * Wrapper around RecyclerView that integrates StickToBottomScrollListener
 * and provides prompt-kit ChatContainer behavior.
 *
 * Corresponds to prompt-kit's ChatContainerRoot + ChatContainerContent + ChatContainerScrollAnchor
 */
class ChatRecyclerView constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private var stickToBottomListener: StickToBottomScrollListener? = null
    private var scrollAnchor: View? = null

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    init {
        initChatRecyclerView()
    }

    private fun initChatRecyclerView() {
        // prompt-kit: flex overflow-y-auto, flex-col
        setOverScrollMode(OVER_SCROLL_NEVER)
        setClipToPadding(false)
        setHasFixedSize(false)

        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        layoutManager.stackFromEnd = false // We manage scroll position manually
        this.layoutManager = layoutManager

        // Add stick-to-bottom listener
        stickToBottomListener = StickToBottomScrollListener(this, layoutManager)
        addOnScrollListener(stickToBottomListener!!)

        // Add scroll anchor (prompt-kit: ChatContainerScrollAnchor - h-px w-full shrink-0 scroll-mt-4)
        scrollAnchor = View(context)
        scrollAnchor?.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
        // We don't add it to RecyclerView directly; it's used as a scroll target
    }

    /** Attach adapter and notify stick-to-bottom listener */
    override fun setAdapter(adapter: Adapter<*>?) {
        super.setAdapter(adapter)
        stickToBottomListener?.onContentChanged(false)
    }

    /** Notify that content changed (resize, new items) - triggers stick-to-bottom logic */
    fun notifyContentChanged(smooth: Boolean = true) {
        stickToBottomListener?.onContentChanged(smooth)
    }

    /** Notify new message appended - triggers stick-to-bottom if user was at bottom */
    fun notifyNewMessage(smooth: Boolean = true) {
        stickToBottomListener?.onNewMessage(smooth)
    }

    /** Returns true if user is scrolled to bottom */
    fun isUserAtBottom(): Boolean = stickToBottomListener?.isUserAtBottom() ?: true

    /** Scroll to bottom instantly */
    fun scrollToBottomInstant() {
        stickToBottomListener?.let { listener ->
            val itemCount = layoutManager?.itemCount ?: 0
            if (itemCount > 0) {
                scrollToPosition(itemCount - 1)
            }
        }
    }

    /** Scroll to bottom smoothly */
    fun scrollToBottomSmooth() {
        val itemCount = layoutManager?.itemCount ?: 0
        if (itemCount > 0) {
            smoothScrollToPosition(itemCount - 1)
        }
    }

    /** Get the stick-to-bottom listener for external control (e.g., ScrollToBottomFab) */
    fun getStickToBottomListener(): StickToBottomScrollListener? = stickToBottomListener

    /** LayoutManager accessor */
    fun getChatLayoutManager(): LinearLayoutManager? = layoutManager as? LinearLayoutManager
}