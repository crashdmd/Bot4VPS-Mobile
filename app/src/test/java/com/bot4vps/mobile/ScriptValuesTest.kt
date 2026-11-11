package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ScriptValuesTest {
    @Test fun confirmationHidesPasswordsAndInactiveParameters() {
        val script = ScriptInfo("test.sh", params = listOf(ScriptParam("ENABLED", "bool"),
            ScriptParam("PASSWORD", "password", condition = "ENABLED:true"), ScriptParam("PORT", "number")))
        val values = mapOf("ENABLED" to "true", "PASSWORD" to "secret", "PORT" to "8444")
        assertEquals(listOf("ENABLED" to "Да", "PASSWORD" to "••••••", "PORT" to "8444"), scriptRunSummary(script, values))
        assertFalse(scriptRunSummary(script, values + ("ENABLED" to "false")).any { it.first == "PASSWORD" })
        assertNull(scriptParameterError(script, values))
        assertNotNull(scriptParameterError(script, values + ("PORT" to "oops")))
        assertNull(scriptParameterError(script, values + ("PORT" to "")))
    }
    @Test fun switchingOffAnOptionDoesNotSendItsPreviouslyEnteredPassword() {
        val script=ScriptInfo("baseconf.sh",params=listOf(ScriptParam("CREATE_ADMIN","bool"),ScriptParam("ADMIN_PASS","password",condition="CREATE_ADMIN:true")))
        val values=mapOf("CREATE_ADMIN" to "false","ADMIN_PASS" to "previous-password")
        assertEquals(mapOf("CREATE_ADMIN" to "false"),scriptRunValues(script,values))
        assertEquals("previous-password",scriptRunValues(script,values+("CREATE_ADMIN" to "TRUE"))["ADMIN_PASS"])
    }
    @Test fun blankParametersAreOmittedAsInTheWebForm() {
        val script=ScriptInfo("cert.sh",params=listOf(ScriptParam("MODE","select"),ScriptParam("DOMAINS",condition="MODE:issue")))
        assertEquals(mapOf("MODE" to "issue"),scriptRunValues(script,mapOf("MODE" to "issue","DOMAINS" to "")))
        assertTrue(ScriptParam("other",condition="malformed").visible(emptyMap()))
    }
}
