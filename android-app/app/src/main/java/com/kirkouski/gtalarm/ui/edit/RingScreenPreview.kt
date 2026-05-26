package com.kirkouski.gtalarm.ui.edit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Miniature phone-frame preview of the alarm ring screen. */
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
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = timeText,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            if (labelText.isNotEmpty()) {
                Text(
                    text = labelText,
                    color = Color.White,
                    fontSize = 9.sp,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.35f)),
        )
    }
}
