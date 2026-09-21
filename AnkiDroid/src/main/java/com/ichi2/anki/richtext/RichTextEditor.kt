// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.richtext

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.ColorInt
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Drives the contenteditable page behind the note editor's rich text mode.
 *
 * The [com.ichi2.anki.FieldEditText] list stays the single source of truth: this
 * class pushes the field HTML into the page when the mode is entered, and pushes
 * every edit straight back out through [onFieldChanged]. Saving, duplicate
 * checking and the multimedia buttons therefore keep working untouched.
 */
class RichTextEditor(
    private val webView: WebView,
    private val onFieldChanged: (index: Int, html: String) -> Unit,
    private val onHeightChanged: (heightPx: Int) -> Unit,
    private val onFormatStateChanged: (commands: Set<String>) -> Unit,
    private val onCaretMoved: (topPx: Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var loaded = false

    /** Calls made before the page finished loading, replayed in order once it has. */
    private val pending = mutableListOf<() -> Unit>()

    @SuppressLint("SetJavaScriptEnabled")
    fun load(mediaDir: File?) {
        webView.settings.apply {
            javaScriptEnabled = true
            // Field HTML may reference collection media by bare filename.
            allowFileAccess = true
            domStorageEnabled = false
        }
        webView.isVerticalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.addJavascriptInterface(Bridge(), "AnkiRich")
        webView.webViewClient =
            object : android.webkit.WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    loaded = true
                    pending.forEach { it() }
                    pending.clear()
                }
            }

        val html = webView.context.assets.open(ASSET).bufferedReader().use { it.readText() }
        // A base URL inside the media folder is what makes <img src="foo.jpg"> resolve.
        val baseUrl = mediaDir?.let { "file://${it.absolutePath}/" }
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
    }

    fun setTheme(
        @ColorInt background: Int,
        @ColorInt foreground: Int,
        @ColorInt label: Int,
        @ColorInt border: Int,
        @ColorInt accent: Int,
    ) = whenLoaded {
        call(
            "setTheme",
            css(background),
            css(foreground),
            css(label),
            css(border),
            css(accent),
        )
    }

    /** Replaces the page contents with [names] labelled fields holding [values]. */
    fun setFields(
        names: List<String>,
        values: List<String>,
    ) = whenLoaded {
        val fields = JSONArray()
        names.forEachIndexed { index, name ->
            fields.put(
                JSONObject()
                    .put("name", name)
                    .put("html", values.getOrElse(index) { "" }),
            )
        }
        webView.evaluateJavascript("setFields($fields)", null)
    }

    /** Applies a `document.execCommand` to the current selection. */
    fun exec(
        command: String,
        value: String? = null,
    ) = whenLoaded { call("exec", command, value) }

    fun focusField(index: Int) = whenLoaded { webView.evaluateJavascript("focusField($index)", null) }

    /** Pushes every field back out, for the moment before a save or a mode switch. */
    fun flush() {
        if (loaded) webView.evaluateJavascript("flushAll()", null)
    }

    private fun whenLoaded(block: () -> Unit) {
        if (loaded) block() else pending.add(block)
    }

    private fun call(
        function: String,
        vararg args: String?,
    ) {
        val quoted = args.joinToString(",") { if (it == null) "null" else JSONObject.quote(it) }
        webView.evaluateJavascript("$function($quoted)", null)
    }

    private fun css(
        @ColorInt color: Int,
    ) = String.format("#%06X", 0xFFFFFF and color)

    private inner class Bridge {
        @JavascriptInterface
        fun onFieldChanged(payload: String) {
            // "<index>:<html>" - the HTML itself may contain colons.
            val separator = payload.indexOf(':')
            if (separator < 0) return
            val index = payload.substring(0, separator).toIntOrNull() ?: return
            val html = payload.substring(separator + 1)
            handler.post { this@RichTextEditor.onFieldChanged(index, html) }
        }

        @JavascriptInterface
        fun onHeight(height: Int) {
            handler.post { this@RichTextEditor.onHeightChanged(toDevicePixels(height)) }
        }

        @JavascriptInterface
        fun onFormatState(commands: String) {
            val set = commands.split(',').filter { it.isNotEmpty() }.toSet()
            handler.post { this@RichTextEditor.onFormatStateChanged(set) }
        }

        @JavascriptInterface
        fun onCaret(top: Int) {
            handler.post { this@RichTextEditor.onCaretMoved(toDevicePixels(top)) }
        }

        /** The page is laid out at `initial-scale=1`, so a CSS pixel is a dp. */
        private fun toDevicePixels(cssPixels: Int) =
            (cssPixels * webView.resources.displayMetrics.density).toInt()
    }

    fun destroy() {
        try {
            webView.removeJavascriptInterface("AnkiRich")
            webView.destroy()
        } catch (e: Exception) {
            Timber.w(e, "failed to destroy the rich text editor")
        }
    }

    companion object {
        private const val ASSET = "rich_text_editor.html"

        /** The highlighter colour, matching the marker pen on the toolbar button. */
        const val HIGHLIGHT_COLOR = "#FFF59D"
    }
}
