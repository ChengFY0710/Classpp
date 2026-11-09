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
}
