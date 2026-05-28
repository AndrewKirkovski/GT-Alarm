package com.kirkouski.gtalarm.ui.nav

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kirkouski.gtalarm.R

private data class BottomTab(
    val route: String,
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val labelRes: Int,
)

private val BOTTOM_TABS = listOf(
    BottomTab(Routes.LIST, R.drawable.ic_alarm, R.string.screen_list_title),
    BottomTab(Routes.SETTINGS, R.drawable.ic_settings, R.string.action_open_settings),
    BottomTab(Routes.HELP, R.drawable.ic_help, R.string.action_open_help),
)

/** Routes that show the bottom tab bar. EDIT is a pushed route with no bar. */
val BOTTOM_BAR_ROUTES: Set<String> = BOTTOM_TABS.map { it.route }.toSet()

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

@Composable
fun AppBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 48.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT_DP.dp),
            ) {
                BOTTOM_TABS.forEach { tab ->
                    BottomTabItem(
                        tab = tab,
                        selected = currentRoute == tab.route,
                        onClick = { onSelect(tab.route) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomTabItem(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Clickable on the outer cell so the full weight(1f) area is tappable,
    // not just the 44 dp inner circle. The circle is visual-only.
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(tab.iconRes),
                contentDescription = stringResource(tab.labelRes),
                tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
