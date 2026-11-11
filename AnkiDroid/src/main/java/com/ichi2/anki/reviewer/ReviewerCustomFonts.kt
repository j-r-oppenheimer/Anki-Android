// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2013 Flavio Lerda <flerda@gmail.com>

package com.ichi2.anki.reviewer

import com.ichi2.anki.customfont.CustomFont

class ReviewerCustomFonts {
    /**
     * The CSS used to set the theme font.
     */
    private val customStyle =
        "BODY {font-family: 'OpenSans';font-weight: normal;font-style: normal;font-stretch: normal;}"

    fun updateCssStyle(cssStyle: StringBuilder) {
        cssStyle.append(customStyle)
        // 커스텀 폰트를 카드에도 적용하도록 켜 두면 @font-face 를 덧붙입니다.
        CustomFont.appendCardCss(cssStyle)
        CustomFont.appendCardThemeCss(cssStyle)
    }
}
