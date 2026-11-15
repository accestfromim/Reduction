package com.mindtravel.entropy.model

import com.mindtravel.entropy.core.audio.AudioGateway
import com.mindtravel.entropy.model.chat.ChatGateway
import com.mindtravel.entropy.model.chat.DeepSeekChatGateway
import com.mindtravel.entropy.model.chat.DeepSeekSearchApi
import com.mindtravel.entropy.model.chat.SearchGateway
import com.mindtravel.entropy.model.voice.DeepSeekAudioApi

/** The remote models: chat, search, speech recognition and synthesis. */
class ModelGraph {
    private val http = DeepSeekHttp()

    val chatGateway: ChatGateway = DeepSeekChatGateway(http)
    val searchGateway: SearchGateway = DeepSeekSearchApi(http)
    val audioGateway: AudioGateway = DeepSeekAudioApi(http)
}
