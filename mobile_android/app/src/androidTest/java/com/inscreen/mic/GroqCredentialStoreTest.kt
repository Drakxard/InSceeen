package com.inscreen.mic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroqCredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = GroqCredentialStore(context)

    @After fun cleanUp() = store.clear()

    @Test fun encryptsReadsReplacesAndClearsCredentials() {
        store.clear()
        store.save("gsk-first-secret", "model-a")
        assertEquals("gsk-first-secret", store.apiKey())
        assertEquals("model-a", store.model())

        val rawPreferences = context.getSharedPreferences("groq_secure_settings", Context.MODE_PRIVATE)
        assertFalse(rawPreferences.all.values.any { it.toString().contains("gsk-first-secret") })
        assertTrue(rawPreferences.contains("api_key_ciphertext"))

        store.save("gsk-second-secret", "model-b")
        assertEquals("gsk-second-secret", store.apiKey())
        assertEquals("model-b", store.model())

        store.clear()
        assertNull(store.apiKey())
        assertNull(store.model())
    }
}
