// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.richtext

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.webkit.WebView
import android.widget.ScrollView

/**
 * The note editor's rich text page.
 *
 * The page is laid out at its full height inside the editor's scrolling view, so
 * it never has anything of its own to scroll. A [WebView] still answers a mouse
 * wheel, swallowing it and leaving the fields around it stuck, so the wheel is
 * handed back to the view above.
 *
 * A mouse drag goes the other way. It selects text, but the scrolling view takes
 * any drag that strays off the horizontal as a scroll, so it is kept out of it.
 * A finger still scrolls the fields as before.
 *
 * Since the page has nothing to scroll, dragging a selection to the top or bottom
 * of the screen would stop there. The scrolling view is moved on the page's
 * behalf instead, and the selection follows it.
 *
 * Focus is the last thing kept from the scrolling view. It scrolls a newly focused
 * child into view, and for a page taller than the screen that means its top,
 * wherever the tap was. The page already scrolls its own caret into view.
 *
 * The engine can still scroll the page itself, to reveal a selection such as a
 * double-clicked word. The wheel goes to the scrolling view, so nothing would
 * ever scroll the page back: everything above the field stays hidden. That
 * scroll is handed to the scrolling view instead, which shows the same place.
 */
class RichTextWebView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : WebView(context, attrs) {
        /** The latest event of a mouse drag, held while the button is down. */
        private var drag: MotionEvent? = null

        /** When the edge scroll last moved, or 0 while it is not running. */
        private var lastEdgeFrame = 0L

        /** The part of a pixel the edge scroll owes, so a slow scroll still moves. */
        private var edgeCarry = 0f

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) revealOnFocusHint = false
        }

        private val edgeScroll =
            object : Runnable {
                override fun run() {
                    val event = drag ?: return stopEdgeScroll()
                    val scroller = scroller() ?: return stopEdgeScroll()
                    val speed = edgeSpeed(scroller, event.rawY)
                    if (speed == 0f || !scroller.canScrollVertically(if (speed > 0) 1 else -1)) {
                        return stopEdgeScroll()
                    }
                    // Move by time, not by frame, so a 120 Hz screen is no faster.
                    val now = SystemClock.uptimeMillis()
                    val elapsed = if (lastEdgeFrame == 0L) FRAME_MS else (now - lastEdgeFrame).coerceIn(0L, MAX_FRAME_MS)
                    lastEdgeFrame = now
                    edgeCarry += speed * elapsed / 1000f
                    val step = edgeCarry.toInt()
                    edgeCarry -= step
                    if (step != 0) {
                        scroller.scrollBy(0, step)
                        // The page moved under a mouse that did not; tell it where the
                        // pointer now sits so the selection keeps up.
                        val moved = movedTo(event)
                        super@RichTextWebView.onTouchEvent(moved)
                        moved.recycle()
                    }
                    postOnAnimation(this)
                }
            }

        override fun onScrollChanged(
            l: Int,
            t: Int,
            oldl: Int,
            oldt: Int,
        ) {
            super.onScrollChanged(l, t, oldl, oldt)
            if (t == 0) return
            scrollTo(scrollX, 0)
            scroller()?.scrollBy(0, t)
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_SCROLL) return false
            return super.onGenericMotionEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) trackDrag(event)
            return super.onTouchEvent(event)
        }

        private fun trackDrag(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) holdDrag(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (drag == null) return
                    holdDrag(event)
                    removeCallbacks(edgeScroll)
                    postOnAnimation(edgeScroll)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDrag()
            }
        }

        private fun holdDrag(event: MotionEvent) {
            drag?.recycle()
            drag = MotionEvent.obtain(event)
        }

        private fun stopDrag() {
            removeCallbacks(edgeScroll)
            stopEdgeScroll()
            drag?.recycle()
            drag = null
        }

        private fun stopEdgeScroll() {
            lastEdgeFrame = 0L
            edgeCarry = 0f
        }

        override fun onDetachedFromWindow() {
            stopDrag()
            super.onDetachedFromWindow()
        }

        private fun scroller(): ScrollView? = generateSequence(parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()

        /**
         * How fast to scroll, in pixels a second, for a pointer at [rawY]: not at all
         * away from the edges, and faster the further into an edge, or past it, the
         * pointer goes.
         */
        private fun edgeSpeed(
            scroller: ScrollView,
            rawY: Float,
        ): Float {
            val top = IntArray(2).also { scroller.getLocationOnScreen(it) }[1]
            val density = resources.displayMetrics.density
            val edge = EDGE_DP * density
            val below = rawY - (top + scroller.height - edge)
            val above = top + edge - rawY
            val depth =
                when {
                    below > 0 -> below
                    above > 0 -> -above
                    else -> return 0f
                }
            return (depth / edge).coerceIn(-1f, 1f) * MAX_SPEED_DP * density
        }

        /** [event] again, now, at the same place on screen after the page has moved. */
        private fun movedTo(event: MotionEvent): MotionEvent {
            val location = IntArray(2).also { getLocationOnScreen(it) }
            val properties = PointerProperties().also { event.getPointerProperties(0, it) }
            val coords =
                PointerCoords().also {
                    event.getPointerCoords(0, it)
                    it.x = event.rawX - location[0]
                    it.y = event.rawY - location[1]
                }
            return MotionEvent.obtain(
                event.downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                1,
                arrayOf(properties),
                arrayOf(coords),
                event.metaState,
                event.buttonState,
                event.xPrecision,
                event.yPrecision,
                event.deviceId,
                event.edgeFlags,
                event.source,
                event.flags,
            )
        }

        companion object {
            /** How close to the top or bottom of the fields a drag starts scrolling them. */
            private const val EDGE_DP = 40f

            /** The fastest the fields scroll, per second: with the pointer past the edge. */
            private const val MAX_SPEED_DP = 400f

            /** A frame at 60 Hz, for the first step before there is a frame to time. */
            private const val FRAME_MS = 16L

            /** A stalled frame should not jump the fields by the whole wait. */
            private const val MAX_FRAME_MS = 50L
        }
    }
