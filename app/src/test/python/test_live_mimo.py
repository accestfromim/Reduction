"""Opt in with REDUCTION_TEST_MIMO=1 on Linux with mimo. The model runs on loopback."""
import base64
import json
import os
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HARNESS = Path(__file__).resolve().parents[2] / "main/assets/harness"


class Model(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        messages = body["messages"]
        conversation = json.dumps(messages, ensure_ascii=False)
        last = messages[-1]
        has_question = any(t.get("function", {}).get("name") == "question" for t in body.get("tools", []))
        has_worker_save = any(call.get("id") == "call_progress"
                              for msg in messages for call in (msg.get("tool_calls") or []))
        if has_question and last["role"] == "tool" and "采用稳定版" in conversation and not has_worker_save:
            command = ("python3 - <<'PY'\n"
                       "import json, os, pathlib, subprocess\n"
                       "card = str(pathlib.Path(os.environ['REDUCTION_TASK_DIR']) / 'card.py')\n"
                       "doc = json.loads(subprocess.check_output(['python3', card, 'read']))\n"
                       "subprocess.run(['python3', card, 'save', str(doc['rev'])], check=True, "
                       "input=doc['text'] + '\\n执行器写入的进展', text=True)\nPY")
            delta = {"tool_calls": [{"index": 0, "id": "call_progress", "type": "function",
                                     "function": {"name": "bash", "arguments": json.dumps({
                                         "command": command, "description": "更新任务卡进展"})}}]}
        elif has_question and "fixture-followup" in str(last.get("content")):
            delta = {"content": "原会话续聊成功" if "采用稳定版" in conversation else "缺少上轮历史"}
        elif has_question and last["role"] == "user" and "fixture-first" in str(last.get("content")):
            questions = [{"header": "范围", "question": "采用哪个版本？",
                          "options": [{"label": "稳定版", "description": "已发布"}]}]
            delta = {"tool_calls": [{"index": 0, "id": "call_question", "type": "function",
                                     "function": {"name": "question", "arguments": json.dumps({"questions": questions})}}]}
        else:
            delta = {"content": "收到原生问题的回答：采用稳定版" if "采用稳定版" in conversation else "任务标题"}
        stop = "tool_calls" if "tool_calls" in delta else "stop"
        self.send_response(200)
        if body.get("stream"):
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            for chunk, reason in [({"role": "assistant", **delta}, None), ({}, stop)]:
                data = {"id": "fixture", "model": "fixture", "object": "chat.completion.chunk", "created": 1,
                        "choices": [{"index": 0, "delta": chunk, "finish_reason": reason}]}
                self.wfile.write(("data: " + json.dumps(data) + "\n\n").encode())
            self.wfile.write(b"data: [DONE]\n\n")
        else:
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"id": "fixture", "object": "chat.completion", "created": 1, "model": "fixture",
                                         "choices": [{"index": 0, "message": {"role": "assistant", **delta},
                                                      "finish_reason": stop}]}).encode())


@unittest.skipUnless(os.environ.get("REDUCTION_TEST_MIMO") == "1", "real MiMo Code integration")
class LiveMiMoTest(unittest.TestCase):
    def test_native_question_and_followup_share_one_session(self):
        model = ThreadingHTTPServer(("127.0.0.1", 0), Model)
        threading.Thread(target=model.serve_forever, daemon=True).start()
        self.addCleanup(model.server_close)
        self.addCleanup(model.shutdown)
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            config = home / "account/config"
            config.mkdir(parents=True)
            (config / "mimocode.json").write_text(json.dumps({
                "model": "fixture/test", "small_model": "fixture/test", "enabled_providers": ["fixture"],
                "provider": {"fixture": {"npm": "@ai-sdk/openai-compatible", "name": "Fixture",
                                         "options": {"baseURL": f"http://127.0.0.1:{model.server_port}/v1", "apiKey": "fixture"},
                                         "models": {"test": {"name": "Local fixture", "tool_call": True}}}},
            }))
            env = {"HOME": str(home), "PATH": os.environ["PATH"], "MIMOCODE_HOME": str(config.parent),
                   "MIMOCODE_BIN_PATH": shutil.which("mimo") or "mimo", "AGENT_ENGINE": "mimocode", "AGENT_TIMEOUT": "90",
                   "REDUCTION_TASK_DRIVER": base64.b64encode((HARNESS / "task-session.py").read_bytes()).decode()}
            folder = "~/reduction-agents/verify"
            launcher = ["sh", str(HARNESS / "run-agent.sh")]
            receipt = subprocess.check_output(launcher + ["start", "plan", folder, "--task-stdin"], env=env, text=True,
                                              input=json.dumps({"prompt": "fixture-first", "body": "确认版本"}))
            self.assertIn("TASK_PATH ", receipt, receipt)
            run = Path(receipt.split("TASK_PATH ", 1)[1].strip())

            def rpc(op, **fields):
                return json.loads(subprocess.check_output(["python3", str(run / "bridge.py"), "rpc", str(run)],
                                  env=env, text=True, input=json.dumps(dict(op=op, **fields))))

            def wait_for(predicate):
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    status = rpc("status")
                    self.assertNotEqual("failed", status["status"], status)
                    if predicate(status):
                        return status
                    time.sleep(.25)
                self.fail(f"timed out: {status}, {rpc('read')}")

            try:
                pending = wait_for(lambda s: s["question"])
                question = pending["question"]
                self.assertIn("采用哪个版本", question["text"])
                card = rpc("read")
                self.assertEqual(1, card["text"].count("采用哪个版本"))
                rpc("save", base=card["rev"], text=card["text"] + "\n手机的进展")
                rpc("send", id="reply", question=question["id"], text="采用稳定版")
                wait_for(lambda s: s["status"] == "idle")
                self.assertIn("收到原生问题的回答：采用稳定版", rpc("read")["text"])
                rpc("send", id="followup", text="fixture-followup")
                again = wait_for(lambda s: s["status"] == "idle")
                self.assertEqual(pending["session"], again["session"])
                body = rpc("read")["text"]
                self.assertIn("原会话续聊成功", body)
                self.assertIn("手机的进展", body)
                self.assertIn("执行器写入的进展", body)
            finally:
                subprocess.run(launcher + ["stop", folder, run.name], env=env, capture_output=True, check=True)

            legacy = dict(env)
            del legacy["REDUCTION_TASK_DRIVER"]
            receipt = subprocess.check_output(launcher + ["start", "plan", folder, "单次任务"], env=legacy, text=True)
            self.assertTrue(receipt.startswith("RUN "), receipt)
            legacy_id = receipt.strip().split()[1]
            try:
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    status = subprocess.check_output(launcher + ["status", folder, legacy_id], env=legacy, text=True)
                    if "STATE done" in status:
                        break
                    time.sleep(.25)
                self.assertIn("STATE done 0", status)
                self.assertIn("任务标题", status)
            finally:
                subprocess.run(launcher + ["stop", folder, legacy_id], env=legacy, capture_output=True, check=True)
