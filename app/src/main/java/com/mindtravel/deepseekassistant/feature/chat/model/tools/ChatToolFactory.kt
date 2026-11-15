package com.mindtravel.deepseekassistant.feature.chat.model.tools

import com.mindtravel.deepseekassistant.core.events.EventSink
import com.mindtravel.deepseekassistant.core.host.ToolApprovalHost
import com.mindtravel.deepseekassistant.core.settings.AppSettings
import com.mindtravel.deepseekassistant.feature.chat.model.SearchGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcProfile

class ChatToolFactory(
    private val searchGateway: SearchGateway,
    private val pcGateway: PcGateway,
    private val approvalHost: ToolApprovalHost,
    private val emitter: EventSink
) {
    fun create(settings: AppSettings): ToolRegistry {
        val tools = buildList {
            add(BriefTool(emitter))
            if (settings.webSearchEnabled) {
                add(WebSearchTool(searchGateway, settings.apiKey))
            }
            if (settings.calculatorEnabled) {
                add(CalculatorTool())
            }
            if (settings.opencodeEnabled && settings.pcHost.isNotBlank() && settings.pcUser.isNotBlank()) {
                add(
                    OpenCodeTool(
                        pcGateway = pcGateway,
                        profile = PcProfile(settings.pcHost, settings.pcPort, settings.pcUser),
                        project = settings.pcProject,
                        sessionId = settings.opencodeSessionId
                    )
                )
            }
            if (settings.opencodeEnabled &&
                settings.pcHost.isNotBlank() &&
                settings.pcUser.isNotBlank()
            ) {
                add(
                    OpenCodeVerifyTool(
                        pcGateway = pcGateway,
                        profile = PcProfile(settings.pcHost, settings.pcPort, settings.pcUser),
                        project = settings.pcProject
                    )
                )
            }
            if (settings.opencodeWriteEnabled &&
                settings.pcHost.isNotBlank() &&
                settings.pcUser.isNotBlank()
            ) {
                add(
                    OpenCodeEditTool(
                        pcGateway = pcGateway,
                        profile = PcProfile(settings.pcHost, settings.pcPort, settings.pcUser),
                        project = settings.pcProject,
                        sessionId = settings.opencodeSessionId,
                        approvalHost = approvalHost
                    )
                )
            }
        }
        return ToolRegistry(tools)
    }
}
