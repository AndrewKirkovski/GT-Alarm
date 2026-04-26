package com.kirkouski.gtalarm.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.unit.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import android.text.format.DateFormat
import com.kirkouski.gtalarm.MainActivity
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.data.AlarmRepository
import com.kirkouski.gtalarm.domain.NextTriggerCalculator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NextAlarmGlanceWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun repository(): AlarmRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        ).repository()
        val nextLabel = computeNext(context, repo)

        provideContent {
            WidgetContent(nextLabel = nextLabel)
        }
    }

    private suspend fun computeNext(context: Context, repo: AlarmRepository): String {
        val enabled = repo.getAll().filter { it.enabled }
        if (enabled.isEmpty()) return ""
        val (alarm, soonest) = enabled
            .map { it to NextTriggerCalculator.nextTriggerEpochMillis(it) }
            .minByOrNull { it.second } ?: return ""
        val pattern = if (DateFormat.is24HourFormat(context)) "EEE HH:mm" else "EEE h:mm a"
        val locale = context.resources.configuration.locales[0]
        val formatter = DateTimeFormatter.ofPattern(pattern, locale)
        val time = Instant.ofEpochMilli(soonest).atZone(ZoneId.systemDefault()).format(formatter)
        return if (alarm.label.isBlank()) time else "$time · ${alarm.label}"
    }
}

@Composable
private fun WidgetContent(nextLabel: String) {
    val context = LocalContext.current
    val fg = ColorProvider(Color.White)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFF121212)))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = context.getString(R.string.widget_label),
            style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = nextLabel.ifBlank { context.getString(R.string.widget_next_alarm_none) },
            style = TextStyle(color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(8.dp))
        Button(
            text = context.getString(R.string.add_alarm),
            onClick = actionStartActivity(
                Intent(context, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_DEEP_LINK_SCREEN, MainActivity.SCREEN_ADD)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            ),
        )
    }
}
