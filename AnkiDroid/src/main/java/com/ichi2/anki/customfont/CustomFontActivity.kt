// SPDX-License-Identifier: GPL-3.0-or-later
//
// 저장할 위치:
// AnkiDroid/src/main/java/com/ichi2/anki/customfont/CustomFontActivity.kt
//
// 설정 -> 화면 -> 커스텀 폰트 에서 열리는 화면.
// 레이아웃 XML 없이 코드로 만듭니다. 새 레이아웃 파일을 넣으면 릴리즈 lint 의
// HardcodedText/MergeRootFrame 같은 fatal 규칙을 하나씩 더 신경써야 해서입니다.
// 적용 토글 2개는 설정 화면(preferences_appearance.xml)에 있습니다.

package com.ichi2.anki.customfont

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.showThemedToast

class CustomFontActivity : AnkiActivity() {
    private lateinit var nameView: TextView
    private lateinit var sampleView: TextView
    private lateinit var removeButton: Button

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                onFontPicked(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        refresh()
    }

    private fun buildContent(): View {
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(40))
            }

        column.addView(
            TextView(this).apply {
                text = getString(R.string.custom_font_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.DEFAULT_BOLD
            },
        )

        column.addView(
            TextView(this).apply {
                text = getString(R.string.custom_font_note)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                alpha = 0.75f
                setLineSpacing(dp(4).toFloat(), 1f)
            },
            marginTop(dp(10)),
        )

        nameView =
            TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
        column.addView(nameView, marginTop(dp(28)))

        column.addView(
            filledButton(getString(R.string.custom_font_choose)) {
                picker.launch(arrayOf("*/*"))
            },
            marginTop(dp(12)),
        )

        sampleView =
            TextView(this).apply {
                text = getString(R.string.custom_font_sample)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setLineSpacing(dp(6).toFloat(), 1f)
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(Color.argb(26, 128, 128, 128))
                    }
            }
        column.addView(sampleView, marginTop(dp(24)))

        removeButton =
            outlinedButton(getString(R.string.custom_font_remove)) {
                CustomFont.remove(this)
                refresh()
            }
        column.addView(removeButton, marginTop(dp(24)))

        return ScrollView(this).apply { addView(column) }
    }

    private fun onFontPicked(uri: Uri) {
        val name = fileNameOf(uri) ?: getString(R.string.custom_font_unnamed)
        if (CustomFont.install(this, uri, name)) {
            refresh()
        } else {
            showThemedToast(this, R.string.custom_font_error, false)
        }
    }

    private fun refresh() {
        val name = CustomFont.displayName(this)
        nameView.text = name ?: getString(R.string.custom_font_none)

        val typeface = CustomFont.typeface(this)
        sampleView.typeface = typeface ?: Typeface.DEFAULT
        sampleView.visibility = if (typeface == null) View.GONE else View.VISIBLE
        removeButton.isEnabled = name != null
    }

    private fun fileNameOf(uri: Uri): String? =
        contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }

    // ------------------------------------------------------------ 작은 도우미

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun marginTop(top: Int): LinearLayout.LayoutParams =
        LinearLayout
            .LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = top }

    private fun accentColor(): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(android.R.attr.colorAccent, value, true)) {
            return Color.parseColor("#3F7FD0")
        }
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    private fun filledButton(
        label: String,
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(52)
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(accentColor())
                }
            setOnClickListener { onClick() }
        }

    private fun outlinedButton(
        label: String,
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(52)
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.argb(80, 128, 128, 128))
                    setColor(Color.TRANSPARENT)
                }
            setOnClickListener { onClick() }
        }
}
