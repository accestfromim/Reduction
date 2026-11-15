package com.mindtravel.deepseekassistant.api

import com.mindtravel.deepseekassistant.api.chat.DeepSeekChatGateway
import com.mindtravel.deepseekassistant.api.chat.DeepSeekSearchApi
import com.mindtravel.deepseekassistant.api.pc.PcClient
import com.mindtravel.deepseekassistant.api.voice.DeepSeekAudioApi
import com.mindtravel.deepseekassistant.core.audio.AudioGateway
import com.mindtravel.deepseekassistant.feature.chat.model.ChatGateway
import com.mindtravel.deepseekassistant.feature.chat.model.SearchGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcGateway

class ApiGraph {
    private val http = DeepSeekHttp()

    val chatGateway: ChatGateway = DeepSeekChatGateway(http)
    val searchGateway: SearchGateway = DeepSeekSearchApi(http)
    val audioGateway: AudioGateway = DeepSeekAudioApi(http)
    val pcGateway: PcGateway = PcClient()
}
