package com.mindtravel.deepseekassistant.ui.bridge

import android.webkit.WebView
import org.json.JSONObject

class JsEmitter(private val webView: WebView) {

    fun emit(function: String, vararg args: Any?) {
        val arguments = args.joinToString(",") { encode(it) }
        val script = "window.Native && window.Native.$function($arguments);"
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
