package com.kirkouski.gtwake.companion.ui.picktime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.anhaki.picktime.utils.PickTimeTextStyle
import kotlinx.coroutines.launch

// reason: ported verbatim from anhaki/PickTime-Compose — parameter count, length,
// and complexity are inherent to the library's generic wheel implementation.
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList", "UnusedParameter")
@Composable
internal fun <T> Wheel(
    modifier: Modifier = Modifier,
    items: List<T>,
    selectedItem: Int,
    onItemSelected: (Int) -> Unit,
    space: Dp,
    selectedTextStyle: PickTimeTextStyle,
    unselectedTextStyle: PickTimeTextStyle,
    extraRow: Int,
    isLooping: Boolean,
    overlayColor: Color,
    itemToString: (T) -> String,
    longestText: String,
) {
    var localSelectedItem by remember { mutableIntStateOf(selectedItem) }

    val listState = if (isLooping) {
        rememberLazyListState(nearestIndexTarget(localSelectedItem - extraRow, items.size))
    } else {
        rememberLazyListState(initialFirstVisibleItemIndex = localSelectedItem)
    }

    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val spacePX = with(density) { space.toPx() }

    val selectedTextLineHeightPx = measureTextHeight(selectedTextStyle)
    val selectedTextLineHeightDp = with(density) { selectedTextLineHeightPx.toDp() }
    val unselectedTextLineHeightPx = measureTextHeight(unselectedTextStyle)
    val unselectedTextLineHeightDp = with(density) { unselectedTextLineHeightPx.toDp() }
    val selectedTextLineWidthPx = measureTextWidth(longestText, selectedTextStyle)
    val selectedTextLineWidthDp = with(density) { selectedTextLineWidthPx.toDp() }

    val wheelHeight = (unselectedTextLineHeightDp * (extraRow * 2)) +
        (space * (extraRow * 2 + 2)) +
        selectedTextLineHeightDp
    val wheelHeightPx = with(density) { wheelHeight.toPx() }
    val maxOffset = with(density) { unselectedTextLineHeightPx + spacePX }

    val firstIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val firstVisibleOffset by remember { derivedStateOf { listState.firstVisibleItemScrollOffset } }
    val isScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    val progress = (firstVisibleOffset / maxOffset).coerceIn(0f, 1f)

    val sizeInterpolator = { index: Int ->
        when (index) {
            firstIndex -> transition(selectedTextStyle.fontSize.value, unselectedTextStyle.fontSize.value, progress)
            firstIndex + 1 -> transition(unselectedTextStyle.fontSize.value, selectedTextStyle.fontSize.value, progress)
            else -> unselectedTextStyle.fontSize.value
        }
    }

    val heightInterpolator = { index: Int ->
        when (index) {
            firstIndex -> transition(selectedTextLineHeightPx, unselectedTextLineHeightPx, progress)
            firstIndex + 1 -> transition(unselectedTextLineHeightPx, selectedTextLineHeightPx, progress)
            else -> unselectedTextLineHeightPx
        }
    }

    val colorInterpolator = { index: Int ->
        when (index) {
            firstIndex -> transition(selectedTextStyle.color, unselectedTextStyle.color, progress)
            firstIndex + 1 -> transition(unselectedTextStyle.color, selectedTextStyle.color, progress)
            else -> unselectedTextStyle.color
        }
    }

    LaunchedEffect(firstVisibleOffset) {
        val selected = if (isLooping) {
            (firstIndex + if (firstVisibleOffset > maxOffset / 2) extraRow + 1 else extraRow) % items.size
        } else {
            (firstIndex + if (firstVisibleOffset > maxOffset / 2) 1 else 0) % items.size
        }
        if (localSelectedItem != selected) {
            localSelectedItem = selected
            onItemSelected(selected)
        }
    }

    LaunchedEffect(isScrolling) {
        if (!isScrolling && firstVisibleOffset != 0) {
            coroutineScope.launch {
                listState.animateScrollToItem(firstIndex + if (firstVisibleOffset > maxOffset / 2) 1 else 0)
            }
        }
    }

    Box(
        modifier = modifier
            .height(wheelHeight)
            .width(selectedTextLineWidthDp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                val topOverlayPosition = maxOffset / size.height
                val bottomOverlayPosition = (size.height - maxOffset) / size.height
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(0f to Color.Black, topOverlayPosition to Color.Transparent),
                    topLeft = Offset(x = 0f, y = 0f),
                    blendMode = BlendMode.DstOut,
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        bottomOverlayPosition to Color.Transparent,
                        1f to Color.Black,
                    ),
                    topLeft = Offset(x = 0f, y = wheelHeightPx - (unselectedTextLineHeightPx + spacePX)),
                    size = Size(size.width, unselectedTextLineHeightPx + spacePX),
                    blendMode = BlendMode.DstOut,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        LazyColumn(
            modifier = Modifier.width(selectedTextLineWidthDp),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(space),
            contentPadding = PaddingValues(
                top = space,
                bottom = if (isLooping) space
                else (space * (extraRow + 1)) + (unselectedTextLineHeightDp * extraRow),
            ),
        ) {
            if (!isLooping) {
                repeat(extraRow) {
                    item {
                        Text(
                            modifier = Modifier.height(unselectedTextLineHeightDp).fillMaxWidth(),
                            text = " ",
                            color = Color.Transparent,
                            fontSize = unselectedTextStyle.fontSize,
                            fontWeight = FontWeight.Normal,
                            fontFamily = unselectedTextStyle.fontFamily,
                        )
                    }
                }
            }

            items(count = if (isLooping) Int.MAX_VALUE else items.size) { index ->
                val itemIndex = index % items.size
                val item = items[itemIndex]
                val countIndex = index % Int.MAX_VALUE
                val isItemSelected = localSelectedItem == itemIndex
                val adjustedIndex = if (isLooping) countIndex - extraRow else itemIndex
                val fw = if (isItemSelected) selectedTextStyle.fontWeight else unselectedTextStyle.fontWeight
                val ff = if (isItemSelected) selectedTextStyle.fontFamily else unselectedTextStyle.fontFamily

                Box(
                    modifier = Modifier.height(with(density) { heightInterpolator(adjustedIndex).toDp() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = itemToString(item),
                        color = colorInterpolator(adjustedIndex),
                        fontSize = sizeInterpolator(adjustedIndex).sp,
                        fontWeight = fw,
                        fontFamily = ff,
                    )
                }
            }
        }
    }
}

private fun transition(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction

private fun transition(start: Color, end: Color, fraction: Float) = Color(
    start.red + (end.red - start.red) * fraction,
    start.green + (end.green - start.green) * fraction,
    start.blue + (end.blue - start.blue) * fraction,
    start.alpha + (end.alpha - start.alpha) * fraction,
)

private fun nearestIndexTarget(target: Int, size: Int): Int {
    val initialIndex = Int.MAX_VALUE / 2
    val upperLimit = (initialIndex / size) * size + target
    val lowerLimit = upperLimit + size
    return if ((initialIndex - upperLimit) <= (lowerLimit - initialIndex)) upperLimit else lowerLimit
}
