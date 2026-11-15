package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.ApiError
import com.bot4vps.mobile.data.api.errorText
import com.bot4vps.mobile.data.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhoneRoleMessageTest {
    @Test fun oldPanelRoleMessagesChangeWithoutRenamingTheUsersPhoneOrMainScreen() {
        val text = "Главный телефон отвязан. Телефон «Главный телефон» автоматически назначен главным. Откройте главное меню."
        val expected = "Основной телефон отвязан. Телефон «Главный телефон» автоматически назначен основным. Откройте главное меню."
        val request = securityRequest(buildJsonObject {
            put("id", "request"); put("message", text); put("target_name", "Главный телефон")
        })
        assertEquals(expected, request.message)
        assertEquals("Главный телефон", request.targetName)
        assertEquals("Доступно только основному телефону", errorText(ApiError(403, "Доступно только главному телефону")))
        assertEquals("Основное устройство", phoneRoleMessage("Главное устройство"))
        assertEquals("На главном экране", phoneRoleMessage("На главном экране"))
    }
}
