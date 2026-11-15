package com.mindtravel.deepseekassistant.feature.pc.model

import org.json.JSONArray
import org.json.JSONObject

data class PcInfo(
    val hostname: String = "",
    val os: String = "",
    val user: String = "",
    val ips: List<String> = emptyList(),
    val time: String = "",
    val hostKey: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("hostname", hostname)
        put("os", os)
        put("user", user)
        put("ips", JSONArray(ips))
        put("time", time)
        put("hostKey", hostKey)
    }
}
