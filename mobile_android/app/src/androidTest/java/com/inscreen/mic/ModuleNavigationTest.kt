package com.inscreen.mic

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleNavigationTest {
    @Test fun sharedIntentTargetsSubjectWithoutSingleTopReuse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = ModuleHostActivity.intent(context, "materia-1")

        assertEquals(ModuleHostActivity::class.java.name, intent.component?.className)
        assertEquals("materia-1", intent.getStringExtra(ModuleHostActivity.EXTRA_SUBJECT_ID))
        assertFalse(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }
}
