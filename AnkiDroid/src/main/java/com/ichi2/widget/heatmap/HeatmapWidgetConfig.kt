// SPDX-License-Identifier: GPL-3.0-or-later
//
// 저장할 위치:
// AnkiDroid/src/main/java/com/ichi2/widget/heatmap/HeatmapWidgetConfig.kt
//
// 위쪽은 실제 앱바(MaterialToolbar), 아래는 WebView 입니다.
// 화면에 나오는 글자와 색을 HTML에 박아두지 않고 여기서 넘깁니다.
//
//  - 글자: 안드로이드 문자열 리소스에서 읽으므로 AnkiDroid 언어 설정을 따릅니다.
//          기본이 영어이고, 한국어 설정일 때만 values-ko/ 값이 쓰입니다.
//  - 색  : 현재 적용된 테마에서 읽으므로 밝은 테마를 쓰면 이 화면도 밝습니다.

package com.ichi2.widget.heatmap

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.ichi2.anki.R
import com.ichi2.themes.Themes
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/** 설정 화면 HTML이 쓰는 문자열. 왼쪽이 JS 키, 오른쪽이 안드로이드 리소스입니다. */
private val UI_STRINGS =
    listOf(
        "axis_color" to R.string.heatmap_ui_axis_color,
        "axis_dark" to R.string.heatmap_ui_axis_dark,
        "axis_light" to R.string.heatmap_ui_axis_light,
        "cancel" to R.string.heatmap_ui_cancel,
        "cell_size" to R.string.heatmap_ui_cell_size,
        "corner" to R.string.heatmap_ui_corner,
        "count_n" to R.string.heatmap_ui_count_n,
        "empty_opacity" to R.string.heatmap_ui_empty_opacity,
        "hex_label" to R.string.heatmap_ui_hex_label,
        "mode_dark" to R.string.heatmap_ui_mode_dark,
        "mode_light" to R.string.heatmap_ui_mode_light,
        "opacity" to R.string.heatmap_ui_opacity,
        "padding" to R.string.heatmap_ui_padding,
        "peak_opacity" to R.string.heatmap_ui_peak_opacity,
        "save" to R.string.heatmap_ui_save,
        "section_background" to R.string.heatmap_ui_section_background,
        "section_color" to R.string.heatmap_ui_section_color,
        "section_curve" to R.string.heatmap_ui_section_curve,
        "section_shape" to R.string.heatmap_ui_section_shape,
        "subtitle" to R.string.heatmap_ui_subtitle,
        "target_base" to R.string.heatmap_ui_target_base,
        "target_empty" to R.string.heatmap_ui_target_empty,
        "title" to R.string.heatmap_ui_title,
    )

class HeatmapWidgetConfig : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        // AnkiActivity 가 하는 것과 같은 순서입니다. 사용자가 고른 테마는 런타임에
        // 적용되므로, super.onCreate 보다 먼저 불러야 앱바와 배경까지 반영됩니다.
        // 이게 없으면 매니페스트의 기본 테마가 그대로 쓰여서, 설정 화면만
        // 앱과 다른 색으로 뜨고 WebView 에 넘기는 색도 전부 어긋납니다.
        Themes.setTheme(this, savedInstanceState)
        super.onCreate(savedInstanceState)

        // 사용자가 뒤로 가면 위젯 배치가 취소되도록 기본값을 먼저 설정합니다.
        setResult(RESULT_CANCELED)

        appWidgetId =
            intent?.extras?.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_heatmap_widget_config)

        val toolbar = findViewById<MaterialToolbar>(R.id.heatmap_config_toolbar)
        toolbar.title = getString(R.string.heatmap_ui_title)
        toolbar.setNavigationOnClickListener { finish() }

        webView = findViewById(R.id.heatmap_config_webview)
        webView.setBackgroundColor(themeColor(android.R.attr.colorBackground))
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(Bridge(), "AnkiHeatmap")
        webView.loadUrl("file:///android_asset/heatmap_picker.html")

        applySystemBarInsets(toolbar)
    }

    /**
     * 안드로이드 15 부터는 창이 시스템 바 아래까지 그려집니다. 그대로 두면 앱바가
     * 상태바에 깔립니다. 앱바 배경이 상태바 뒤까지 이어지도록 루트가 아니라 앱바
     * 자체에 위쪽 여백을 줍니다. 낮은 버전에서는 인셋이 0 이라 아무 일도 안 합니다.
     */
    private fun applySystemBarInsets(toolbar: MaterialToolbar) {
        val root = findViewById<View>(R.id.heatmap_config_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            root.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            toolbar.updatePadding(top = bars.top)
            insets
        }
    }

    /** 테마 속성 하나를 색으로 읽습니다. */
    private fun themeColor(
        @AttrRes attr: Int,
    ): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) getColor(value.resourceId) else value.data
        } else {
            Color.GRAY
        }
    }

    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    /** 현재 테마의 색을 HTML이 쓸 수 있는 형태로 모읍니다. */
    private fun themeJson(): JSONObject {
        val background = themeColor(android.R.attr.colorBackground)
        val text = themeColor(android.R.attr.textColor)
        val dark = ColorUtils.calculateLuminance(background) < 0.5

        // 카드/구분선은 배경과 글자색을 섞어서 만듭니다.
        // 테마마다 전용 속성이 다 있지는 않아서, 이렇게 하면 어떤 테마에서도 자연스럽습니다.
        fun mix(ratio: Float) = ColorUtils.blendARGB(background, text, ratio)

        // colorAccent 는 AppCompat 이 선언한 속성입니다. AnkiDroid 는 non-transitive R 을
        // 쓰기 때문에 앱 모듈의 R.attr 에는 없고, 선언한 라이브러리의 R 을 거쳐야 합니다.
        val accent = themeColor(androidx.appcompat.R.attr.colorAccent)
        val onAccent = if (ColorUtils.calculateLuminance(accent) < 0.5) Color.WHITE else Color.BLACK

        return JSONObject()
            .put("dark", dark)
            .put("bg", hex(background))
            .put("surface", hex(mix(0.05f)))
            .put("surface2", hex(mix(0.10f)))
            .put("line", hex(mix(0.18f)))
            .put("text", hex(text))
            .put("muted", hex(themeColor(android.R.attr.textColorSecondary)))
            .put("accent", hex(accent))
            .put("onAccent", hex(onAccent))
            // 위젯 미리보기 뒤에 깔리는 배경. 배경화면을 흉내 낸 것이라
            // 테마 색에서 살짝 벗어난 톤으로 잡습니다.
            .put("stage1", hex(ColorUtils.blendARGB(background, if (dark) Color.WHITE else Color.BLACK, 0.22f)))
            .put("stage2", hex(ColorUtils.blendARGB(background, if (dark) Color.WHITE else Color.BLACK, 0.13f)))
            .put("stage3", hex(ColorUtils.blendARGB(background, if (dark) Color.WHITE else Color.BLACK, 0.06f)))
    }

    private fun stringsJson(): JSONObject {
        val json = JSONObject()
        UI_STRINGS.forEach { (key, resId) -> json.put(key, getString(resId)) }
        return json
    }

    /** 학습 기록을 읽어 설정 화면에 넘깁니다. 실패해도 화면은 기본값으로 동작합니다. */
    private fun loadStats() {
        lifecycleScope.launch {
            val json =
                try {
                    historyToJson(loadHistory())
                } catch (e: Exception) {
                    Timber.w(e, "could not read review history for the heatmap config screen")
                    return@launch
                }
            webView.evaluateJavascript(
                "window.ankiHeatmapStats && window.ankiHeatmapStats($json)",
                null,
            )
        }
    }

    private inner class Bridge {
        /** 설정 화면이 시작할 때 설정값, 문자열, 테마 색을 한 번에 읽어갑니다. */
        @JavascriptInterface
        fun load(): String {
            val json = JSONObject(HeatmapPrefs.toJson(this@HeatmapWidgetConfig, appWidgetId))
            return try {
                json
                    .put("strings", stringsJson())
                    .put("theme", themeJson())
                    .toString()
            } catch (e: Exception) {
                Timber.w(e, "could not attach strings or theme to the heatmap config payload")
                json.toString()
            }
        }

        /** 페이지의 스크립트가 준비되면 학습 기록을 요청합니다. */
        @JavascriptInterface
        fun requestStats() {
            runOnUiThread { loadStats() }
        }

        /** 취소 버튼. */
        @JavascriptInterface
        fun cancel() {
            runOnUiThread { finish() }
        }

        /** 저장 버튼을 누르면 호출됩니다. JS 스레드에서 오므로 UI 작업은 넘겨줍니다. */
        @JavascriptInterface
        fun save(json: String) {
            try {
                HeatmapPrefs.fromJson(this@HeatmapWidgetConfig, appWidgetId, JSONObject(json))
            } catch (e: Exception) {
                Timber.w(e, "failed to save heatmap widget settings")
            }

            runOnUiThread {
                val manager = AppWidgetManager.getInstance(this@HeatmapWidgetConfig)
                val provider = ComponentName(this@HeatmapWidgetConfig, HeatmapWidget::class.java)
                val updateIntent =
                    Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                        component = provider
                        putExtra(
                            AppWidgetManager.EXTRA_APPWIDGET_IDS,
                            manager.getAppWidgetIds(provider),
                        )
                    }
                sendBroadcast(updateIntent)

                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                )
                finish()
            }
        }
    }
}
