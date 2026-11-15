package com.bot4vps.mobile.data.model

fun primaryUnlinkWarning(primary: Boolean, deviceCount: Int?): String {
    if (!primary) return ""
    return "Вы отключаете основной телефон. " + when (deviceCount) {
        1 -> "Привязанных телефонов больше не останется."
        2 -> "Оставшийся телефон автоматически станет основным."
        else -> "Если останется один телефон, он автоматически станет основным. Если несколько — выберите основной в веб-панели, подтвердив выбор только паролем администратора."
    }
}
