package com.bot4vps.mobile.data.local

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class DeviceNameProvider @Inject constructor(@ApplicationContext private val context: Context) {
    fun name(): String = resolveDeviceName(runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull(), Build.MANUFACTURER, Build.MODEL)
}

internal fun resolveDeviceName(configured: String?, manufacturer: String?, model: String?): String {
    fun clean(value: String?) = value.orEmpty().filter { it.code >= 32 && it.code != 127 }.trim().take(80)
    val custom = clean(configured)
    if (custom.isNotEmpty()) return custom
    val maker = clean(manufacturer).takeUnless { it.equals("unknown", true) }.orEmpty()
    val product = clean(model).takeUnless { it.equals("unknown", true) }.orEmpty()
    return (if (product.startsWith(maker, true)) product else listOf(maker, product).filter { it.isNotEmpty() }.joinToString(" ")).take(80).ifBlank { "Android" }
}
