package com.bot4vps.mobile.data.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

class MobileAccessRevoked(val origin: String) : java.io.IOException("Доступ этого телефона отозван в панели. Для подключения создайте новую привязку в Web.")

@Singleton
class MobileAccessStatus @Inject constructor() {
    private val mutable = MutableStateFlow<String?>(null)
    val revoked = mutable.asStateFlow()
    fun report(origin: String) { mutable.value = origin }
    fun clear() { mutable.value = null }
}
