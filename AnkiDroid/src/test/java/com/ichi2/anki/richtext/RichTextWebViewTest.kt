// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.richtext

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class RichTextWebViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a mouse press keeps the scrolling view from taking the drag`() {
        val parent = RecordingParent(context)
        val webView = RichTextWebView(context).also { parent.addView(it) }

        webView.onTouchEvent(press(MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE))

        assertTrue("a mouse drag selects text rather than scrolling", parent.disallowed)
    }

    @Test
    fun `a finger press still lets the scrolling view scroll`() {
        val parent = RecordingParent(context)
        val webView = RichTextWebView(context).also { parent.addView(it) }

        webView.onTouchEvent(press(MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN))

        assertFalse("a finger swipe should still scroll the fields", parent.disallowed)
    }

    @Test
    fun `a mouse drag at the bottom edge scrolls the fields`() {
        val (scrollView, webView) = scrollingPage()

        webView.onTouchEvent(scrollView.mouse(MotionEvent.ACTION_DOWN, y = 50f))
        webView.onTouchEvent(scrollView.mouse(MotionEvent.ACTION_MOVE, y = 95f))
        idle()

        assertThat(scrollView.scrollY, greaterThan(0))
    }

    @Test
    fun `a mouse drag away from the edges does not scroll`() {
        val (scrollView, webView) = scrollingPage()

        webView.onTouchEvent(scrollView.mouse(MotionEvent.ACTION_DOWN, y = 50f))
        webView.onTouchEvent(scrollView.mouse(MotionEvent.ACTION_MOVE, y = 55f))
        idle()

        assertThat(scrollView.scrollY, equalTo(0))
    }

    @Test
    fun `letting go of the mouse stops the scrolling`() {
        val (scrollView, webView) = scrollingPage()

        webView.onTouchEvent(scrollView.mouse(MotionEvent.ACTION_DOWN, y = 50f))
        webView.onTouchEvent(scrollView.mouse(MotionEvent.ACTION_MOVE, y = 95f))
        idle()
        webView.onTouchEvent(scrollView.mouse(MotionEvent.ACTION_UP, y = 95f))
        val stoppedAt = scrollView.scrollY
        idle()

        assertThat(scrollView.scrollY, equalTo(stoppedAt))
    }

    /**
     * A page ten times taller than the scrolling view it sits in, as in the note
     * editor. It has to be on screen: a detached view never runs its animation.
     */
    private fun scrollingPage(): Pair<ScrollView, RichTextWebView> {
        val webView = RichTextWebView(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(webView, LinearLayout.LayoutParams(200, 1000))
        val scrollView = ScrollView(context).apply { addView(column) }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(scrollView, FrameLayout.LayoutParams(200, 100))
        idle()
        return scrollView to webView
    }

    /** A mouse event [y] pixels down from the top of the scrolling view. */
    private fun ScrollView.mouse(
        action: Int,
        y: Float,
    ): MotionEvent {
        val top = IntArray(2).also { getLocationOnScreen(it) }[1]
        return event(action, MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE, top + y)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    private fun press(
        toolType: Int,
        source: Int,
    ) = event(MotionEvent.ACTION_DOWN, toolType, source, 10f)

    private fun event(
        action: Int,
        toolType: Int,
        source: Int,
        y: Float,
    ): MotionEvent {
        val properties =
            PointerProperties().apply {
                id = 0
                this.toolType = toolType
            }
        val coords =
            PointerCoords().apply {
                x = 10f
                this.y = y
            }
        return MotionEvent.obtain(
            0L,
            0L,
            action,
            1,
            arrayOf(properties),
            arrayOf(coords),
            0,
            if (toolType == MotionEvent.TOOL_TYPE_MOUSE && action != MotionEvent.ACTION_UP) MotionEvent.BUTTON_PRIMARY else 0,
            1f,
            1f,
            0,
            0,
            source,
            0,
        )
    }

    private class RecordingParent(
        context: Context,
    ) : FrameLayout(context) {
        var disallowed = false

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            disallowed = disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }
}
