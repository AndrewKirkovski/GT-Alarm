package com.kirkouski.gtwake.companion.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaChoiceTest {

    @Test
    fun `alarm with its own sound can remove it`() {
        val choice = alarmSoundChoice("content://own", "Forest", "content://def", "Rain")
        assertEquals(SoundChoice.Own("Forest"), choice)
        assertTrue(choice.removable)
    }

    @Test
    fun `alarm without a sound names the default and has nothing to remove`() {
        val choice = alarmSoundChoice(null, null, "content://def", "Rain")
        assertEquals(SoundChoice.Default("Rain"), choice)
        assertFalse(choice.removable)
    }

    @Test
    fun `alarm with no sound and no default uses the system sound`() {
        val choice = alarmSoundChoice(null, null, null, null)
        assertEquals(SoundChoice.System, choice)
        assertFalse(choice.removable)
    }

    @Test
    fun `a default sound set in Settings can be removed`() {
        val choice = defaultSoundChoice("content://def", "Rain")
        assertEquals(SoundChoice.Own("Rain"), choice)
        assertTrue(choice.removable)
    }

    @Test
    fun `no default sound in Settings shows the system sound`() {
        val choice = defaultSoundChoice(null, null)
        assertEquals(SoundChoice.System, choice)
        assertFalse(choice.removable)
    }

    @Test
    fun `alarm with its own picture can remove it`() {
        val choice = alarmPictureChoice("content://own", "content://def")
        assertEquals(PictureChoice.Own, choice)
        assertTrue(choice.removable)
    }

    @Test
    fun `alarm using the default picture has nothing to remove`() {
        val choice = alarmPictureChoice(null, "content://def")
        assertEquals(PictureChoice.Default, choice)
        assertFalse(choice.removable)
    }

    @Test
    fun `no picture anywhere has nothing to remove`() {
        assertEquals(PictureChoice.None, alarmPictureChoice(null, null))
        assertEquals(PictureChoice.None, defaultPictureChoice(null))
        assertFalse(PictureChoice.None.removable)
    }

    @Test
    fun `a default picture set in Settings can be removed`() {
        val choice = defaultPictureChoice("content://def")
        assertEquals(PictureChoice.Set, choice)
        assertTrue(choice.removable)
    }
}
