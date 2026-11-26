package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.feature.article.ArticleBuilder
import com.mindtravel.reduction.feature.article.RenderArticleTool
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.data.TaskCards
import com.mindtravel.reduction.feature.chat.model.ChatEngine
import java.io.File
import com.mindtravel.reduction.feature.memo.MemoTool
import com.mindtravel.reduction.feature.memo.data.MemoStore
import com.mindtravel.reduction.hardware.pc.PcGateway
import com.mindtravel.reduction.hardware.pc.PcProfile
import com.mindtravel.reduction.model.chat.SearchGateway
import java.time.LocalDate

class ChatToolFactory(
    private val searchGateway: SearchGateway,
    private val pcGateway: PcGateway,
    private val emitter: EventSink,
    private val cards: CardRepository,
    /** To-dos and their times: the memo plugin, and the only place they are kept. */
    private val memos: MemoStore,
    /** The sandbox launcher that runs on the computer. */
    private val agentLauncherScript: () -> String,
    private val taskDriverScript: () -> String,
    private val questionEngine: ChatEngine,
    /** The runs started so far; they outlive any one reply. */
    private val agentRuns: AgentRuns,
    /** Sets a card as an article; with [renderArticleScript], draws it on the computer. Null: no such tool. */
    private val articles: ArticleBuilder? = null,
    private val renderArticleScript: () -> String = { "" },
    private val hostTools: (AppSettings) -> List<ChatTool> = { emptyList() },
    private val macAgentLauncherScript: (() -> String)? = null
) {
    /** Answers too long for one go, kept whole across the replies of a conversation. */
    private val longResults = LongResults()

    fun create(settings: AppSettings, referenceDate: LocalDate): ToolRegistry {
        val harness = harness(settings)
        val tools = buildList {
            add(PatchCardsTool(cards, emitter))
            add(ReadCardTool(cards, memos))
            addAll(hostTools(settings))
            add(OpenCardTool(cards, emitter))
            add(SearchCardsTool(cards, memos))
            add(MemoTool(memos, emitter) { referenceDate })
            if (settings.webSearchEnabled) add(WebSearchTool(searchGateway, settings.apiKey))
            if (settings.calculatorEnabled) add(CalculatorTool())
            // Drawing an article is done by the computer's browser: offered when there is a computer to ask.
            if (articles != null && harness.isConfigured) add(RenderArticleTool(cards, articles, harness, renderArticleScript))
            if (harness.isConfigured && (settings.opencodeEnabled || settings.opencodeWriteEnabled)) {
                add(RemoveSubAgentTool(harness, agentRuns, cards))
                add(
                    RunAgentTool(
                        harness = harness,
                        runs = agentRuns,
                        project = settings.pcProject,
                        agentsRoot = AGENTS_ROOT,
                        allowWrite = settings.opencodeWriteEnabled,
                        cards = cards,
                        directories = settings.agentDirectories
                    )
                )
            }
        }
        return ToolRegistry(tools, longResults)
    }

    /** True while some run has not been seen to end: there is something to watch. */
    fun hasOpenRuns(): Boolean = agentRuns.unfinished().isNotEmpty()

    /** The reports of runs that have ended since they were last looked at, each with its number. */
    fun endedRuns(settings: AppSettings): List<RunAgentTool.Ended> {
        cards.retryTaskEdits()
        if (!hasOpenRuns()) return emptyList()
        return RunAgentTool(harness(settings), agentRuns, settings.pcProject, AGENTS_ROOT, settings.opencodeWriteEnabled,
            settings.agentDirectories, cards = cards, answerQuestion = { run, text ->
                answerTaskQuestion(questionEngine, settings, cards, memos, run, text)
            }).endedRuns()
    }

    fun bindTaskCards(dir: File, settings: () -> AppSettings) {
        cards.taskCards = TaskCards(dir, agentRuns) { run, request ->
            harness(settings()).task(run, request)
        }
    }

    private fun harness(settings: AppSettings) = AgentHarness(
        pcGateway = pcGateway,
        profile = PcProfile(settings),
        apiKey = settings.apiKey,
        model = settings.model,
        defaultEngine = AgentHarness.Engine.entries.firstOrNull { it.wire == settings.agentEngine } ?: AgentHarness.Engine.OPENCODE,
        taskDriverScript = taskDriverScript,
        launcherScript = agentLauncherScript,
        macLauncherScript = macAgentLauncherScript,
        directories = settings.agentDirectories
    )

    private companion object {
        /** Where sub agents' own folders live on the computer. */
        const val AGENTS_ROOT = "~/reduction-agents"
    }
}
