// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.richtext

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
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
import org.hamcrest.Matchers.lessThanOrEqualTo
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

    @Test
    fun `edge scrolling keeps to a readable speed`() {
        // tall enough that the scroll never reaches the end while it is timed
        val (scrollView, webView) = scrollingPage(pageHeight = 100_000)

        webView.onTouchEvent(scrollView.mouse(MotionEvent.ACTION_DOWN, y = 50f))
        // past the bottom of the fields, which is the fastest it goes
        webView.onTouchEvent(scrollView.mouse(MotionEvent.ACTION_MOVE, y = 150f))
        val start = SystemClock.uptimeMillis()
        // Robolectric keeps running frames past the time asked for, so the speed
        // is measured against the time that actually went by
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val seconds = (SystemClock.uptimeMillis() - start) / 1000f

        assertThat(scrollView.scrollY, greaterThan(0))
        assertThat(scrollView.scrollY / seconds, lessThanOrEqualTo(MAX_SPEED_PX_PER_SECOND))
    }

    @Test
    fun `focusing the page does not make the fields jump to it`() {
        // A ScrollView scrolls a newly focused child into view. The page is taller
        // than the screen, so that would put its top at the top of the screen
        // wherever the tap was. Robolectric lays a WebView out with no height, so
        // the jump itself cannot be shown here; the flag that stops it can.
        assertFalse(RichTextWebView(context).revealOnFocusHint)
    }

    @Test
    fun `the page hands a scroll of its own to the fields`() {
        val (scrollView, webView) = scrollingPage()

        // what the engine does to reveal a selection, such as a double-clicked word
        webView.scrollTo(0, 300)

        assertThat("the page stays at its top, where the wheel can reach it", webView.scrollY, equalTo(0))
        assertThat("the fields show the same place instead", scrollView.scrollY, equalTo(300))
    }

    @Test
    fun `Ctrl+Z undoes in the page, ahead of the keyboard app`() {
        val webView = RichTextWebView(context)

        assertTrue(webView.dispatchKeyEventPreIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)))
        assertThat(shadowOf(webView).lastEvaluatedJavascript, equalTo("document.execCommand('undo')"))
        assertTrue(
            "the release is taken too, so nothing undoes a second time",
            webView.dispatchKeyEventPreIme(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)),
        )
    }

    @Test
    fun `Ctrl+Shift+Z and Ctrl+Y redo in the page`() {
        val webView = RichTextWebView(context)

        webView.dispatchKeyEventPreIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
        assertThat(shadowOf(webView).lastEvaluatedJavascript, equalTo("document.execCommand('redo')"))

        webView.evaluateJavascript("marker()", null)
        webView.dispatchKeyEventPreIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON))
        assertThat(shadowOf(webView).lastEvaluatedJavascript, equalTo("document.execCommand('redo')"))
    }

    @Test
    fun `other keys still reach the keyboard app`() {
        val webView = RichTextWebView(context)
        webView.evaluateJavascript("marker()", null)

        assertFalse(webView.dispatchKeyEventPreIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0)))
        assertThat(shadowOf(webView).lastEvaluatedJavascript, equalTo("marker()"))
    }

    private fun key(
        action: Int,
        code: Int,
        meta: Int,
    ) = KeyEvent(0L, 0L, action, code, 0, meta)

    /**
     * A page ten times taller than the scrolling view it sits in, as in the note
     * editor. It has to be on screen: a detached view never runs its animation.
     */
    private fun scrollingPage(pageHeight: Int = 1000): Pair<ScrollView, RichTextWebView> {
        val webView = RichTextWebView(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(webView, LinearLayout.LayoutParams(200, pageHeight))
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

    companion object {
        /** The test runs at mdpi, so a dp is a pixel. */
        private const val MAX_SPEED_PX_PER_SECOND = 400f
    }
}
