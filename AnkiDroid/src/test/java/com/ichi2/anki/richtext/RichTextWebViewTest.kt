// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.richtext

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    private fun press(
        toolType: Int,
        source: Int,
    ): MotionEvent {
        val properties =
            PointerProperties().apply {
                id = 0
                this.toolType = toolType
            }
        val coords =
            PointerCoords().apply {
                x = 10f
                y = 10f
            }
        return MotionEvent.obtain(
            0L,
            0L,
            MotionEvent.ACTION_DOWN,
            1,
            arrayOf(properties),
            arrayOf(coords),
            0,
            if (toolType == MotionEvent.TOOL_TYPE_MOUSE) MotionEvent.BUTTON_PRIMARY else 0,
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
