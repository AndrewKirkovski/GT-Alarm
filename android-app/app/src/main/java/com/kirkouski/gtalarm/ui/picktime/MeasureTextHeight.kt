package com.kirkouski.gtalarm.ui.picktime

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.rememberTextMeasurer
import com.anhaki.picktime.utils.PickTimeTextStyle

@Composable
internal fun measureTextHeight(textStyle: PickTimeTextStyle): Float {
    val textMeasurer = rememberTextMeasurer()
    val layoutResult = textMeasurer.measure(text = " \n ", style = textStyle.toTextStyle())
    return (layoutResult.size.height * 0.6).toFloat()
}
