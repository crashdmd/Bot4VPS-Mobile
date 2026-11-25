package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.ui.gateways.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GatewayFormsTest {
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private val hash = "a".repeat(64)
    private fun config(value: String = "{}") = JsonObject(obj(value) + mapOf("config_hash" to JsonPrimitive(hash), "settings_hash" to JsonPrimitive(hash)))
    private fun values(form: GatewayForm) = form.initial.mapValues { gatewayText(it.value) } + form.fields.associate { it.key to (form.initial[it.key]?.let(::gatewayText) ?: it.initial) }

    @Test fun disabledFeatureSuppressesRetainedGatewayRows() {
        val inventory = decodeGateways(obj("""{"enabled":false,"gateways":[{"id":"router","name":"Old"}]}"""))
        assertFalse(inventory.enabled); assertTrue(inventory.gateways.isEmpty())
    }
    @Test fun enabledEmptyFeatureKeepsNavigationAvailable() {
        assertTrue(decodeGateways(obj("""{"enabled":true,"gateways":[]}""")).enabled)
    }
    @Test fun staleAndFailedSamplesNeverEnableOperations() {
        for (monitor in listOf("""{"ok":false,"capabilities":{"controls":{"available":true}}}""", """{"ok":true,"stale":true,"capabilities":{"controls":{"available":true}}}""")) {
            val node = Gateway(obj("""{"id":"r","monitor":$monitor}""")); assertFalse(node.controls); assertFalse(node.online)
        }
        assertTrue(Gateway(obj("""{"id":"r","monitor":{"ok":true,"capabilities":{"controls":{"available":true}}}}""")).controls)
    }
    @Test fun unknownOptionalCapabilitiesDoNotImplyManagementAccess() {
        assertFalse(Gateway(obj("""{"id":"r","monitor":{"ok":true}}""")).controls)
    }
    @Test fun staticIpv4AndWireguardSettingsNeverMix() {
        val input = mapOf("proto" to "static", "ipaddr" to "192.168.1.1", "netmask" to "255.255.255.0", "private_key" to "private", "password" to "password", "addresses" to "10.0.0.1/24")
        val output = gatewayInterfaceSettings(input)
        assertEquals("192.168.1.1", output.text("ipaddr")); assertFalse(output.containsKey("private_key")); assertFalse(output.containsKey("password")); assertFalse(output.containsKey("addresses"))
    }
    @Test fun dhcpDropsOldStaticAndPppoeCredentials() {
        val output = gatewayInterfaceSettings(mapOf("proto" to "dhcp", "ipaddr" to "192.168.1.1", "password" to "old", "username" to "old", "dns" to "1.1.1.1, 8.8.8.8"))
        assertFalse(output.containsKey("ipaddr")); assertFalse(output.containsKey("password")); assertFalse(output.containsKey("username"))
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), output.strings("dns"))
    }
    @Test fun emptyPppoeAndWireguardSecretsPreserveExistingKeys() {
        for (proto in listOf("wireguard", "pppoe")) {
            val output = gatewayInterfaceSettings(mapOf("proto" to proto, "password" to "", "private_key" to "", "has_private_key" to "true", "has_password" to "true", "addresses" to "10.0.0.1/24"))
            assertFalse(output.containsKey("password")); assertFalse(output.containsKey("private_key")); assertFalse(output.containsKey("has_password")); assertFalse(output.containsKey("has_private_key"))
        }
    }
    @Test fun wirelessUnchangedPasswordAndNetworkAreNotTransmitted() {
        val original = obj("""{"ssid":"Home","encryption":"psk2","network":["lan"],"has_key":true}""")
        val changes = gatewayWirelessChanges(mapOf("ssid" to "Home", "encryption" to "psk2", "network" to "lan", "key" to ""), original, listOf("ssid", "encryption", "network", "key"))
        assertTrue(changes.isEmpty())
    }
    @Test fun disablingWirelessEncryptionClearsKeyExplicitly() {
        val changes = gatewayWirelessChanges(mapOf("encryption" to "none", "key" to ""), obj("""{"encryption":"sae"}"""), listOf("encryption", "key"))
        assertEquals(JsonNull, changes["key"]); assertEquals("none", changes.text("encryption"))
    }
    @Test fun aNewWirelessPasswordIsIncludedOnlyWhenEntered() {
        val changes = gatewayWirelessChanges(mapOf("key" to "new-password"), obj("""{"has_key":true}"""), listOf("key"))
        assertEquals("new-password", changes.text("key"))
    }
    @Test fun wirelessChangesKeepTheSnapshotHashAndOriginalInterfaceIds() {
        val radio = obj("""{"name":"radio0","channel":"auto","disabled":"0","htmode":"HE20","interfaces":[{"id":"default_radio0","ssid":"Home","encryption":"psk2","network":["lan"]}]}""")
        val form = gatewayWifiForm(config(), radio)
        val group = form.groups.single()
        val row = group.rows.single().mapValues { gatewayText(it.value) } + group.fields.associate { it.key to (group.rows.single()[it.key]?.let(::gatewayText) ?: it.initial) }
        val result = form.result(values(form) + ("channel" to "6"), mapOf("interfaces" to listOf(row)))
        assertEquals(hash, result.text("config_hash")); assertEquals("radio0", result.text("radio")); assertEquals("6", (result["settings"] as JsonObject).text("channel"))
        assertEquals("default_radio0", result.objects("interfaces").single().text("id")); assertFalse((result.objects("interfaces").single()["settings"] as JsonObject).containsKey("key"))
    }
    @Test fun emptyWireguardPeerIdAndPresharedKeyAreOmitted() {
        val output = gatewayFields(mapOf("id" to "", "public_key" to "public", "preshared_key" to "", "allowed_ips" to "0.0.0.0/0, ::/0"), gatewayPeerFields, setOf("allowed_ips"), setOf("id", "preshared_key"))
        assertFalse(output.containsKey("id")); assertFalse(output.containsKey("preshared_key")); assertEquals(listOf("0.0.0.0/0", "::/0"), output.strings("allowed_ips"))
    }
    @Test fun routingUsesOnlyUserEditableFieldsAndRetainsHash() {
        val form = gatewayRoutesForm(config())
        val result = form.result(emptyMap(), mapOf("routes" to listOf(mapOf("id" to "cfg001", "interface" to "lan", "target" to "192.168.2.0/24", "onlink" to "1", "unknown" to "must-not-send"))))
        assertEquals(hash, result.text("config_hash")); assertFalse(result.objects("routes").single().containsKey("id")); assertFalse(result.objects("routes").single().containsKey("unknown"))
    }
    @Test fun dnsAndDhcpUseSettingsHashAndSeparatePayloads() {
        val state = config("""{"config_hash":"network-hash","settings_hash":"settings-hash"}""")
        val dns = gatewayDnsForm(state, obj("""{"id":"dns0"}"""))
        val payload = dns.result(values(dns) + mapOf("server" to "1.1.1.1\n127.0.0.1#5353"), emptyMap())
        assertEquals(state.text("settings_hash"), payload.text("config_hash")); assertEquals("dns0", payload.text("section")); assertEquals(listOf("1.1.1.1", "127.0.0.1#5353"), (payload["settings"] as JsonObject).strings("server"))
        val dhcp = gatewayDhcpForm(state, "lan"); val data = dhcp.result(values(dhcp), emptyMap())
        assertEquals("lan", data.text("interface")); assertFalse((data["settings"] as JsonObject).containsKey("server"))
    }
    @Test fun firewallDeletesOnlyEditableRulesAndDoesNotTransmitSpecialTargets() {
        val state = config("""{"firewall":{"defaults":{"input":"REJECT","output":"ACCEPT","forward":"REJECT"},"zones":[],"rules":[{"id":"editable","name":"SSH","proto":"tcp","target":"ACCEPT"},{"id":"special","target":"MARK","set_mark":"123"}]}}""")
        val form = gatewayFirewallForm(state)
        assertEquals(listOf("editable"), form.groups.first { it.key == "rules" }.rows.map { it.text("id") })
        val result = form.result(values(form), mapOf("zones" to emptyList(), "rules" to emptyList()))
        assertEquals(listOf("editable"), result.strings("deleted_rules")); assertTrue(result.objects("rules").isEmpty())
    }
    @Test fun firewallCombinesTcpAndUdpAsProtocolArray() {
        val state = config("""{"firewall":{"defaults":{},"zones":[],"rules":[]}}"""); val form = gatewayFirewallForm(state)
        val result = form.result(values(form), mapOf("zones" to emptyList(), "rules" to listOf(mapOf("name" to "Test", "src" to "lan", "proto" to "tcp udp", "target" to "ACCEPT", "enabled" to "1"))))
        assertEquals(listOf("tcp", "udp"), result.objects("rules").single().strings("proto"))
    }
    @Test fun sshKeyAuthenticationAndPortKeepApiTypes() {
        val form = gatewaySshForm(keys = listOf("router.key"), add = true)
        val data = form.result(values(form) + mapOf("host" to "192.168.1.1", "auth_type" to "key", "key_path" to "router.key", "name" to ""), emptyMap())
        assertEquals(22, data["port"]?.jsonPrimitive?.int); assertEquals("key", data.text("auth_type")); assertEquals("router.key", data.text("key_path"))
        assertEquals(JsonPrimitive(""), data["name"])
    }
    @Test fun firewallRedirectChangesPreserveOtherNatSectionsAndCarryBothProtocols() {
        val form = gatewayFirewallForm(config("""{"firewall":{"defaults":{},"zones":[],"rules":[],"redirects":[{"id":"old","target":"DNAT","src_dport":"443","dest_ip":"192.168.1.10"},{"id":"special","target":"SNAT"}]}}"""))
        assertEquals(listOf("old"), form.groups.first { it.key == "redirects" }.rows.map { it.text("id") })
        val result = form.result(values(form), mapOf("redirects" to listOf(mapOf("name" to "Device", "src" to "wan", "dest" to "lan", "proto" to "tcp udp", "src_dport" to "8443", "dest_ip" to "192.168.1.10", "dest_port" to "443", "enabled" to "1"))))
        assertEquals(listOf("old"), result.strings("deleted_redirects"))
        val row = result.objects("redirects").single()
        assertEquals(listOf("tcp", "udp"), row.strings("proto")); assertEquals("8443", row.text("src_dport")); assertEquals("443", row.text("dest_port"))
        assertFalse(row.containsKey("target")); assertFalse(row.containsKey("id"))
    }
    @Test fun anOlderGatewayCannotSilentlyReceiveIgnoredPortForwardChanges() {
        val form = gatewayFirewallForm(config("""{"firewall":{"defaults":{},"zones":[],"rules":[]}}"""))
        assertFalse(form.groups.first { it.key == "redirects" }.add)
        val payload = form.result(values(form), emptyMap())
        assertFalse(payload.containsKey("redirects")); assertFalse(payload.containsKey("deleted_redirects"))
    }
    @Test fun manualApiRejectsRootAndMalformedPinsBeforeRequest() {
        val form = gatewayAccessForm(); val good = values(form) + mapOf("url" to "https://router.local/ubus", "username" to "bot4vps_phone", "password" to "secret")
        assertTrue(runCatching { form.result(good + ("username" to "root"), emptyMap()) }.isFailure)
        assertTrue(runCatching { form.result(good + ("tls_sha256" to "bad"), emptyMap()) }.isFailure)
        assertTrue(runCatching { form.result(good + ("url" to "https://root:secret@router.local/ubus"), emptyMap()) }.isFailure)
        assertEquals(JsonPrimitive(""), form.result(good, emptyMap())["name"])
    }
    @Test fun taskStatesDistinguishWarningsFailuresAndPendingResults() {
        for (status in listOf("success", "success_warn")) { val task = obj("""{"status":"$status"}"""); assertTrue(gatewayTaskFinished(task)); assertTrue(gatewayTaskSucceeded(task)) }
        for (status in listOf("failed", "cancelled")) { val task = obj("""{"status":"$status"}"""); assertTrue(gatewayTaskFinished(task)); assertFalse(gatewayTaskSucceeded(task)) }
        assertFalse(gatewayTaskFinished(obj("""{"status":"running"}""")))
    }
}
