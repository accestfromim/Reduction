package com.mindtravel.reduction.feature.chat.data

import com.mindtravel.reduction.feature.chat.model.tools.AgentRuns
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TaskCardsTest {
    @get:Rule val tmp = TemporaryFolder()
    private var revision = 1L
    private var server: String? = "目标\n"
    private var reachable = true
    private val calls = mutableListOf<String>()
    private val name = "sub agent/任务 1"

    private fun request(run: AgentRuns.Run, req: JSONObject): JSONObject {
        assertEquals("remote-run", run.launcherId)
        check(reachable) { "offline" }
        val op = req.getString("op")
        calls += op
        val base = req.getLong("base")
        if (op != "read" && base == revision) {
            server = if (op == "delete") null else req.getString("text")
            revision++
            return JSONObject().put("rev", revision).put("deleted", server == null)
        }
        return JSONObject().put("rev", revision).put("deleted", server == null).apply {
            if (base != revision && server != null) put("text", server)
            if (op != "read") put("conflict", true)
        }
    }

    private fun repo(runs: AgentRuns): CardRepository = CardRepository(File(tmp.root, "cards")).also {
        it.taskCards = TaskCards(File(tmp.root, "sync"), runs, ::request)
    }

    private fun start(): Pair<CardRepository, AgentRuns> {
        val runs = AgentRuns(File(tmp.root, "runs.json"))
        runs.add("remote-run", "test", "/test", "目标", host = "u@h:22", card = name)
        return repo(runs).apply { registerTask(name, server!!) } to runs
    }

    @Test fun everyBodyReadChecksTheServerAndCatalogReadsUseOnlyMetadata() {
        val (cards, _) = start()
        cards.directoryJson()
        cards.promptDirectory()
        cards.heldMedia()
        assertTrue(calls.isEmpty())
        assertEquals("目标\n", cards.read(name))
        server = "新的进展和提问"; revision++
        assertEquals(server, cards.read(name))
        assertEquals(server, cards.export().getJSONArray("cards").getJSONObject(0).getString("text"))
        assertEquals(listOf("read", "read", "read"), calls)
        assertEquals(0, cards.export(includeTaskCards = false).getJSONArray("cards").length())
        assertTrue(cards.save(name, "来自手机"))
        assertEquals("来自手机", server)
        assertEquals("save", calls.last())
    }

    @Test fun conflictingFullEditKeepsBothAndOfflineDraftSurvivesRestart() {
        val (cards, _) = start()
        val base = cards.read(name)!!
        server = "执行器正在问一个问题"; revision++
        assertEquals("conflict", cards.autosave(name, "", "手机的编辑", false, "", base))
        assertEquals("执行器正在问一个问题", cards.read(name))
        val draft = cards.list().single { it.name.startsWith("同步草稿/") }
        assertEquals("手机的编辑", cards.read(draft.name))
        reachable = false
        assertEquals("pending", cards.autosave(name, "", "离线的编辑", false, "", server!!))
        assertThrows(TaskCards.Offline::class.java) { cards.read(name) }
        val restarted = repo(AgentRuns(File(tmp.root, "runs.json")))
        reachable = true
        restarted.retryTaskEdits()
        assertEquals("离线的编辑", server)
        assertEquals(server, restarted.read(name))
    }

    @Test fun renamedCardRetainsItsBindingAndDeletingOnlyTheBodyRetainsTheSession() {
        val (cards, runs) = start()
        val newName = "项目/调查"
        cards.read(name)
        assertTrue(cards.rename(name, newName))
        assertEquals(newName, runs.find(1)!!.card)
        server = "改名后的远端修改"; revision++
        assertEquals(server, cards.read(newName))
        cards.patch("*** Begin Patch\n*** Update Card: $newName\n@@\n-改名后的远端修改\n+手机补充\n*** End Patch")
        assertEquals("手机补充", server?.trim())
        assertTrue(cards.delete(newName))
        assertNull(server)
        assertEquals("", runs.find(1)!!.card)
        assertFalse(cards.list().any { it.name == newName })
    }
}
