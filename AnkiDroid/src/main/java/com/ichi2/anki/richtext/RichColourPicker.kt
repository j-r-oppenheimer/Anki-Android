// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.richtext

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.EditText
import androidx.annotation.ColorInt
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.ichi2.anki.R
import com.ichi2.utils.customView
import com.ichi2.utils.negativeButton
import com.ichi2.utils.positiveButton
import com.ichi2.utils.show
import com.ichi2.utils.title
import com.skydoves.colorpickerview.ColorPickerView
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import com.skydoves.colorpickerview.sliders.AlphaSlideBar
import com.skydoves.colorpickerview.sliders.BrightnessSlideBar
import java.util.Locale

/**
 * Picks a colour for one of the rich text editor's swatches.
 *
 * The wheel and the two sliders are the usual ones, with the value each slider
 * controls shown beside it and editable: a hex code for the colour, and a
 * percentage for the opacity. Typing in either moves the matching slider, so a
 * colour can be reproduced exactly instead of only dragged towards.
 */
fun Context.showRichColourPicker(
    @ColorInt initial: Int,
    onPicked: (Int) -> Unit,
) {
    val view = LayoutInflater.from(this).inflate(R.layout.dialog_rich_colour_picker, null)
    val wheel = view.findViewById<ColorPickerView>(R.id.colour_wheel)
    val hexField = view.findViewById<EditText>(R.id.colour_hex)
    val opacityField = view.findViewById<EditText>(R.id.colour_alpha)
    val alphaBar = view.findViewById<AlphaSlideBar>(R.id.colour_alpha_bar)
    wheel.attachBrightnessSlider(view.findViewById<BrightnessSlideBar>(R.id.colour_brightness_bar))
    wheel.attachAlphaSlider(alphaBar)

    var colour = initial
    // The fields are written from the sliders and read back when typed in, so
    // without this they would answer their own updates.
    var writing = false

    fun publish(
        @ColorInt picked: Int,
    ) {
        colour = picked
        writing = true
        hexField.setText(String.format(Locale.ROOT, "#%06X", 0xFFFFFF and picked))
        opacityField.setText((Color.alpha(picked) * 100 / 255).toString())
        writing = false
    }

    wheel.setColorListener(
        ColorEnvelopeListener { envelope, fromUser ->
            if (envelope != null && fromUser) publish(envelope.color)
        },
    )

    hexField.doAfterTextChanged { text ->
        if (writing) return@doAfterTextChanged
        val rgb = text?.toString()?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)
        if (rgb == null) return@doAfterTextChanged
        colour = (Color.alpha(colour) shl 24) or rgb
        wheel.setInitialColor(colour)
    }

    opacityField.doAfterTextChanged { text ->
        if (writing) return@doAfterTextChanged
        val percent = text?.toString()?.toIntOrNull()?.coerceIn(0, 100) ?: return@doAfterTextChanged
        colour = (percent * 255 / 100 shl 24) or (colour and 0xFFFFFF)
        alphaBar.setSelectorByHalfSelectorPosition(percent / 100f)
    }

    // The sliders only take a colour once they have been measured.
    wheel.post {
        wheel.setInitialColor(initial)
        publish(initial)
    }

    AlertDialog.Builder(this).show {
        title(R.string.choose_color)
        customView(view)
        positiveButton(R.string.dialog_ok) { onPicked(colour) }
        negativeButton(R.string.dialog_cancel)
    }
}
