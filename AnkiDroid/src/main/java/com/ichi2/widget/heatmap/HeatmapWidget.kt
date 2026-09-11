// SPDX-License-Identifier: GPL-3.0-or-later
//
// 저장할 위치:
// AnkiDroid/src/main/java/com/ichi2/widget/heatmap/HeatmapWidget.kt
//
// 색 모델: 4단계 고정 팔레트 -> 기준색 1개 + 강도 곡선.
//   빈 날 색에서 기준색까지를 OkLab 공간에서 보간합니다.
//   보간 위치는 (그날 복습 수 / 최대 개수)에 감마 곡선을 적용한 값입니다.
//   감마는 설정 화면에서 곡선 손잡이를 끌어 정하고, curveX/curveY 로 저장됩니다.
//
// 그리기: 칸 크기를 위젯의 가로세로 비율에서 뽑아내므로 위아래로 남는 공간이
//   거의 없습니다. 남는 공간은 fitCenter 가 사방에 고르게 나눕니다.

package com.ichi2.widget.heatmap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.common.coroutines.applicationScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val ROWS = 7
private const val SECONDS_PER_DAY = 86400L
private const val MAX_BITMAP_PIXELS = 250_000

/** 53주 x 7일. 그리드 최대치와 자동 최대 개수 계산에 함께 쓰입니다. */
internal const val HISTORY_DAYS = 371

/** 위젯 레이아웃의 사방 여백(dp). widget_heatmap.xml 의 padding 과 같아야 합니다. */
private const val WIDGET_PADDING_DP = 8

/** 복습이 1개라도 있으면 최소 이만큼은 기준색 쪽으로 섞습니다. */
private const val INTENSITY_FLOOR = 0.18

/** 설정 화면에서 고른 값. 색은 전부 ARGB Int. */
class HeatmapSettings(
    val base: Int,
    val empty: Int,
    val background: Int,
    val cornerRatio: Float,
    val gapRatio: Float,
    val curveX: Int,
    val curveY: Int,
    /** 0 이면 학습 기록에서 자동으로 정합니다. */
    val maxCount: Int,
)

/** 위젯별로 SharedPreferences에 저장합니다. */
object HeatmapPrefs {
    private const val FILE = "heatmap_widget"

    /** 안드로이드 11 이하, 또는 시스템 색을 못 읽을 때 쓰는 값. */
    private const val FALLBACK_BASE = 0xFF2F81F7.toInt()
    private const val FALLBACK_EMPTY = 0xFF2A2C31.toInt()
    private const val DEFAULT_BACKGROUND = 0x66000000
    private const val DEFAULT_CORNER = 25
    private const val DEFAULT_GAP = 18
    private const val DEFAULT_CURVE = 50

    private fun key(
        name: String,
        id: Int,
    ) = "${name}_$id"

    /**
     * 기본 기준색. 안드로이드 12 이상에서는 배경화면에서 뽑아낸 시스템 강조색을
     * 그대로 씁니다(Material You). 위젯을 놓기만 해도 배경화면과 어울리는
     * 색으로 시작합니다.
     */
    private fun defaultBase(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getColor(android.R.color.system_accent1_400)
        } else {
            FALLBACK_BASE
        }

    /** 복습이 없던 날의 기본색. 시스템 중성색이 있으면 그쪽이 더 잘 어울립니다. */
    private fun defaultEmpty(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getColor(android.R.color.system_neutral1_800)
        } else {
            FALLBACK_EMPTY
        }

    /**
     * 설정 화면에 보여줄 "배경화면 색" 견본.
     * 안드로이드 11 이하에서는 빈 목록이고, 그러면 그 줄이 아예 숨겨집니다.
     */
    private fun themeColors(context: Context): List<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return listOf(
            android.R.color.system_accent1_300,
            android.R.color.system_accent1_400,
            android.R.color.system_accent1_600,
            android.R.color.system_accent2_400,
            android.R.color.system_accent3_400,
        ).map { context.getColor(it) }
    }

    fun get(
        context: Context,
        appWidgetId: Int,
    ): HeatmapSettings {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // 이전 버전(4단계 팔레트)에서 올라온 경우 가장 진한 단계를 기준색으로 물려받습니다.
        val legacyBase = prefs.getInt(key("level4", appWidgetId), defaultBase(context))
        return HeatmapSettings(
            base = prefs.getInt(key("base", appWidgetId), legacyBase),
            empty = prefs.getInt(key("empty", appWidgetId), defaultEmpty(context)),
            background = prefs.getInt(key("background", appWidgetId), DEFAULT_BACKGROUND),
            cornerRatio = prefs.getInt(key("corner", appWidgetId), DEFAULT_CORNER) / 100f,
            gapRatio = prefs.getInt(key("gap", appWidgetId), DEFAULT_GAP) / 100f,
            curveX = prefs.getInt(key("curveX", appWidgetId), DEFAULT_CURVE),
            curveY = prefs.getInt(key("curveY", appWidgetId), DEFAULT_CURVE),
            maxCount = prefs.getInt(key("maxCount", appWidgetId), 0),
        )
    }

    /** 설정 화면이 시작할 때 읽어가는 JSON. 색은 #RRGGBB 문자열로 내보냅니다. */
    fun toJson(
        context: Context,
        appWidgetId: Int,
    ): String {
        val s = get(context, appWidgetId)
        val theme = JSONArray()
        themeColors(context).forEach { theme.put(hex(it)) }

        return JSONObject()
            .put("themeColors", theme)
            .put("base", hex(s.base))
            .put("empty", hex(s.empty))
            .put("background", hex(s.background))
            .put("alpha", Color.alpha(s.background))
            .put("corner", (s.cornerRatio * 100).roundToInt())
            .put("gap", (s.gapRatio * 100).roundToInt())
            .put("curveX", s.curveX)
            .put("curveY", s.curveY)
            .put("maxCount", s.maxCount)
            .toString()
    }

    /** 저장 버튼을 눌렀을 때 설정 화면이 보내온 JSON을 반영합니다. */
    fun fromJson(
        context: Context,
        appWidgetId: Int,
        json: JSONObject,
    ) {
        val alpha = json.optInt("alpha", 102).coerceIn(1, 254)
        val bgRgb = Color.parseColor(json.getString("background"))

        context
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(key("base", appWidgetId), Color.parseColor(json.getString("base")))
            .putInt(key("empty", appWidgetId), Color.parseColor(json.getString("empty")))
            .putInt(
                key("background", appWidgetId),
                Color.argb(alpha, Color.red(bgRgb), Color.green(bgRgb), Color.blue(bgRgb)),
            ).putInt(key("corner", appWidgetId), json.optInt("corner", DEFAULT_CORNER).coerceIn(0, 50))
            .putInt(key("gap", appWidgetId), json.optInt("gap", DEFAULT_GAP).coerceIn(0, 35))
            .putInt(key("curveX", appWidgetId), json.optInt("curveX", DEFAULT_CURVE).coerceIn(4, 96))
            .putInt(key("curveY", appWidgetId), json.optInt("curveY", DEFAULT_CURVE).coerceIn(4, 96))
            .putInt(key("maxCount", appWidgetId), json.optInt("maxCount", 0).coerceIn(0, 9999))
            .apply()
    }

    fun clear(
        context: Context,
        appWidgetId: Int,
    ) {
        val editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        listOf(
            "base",
            "empty",
            "background",
            "corner",
            "gap",
            "curveX",
            "curveY",
            "maxCount",
            // 이전 버전의 키도 함께 정리합니다.
            "level1",
            "level2",
            "level3",
            "level4",
        ).forEach { editor.remove(key(it, appWidgetId)) }
        editor.apply()
    }

    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)
}

class HeatmapWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // goAsync() 로 브로드캐스트를 붙잡아 둡니다. 이게 없으면 onUpdate 가 끝나는 순간
        // 프로세스가 빈 상태가 되어, 컬렉션을 여는 도중에 안드로이드가 죽여버립니다.
        // 홈 화면 위젯만 기록을 못 불러오고 설정 화면은 멀쩡한 증상이 이것 때문입니다.
        val pending = goAsync()
        applicationScope.launch {
            try {
                appWidgetIds.forEach { render(context, appWidgetManager, it) }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pending = goAsync()
        applicationScope.launch {
            try {
                render(context, appWidgetManager, appWidgetId)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { HeatmapPrefs.clear(context, it) }
    }

    private suspend fun render(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        // onUpdate 가 코루틴을 열고 goAsync 로 프로세스를 붙잡으므로, 여기서는
        // 코루틴을 새로 열지 않습니다. run 은 아래 블록의 들여쓰기를 유지하기 위한 것입니다.
        run {
            try {
                val settings = HeatmapPrefs.get(context, appWidgetId)
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)

                // MIN_WIDTH/MAX_HEIGHT 는 세로 화면에서의 크기, MAX_WIDTH/MIN_HEIGHT 는 가로 화면.
                // 섞어 쓰면 실제로 존재하지 않는 비율이 나오므로 현재 방향에 맞춰 짝을 맞춥니다.
                val portrait =
                    context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
                val widthDp =
                    optionDp(
                        options,
                        if (portrait) {
                            AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
                        } else {
                            AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                        },
                        250,
                    )
                val heightDp =
                    optionDp(
                        options,
                        if (portrait) {
                            AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
                        } else {
                            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                        },
                        110,
                    )

                val history = loadHistory()
                val bitmap = drawHeatmap(context, history, widthDp, heightDp, settings)

                val views = RemoteViews(context.packageName, R.layout.widget_heatmap)
                views.setInt(android.R.id.background, "setBackgroundColor", settings.background)
                views.setImageViewBitmap(R.id.heatmap_image, bitmap)
                views.setOnClickPendingIntent(android.R.id.background, openAnkiDroid(context))

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                Timber.w(e, "failed to render heatmap widget")
            }
        }
    }

    private fun openAnkiDroid(context: Context): PendingIntent {
        val intent =
            Intent(context, DeckPicker::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

private fun optionDp(
    options: Bundle,
    key: String,
    fallback: Int,
): Int {
    val value = options.getInt(key, 0)
    return if (value > 0) value else fallback
}

/** 하루 단위 복습 수. [counts] 의 마지막 칸이 오늘입니다. */
internal class HeatmapHistory(
    val counts: IntArray,
    /** 오늘의 요일. 일요일 0. */
    val todayWeekday: Int,
)

/**
 * revlog를 하루 단위로 한 번에 묶어서 읽습니다.
 * id 는 복습 시각(epoch 밀리초)이고 기본키라서 범위 조회가 인덱스를 탑니다.
 * ease = 0 은 수동 스케줄 변경이라 제외합니다.
 */
internal suspend fun loadHistory(): HeatmapHistory =
    withCol {
        val cutoffSec = sched.dayCutoff
        val startSec = cutoffSec - SECONDS_PER_DAY * HISTORY_DAYS
        val counts = IntArray(HISTORY_DAYS)

        db
            .query(
                "select cast((id / 1000 - ?) / 86400 as int) as d, count() from revlog " +
                    "where id >= ? and id < ? and ease > 0 group by d",
                startSec,
                startSec * 1000L,
                cutoffSec * 1000L,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val day = cursor.getInt(0)
                    if (day in 0 until HISTORY_DAYS) {
                        counts[day] = cursor.getInt(1)
                    }
                }
            }

        // dayCutoff 는 오늘 Anki 날의 "끝"(보통 내일 새벽 4시)입니다. 여기서 1초만 빼면
        // 달력상 날짜가 여전히 내일이라 요일이 하루 밀립니다. Anki 하루의 한가운데를
        // 기준으로 잡으면 롤오버 시각이 몇 시든 항상 오늘 날짜가 나옵니다.
        val todayDate =
            Instant
                .ofEpochSecond(cutoffSec - SECONDS_PER_DAY / 2)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()

        HeatmapHistory(counts, todayDate.dayOfWeek.value % 7)
    }

/**
 * 색이 가장 진해지는 하루 복습 수. 사용자가 직접 정하지 않았으면
 * 복습한 날들의 90번째 백분위수를 씁니다. 어쩌다 한 번 몰아친 날 때문에
 * 전체가 흐려지지 않게 최댓값 대신 백분위수를 씁니다.
 */
internal fun autoMaxCount(counts: IntArray): Int {
    val studied = counts.filter { it > 0 }.sorted()
    // 기록이 아예 없을 때만 쓰는 값입니다. 설정 화면은 기록이 오기 전에는
    // 숫자를 보여주지 않고 불러오는 중이라고 표시합니다.
    if (studied.isEmpty()) return 40
    val index = ((studied.size - 1) * 0.9).roundToInt()
    val value = studied[index]
    return when {
        value < 20 -> value.coerceAtLeast(5)
        value < 50 -> (value / 5) * 5
        else -> (value / 10) * 10
    }
}

/**
 * 곡선 손잡이의 지수.
 *
 * 설정 화면의 그래프는 가로축이 색(연함 -> 진함), 세로축이 하루 복습 수입니다.
 * 손잡이는 (색 = curveX%, 개수 = 최대 개수의 curveY%) 지점에 있고,
 * 색 강도는 개수비^gamma 로 구하므로 curveX = curveY^gamma 를 만족해야 합니다.
 */
internal fun gammaOf(
    curveX: Int,
    curveY: Int,
): Double {
    val color = curveX.coerceIn(4, 96) / 100.0
    val count = curveY.coerceIn(4, 96) / 100.0
    if (abs(color - count) < 1e-4) return 1.0
    return (ln(color) / ln(count)).coerceIn(0.15, 6.0)
}

internal fun colorForCount(
    count: Int,
    maxCount: Int,
    settings: HeatmapSettings,
): Int {
    if (count <= 0) return settings.empty
    val ratio = (count.toDouble() / maxCount.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val intensity = ratio.pow(gammaOf(settings.curveX, settings.curveY))
    val mix = INTENSITY_FLOOR + (1.0 - INTENSITY_FLOOR) * intensity
    return mixOklab(settings.empty, settings.base, mix)
}

// ------------------------------------------------------------------ OkLab
// sRGB를 그대로 섞으면 중간색이 탁해지므로 인지적으로 균등한 OkLab에서 섞습니다.
// 설정 화면(heatmap_picker.html)에 같은 계산이 들어 있어 미리보기와 결과가 일치합니다.

private fun srgbToLinear(channel: Double): Double = if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

private fun linearToSrgb(channel: Double): Double = if (channel <= 0.0031308) 12.92 * channel else 1.055 * channel.pow(1.0 / 2.4) - 0.055

private fun toOklab(color: Int): DoubleArray {
    val r = srgbToLinear(Color.red(color) / 255.0)
    val g = srgbToLinear(Color.green(color) / 255.0)
    val b = srgbToLinear(Color.blue(color) / 255.0)

    val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)

    return doubleArrayOf(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}

private fun fromOklab(lab: DoubleArray): Int {
    val lp = lab[0] + 0.3963377774 * lab[1] + 0.2158037573 * lab[2]
    val mp = lab[0] - 0.1055613458 * lab[1] - 0.0638541728 * lab[2]
    val sp = lab[0] - 0.0894841775 * lab[1] - 1.2914855480 * lab[2]

    val l = lp * lp * lp
    val m = mp * mp * mp
    val s = sp * sp * sp

    val r = linearToSrgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s)
    val g = linearToSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s)
    val b = linearToSrgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)

    return Color.rgb(
        (r * 255).roundToInt().coerceIn(0, 255),
        (g * 255).roundToInt().coerceIn(0, 255),
        (b * 255).roundToInt().coerceIn(0, 255),
    )
}

private fun mixOklab(
    from: Int,
    to: Int,
    amount: Double,
): Int {
    val t = amount.coerceIn(0.0, 1.0)
    val a = toOklab(from)
    val b = toOklab(to)
    return fromOklab(
        doubleArrayOf(
            a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t,
        ),
    )
}

// ------------------------------------------------------------------ 그리기

/**
 * 칸 크기를 세로 기준으로 잡고 거기에 맞춰 열 수를 정합니다.
 * 그래야 그리드가 위젯을 거의 꽉 채우고 남는 여백이 사방에 고르게 갑니다.
 */
private fun columnsAndPitch(
    widthPx: Int,
    heightPx: Int,
): Pair<Int, Float> {
    val pitchByHeight = heightPx.toFloat() / ROWS
    val columns = (widthPx / pitchByHeight).roundToInt().coerceIn(4, HISTORY_DAYS / ROWS)
    val pitch = min(widthPx.toFloat() / columns, pitchByHeight)
    return columns to pitch
}

private fun drawHeatmap(
    context: Context,
    history: HeatmapHistory,
    widthDp: Int,
    heightDp: Int,
    settings: HeatmapSettings,
): Bitmap {
    val density = context.resources.displayMetrics.density
    val inset = WIDGET_PADDING_DP * 2

    var availableWidth = ((widthDp - inset) * density).roundToInt().coerceAtLeast(80)
    var availableHeight = ((heightDp - inset) * density).roundToInt().coerceAtLeast(56)

    val pixels = availableWidth.toLong() * availableHeight
    if (pixels > MAX_BITMAP_PIXELS) {
        val scale = sqrt(MAX_BITMAP_PIXELS.toDouble() / pixels)
        availableWidth = (availableWidth * scale).roundToInt()
        availableHeight = (availableHeight * scale).roundToInt()
    }

    val (columns, pitch) = columnsAndPitch(availableWidth, availableHeight)
    val gap = pitch * settings.gapRatio
    val cell = pitch - gap
    val radius = cell * settings.cornerRatio

    val gridWidth = (pitch * columns).roundToInt().coerceAtLeast(1)
    val gridHeight = (pitch * ROWS).roundToInt().coerceAtLeast(1)

    val maxCount =
        if (settings.maxCount > 0) settings.maxCount else autoMaxCount(history.counts)

    // 오른쪽 끝 열이 오늘이 든 주. 잘라낸 조각의 0번은 항상 일요일입니다.
    val visibleDays = (columns - 1) * ROWS + history.todayWeekday + 1
    val from = max(0, history.counts.size - visibleDays)
    val visible = history.counts.copyOfRange(from, history.counts.size)

    val bitmap = Bitmap.createBitmap(gridWidth, gridHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.TRANSPARENT)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = RectF()

    for (index in visible.indices) {
        val column = index / ROWS
        val row = index % ROWS
        if (column >= columns) continue

        val left = column * pitch + gap / 2f
        val top = row * pitch + gap / 2f
        rect.set(left, top, left + cell, top + cell)

        paint.color = colorForCount(visible[index], maxCount, settings)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    return bitmap
}

/** 설정 화면이 곡선 그래프를 그릴 때 쓰는 요약. */
internal fun historyToJson(history: HeatmapHistory): String {
    val counts = JSONArray()
    history.counts.forEach { counts.put(it) }
    return JSONObject()
        .put("counts", counts)
        .put("auto", autoMaxCount(history.counts))
        .put("peak", history.counts.maxOrNull() ?: 0)
        .put("weekday", history.todayWeekday)
        .toString()
}
