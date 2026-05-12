package com.kirkouski.gtalarm.ui.nav

object Routes {
    const val LIST = "list"
    const val EDIT = "edit"
    const val EDIT_ARG_ID = "alarmId"
    const val EDIT_WITH_ARG = "$EDIT?$EDIT_ARG_ID={${EDIT_ARG_ID}}"
    const val HELP = "help"
    fun edit(id: Long?): String = if (id == null) EDIT else "$EDIT?$EDIT_ARG_ID=$id"
}
