package com.kirkouski.gtalarm.ui.picktime

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.rememberTextMeasurer
import com.anhaki.picktime.utils.PickTimeTextStyle

@Composable
internal fun measureTextWidth(text: String, textStyle: PickTimeTextStyle): Float {
    val textMeasurer = rememberTextMeasurer()
    val layoutResult = textMeasurer.measure(text = "$text ", style = textStyle.toTextStyle())
    return layoutResult.size.width.toFloat()
}
