package com.openminis.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-sandbox-keepalive-open] The overlay tap-to-open / deep link only
 * accepts chat-scoped ids; FGS keep-alive keys must be rejected or the user
 * lands on a blank conversation.
 */
class SessionKeyPolicyTest {

    @Test
    fun `keep-alive keys are not chat scoped`() {
        assertFalse(SessionActivityTracker.isChatScopedSessionId("sandbox:abc"))
        assertFalse(SessionActivityTracker.isChatScopedSessionId("sandbox:e6eac1b3"))
    }

    @Test
    fun `real sessions and drafts stay chat scoped`() {
        assertTrue(
            SessionActivityTracker.isChatScopedSessionId("e6eac1b3-8087-47f6-8fd2-09e1e4cb223a"),
        )
        assertTrue(SessionActivityTracker.isChatScopedSessionId("__new__x"))
    }

    @Test
    fun `blank ids are never chat scoped`() {
        assertFalse(SessionActivityTracker.isChatScopedSessionId(null))
        assertFalse(SessionActivityTracker.isChatScopedSessionId(""))
        assertFalse(SessionActivityTracker.isChatScopedSessionId("   "))
    }
}
