package com.bot4vps.mobile.data.model

import java.security.MessageDigest
import java.util.UUID

/** Describes ownership without retaining the copied secret in app state. */
class SecretClipboardLease(value: String, val expiresAt: Long, val id: String = UUID.randomUUID().toString()) {
    private val fingerprint = digest(value)
    fun expired(now: Long): Boolean = now >= expiresAt
    fun owns(copyId: String?, value: String?): Boolean = copyId == id && value != null &&
        MessageDigest.isEqual(fingerprint, digest(value))
    companion object { private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)) }
}
