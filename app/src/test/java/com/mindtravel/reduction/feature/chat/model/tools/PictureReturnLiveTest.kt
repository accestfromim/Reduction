package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.model.DeepSeekHttp
import com.mindtravel.reduction.model.chat.ChatMessage
import com.mindtravel.reduction.model.chat.ChatRequest
import com.mindtravel.reduction.model.chat.ChatStreamEvent
import com.mindtravel.reduction.model.chat.DeepSeekChatGateway
import com.mindtravel.reduction.model.chat.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The whole way back with the real model, run only when asked for: REDUCTION_PICTURE_STATUS
 * names a file holding what the launcher's `status` printed for a run that left a picture,
 * and REDUCTION_PICTURE_KEY the model key. The model is asked what the picture shows.
 */
class PictureReturnLiveTest {
    @Test
    fun theAssistantSeesThePictureASubAgentLeft() {
        val status = System.getenv("REDUCTION_PICTURE_STATUS").orEmpty()
        val key = System.getenv("REDUCTION_PICTURE_KEY").orEmpty()
        assumeTrue(status.isNotEmpty() && key.isNotEmpty())

        val progress = AgentHarness.parse(File(status).readText())
        assertEquals(listOf("flag.png"), progress.images.map { it.name })
        val message = ChatMessage(
            Role.USER,
            "【任务 1 已结束】下面是它交回的结果。附图 1 张。这张图分成四格，按 左上、右上、左下、右下 的顺序说出每格的颜色，只答四个颜色词。",
            images = progress.images
        )
        val said = StringBuilder()
        DeepSeekChatGateway(DeepSeekHttp()).stream(
            ChatRequest(key, System.getenv("REDUCTION_PICTURE_MODEL") ?: "deepseek-flash", listOf(message), emptyList(), temperature = 0.0)
        ) { event ->
            when (event) {
                is ChatStreamEvent.Content -> said.append(event.text)
                is ChatStreamEvent.Failure -> throw IllegalStateException(event.message)
                else -> Unit
            }
        }
        println("MODEL SAID: $said")
        val order = listOf("红", "绿", "蓝", "黄").map { said.indexOf(it) }
        assertTrue("the model did not name the four colours in order: $said", order.all { it >= 0 } && order == order.sorted())
    }
}
