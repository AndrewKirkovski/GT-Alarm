package com.kirkouski.gtalarm.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface WidgetRefresher {
    suspend fun refresh()
}

@Singleton
class GlanceWidgetRefresher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WidgetRefresher {
    override suspend fun refresh() {
        val manager = GlanceAppWidgetManager(context)
        val widget = NextAlarmGlanceWidget()
        manager.getGlanceIds(NextAlarmGlanceWidget::class.java).forEach { id ->
            widget.update(context, id)
        }
    }
}
