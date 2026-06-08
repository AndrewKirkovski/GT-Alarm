package com.kirkouski.gtwake.companion.ui.nav

import com.kirkouski.gtwake.companion.ui.edit.AlarmMode

object Routes {
    const val LIST = "list"
    const val EDIT = "edit"
    const val EDIT_ARG_ID = "alarmId"
    const val EDIT_ARG_MODE = "mode"
    const val EDIT_WITH_ARG = "$EDIT?$EDIT_ARG_ID={$EDIT_ARG_ID}&$EDIT_ARG_MODE={$EDIT_ARG_MODE}"
    const val HELP = "help"
    const val SETTINGS = "settings"
    fun edit(id: Long?, mode: AlarmMode = AlarmMode.ABSOLUTE): String {
        val idPart = if (id != null) "$EDIT_ARG_ID=$id&" else ""
        return "$EDIT?${idPart}$EDIT_ARG_MODE=${mode.name}"
    }
}
