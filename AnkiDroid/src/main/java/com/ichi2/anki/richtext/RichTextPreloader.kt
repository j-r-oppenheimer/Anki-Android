// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.richtext

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting

/**
 * Keeps one rich text page loaded ahead of time.
 *
 * A WebView shows nothing until its engine has started and the page has loaded,
 * which the HTML editor's plain fields never wait for. With a page loaded while the
 * app is idle, the next note editor draws its fields as soon as it opens.
 *
 * The page is made with the application's context, wrapped so that the note editor
 * taking it can hand it its own activity, which popups and text selection need.
 */
object RichTextPreloader {
    private var waiting: RichTextEditor? = null

    @VisibleForTesting
    val waitingPage: WebView?
        get() = waiting?.webView

    /** Starts loading a page for the next note editor, unless one is already waiting. */
    @MainThread
    fun warm(context: Context) {
        if (waiting?.belongsTo(context) == true) return
        discard()
        val webView = RichTextWebView(MutableContextWrapper(context.applicationContext))
        waiting = RichTextEditor(webView).also { it.load() }
    }

    /** The waiting page, now belonging to [activity], or null when there is none. */
    @MainThread
    fun take(activity: Activity): RichTextEditor? {
        val editor = waiting ?: return null
        waiting = null
        // A page from an earlier run of the app, or of a test, is of no use now.
        if (!editor.belongsTo(activity)) {
            editor.destroy()
            return null
        }
        (editor.webView.context as? MutableContextWrapper)?.baseContext = activity
        return editor
    }

    @MainThread
    fun discard() {
        waiting?.destroy()
        waiting = null
    }

    private fun RichTextEditor.belongsTo(context: Context) = webView.context.applicationContext === context.applicationContext
}
