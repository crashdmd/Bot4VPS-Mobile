package com.bot4vps.mobile.data.model

fun serverListDetail(server: ServerSummary, sort: ServerListSort): String = when (sort) {
    ServerListSort.NAME -> server.hostIp?.takeIf(String::isNotBlank) ?: server.host.orEmpty()
    ServerListSort.SSL -> when {
        server.certificateCheck == false -> "SSL: проверка выключена"
        server.sslStatus == "error" -> "SSL: ошибка проверки"
        server.sslDaysLeft != null -> when {
            server.sslDaysLeft < 0 -> "SSL: истёк ${-server.sslDaysLeft.toLong()} дн. назад"
            server.sslDaysLeft == 0 -> "SSL: истекает сегодня"
            else -> "SSL: осталось ${server.sslDaysLeft} дн."
        }
        server.sslStatus == "expired" -> "SSL: срок истёк"
        else -> "SSL: нет данных о сроке"
    }
    ServerListSort.UPTIME -> {
        val seconds = server.uptimeSeconds?.takeIf { it.isFinite() && it >= 0 }?.toLong()
        val duration = seconds?.let {
            val days = it / 86400; val hours = it % 86400 / 3600; val minutes = it % 3600 / 60
            when { days > 0 -> "$days д. $hours ч."; hours > 0 -> "$hours ч. $minutes мин."; minutes > 0 -> "$minutes мин."; else -> "меньше минуты" }
        } ?: server.uptime?.takeIf { it.isNotBlank() && it !in setOf("—", "N/A") } ?: "нет данных"
        "Время работы: $duration"
    }
    ServerListSort.GROUP -> server.group?.takeIf { it.isNotBlank() && it != "—" }?.let { "Группа: $it" } ?: "Без группы"
    ServerListSort.STATUS -> when {
        server.online == true && !server.sshError.isNullOrBlank() -> "В сети · ошибка SSH"
        server.online == true -> "Сервер доступен"
        server.online == false -> "Сервер недоступен"
        else -> "Состояние ещё не проверено"
    }
}
