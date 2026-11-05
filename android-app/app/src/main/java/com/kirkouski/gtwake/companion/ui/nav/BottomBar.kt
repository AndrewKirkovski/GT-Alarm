package com.kirkouski.gtwake.companion.ui.nav

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kirkouski.gtwake.companion.R

private data class BottomTab(
    val route: String,
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val labelRes: Int,
)

private val BOTTOM_TABS = listOf(
    BottomTab(Routes.LIST, R.drawable.ic_alarm, R.string.screen_list_title),
    BottomTab(Routes.TIMER, R.drawable.ic_hourglass, R.string.screen_timer_title),
    BottomTab(Routes.STOPWATCH, R.drawable.ic_stopwatch, R.string.screen_stopwatch_title),
)

/** Reached through More. The pill stays on these routes too. */
private val MORE_TABS = listOf(
    BottomTab(Routes.SETTINGS, R.drawable.ic_settings, R.string.action_open_settings),
    BottomTab(Routes.HELP, R.drawable.ic_help, R.string.action_open_help),
)

/** Routes that show the bottom tab bar. EDIT is a pushed route with no bar. */
val BOTTOM_BAR_ROUTES: Set<String> = (BOTTOM_TABS + MORE_TABS).map { it.route }.toSet()

/**
 * Switches to a top-level tab. saveState/restoreState keep each tab's scroll
 * + UI state across switches; popUpTo(LIST) keeps a single-entry back stack.
 */
fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(Routes.LIST) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private const val BAR_HEIGHT_DP = 60

// Icons only, like Samsung Clock, by owner decision: an accepted exception to
// Material's label rule. A long press shows the name and TalkBack reads it.
@Composable
fun AppBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    val spreadColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
    } else {
        lerp(Color.White, MaterialTheme.colorScheme.tertiary, 0.14f).copy(alpha = 0.86f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 48.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .spreadShadow(spread = 5.dp, radius = (BAR_HEIGHT_DP / 2).dp, color = spreadColor),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT_DP.dp)
                    .selectableGroup(),
            ) {
                BOTTOM_TABS.forEach { tab ->
                    BottomTabItem(
                        tab = tab,
                        selected = currentRoute == tab.route,
                        onClick = { onSelect(tab.route) },
                        modifier = Modifier.weight(1f),
                    )
                }
                MoreTabItem(
                    selected = MORE_TABS.any { it.route == currentRoute },
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun Modifier.spreadShadow(spread: Dp, radius: Dp, color: Color): Modifier = drawBehind {
    val spreadPx = spread.toPx()
    val radiusPx = radius.toPx() + spreadPx
    drawRoundRect(
        color = color,
        topLeft = Offset(-spreadPx, -spreadPx),
        size = Size(size.width + spreadPx * 2f, size.height + spreadPx * 2f),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomTabItem(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(tab.labelRes)
    // TooltipBox gives its modifier to an inner node, so a Row weight must sit on this wrapper.
    Box(modifier = modifier.fillMaxHeight()) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
            modifier = Modifier.fillMaxSize(),
        ) {
            // The whole cell is the tap target, but only the circle shows the press.
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Tab,
                        onClick = onClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .indication(interaction, ripple()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = label,
                        tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreTabItem(selected: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxHeight()) {
        BottomTabItem(
            tab = BottomTab(route = "", iconRes = R.drawable.ic_dots, labelRes = R.string.action_more),
            selected = selected,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MORE_TABS.forEach { tab ->
                DropdownMenuItem(
                    text = { Text(stringResource(tab.labelRes)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(tab.iconRes),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(tab.route)
                    },
                )
            }
        }
    }
}
