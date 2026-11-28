package com.fangyi.classpp.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPreferencesTest {

    @Test
    fun `auto complete defaults off and can toggle`() {
        val preferences = MutableSyncPreferences()

        assertFalse(preferences.autoCompleteOnVanish.value)
        preferences.setAutoCompleteOnVanish(true)
        assertTrue(preferences.autoCompleteOnVanish.value)
        preferences.setAutoCompleteOnVanish(false)
        assertFalse(preferences.autoCompleteOnVanish.value)
    }

    @Test
    fun `homework notes default on and can toggle`() {
        val preferences = MutableSyncPreferences()

        assertTrue(preferences.syncHomeworkNotes.value)
        preferences.setSyncHomeworkNotes(false)
        assertFalse(preferences.syncHomeworkNotes.value)
        preferences.setSyncHomeworkNotes(true)
        assertTrue(preferences.syncHomeworkNotes.value)
    }

    @Test
    fun `todo pull sync defaults off and can toggle`() {
        val preferences = MutableSyncPreferences()

        assertFalse(preferences.todoPullSync.value)
        preferences.setTodoPullSync(true)
        assertTrue(preferences.todoPullSync.value)
        preferences.setTodoPullSync(false)
        assertFalse(preferences.todoPullSync.value)
    }
}
