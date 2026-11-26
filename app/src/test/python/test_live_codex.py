"""Opt in with REDUCTION_LIVE_CODEX=1 on a Linux host with bwrap and a logged-in Codex."""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest


@unittest.skipUnless(os.environ.get("REDUCTION_LIVE_CODEX") == "1", "live Codex is opt-in")
class LiveCodexTest(unittest.TestCase):
    def test_question_active_steer_resume_and_worker_edit(self):
        self.assertTrue(sys.platform.startswith("linux") and shutil.which("bwrap") and shutil.which("codex"))
        assets = Path(__file__).resolve().parents[2] / "main/assets/harness"
        auth = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex")) / "auth.json"
        with tempfile.TemporaryDirectory(prefix="reduction-live-codex-") as tmp:
            # Only the login is copied. Every thread, rollout and workspace lives under the temporary home.
            home = Path(tmp) / "account"
            login = home / ".local/share/reduction-codex"
            login.mkdir(parents=True, mode=0o700)
            shutil.copyfile(auth, login / "auth.json")
            (login / "auth.json").chmod(0o600)
            env = dict(os.environ, HOME=str(home), AGENT_ENGINE="codex", AGENT_TIMEOUT="150",
                       AGENT_DIRECTORIES="[]", REDUCTION_TASK_DRIVER=base64.b64encode(
                           (assets / "task-session.py").read_bytes()).decode())
            folder = "~/reduction-agents/smoke"

            def cli(*args, payload=None):
                return subprocess.run(["sh", str(assets / "run-agent.sh"), *args], env=env, check=True,
                                      input=json.dumps(payload) if payload else None, timeout=30,
                                      capture_output=True, text=True).stdout.strip()

            def rpc(op, **fields):
                return json.loads(cli("task", folder, rid, payload=dict(op=op, **fields)))

            def wait(check):
                deadline = time.monotonic() + 150
                while time.monotonic() < deadline:
                    status = rpc("status")
                    self.assertNotEqual("failed", status.get("status"), status.get("detail"))
                    if check(status):
                        return status
                    time.sleep(.3)
                self.fail("Codex did not reach the expected task state")

            receipt = cli("start", "plan", folder, "--task-stdin", payload={
                "body": "## Task\nColor handshake",
                "prompt": "Local protocol test. Use the structured request_user_input tool now to ask which "
                          "color (blue or green) to use. Wait, then reply FIRST COLOR and the selected color. "
                          "Do not change project files."
            })
            first, path = receipt.splitlines()
            self.assertTrue(first.startswith("RUN "), first)
            rid = first.removeprefix("RUN ")
            run = Path(path.removeprefix("TASK_PATH "))
            self.assertTrue(run.is_relative_to(home))
            try:
                status = wait(lambda s: s.get("question"))
                thread = status["session"]
                rpc("send", id="color", question=status["question"]["id"], text="blue")
                wait(lambda s: s["status"] == "idle")
                self.assertIn("FIRST COLOR BLUE", rpc("read")["text"].upper())
                rpc("send", id="continue", text="Run 'sleep 7' now for this messaging test and then reply with "
                                                "the most recent instruction and the earlier color. Do not edit files.")
                wait(lambda _: json.loads((run / "task.json").read_text())["seen"].get("continue") == "next")
                rpc("send", id="steer", text="For your final reply use exactly STEERED and the color you remember.")
                wait(lambda s: s["status"] == "idle")
                self.assertEqual("current", json.loads((run / "task.json").read_text())["seen"]["steer"])
                self.assertIn("STEERED BLUE", rpc("read")["text"].upper())
                rpc("send", id="worker", text="Run python3 ~/.reduction-task/card.py read, then save its full text "
                                              "plus a new line WORKEREDIT using python3 ~/.reduction-task/card.py "
                                              "save REV with the new document on stdin. Finally call python3 "
                                              "~/.reduction-task/card.py done and reply OK. Keep project files read-only.")
                self.assertEqual(thread, wait(lambda s: s["status"] == "done")["session"])
                body = rpc("read")
                self.assertIn("WORKEREDIT", body["text"])
                self.assertNotIn("text", rpc("read", base=body["rev"]))
                self.assertTrue(rpc("remove")["detached"])
            finally:
                cli("stop", folder, rid)
