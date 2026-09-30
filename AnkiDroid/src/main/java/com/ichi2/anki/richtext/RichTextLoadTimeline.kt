// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.richtext

import android.app.Activity
import android.graphics.Color
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale

/**
 * TEMPORARY diagnostic: shows on screen how long each step of opening the note
 * editor takes, to find why the rich text fields are slow on a tablet-sized
 * window. Remove once that is found.
 */
class RichTextLoadTimeline(
    activity: Activity,
) {
    private val start = SystemClock.elapsedRealtime()
    private val lines = mutableListOf<String>()
    private val seen = mutableSetOf<String>()

    private val overlay =
        TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(16, 12, 16, 12)
            elevation = 100f
        }

    init {
        activity.findViewById<ViewGroup>(android.R.id.content).addView(
            overlay,
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply { topMargin = 200 },
        )
        mark("activity")
    }

    /** Records [step] the first time it happens, with the time since the activity was created. */
    fun mark(
        step: String,
        detail: String = "",
    ) {
        if (!seen.add(step)) return
        val ms = SystemClock.elapsedRealtime() - start
        lines += String.format(Locale.ROOT, "%5d  %s %s", ms, step, detail).trimEnd()
        overlay.post { overlay.text = lines.joinToString("\n") }
    }
}
