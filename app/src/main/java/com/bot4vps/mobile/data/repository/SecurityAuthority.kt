package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.model.SecurityRequest

internal fun mayConfirmSecurity(snapshot: SecuritySnapshot, request: SecurityRequest): Boolean = when {
    snapshot.supported != true || request.informational -> false
    request.purpose == "primary_transfer" -> true // Explicit admin-authorized acceptance on the new phone.
    request.purpose == "login" && !snapshot.twoFactorEnabled -> true
    request.purpose in setOf("login", "unlink", "unlink_other", "two_factor_enable", "two_factor_disable", "protection_disable") -> snapshot.isPrimary
    else -> false
}
