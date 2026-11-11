package com.bot4vps.mobile.data.model

data class JournalFrame(val event: String, val data: String)
class JournalDecoder {
    private var event = "message"
    private val data = mutableListOf<String>()
    fun line(line: String): JournalFrame? {
        if(line.isEmpty()) {
            val frame = if(data.isEmpty()) null else JournalFrame(event, data.joinToString("\n"))
            event = "message"; data.clear(); return frame
        }
        if(line.startsWith(':')) return null
        val name = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when(name) { "event" -> event = value; "data" -> data += value }
        return null
    }
}
