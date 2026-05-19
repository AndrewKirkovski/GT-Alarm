package com.kirkouski.gtalarm.ui.nav

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
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

@Composable
private fun BottomTabItem(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .alpha(if (selected) 1f else 0.45f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(tab.iconRes),
            contentDescription = stringResource(tab.labelRes),
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = stringResource(tab.labelRes),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
