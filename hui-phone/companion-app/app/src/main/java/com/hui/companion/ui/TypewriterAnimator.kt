package com.hui.companion.ui

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView

/**
 * TypewriterAnimator
 *
 * Implements prompt-kit's ResponseStream + useTextStream:
 * - Typewriter mode (default, speed 20) - character by character
 * - Fade mode - word by word with fade-in animation
 * - Configurable: speed (1-100), characterChunkSize, segmentDelay, fadeDuration
 *
 * prompt-kit spec:
 * - speed: 1-100 (1 slowest, 100 fastest), default 20
 * - mode: "typewriter" | "fade"
 * - characterChunkSize: characters per frame (overrides speed)
 * - segmentDelay: delay between segments in ms (overrides speed)
 * - fadeDuration: fade duration in ms (overrides speed)
 */
class TypewriterAnimator {

    companion object {
        const val TYPEWRITER = 0
        const val FADE = 1
        const val DEFAULT_SPEED = 20
        const val MIN_SPEED = 1
        const val MAX_SPEED = 100
    }

    private var textView: TextView
    private var fullText = ""
    private var displayedText = ""
    private var currentIndex = 0
    private var animator: ValueAnimator? = null
    private var isComplete = false
    private var mode = TYPEWRITER
    private var speed = DEFAULT_SPEED
    private var characterChunkSize: Int? = null
    private var segmentDelayMs: Long? = null
    private var fadeDurationMs: Long? = null
    private var onCompleteListener: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val fadeColor: Int

    constructor(textView: TextView, fadeColor: Int = 0xFF000000.toInt()) {
        this.textView = textView
        this.fadeColor = fadeColor
    }

    fun start(text: String, mode: Int = TYPEWRITER, speed: Int = DEFAULT_SPEED,
              characterChunkSize: Int? = null, segmentDelayMs: Long? = null,
              fadeDurationMs: Long? = null, onComplete: (() -> Unit)? = null) {
        this.fullText = text
        this.mode = mode
        this.speed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        this.characterChunkSize = characterChunkSize
        this.segmentDelayMs = segmentDelayMs
        this.fadeDurationMs = fadeDurationMs
        this.onCompleteListener = onComplete
        this.displayedText = ""
        this.currentIndex = 0
        this.isComplete = false

        cancel()

        if (mode == TYPEWRITER) {
            startTypewriter()
        } else {
            startFade()
        }
    }

    private fun startTypewriter() {
        val chunkSize = characterChunkSize ?: calculateChunkSize()
        val delayMs = calculateTypewriterDelay()

        animator = ValueAnimator.ofInt(0, fullText.length).apply {
            duration = (fullText.length.toDouble() / chunkSize * delayMs).toLong()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Int
                if (progress > currentIndex) {
                    currentIndex = progress
                    displayedText = fullText.substring(0, currentIndex)
                    textView.text = displayedText
                }
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isComplete = true
                    onCompleteListener?.invoke()
                }
                override fun onAnimationStart(animation: android.animation.Animator) {}
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    isComplete = true
                    onCompleteListener?.invoke()
                }
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
            })
            start()
        }
    }

    private fun startFade() {
        // Split text into words (segments)
        val segments = splitIntoSegments(fullText)
        val delayMs = segmentDelayMs ?: calculateSegmentDelay()
        val fadeMs = fadeDurationMs ?: calculateFadeDuration()

        var currentSegment = 0
        val spannable = SpannableStringBuilder(fullText)

        // Initially all transparent
        spannable.setSpan(ForegroundColorSpan(0x00000000), 0, fullText.length, 0)

        animator = ValueAnimator.ofInt(0, segments.size).apply {
            duration = (segments.size.toDouble() * delayMs + fadeMs).toLong()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Int
                if (progress > currentSegment && currentSegment < segments.size) {
                    val segment = segments[currentSegment]
                    // Apply fade-in to this segment
                    val start = fullText.indexOf(segment, if (currentSegment == 0) 0 else fullText.indexOf(segments[currentSegment - 1]) + segments[currentSegment - 1].length)
                    val end = start + segment.length
                    if (start >= 0 && end <= fullText.length) {
                        spannable.setSpan(
                            ForegroundColorSpan(0xFF000000.toInt()),
                            start, end, 0
                        )
                        // Animate alpha from 0 to 1
                        val alphaAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = fadeDurationMs ?: calculateFadeDuration()
                            addUpdateListener { anim ->
                                val alpha = (anim.animatedValue as Float * 255).toInt()
                                spannable.setSpan(
                                    ForegroundColorSpan((alpha shl 24) or 0x000000),
                                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                                textView.text = spannable
                            }
                        }
                        alphaAnim.start()
                    }
                    currentSegment = progress
                    textView.text = spannable
                }
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isComplete = true
                    onCompleteListener?.invoke()
                }
                override fun onAnimationStart(animation: android.animation.Animator) {}
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    isComplete = true
                    onCompleteListener?.invoke()
                }
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
            })
            start()
        }
    }

    private fun splitIntoSegments(text: String): List<String> {
        // Use word boundaries like prompt-kit's Intl.Segmenter
        return text.split(" ").filter { it.isNotEmpty() }.map { it + " " }
    }

    private fun calculateChunkSize(): Int {
        return when {
            speed < 25 -> 1
            else -> ((speed - 25) / 10).coerceAtLeast(1)
        }
    }

    private fun calculateTypewriterDelay(): Long {
        return (100.0 / Math.sqrt(speed.toDouble())).toLong().coerceAtLeast(1)
    }

    private fun calculateSegmentDelay(): Long {
        return (100.0 / Math.sqrt(speed.toDouble())).toLong().coerceAtLeast(1)
    }

    private fun calculateFadeDuration(): Long {
        return (1000.0 / Math.sqrt(speed.toDouble())).toLong().coerceAtLeast(10)
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    fun pause() {
        animator?.pause()
    }

    fun resume() {
        animator?.resume()
    }

    fun isComplete(): Boolean = isComplete

    fun getDisplayedText(): String = displayedText
}