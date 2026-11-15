package com.mindtravel.deepseekassistant.api.chat

import com.mindtravel.deepseekassistant.core.arrayOrEmpty
import com.mindtravel.deepseekassistant.core.objects
import com.mindtravel.deepseekassistant.core.stringOrNull
import com.mindtravel.deepseekassistant.feature.chat.model.SearchGateway
import com.mindtravel.deepseekassistant.feature.chat.model.SearchResult
import com.mindtravel.deepseekassistant.api.DeepSeekHttp
import org.json.JSONObject

class DeepSeekSearchApi(private val http: DeepSeekHttp) : SearchGateway {

    override fun search(apiKey: String, query: String, count: Int): List<SearchResult> {
        val body = JSONObject().apply {
            put("query", query.take(128))
            put("count", count)
            put("need_content", false)
        }
        val response = http.postJson("/v1/tools/search", apiKey, body)
        return response.arrayOrEmpty("results").objects().mapNotNull { item ->
            val url = item.stringOrNull("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = item.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: url
            val summary = item.stringOrNull("summary")?.takeIf { it.isNotBlank() }
                ?: item.stringOrNull("snippet")?.takeIf { it.isNotBlank() }
                ?: ""
            SearchResult(title = title, url = url, summary = summary)
        }
    }
}
