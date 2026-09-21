// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.richtext

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.ColorInt
import com.ichi2.anki.customfont.CustomFont
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.util.Locale

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
    private val onContextMenu: (xPx: Int, yPx: Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var loaded = false

    /** Calls made before the page finished loading, replayed in order once it has. */
    private val pending = mutableListOf<() -> Unit>()
    private var mediaDir: File? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun load(mediaDir: File?) {
        this.mediaDir = mediaDir
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
        }
        webView.isVerticalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.addJavascriptInterface(Bridge(), "AnkiRich")
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ): WebResourceResponse? = CustomFont.interceptPageFontRequest(request) ?: serveMedia(request)

                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    loaded = true
                    CustomFont.injectIntoPage(webView)
                    pending.forEach { it() }
                    pending.clear()
                }
            }

        val html = webView.context.assets.open(ASSET).bufferedReader().use { it.readText() }
        // The page needs a real origin, not file://, or the custom font is refused
        // as a cross-origin request. Bare <img src="foo.jpg"> then resolves under
        // BASE_URL and comes back through serveMedia.
        webView.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
    }

    /** Serves a file from the collection's media folder to the page. */
    private fun serveMedia(request: WebResourceRequest): WebResourceResponse? {
        val dir = mediaDir ?: return null
        val path = request.url.path ?: return null
        if (!path.startsWith(MEDIA_PATH)) return null
        return try {
            val file = File(dir, Uri.decode(path.removePrefix(MEDIA_PATH)))
            // A field could name "../secret"; keep the lookup inside the media folder.
            if (!file.canonicalPath.startsWith(dir.canonicalPath) || !file.isFile) return null
            val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            WebResourceResponse(mime, null, FileInputStream(file))
        } catch (e: Exception) {
            Timber.w(e, "failed to serve media to the rich text editor")
            null
        }
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

    /**
     * Highlights the selection. With [toggle] the highlight comes off again when the
     * selection already has one, which is what the toolbar button does; picking a
     * colour from the popup passes false so it recolours instead.
     */
    fun highlight(
        @ColorInt color: Int,
        toggle: Boolean,
    ) = whenLoaded { paint("highlight", color, toggle) }

    /** Colours the selection's text. [toggle] behaves as it does for [highlight]. */
    fun textColour(
        @ColorInt color: Int,
        toggle: Boolean,
    ) = whenLoaded { paint("textColor", color, toggle) }

    /**
     * Runs a list button. Each press steps out one level of nesting, and the last
     * one drops the list; off a list it starts one.
     */
    fun list(
        command: String,
        tag: String,
    ) = whenLoaded { call("listAction", command, tag) }

    private fun paint(
        function: String,
        @ColorInt color: Int,
        toggle: Boolean,
    ) = webView.evaluateJavascript("$function(${JSONObject.quote(rgba(color))}, $toggle)", null)

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
    ) = String.format(Locale.ROOT, "#%06X", 0xFFFFFF and color)

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
        fun onContextMenu(payload: String) {
            val parts = payload.split(':')
            if (parts.size != 2) return
            val x = parts[0].toIntOrNull() ?: return
            val y = parts[1].toIntOrNull() ?: return
            handler.post { this@RichTextEditor.onContextMenu(toDevicePixels(x), toDevicePixels(y)) }
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
        private const val MEDIA_PATH = "/media/"
        private const val BASE_URL = "https://appassets.androidplatform.net$MEDIA_PATH"

        /**
         * The highlighter's starting colours. They are translucent so the highlight
         * blends with whatever background the card is rendered on, rather than
         * sitting on the page as a solid block.
         */
        val DEFAULT_HIGHLIGHTS =
            listOf(
                0x66FFF59D, // yellow
                0x66A5D6A7, // green
                0x6690CAF9, // blue
                0x66F48FB1, // pink
                0x66CE93D8, // purple
            )

        /**
         * The text colours to start from. Unlike the highlights these are opaque,
         * and mid-toned so they stay legible whether the card is light or dark.
         */
        val DEFAULT_TEXT_COLOURS =
            listOf(
                0xFFE53935.toInt(), // red
                0xFFFB8C00.toInt(), // orange
                0xFF43A047.toInt(), // green
                0xFF1E88E5.toInt(), // blue
                0xFF8E24AA.toInt(), // purple
            )

        /** CSS needs the alpha as a 0..1 fraction, which no hex form gives us. */
        fun rgba(
            @ColorInt color: Int,
        ): String =
            String.format(
                Locale.ROOT,
                "rgba(%d, %d, %d, %.3f)",
                Color.red(color),
                Color.green(color),
                Color.blue(color),
                Color.alpha(color) / 255f,
            )
    }
}
