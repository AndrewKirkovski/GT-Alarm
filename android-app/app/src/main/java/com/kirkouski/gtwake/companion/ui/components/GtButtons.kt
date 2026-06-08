@file:Suppress("MatchingDeclarationName")

package com.kirkouski.gtwake.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kirkouski.gtwake.companion.ui.theme.GtFloatingButtonLight

data class GtSegmentOption<T>(
    val value: T,
    val label: String,
)

@Composable
fun GtAccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCard: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .defaultMinSize(minWidth = 88.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .shadow(elevation = if (onCard) 1.dp else 6.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
fun GtFloatingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCard: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val container = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        GtFloatingButtonLight
    }
    val contentColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .defaultMinSize(minWidth = 88.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .shadow(elevation = if (onCard) 2.dp else 8.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
fun <T> GtSegmentedControl(
    options: List<GtSegmentOption<T>>,
    selectedValue: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    onCard: Boolean = true,
    containerColor: Color = GtFloatingButtonLight,
    unselectedContentColor: Color = Color(0xFF1B1720),
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .shadow(elevation = if (onCard) 2.dp else 6.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(containerColor),
    ) {
        options.forEachIndexed { index, option ->
            val selected = selectedValue == option.value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(segmentShape(index, options.lastIndex))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            containerColor
                        },
                    )
                    .clickable(role = Role.Button) { onSelected(option.value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        unselectedContentColor
                    },
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun segmentShape(index: Int, lastIndex: Int) = when (index) {
    0 -> RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)
    lastIndex -> RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
    else -> RoundedCornerShape(0.dp)
}
