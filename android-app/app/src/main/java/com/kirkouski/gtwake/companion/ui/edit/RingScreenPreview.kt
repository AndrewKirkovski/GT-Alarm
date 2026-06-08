package com.kirkouski.gtwake.companion.ui.edit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kirkouski.gtwake.companion.ui.components.PhoneRingOverlay

/** Miniature phone-frame preview of the alarm ring screen. */
// reason: each visual layer (bg image, dark overlay, gradient placeholder, time
// text, bottom button circle) is a single composable callsite; extracting would
// just push layout-state across more files for no readability gain.
@Suppress("LongMethod")
@Composable
fun RingScreenPreview(
    backgroundUri: String?,
    timeText: String,
    labelText: String,
    modifier: Modifier = Modifier,
    width: Dp = 100.dp,
    height: Dp = 178.dp,
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
    ) {
        val bitmapState = rememberBackgroundBitmap(backgroundUri)
        val bm = bitmapState.value
        if (backgroundUri != null && bm != null) {
            Image(
                bitmap = bm,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
            )
        } else {
            // Default placeholder — app gradient so preview looks intentional, not broken.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF5BB8C8),
                                Color(0xFFD08EC0),
                                Color(0xFF7898C8),
                            ),
                        ),
                    ),
            )
        }
        PhoneRingOverlay(
            timeText = timeText,
            labelText = labelText,
            awaitingWatch = false,
            onDismiss = null,
            onSnooze = null,
            showSnooze = true,
            scale = width.value / PHONE_REFERENCE_WIDTH_DP,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

private const val PHONE_REFERENCE_WIDTH_DP = 360f
