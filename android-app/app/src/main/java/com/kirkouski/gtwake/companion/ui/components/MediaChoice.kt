package com.kirkouski.gtwake.companion.ui.components

/**
 * What a sound row shows. Pure, so every row state in docs/ui-design-rules.md
 * has a unit test. Only [Own] can be removed: the other two are fallbacks.
 */
sealed interface SoundChoice {
    data class Own(val name: String?) : SoundChoice
    data class Default(val name: String?) : SoundChoice
    data object System : SoundChoice
}

val SoundChoice.removable: Boolean get() = this is SoundChoice.Own

fun alarmSoundChoice(
    audioUri: String?,
    audioName: String?,
    defaultUri: String?,
    defaultName: String?,
): SoundChoice = when {
    audioUri != null -> SoundChoice.Own(audioName)
    defaultUri != null -> SoundChoice.Default(defaultName)
    else -> SoundChoice.System
}

fun defaultSoundChoice(uri: String?, name: String?): SoundChoice =
    if (uri != null) SoundChoice.Own(name) else SoundChoice.System

/** What a picture row shows. [Own] and [Set] can be removed. */
enum class PictureChoice { Own, Default, Set, None }

val PictureChoice.removable: Boolean get() = this == PictureChoice.Own || this == PictureChoice.Set

fun alarmPictureChoice(uri: String?, defaultUri: String?): PictureChoice = when {
    uri != null -> PictureChoice.Own
    defaultUri != null -> PictureChoice.Default
    else -> PictureChoice.None
}

fun defaultPictureChoice(uri: String?): PictureChoice =
    if (uri != null) PictureChoice.Set else PictureChoice.None
