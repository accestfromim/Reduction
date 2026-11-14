package com.mindtravel.deepseekassistant.feature.chat.model

data class SearchResult(
    val title: String,
    val url: String,
    val summary: String
)

interface SearchGateway {
    fun search(apiKey: String, query: String, count: Int = 5): List<SearchResult>
}
