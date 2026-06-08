package com.kirkouski.gtwake.companion.ui.picktime

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import com.anhaki.picktime.utils.PickTimeTextStyle

internal data class ColonAlignmentOffsets(
    val rowOffset: Dp,
    val textOffset: Dp,
)

@Composable
internal fun measureColonAlignmentOffsets(
    textStyle: PickTimeTextStyle,
    selectedItemHeightPx: Float,
): ColonAlignmentOffsets {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val composeTextStyle = textStyle.toTextStyle()
    val digitLayout = textMeasurer.measure(text = "0", style = composeTextStyle)
    val colonLayout = textMeasurer.measure(text = ":", style = composeTextStyle)
    val fontSizePx = with(density) { textStyle.fontSize.toPx() }
    val isBold = textStyle.fontWeight.weight >= 600

    return remember(
        fontSizePx,
        isBold,
        selectedItemHeightPx,
        digitLayout.size.height,
        digitLayout.firstBaseline,
        colonLayout.size.height,
        colonLayout.firstBaseline,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            typeface = resolveTypeface(textStyle.fontFamily, isBold)
        }

        val rowCenter = selectedItemHeightPx / 2f
        val digitInkCenter = inkCenterInRow(
            selectedItemHeightPx = selectedItemHeightPx,
            textHeight = digitLayout.size.height.toFloat(),
            firstBaseline = digitLayout.firstBaseline,
            inkCenter = paintInkCenterY(paint, "0"),
        )
        val colonInkCenter = inkCenterInRow(
            selectedItemHeightPx = selectedItemHeightPx,
            textHeight = colonLayout.size.height.toFloat(),
            firstBaseline = colonLayout.firstBaseline,
            inkCenter = paintInkCenterY(paint, ":"),
        )

        ColonAlignmentOffsets(
            rowOffset = with(density) { (digitInkCenter - rowCenter).toDp() },
            textOffset = with(density) { (rowCenter - colonInkCenter).toDp() },
        )
    }
}

private fun inkCenterInRow(
    selectedItemHeightPx: Float,
    textHeight: Float,
    firstBaseline: Float,
    inkCenter: Float,
): Float = ((selectedItemHeightPx - textHeight) / 2f) + firstBaseline + inkCenter

private fun paintInkCenterY(paint: Paint, text: String): Float {
    val path = Path()
    paint.getTextPath(text, 0, text.length, 0f, 0f, path)
    val bounds = RectF()
    path.computeBounds(bounds, true)
    return (bounds.top + bounds.bottom) / 2f
}

private fun resolveTypeface(fontFamily: FontFamily?, isBold: Boolean): Typeface {
    val base = when (fontFamily) {
        FontFamily.Serif -> Typeface.SERIF
        FontFamily.Monospace -> Typeface.MONOSPACE
        FontFamily.Cursive -> Typeface.create("casual", Typeface.NORMAL)
        else -> Typeface.DEFAULT
    }
    return Typeface.create(base, if (isBold) Typeface.BOLD else Typeface.NORMAL)
}
