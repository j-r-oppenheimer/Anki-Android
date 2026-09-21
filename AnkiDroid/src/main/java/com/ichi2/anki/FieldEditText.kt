// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2015 Timothy Rae <perceptualchaos2@gmail.com>

package com.ichi2.anki

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.LocaleList
import android.os.Parcelable
import android.text.InputType
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.toColorInt
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.utils.annotation.KotlinCleanup
import com.ichi2.anki.servicelayer.NoteService
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.ui.FixedEditText
import com.ichi2.utils.ClipboardUtil.getDescription
import com.ichi2.utils.ClipboardUtil.getPlainText
import com.ichi2.utils.ClipboardUtil.getUri
import com.ichi2.utils.ClipboardUtil.hasMedia
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class FieldEditText :
    FixedEditText,
    NoteService.NoteField {
    override var ord = 0
    private var origBackground: Drawable? = null
    private var selectionChangeListener: TextSelectionListener? = null
    private var pasteListener: PasteListener? = null

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    var clipboard: ClipboardManager? = null

    constructor(context: Context?) : super(context!!)
    constructor(context: Context?, attr: AttributeSet?) : super(context!!, attr)
    constructor(context: Context?, attrs: AttributeSet?, defStyle: Int) : super(context!!, attrs, defStyle)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (shouldDisableExtendedTextUi()) {
            Timber.i("Disabling Extended Text UI")
            this.imeOptions = this.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
    }

    private fun shouldDisableExtendedTextUi(): Boolean = this.context.sharedPrefs().getBoolean("disableExtendedTextUi", false)

    @KotlinCleanup("Simplify")
    override val fieldText: String?
        get() {
            val text = text ?: return null
            return text.toString()
        }

    fun init() {
        try {
            clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        } catch (e: Exception) {
            Timber.w(e)
        }
        minimumWidth = 400
        origBackground = background
        // Fixes bug where new instances of this object have wrong colors, probably
        // from some reuse mechanic in Android.
        setDefaultStyle()

        val highlightColor =
            MaterialColors.getColor(
                context,
                R.attr.editTextHighlightColor,
                "#99CCFF".toColorInt(), // light blue color for fallback just-in-case
            )
        setHighlightColor(highlightColor)
    }

    fun setPasteListener(pasteListener: PasteListener) {
        this.pasteListener = pasteListener
    }

    override fun onSelectionChanged(
        selStart: Int,
        selEnd: Int,
    ) {
        if (selectionChangeListener != null) {
            try {
                selectionChangeListener!!.onSelectionChanged(selStart, selEnd)
            } catch (e: Exception) {
                Timber.w(e, "mSelectionChangeListener")
            }
        }
        super.onSelectionChanged(selStart, selEnd)
    }

    fun setHintLocale(locale: Locale) {
        Timber.d("Setting hint locale to '%s'", locale)
        imeHintLocales = LocaleList(locale)
    }

    /**
     * Modify the style of this view to represent a duplicate field.
     */
    fun setDupeStyle() {
        setBackgroundColor(MaterialColors.getColor(context, R.attr.duplicateColor, 0))
    }

    /**
     * Restore the default style of this view.
     */
    fun setDefaultStyle() {
        background = origBackground
    }

    fun setContent(
        content: String?,
        replaceNewLine: Boolean,
    ) {
        val text =
            if (content == null) {
                ""
            } else if (replaceNewLine) {
                content.replace("<br(\\s*/*)>".toRegex(), NEW_LINE)
            } else {
                content
            }
        setText(text)
    }

    override fun onSaveInstanceState(): Parcelable {
        val state = super.onSaveInstanceState()
        return SavedState(state, ord)
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        // The current function is called both by Ctrl+V and pasting from the context menu
        // It does not deal with drag and drop
        if (id == android.R.id.paste) {
            // 앱에 따라 이미지 URI 가 첫 항목이 아니거나, 클립보드 설명의 MIME 타입이
            // 미디어로 표시되지 않습니다. 그러면 원본은 아무 일도 안 하거나 텍스트만
            // 붙여넣습니다. 항목을 전부 훑어 실제 타입을 확인한 뒤 판단합니다.
            firstMediaItem()?.let { (uri, description) -> return onPaste(uri, description) }
            if (hasMedia(clipboard)) {
                return onPaste(getUri(clipboard), getDescription(clipboard))
            }
            return pastePlainText()
        }
        return super.onTextContextMenuItem(id)
    }

    /**
     * 클립보드에서 붙여넣을 미디어와 그에 맞는 설명을 찾습니다.
     *
     * 원본은 첫 항목만 보고, 그것도 클립보드 설명에 적힌 MIME 타입만 믿습니다.
     * 이미지를 뒤쪽 항목에 넣거나 타입을 알려주지 않는 앱에서는 붙여넣기가
     * 아무 일도 하지 않습니다.
     *
     * 항목을 전부 훑어 실제 타입을 물어보고, 타입을 끝내 못 알아내더라도 URI 가
     * 있으면 이미지로 보고 시도합니다. 가리기 붙여넣기에서 이 폴백을 넣었더니
     * 동작했으므로, 타입을 알려주지 않는 게 원인입니다.
     */
    private fun firstMediaItem(): Pair<Uri, ClipDescription>? {
        val clip = clipboard?.primaryClip ?: return null
        val resolver = context?.contentResolver ?: return null
        val label = clip.description?.label ?: ""
        var fallback: Uri? = null

        for (index in 0 until clip.itemCount) {
            val uri = clip.getItemAt(index).uri ?: continue
            if (fallback == null) {
                fallback = uri
            }
            val type =
                try {
                    resolver.getType(uri)
                } catch (e: Exception) {
                    Timber.w(e, "could not read the type of a clipboard item")
                    null
                } ?: continue
            if (type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/")) {
                return uri to ClipDescription(label, arrayOf(type))
            }
        }

        // 타입은 못 알아냈지만 URI 는 있는 경우. 이미지로 보고 시도합니다.
        return fallback?.let { it to ClipDescription(label, arrayOf("image/*")) }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun pastePlainText(): Boolean {
        getPlainText(clipboard, context)?.let { pasted ->
            val start = min(selectionStart, selectionEnd)
            val end = max(selectionStart, selectionEnd)
            setText(
                text!!.substring(0, start) + pasted + text!!.substring(end),
            )
            setSelection(start + pasted.length)
            return true
        }
        return false
    }

    private fun onPaste(
        mediaUri: Uri?,
        description: ClipDescription?,
    ): Boolean =
        if (mediaUri == null) {
            false
        } else {
            try {
                pasteListener!!.onPaste(this, mediaUri, description)
            } catch (e: Exception) {
                Timber.w(e, "Failed to paste media")
                showSnackbar(context.getString(R.string.multimedia_editor_something_wrong))
                false
            }
        }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        ord = state.ord
    }

    fun setCapitalize(value: Boolean) {
        val inputType = this.inputType
        this.inputType =
            if (value) {
                inputType or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES.inv()
            }
    }

    val isCapitalized: Boolean
        get() = this.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES == InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    @Parcelize
    internal class SavedState(
        val state: Parcelable?,
        val ord: Int,
    ) : BaseSavedState(state)

    interface TextSelectionListener {
        fun onSelectionChanged(
            selStart: Int,
            selEnd: Int,
        )
    }

    fun interface PasteListener {
        fun onPaste(
            editText: EditText,
            uri: Uri?,
            description: ClipDescription?,
        ): Boolean
    }

    companion object {
        val NEW_LINE: String = System.getProperty("line.separator")!!
    }
}
