package com.mindtravel.entropy.feature.chat.model.tools

import com.mindtravel.entropy.core.events.EventSink
import com.mindtravel.entropy.core.host.ToolApprovalHost
import com.mindtravel.entropy.core.settings.AppSettings
import com.mindtravel.entropy.feature.chat.data.CardRepository
import com.mindtravel.entropy.hardware.pc.PcGateway
import com.mindtravel.entropy.hardware.pc.PcProfile
import com.mindtravel.entropy.model.chat.SearchGateway

class ChatToolFactory(
    private val searchGateway: SearchGateway,
    private val pcGateway: PcGateway,
    private val approvalHost: ToolApprovalHost,
    private val emitter: EventSink,
    private val cards: CardRepository,
    /** The sandbox launcher that runs on the computer. */
    private val agentLauncherScript: () -> String,
    /** The script that reports how the sessions on the computer are doing. */
    private val sessionStatusScript: () -> String
) {
    fun create(settings: AppSettings): ToolRegistry {
        val harness = AgentHarness(
            pcGateway = pcGateway,
            profile = PcProfile(settings.pcHost, settings.pcPort, settings.pcUser),
            apiKey = settings.apiKey,
            model = settings.model,
            launcherScript = agentLauncherScript
        )
        val tools = buildList {
            add(PatchCardsTool(cards, emitter))
            add(ReadCardTool(cards))
            if (settings.webSearchEnabled) add(WebSearchTool(searchGateway, settings.apiKey))
            if (settings.calculatorEnabled) add(CalculatorTool())
            if (harness.isConfigured && (settings.opencodeEnabled || settings.opencodeWriteEnabled)) {
                add(SessionStatusTool(harness, sessionStatusScript))
                add(
                    RunAgentTool(
                        harness = harness,
                        project = settings.pcProject,
                        agentsRoot = AGENTS_ROOT,
                        allowWrite = settings.opencodeWriteEnabled,
                        approvalHost = approvalHost
                    )
                )
            }
        }
        return ToolRegistry(tools)
    }

    private companion object {
        /** Where sub agents' own folders live on the computer. */
        const val AGENTS_ROOT = "/home/user/entropy-agents"
    }
}
