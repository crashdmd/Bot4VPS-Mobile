package com.bot4vps.mobile.data.model

import com.bot4vps.mobile.data.api.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.util.Locale

@Serializable
internal data class SessionRates(
    @SerialName("rx_bps") val received: Long,
    @SerialName("tx_bps") val sent: Long,
)

@Serializable
internal data class SessionConnection(
    val id: String,
    @SerialName("device_ip") val deviceIp: String,
    @SerialName("device_name") val deviceName: String? = null,
    val name: String? = null,
    val domain: String? = null,
    @SerialName("destination_ip") val destinationIp: String? = null,
    @SerialName("observed_ip") val observedIp: String? = null,
    @SerialName("via_fake_ip") val viaFakeIp: Boolean,
    @SerialName("rx_bps") val received: Long,
    @SerialName("tx_bps") val sent: Long,
)

internal fun List<SessionConnection>.filterSessions(
    deviceIp: String, destination: String, viaFakeIp: Boolean?,
): List<SessionConnection> {
    val destinationTerm = destination.trim()
    return filter { row ->
        (viaFakeIp == null || row.viaFakeIp == viaFakeIp) &&
            (deviceIp.isEmpty() || row.deviceIp == deviceIp) &&
            (destinationTerm.isEmpty() || row.name?.contains(destinationTerm, ignoreCase = true) == true ||
                row.domain?.contains(destinationTerm, ignoreCase = true) == true ||
                row.destinationIp?.contains(destinationTerm, ignoreCase = true) == true ||
                row.observedIp?.contains(destinationTerm, ignoreCase = true) == true)
    }
}

@Serializable
internal data class ActiveSessionSample(
    val status: String,
    val wan: SessionRates? = null,
    val total: SessionRates? = null,
    val connections: List<SessionConnection>? = null,
    val offloaded: Boolean = false,
    val truncated: Boolean = false,
)

internal class SessionHistory {
    private val entries = linkedMapOf<String, SessionConnection>()

    fun record(connections: List<SessionConnection>): List<SessionConnection> {
        for (connection in connections) {
            val name = connection.deviceName ?: entries[connection.id]?.deviceName
            entries[connection.id] = connection.copy(deviceName = name)
        }
        return entries.values.toList()
    }
}

internal fun sessionDeviceLabel(ip: String, name: String?): String =
    if (name.isNullOrBlank()) ip else "$name ($ip)"

internal fun List<SessionConnection>.sessionDevices(): Map<String, String?> {
    val devices = linkedMapOf<String, String?>()
    for (row in this) {
        if (!row.deviceName.isNullOrBlank() || row.deviceIp !in devices) devices[row.deviceIp] = row.deviceName
    }
    return devices
}

internal sealed interface ActiveSessionUpdate {
    data class Received(val sample: ActiveSessionSample) : ActiveSessionUpdate
    data object Unsupported : ActiveSessionUpdate
    data object Unavailable : ActiveSessionUpdate
}

internal fun activeSessionUpdates(load: suspend () -> ActiveSessionSample) = flow {
    while (true) {
        val update = try {
            ActiveSessionUpdate.Received(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (e.status == 404) {
                emit(ActiveSessionUpdate.Unsupported)
                return@flow
            }
            ActiveSessionUpdate.Unavailable
        } catch (_: Exception) {
            ActiveSessionUpdate.Unavailable
        }
        emit(update)
        val retrySlowly = update == ActiveSessionUpdate.Unavailable ||
            (update as? ActiveSessionUpdate.Received)?.sample?.status == "integration"
        delay(if (retrySlowly) 10_000L else 3_000L)
    }
}

internal fun sessionSpeed(bits: Long?): String {
    if (bits == null) return "—"
    if (bits in 1L..99_999L) return "<0,1"
    return NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        isGroupingUsed = false
    }.format(bits / 1_000_000.0)
}
