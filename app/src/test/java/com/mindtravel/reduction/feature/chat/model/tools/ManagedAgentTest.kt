package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.data.TaskCards
import com.mindtravel.reduction.feature.chat.model.ChatCallbacks
import com.mindtravel.reduction.feature.chat.model.ChatEngine
import com.mindtravel.reduction.feature.memo.data.MemoStore
import com.mindtravel.reduction.hardware.pc.PcProfile
import com.mindtravel.reduction.model.chat.ChatRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Exercises the phone's real dispatch, SSH wire format, worker mailbox and background reply together. */
class ManagedAgentTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun workerQuestionWakesOnePrivateTurnAndSendResumesSameSession() {
        assumeTrue(System.getProperty("os.name").orEmpty().lowercase().contains("linux"))
        val home = tmp.newFolder("server")
        val fake = File(home, ".opencode/bin/opencode").apply { requireNotNull(parentFile).mkdirs() }
        fake.writeText("""#!/usr/bin/env python3
import json, os, sys, time, uuid
from pathlib import Path
args = sys.argv[1:]
result = Path(os.environ["REDUCTION_RESULT_DIR"])
def worker(op, **args):
    rid = uuid.uuid4().hex
    tmp = result / (rid + ".tmp")
    tmp.write_text(json.dumps(dict(op=op, **args)))
    tmp.rename(result / ("req-" + rid))
    reply = result / ("res-" + rid)
    for _ in range(100):
        if reply.exists(): return
        time.sleep(.05)
    sys.exit(2)
print(json.dumps({"type": "step_start", "sessionID": "ses_original"}), flush=True)
if "--session" in args:
    assert args[args.index("--session")+1] == "ses_original"
    worker("done")
    text = "已收到：" + args[-1]
else:
    worker("ask", text="选择哪个版本？")
    text = "等待手机助手"
print(json.dumps({"type":"text", "part":{"text":text}, "sessionID":"ses_original"}), flush=True)
""")
        assertTrue(fake.setExecutable(true))
        val assets = File("src/main/assets/harness")
        val harness = AgentHarness(LocalShell(home), PcProfile("test", 22, "u"), "sk-test", "test",
            taskDriverScript = { File(assets, "task-session.py").readText() }) {
            File(assets, "run-agent.sh").readText()
        }
        val runs = AgentRuns()
        val cards = CardRepository(tmp.newFolder("cards"))
        cards.taskCards = TaskCards(tmp.newFolder("sync"), runs, harness::task)
        cards.save("项目决定", "使用稳定版")
        var wakes = 0
        val engine = object : ChatEngine {
            override fun run(request: ChatRequest, tools: ToolRegistry, callbacks: ChatCallbacks) {
                wakes++
                assertTrue(request.messages.last().content!!.contains("选择哪个版本"))
                assertTrue(tools.execute("read_card", """{"name":"项目决定"}""").contains("稳定版"))
                assertFalse(tools.specs().any { it.name == "send" || it.name == "answer" || it.name == "run_agent" })
                callbacks.onCompleted("使用稳定版")
            }
        }
        val tool = RunAgentTool(harness, runs, "/project", "~/reduction-agents", false, emptyList(), cards = cards,
            answerQuestion = { run, q -> answerTaskQuestion(engine, AppSettings(apiKey = "test"),
                cards, MemoStore(File(tmp.root, "memos")), run, q) })
        fun act(vararg pairs: Pair<String, String>) = tool.execute(JSONObject(mapOf(*pairs)).toString())
        fun finish() {
            val until = System.currentTimeMillis() + 15_000
            do {
                tool.endedRuns()
                if (runs.find(1)!!.finished) return
                Thread.sleep(75)
            } while (System.currentTimeMillis() < until)
            fail("task did not complete: " + act("action" to "status", "run" to "1"))
        }
        assertTrue(act("action" to "start", "task" to "测试任务").contains("任务 1"))
        val run = runs.find(1)!!
        try {
            finish()
            assertEquals(1, wakes)
            val body = cards.read(run.card)!!
            assertTrue(body.contains("已收到：使用稳定版"))
            assertTrue(AgentLedger.render(runs.all()).contains("#card="))
            assertTrue(cards.delete(run.card))
            assertTrue(act("action" to "send", "run" to "1", "text" to "下一步").contains("原会话"))
            finish()
            assertEquals("", run.card)
            val remove = RemoveSubAgentTool(harness, runs, cards)
            assertTrue(remove.execute("""{"run":"1"}""").contains("已移除"))
            assertTrue(remove.execute("""{"run":"1"}""").contains("已移除"))
            assertTrue(harness.task(run, JSONObject().put("op", "status")).getBoolean("detached"))
            assertTrue(runs.all().isEmpty())
        } finally {
            harness.stop(run.folder, run.launcherId)
        }
    }
}
