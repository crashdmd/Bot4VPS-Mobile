package com.bot4vps.mobile.data.model

enum class ServerListSort(val title: String) {
    NAME("Имя"), STATUS("Статус"), SSL("SSL"), UPTIME("Uptime"), GROUP("Группа")
}

/** Applied when the user changes sorting; live updates keep row positions. */
fun sortServerList(servers: List<ServerSummary>, key: ServerListSort, descending: Boolean): List<ServerSummary> {
    val comparator: Comparator<ServerSummary> = when (key) {
        ServerListSort.NAME -> compareBy { (it.name ?: it.id).lowercase() }
        ServerListSort.STATUS -> compareBy { when (it.online) { true -> 0; false -> 1; null -> 2 } }
        ServerListSort.SSL -> compareBy { it.sslDaysLeft ?: Int.MIN_VALUE }
        ServerListSort.UPTIME -> compareBy { it.uptimeSeconds ?: -1.0 }
        ServerListSort.GROUP -> compareBy { it.group.orEmpty().lowercase() }
    }
    return servers.sortedWith(if (descending) comparator.reversed() else comparator)
}
