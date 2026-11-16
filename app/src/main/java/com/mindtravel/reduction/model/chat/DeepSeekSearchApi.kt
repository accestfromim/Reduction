package com.mindtravel.reduction.model.chat

import com.mindtravel.reduction.core.arrayOrEmpty
import com.mindtravel.reduction.core.objects
import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.model.chat.SearchGateway
import com.mindtravel.reduction.model.chat.SearchResult
import com.mindtravel.reduction.model.DeepSeekHttp
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
