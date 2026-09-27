// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.richtext

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.webkit.WebView
import android.widget.ScrollView
import kotlin.math.ceil
import kotlin.math.floor

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
 */
class RichTextWebView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : WebView(context, attrs) {
        /** The latest event of a mouse drag, held while the button is down. */
        private var drag: MotionEvent? = null

        private val edgeScroll =
            object : Runnable {
                override fun run() {
                    val event = drag ?: return
                    val scroller = scroller() ?: return
                    val step = edgeStep(scroller, event.rawY)
                    if (step == 0 || !scroller.canScrollVertically(step)) return
                    scroller.scrollBy(0, step)
                    // The page moved under a mouse that did not; tell it where the
                    // pointer now sits so the selection keeps up.
                    val moved = movedTo(event)
                    super@RichTextWebView.onTouchEvent(moved)
                    moved.recycle()
                    postOnAnimation(this)
                }
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
            drag?.recycle()
            drag = null
        }

        override fun onDetachedFromWindow() {
            stopDrag()
            super.onDetachedFromWindow()
        }

        private fun scroller(): ScrollView? = generateSequence(parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()

        /**
         * How far to scroll this frame for a pointer at [rawY]: nothing away from the
         * edges, and faster the further into an edge, or past it, the pointer goes.
         */
        private fun edgeStep(
            scroller: ScrollView,
            rawY: Float,
        ): Int {
            val top = IntArray(2).also { scroller.getLocationOnScreen(it) }[1]
            val density = resources.displayMetrics.density
            val edge = EDGE_DP * density
            val below = rawY - (top + scroller.height - edge)
            val above = top + edge - rawY
            val depth =
                when {
                    below > 0 -> below
                    above > 0 -> -above
                    else -> return 0
                }
            val step = (depth / edge).coerceIn(-1f, 1f) * MAX_STEP_DP * density
            return if (step > 0) ceil(step).toInt() else floor(step).toInt()
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

            /** The fastest the fields scroll, per frame. */
            private const val MAX_STEP_DP = 16f
        }
    }
