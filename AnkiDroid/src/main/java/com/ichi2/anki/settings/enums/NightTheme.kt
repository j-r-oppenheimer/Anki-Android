// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.settings.enums

import com.ichi2.anki.R

/** [R.array.night_theme_values] */
enum class NightTheme(
    override val entryResId: Int,
    override val styleResId: Int,
) : Theme {
    BLACK(R.string.theme_black_value, R.style.Theme_Dark_Black),
    DARK(R.string.theme_dark_value, R.style.Theme_Dark),
    SAND_NIGHT(R.string.theme_sandnight_value, R.style.Theme_Dark_SandNight),
    SAGE_NIGHT(R.string.theme_sagenight_value, R.style.Theme_Dark_SageNight),
    MIST_NIGHT(R.string.theme_mistnight_value, R.style.Theme_Dark_MistNight),
}
