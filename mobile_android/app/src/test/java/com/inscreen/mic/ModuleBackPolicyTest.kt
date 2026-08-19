package com.inscreen.mic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleBackPolicyTest {
    @Test fun dispatches_a_cancelable_module_event() {
        assertTrue(ModuleBackPolicy.DISPATCH_SCRIPT.contains("inscreen:atras"))
        assertTrue(ModuleBackPolicy.DISPATCH_SCRIPT.contains("cancelable:true"))
    }

    @Test fun only_literal_true_is_consumed() {
        assertTrue(ModuleBackPolicy.wasConsumed("true"))
        assertFalse(ModuleBackPolicy.wasConsumed("false"))
        assertFalse(ModuleBackPolicy.wasConsumed(null))
    }
}
