// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.richtext

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

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
 */
class RichTextWebView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : WebView(context, attrs) {
        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_SCROLL) return false
            return super.onGenericMotionEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return super.onTouchEvent(event)
        }
    }
