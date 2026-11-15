package com.bot4vps.mobile.data.model

fun revokedPhoneNotification(origin: String): Pair<String, String> =
    "Доступ телефона отозван" to "Сервис: $origin\nТелефон отвязан от этого сервиса. Для подключения создайте новую связь. Локальный Authenticator остаётся доступен."
