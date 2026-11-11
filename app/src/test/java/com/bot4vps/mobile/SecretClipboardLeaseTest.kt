package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.SecretClipboardLease
import org.junit.Assert.*
import org.junit.Test

class SecretClipboardLeaseTest {
    @Test fun copyExpiresAtTheOriginalDisplayDeadline() {
        val lease = SecretClipboardLease("master-secret", 30_000)
        assertFalse(lease.expired(29_999)); assertTrue(lease.expired(30_000))
        assertTrue(lease.owns(lease.id, "master-secret"))
        assertFalse(lease.toString().contains("master-secret"))
    }
    @Test fun replacedContentOrNewCopyOfTheSameValueIsNeverErased() {
        val lease = SecretClipboardLease("master-secret", 30_000)
        assertFalse(lease.owns(lease.id, "another-value"))
        assertFalse(lease.owns("replacement-copy", "master-secret"))
        assertFalse(lease.owns(null, "master-secret")); assertFalse(lease.owns(lease.id, null))
    }
}
