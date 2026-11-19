package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class XuiFormsTest {
    private fun credentials(password: String, username: String = "admin") = buildJsonObject { put("username", username); put("password", password); put("restart", true) }

    @Test fun credentialsUseOneFormAndKeepBothValuesInOneRequest() {
        val schema = xuiForm("change_credentials", JsonObject(emptyMap()), emptyMap())!!
        assertEquals(setOf("username", "password", "restart"), schema.properties().keys)
        assertEquals(setOf("username", "password"), schema.required())
        val values = credentials("  valid password  ", "new.login")
        assertNull(NativeForm.validate(schema, values))
        assertNull(xuiFormError("change_credentials", values))
        assertEquals(values, xuiParams("change_credentials", values, JsonObject(emptyMap())))
        assertEquals(JsonPrimitive("••••••"), NativeForm.redact(schema, values)!!.jsonObject["password"])
    }

    @Test fun passwordRulesMatchUnicodeLengthAndBcryptByteLimit() {
        for (password in listOf("short", "1234567\n", "a".repeat(73), "я".repeat(37), "😀".repeat(19), "😀".repeat(4))) {
            assertNotNull(password, xuiFormError("change_credentials", credentials(password)))
        }
        for (password in listOf("a".repeat(72), "я".repeat(36), "😀".repeat(18), "😀".repeat(8))) {
            assertNull(password, xuiFormError("change_credentials", credentials(password)))
        }
        assertNotNull(xuiFormError("change_credentials", credentials("good password", "ab")))
        assertNotNull(xuiFormError("change_credentials", credentials("good password", "bad:name")))
    }
    private val state = Json.parseToJsonElement("""{"port":2053,"cert":{"panel_engine":"certbot","domains":{"example.org":{},"ip":{}},"certbot_domains":["example.org","another.org"]}}""").jsonObject
    @Test fun sameDomainInTwoEnginesRemainsTwoChoices() {
        val options = xuiCertificates(state)
        assertEquals(4, options.size)
        assertEquals(2, options.count { it.domain == "example.org" })
        listOf("cert_renew", "cert_remove").forEach { action ->
            val params = xuiParams(action, buildJsonObject { put("certificate", "certbot|example.org") }, state)
            assertEquals("example.org", params.text("domain"))
            assertEquals("certbot", params.text("engine"))
            assertFalse(params.containsKey("certificate"))
        }
    }
    @Test fun missingOrRemovedCertificatesCannotBeSubmitted() {
        assertTrue(xuiCertificates(JsonObject(emptyMap())).isEmpty())
        assertTrue(runCatching { xuiParams("cert_renew", buildJsonObject { put("certificate", "acme|gone.org") }, state) }.isFailure)
    }
    @Test fun issuingUsesWebDefaultsAndRequiredDomain() {
        val schema = xuiForm("cert_issue_domain", state, emptyMap())!!
        val values = NativeForm.initial(schema)!!.jsonObject
        assertEquals(JsonPrimitive(80), values["port"])
        assertEquals(JsonPrimitive("certbot"), values["engine"])
        assertEquals(JsonPrimitive(true), values["set_panel"])
        assertNotNull(NativeForm.validate(schema, values))
        assertNull(NativeForm.validate(schema, JsonObject(values + ("domain" to JsonPrimitive("panel.example.org")))))
    }
    @Test fun bbrAndFakesiteDoNotExposeAnArbitraryParametersEditor() {
        assertTrue(xuiForm("set_bbr", state, emptyMap())!!.properties().isEmpty())
        val install = xuiForm("fakesite_install", state, emptyMap())!!
        assertEquals(setOf("domain"), install.properties().keys)
        assertEquals(JsonPrimitive(false), install["additionalProperties"])
        assertTrue(xuiForm("fakesite_remove", state, emptyMap())!!.properties().isEmpty())
    }
    @Test fun invalidIpPortAndDomainAreRejectedBeforeEnqueue() {
        assertNotNull(xuiFormError("cert_issue_ip", buildJsonObject { put("ip", "999.1.2.3") }))
        assertNotNull(xuiFormError("cert_issue_ip", buildJsonObject { put("ip", "1.2.3.4"); put("ipv6", "1:::2") }))
        assertNull(xuiFormError("cert_issue_ip", buildJsonObject { put("ip", "1.2.3.4"); put("ipv6", "2001:db8::1") }))
        assertNotNull(xuiFormError("change_port", buildJsonObject { put("port", 80) }))
        assertNotNull(xuiFormError("fakesite_install", buildJsonObject { put("domain", "https://site.org/path") }))
        assertNull(xuiFormError("fakesite_install", buildJsonObject { put("domain", "site.example.org") }))
    }
}
