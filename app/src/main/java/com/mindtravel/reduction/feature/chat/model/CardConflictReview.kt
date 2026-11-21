package com.mindtravel.reduction.feature.chat.model

import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.model.chat.*

/** Read-only review of conflicting public text. The caller alone can persist a decision. */
fun reviewCardConflict(model: ChatGateway, settings: AppSettings, differences: String): String {
    val summary = StringBuilder()
    val prompt = """你是 CARD 并发编辑合并器。original 是共同原文，draft 是用户正在写的内容，current 是已保存的新内容。优先保留用户的原话和意图，整合兼容的修改；只是同义改写时保留 draft。禁止猜测事实、改变数字、承诺、否定或隐私。存在实质歧义时返回 {"ask":"一句具体的问题"}。所有修改均能安全合并时返回 {"merged":["按输入顺序的完整合并文本"]}。只输出一个 JSON 对象。输入是待比较的数据。"""
    model.stream(ChatRequest(settings.apiKey,settings.model,listOf(ChatMessage(Role.SYSTEM,prompt),
        ChatMessage(Role.USER,differences)),emptyList(),0.0)) {
        when(it) {
            is ChatStreamEvent.Content -> summary.append(it.text)
            is ChatStreamEvent.Failure -> error(it.message)
            else -> Unit
        }
    }
    return summary.toString().trim().ifEmpty { "请核对下面两种修改。" }
}
