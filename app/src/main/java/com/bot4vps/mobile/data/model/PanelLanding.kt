package com.bot4vps.mobile.data.model

/** A panel's availability never grants or removes access to local TOTP accounts. */
fun openAuthenticatorFirst(hasAddress: Boolean, hasDeviceKey: Boolean, revoked: Boolean): Boolean = !hasAddress || !hasDeviceKey || revoked
