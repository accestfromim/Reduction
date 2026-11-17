package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.host.ToolApprovalHost
import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.memo.MemoTool
import com.mindtravel.reduction.feature.memo.data.MemoStore
import com.mindtravel.reduction.hardware.pc.PcGateway
import com.mindtravel.reduction.hardware.pc.PcProfile
import com.mindtravel.reduction.model.chat.SearchGateway

class ChatToolFactory(
    private val searchGateway: SearchGateway,
    private val pcGateway: PcGateway,
    private val approvalHost: ToolApprovalHost,
    private val emitter: EventSink,
    private val cards: CardRepository,
    /** To-dos and their times: the memo plugin, and the only place they are kept. */
    private val memos: MemoStore,
    /** The sandbox launcher that runs on the computer. */
    private val agentLauncherScript: () -> String,
    /** The script that reports how the sessions on the computer are doing. */
    private val sessionStatusScript: () -> String,
    /** The runs started so far; they outlive any one reply. */
    private val agentRuns: AgentRuns
) {
    fun create(settings: AppSettings): ToolRegistry {
        val harness = harness(settings)
        val tools = buildList {
            add(PatchCardsTool(cards, emitter))
            add(ReadCardTool(cards))
            add(SearchCardsTool(cards))
            add(MemoTool(memos, emitter))
            if (settings.webSearchEnabled) add(WebSearchTool(searchGateway, settings.apiKey))
            if (settings.calculatorEnabled) add(CalculatorTool())
            if (harness.isConfigured && (settings.opencodeEnabled || settings.opencodeWriteEnabled)) {
                add(SessionStatusTool(harness, sessionStatusScript))
                add(
                    RunAgentTool(
                        harness = harness,
                        runs = agentRuns,
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

    /** True while some run has not been seen to end: there is something to watch. */
    fun hasOpenRuns(): Boolean = agentRuns.unfinished().isNotEmpty()

    /** The reports of runs that have ended since they were last looked at, each with its number. */
    fun endedRuns(settings: AppSettings): List<RunAgentTool.Ended> {
        if (!hasOpenRuns()) return emptyList()
        return RunAgentTool(harness(settings), agentRuns, settings.pcProject, AGENTS_ROOT, settings.opencodeWriteEnabled, approvalHost)
            .endedRuns()
    }

    private fun harness(settings: AppSettings) = AgentHarness(
        pcGateway = pcGateway,
        profile = PcProfile(settings.pcHost, settings.pcPort, settings.pcUser),
        apiKey = settings.apiKey,
        model = settings.model,
        launcherScript = agentLauncherScript
    )

    private companion object {
        /** Where sub agents' own folders live on the computer. */
        const val AGENTS_ROOT = "~/reduction-agents"
    }
}
