// SPDX-License-Identifier: GPL-3.0-or-later
//
// 저장할 위치:
// AnkiDroid/src/main/java/com/ichi2/anki/customfont/CustomFont.kt
//
// 사용자가 고른 폰트 파일 하나를 앱 전체에 적용합니다.
//
//  1. 파일은 앱 전용 저장소(files/customfont/font.ttf)로 복사합니다.
//     컬렉션의 media 폴더를 건드리지 않으므로 AnkiWeb 동기화 용량이 늘지 않습니다.
//  2. 앱 화면: AnkiActivity.onStart 에서 화면의 TextView 들에 Typeface 를 씌웁니다.
//  3. 카드: ReviewerCustomFonts 가 만드는 CSS 에 @font-face 를 끼워 넣고,
//     폰트 파일 자체는 AbstractFlashcardViewer 의 shouldInterceptRequest 에서
//     돌려줍니다. 카드 페이지는 http://127.0.0.1:PORT/ 를 기준 주소로 쓰기 때문에
//     상대 주소를 쓰면 동일 출처가 되어 CORS 에 막히지 않습니다.

package com.ichi2.anki.customfont

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.LayoutInflaterCompat
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.themes.Themes
import com.ichi2.utils.openInputStreamSafe
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.util.WeakHashMap

object CustomFont {
    /** 카드가 폰트를 받아가는 주소. 상대 주소라 기준 주소와 같은 출처가 됩니다. */
    const val REQUEST_PATH = "/__ankidroid_user_font"

    private const val KEY_APPLY_APP = "customFontApplyApp"
    private const val KEY_APPLY_CARDS = "customFontApplyCards"
    private const val KEY_NAME = "customFontName"
    private const val RESCAN_INTERVAL_MS = 150L
    private const val KEY_CARD_BACKGROUND = "cardThemeBackground"
    private const val FAMILY = "AnkiDroidUserFont"

    private var cached: Typeface? = null

    /** 이미 감시 중인 화면. 화면이 사라지면 항목도 같이 사라집니다. */
    private val watched = WeakHashMap<View, Boolean>()
    private var cachedStamp: String? = null

    fun fontFile(context: Context): File = File(File(context.filesDir, "customfont"), "font.ttf")

    /** 설정 화면에 보여줄 파일 이름. 파일이 없으면 null. */
    fun displayName(context: Context): String? {
        if (!fontFile(context).exists()) return null
        return context.sharedPrefs().getString(KEY_NAME, null)
    }

    fun typeface(context: Context): Typeface? {
        val file = fontFile(context)
        if (!file.exists()) {
            cached = null
            cachedStamp = null
            return null
        }
        val stamp = "${file.length()}:${file.lastModified()}"
        val hit = cached
        if (hit != null && cachedStamp == stamp) return hit
        return try {
            Typeface.createFromFile(file).also {
                cached = it
                cachedStamp = stamp
            }
        } catch (e: Exception) {
            Timber.w(e, "the selected font file could not be loaded")
            null
        }
    }

    /** 고른 파일을 앱 전용 저장소로 복사합니다. */
    fun install(
        context: Context,
        uri: Uri,
        name: String,
    ): Boolean {
        val target = fontFile(context)
        return try {
            target.parentFile?.mkdirs()
            val copied =
                context.contentResolver.openInputStreamSafe(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
            if (!copied) return false

            // 실제로 읽히는 폰트인지 확인합니다. 못 읽으면 예외가 나서 아래 catch로 갑니다.
            cached = Typeface.createFromFile(target)
            cachedStamp = "${target.length()}:${target.lastModified()}"
            context
                .sharedPrefs()
                .edit()
                .putString(KEY_NAME, name)
                .apply()
            true
        } catch (e: Exception) {
            Timber.w(e, "could not install the selected font")
            target.delete()
            cached = null
            cachedStamp = null
            false
        }
    }

    fun remove(context: Context) {
        fontFile(context).delete()
        cached = null
        cachedStamp = null
        context
            .sharedPrefs()
            .edit()
            .remove(KEY_NAME)
            .apply()
    }

    private fun appliesToApp(context: Context): Boolean = context.sharedPrefs().getBoolean(KEY_APPLY_APP, false)

    private fun appliesToCards(context: Context): Boolean = context.sharedPrefs().getBoolean(KEY_APPLY_CARDS, false)

    /** 애플리케이션이 아직 안 만들어졌을 수 있는 경로(테스트 등)에서 안전하게 씁니다. */
    private fun appContext(): Context? =
        try {
            AnkiDroidApp.instance
        } catch (e: Exception) {
            Timber.v(e, "no application instance yet")
            null
        }

    // -------------------------------------------------------------- 앱 화면

    /**
     * 뷰가 XML 에서 만들어지는 순간 폰트를 씌웁니다.
     *
     * 화면이 뜬 뒤에 훑는 방식은 덱 목록처럼 나중에 채워지는 화면에서 한 박자 늦습니다.
     * 여기서 잡으면 처음부터 적용된 상태로 그려집니다.
     * 다이얼로그도 액티비티의 인플레이터를 복제해서 쓰므로 같이 적용됩니다.
     *
     * 반드시 super.onCreate 보다 먼저 불러야 합니다. 인플레이터에 팩토리는 한 번만
     * 설정할 수 있어서, AppCompat 이 자기 것을 심은 뒤에는 넣을 수 없습니다.
     */
    fun installInflaterFactory(activity: AppCompatActivity) {
        if (!appliesToApp(activity)) return
        val typeface = typeface(activity) ?: return
        val delegate = activity.delegate
        try {
            LayoutInflaterCompat.setFactory2(
                activity.layoutInflater,
                object : LayoutInflater.Factory2 {
                    override fun onCreateView(
                        parent: View?,
                        name: String,
                        context: Context,
                        attrs: AttributeSet,
                    ): View? {
                        // AppCompat 이 Button 을 AppCompatButton 으로 바꿔치기하는 처리를
                        // 그대로 거치게 합니다. delegate 를 안 부르면 그게 통째로 사라집니다.
                        // null 이면 인플레이터가 평소대로 직접 만듭니다.
                        val view =
                            delegate.createView(parent, name, context, attrs)
                                ?: createFallbackView(activity.layoutInflater, name, context, attrs)
                        if (view is TextView) {
                            val style = view.typeface?.style ?: Typeface.NORMAL
                            view.typeface = Typeface.create(typeface, style)
                        }
                        return view
                    }

                    override fun onCreateView(
                        name: String,
                        context: Context,
                        attrs: AttributeSet,
                    ): View? = onCreateView(null, name, context, attrs)
                },
            )
        } catch (e: Exception) {
            // 이미 팩토리가 있으면 예외가 납니다. 그 경우 아래의 훑는 방식으로 갑니다.
            Timber.w(e, "could not install the custom font inflater factory")
        }
    }

    /**
     * AppCompat 이 만들지 않는 뷰(Material 위젯, 커스텀 뷰)는 delegate 가 null 을 돌려주고
     * 인플레이터가 직접 만듭니다. 그러면 폰트를 씌울 기회가 없어 한 박자 늦게 바뀝니다.
     * 안드로이드 10 부터는 컨텍스트를 지정해 직접 만들 수 있어서 그 틈을 메웁니다.
     *
     * android:theme 으로 감싼 컨텍스트를 그대로 넘기는 게 중요합니다. 그러지 않으면
     * 답변 버튼 색처럼 뷰별 테마에 기대는 것들이 깨집니다.
     * fragment 태그는 액티비티가 따로 처리하므로 손대지 않습니다.
     */
    private fun createFallbackView(
        inflater: LayoutInflater,
        name: String,
        context: Context,
        attrs: AttributeSet,
    ): View? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (name == "fragment") return null

        val prefixes =
            if (name.contains('.')) {
                arrayOf<String?>(null)
            } else {
                arrayOf<String?>("android.widget.", "android.view.", "android.webkit.")
            }
        for (prefix in prefixes) {
            try {
                return inflater.createView(context, name, prefix, attrs)
            } catch (_: ClassNotFoundException) {
                // 다음 접두어로 넘어갑니다
            } catch (e: Exception) {
                Timber.w(e, "could not create %s, letting the inflater do it", name)
                return null
            }
        }
        return null
    }

    fun applyToActivity(activity: Activity) {
        if (!appliesToApp(activity)) return
        val typeface = typeface(activity) ?: return
        val root = activity.window?.decorView ?: return

        applyRecursively(root, typeface)

        // 덱 목록처럼 컬렉션을 연 뒤에야 내용이 채워지는 화면이 있습니다. 화면이 뜬
        // 직후 몇 차례 나눠서 다시 훑습니다. 이미 적용된 뷰는 건너뛰므로 부담이 작고,
        // 아래 배치 감시가 놓치는 경우를 메웁니다.
        for (delay in longArrayOf(0L, 150L, 400L, 900L, 2000L)) {
            root.postDelayed({ applyRecursively(root, typeface) }, delay)
        }

        // 설정 화면처럼 스크롤하면서 행이 새로 만들어지는 화면은 몇 번 훑는 것만으로는
        // 놓칩니다. 배치가 바뀔 때마다 다시 훑되, 너무 자주 돌지 않게 간격을 둡니다.
        // 이미 적용된 뷰는 건너뛰므로 배치 -> 적용 -> 배치 로 도는 일은 없습니다.
        if (watched[root] == null) {
            watched[root] = true
            var last = 0L
            root.viewTreeObserver.addOnGlobalLayoutListener {
                val now = SystemClock.uptimeMillis()
                if (now - last >= RESCAN_INTERVAL_MS) {
                    last = now
                    applyRecursively(root, typeface)
                }
            }
        }
    }

    private fun applyRecursively(
        view: View,
        typeface: Typeface,
    ) {
        if (view is WebView) return
        if (view is TextView) {
            val style = view.typeface?.style ?: Typeface.NORMAL
            // Typeface.create 는 같은 조합이면 같은 인스턴스를 돌려줍니다.
            // 이미 그 인스턴스면 건드리지 않아야 배치가 다시 돌지 않습니다.
            val styled = Typeface.create(typeface, style)
            if (view.typeface !== styled) {
                view.typeface = styled
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyRecursively(view.getChildAt(index), typeface)
            }
        }
    }

    // -------------------------------------------------------------- 카드

    fun appendCardCss(css: StringBuilder) {
        val context = appContext() ?: return
        if (!appliesToCards(context)) return
        if (!fontFile(context).exists()) return

        css.append("@font-face{font-family:'")
        css.append(FAMILY)
        css.append("';src:url('")
        css.append(REQUEST_PATH)
        css.append("');font-display:swap;}\n")
        css.append("body,.card{font-family:'")
        css.append(FAMILY)
        css.append("',sans-serif !important;}\n")

        // 노트 타입이 하위 요소에 폰트를 지정한 경우 위 규칙만으로는 밀립니다.
        // 수식은 전용 폰트를 써야 하므로 MathJax 는 건드리지 않습니다.
        // :not() 안의 복합 선택자를 못 읽는 옛 WebView 에서는 이 줄만 무시됩니다.
        css.append(".card *:not(mjx-container):not(mjx-container *)")
        css.append(":not(.MathJax):not(.MathJax *){font-family:'")
        css.append(FAMILY)
        css.append("',sans-serif !important;}\n")
    }

    /**
     * 노트 타입 CSS 가 정한 배경/글자색을 앱 테마 색으로 덮어씁니다.
     *
     * 노트 타입의 CSS 는 card_template.html 의 <style> 블록보다 뒤(본문 안)에 들어가기
     * 때문에, !important 없이 그냥 얹으면 순서에서 밀려 무시됩니다.
     * color 는 body 에만 걸리므로 노트 안에서 색을 직접 지정한 글자는 그대로 남습니다.
     */
    fun appendCardThemeCss(css: StringBuilder) {
        val context = appContext() ?: return
        if (!context.sharedPrefs().getBoolean(KEY_CARD_BACKGROUND, false)) return
        try {
            // 앱 컨텍스트의 테마는 매니페스트 기본값이라 사용자가 고른 테마가 아닙니다.
            // 현재 테마의 스타일을 씌운 컨텍스트에서 색을 읽어야 합니다.
            val themed = ContextThemeWrapper(context, Themes.currentTheme.styleResId)
            val background =
                MaterialColors.getColor(themed, android.R.attr.colorBackground, Color.TRANSPARENT)
            val text =
                MaterialColors.getColor(themed, android.R.attr.textColor, Color.TRANSPARENT)
            if (background == Color.TRANSPARENT || text == Color.TRANSPARENT) return

            css.append("html,body,.card{background-color:")
            css.append(hexOf(background))
            css.append(" !important;color:")
            css.append(hexOf(text))
            css.append(" !important;}\n")
        } catch (e: Exception) {
            Timber.w(e, "could not apply the app theme to cards")
        }
    }

    private fun hexOf(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    fun interceptFontRequest(request: WebResourceRequest): WebResourceResponse? {
        if (request.url.path != REQUEST_PATH) return null
        val context = appContext() ?: return null
        if (!appliesToCards(context)) return null
        return serveFont(context)
    }

    // -------------------------------------------------------------- 백엔드 페이지

    /**
     * 통계나 덱 옵션처럼 WebView 로 그리는 화면에 폰트를 씌웁니다.
     * 네이티브 뷰가 아니라 applyToActivity 로는 닿지 않는 화면들입니다.
     * "앱 화면에 적용" 토글을 따릅니다.
     */
    fun injectIntoPage(webView: WebView) {
        val context = appContext() ?: return
        if (!appliesToApp(context)) return
        if (!fontFile(context).exists()) return

        val css =
            "@font-face{font-family:'" + FAMILY + "';src:url('" + REQUEST_PATH +
                "');font-display:swap;}" +
                "*{font-family:'" + FAMILY + "',sans-serif !important;}"
        val script =
            "(function(){var run=function(){" +
                "var s=document.createElement('style');" +
                "s.textContent=" + JSONObject.quote(css) + ";" +
                "document.head.appendChild(s);};" +
                "if(document.readyState==='loading')" +
                "{document.addEventListener('DOMContentLoaded',run);}else{run();}})();"
        webView.evaluateJavascript(script, null)
    }

    /** 백엔드 페이지에서 오는 폰트 요청. 카드와 달리 "앱 화면에 적용" 을 따릅니다. */
    fun interceptPageFontRequest(request: WebResourceRequest): WebResourceResponse? {
        if (request.url.path != REQUEST_PATH) return null
        val context = appContext() ?: return null
        if (!appliesToApp(context)) return null
        return serveFont(context)
    }

    private fun serveFont(context: Context): WebResourceResponse? {
        val file = fontFile(context)
        if (!file.exists()) return null
        return try {
            WebResourceResponse("font/ttf", null, FileInputStream(file))
        } catch (e: Exception) {
            Timber.w(e, "could not serve the custom font")
            null
        }
    }
}
