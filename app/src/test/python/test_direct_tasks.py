"""Exercise the Linux launcher and task driver with ordinary SSH-account permissions."""
import base64
import json
import os
import shlex
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HARNESS = Path(__file__).resolve().parents[2] / "main/assets/harness"


@unittest.skipUnless(sys.platform.startswith("linux"), "Linux or WSL launcher")
class DirectTaskTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory(prefix="reduction direct ")
        self.addCleanup(tmp.cleanup)
        self.home = Path(tmp.name)
        self.bin = self.home / "bin"
        self.bin.mkdir()
        self.env = dict(HOME=tmp.name, PATH=f"{self.bin}:{os.defpath}", AGENT_TIMEOUT="15",
                        REDUCTION_TASK_DRIVER=base64.b64encode((HARNESS / "task-session.py").read_bytes()).decode(),
                        HOST_TEST_SETTING="from-ssh", AGENT_KEY="sk-fixture")
        self.folder = "~/reduction-agents/test"
        self.program("bwrap", 'echo called > "$HOME/bwrap-called"\nexit 99')

    def program(self, name, script):
        path = self.bin / name
        path.write_text("#!/bin/sh\nset -eu\n" + script + "\n")
        path.chmod(0o700)

    def cli(self, *args, body=None):
        return subprocess.check_output(["sh", str(HARNESS / "run-agent.sh"), *args], env=self.env,
                                       text=True, input=json.dumps(body) if body is not None else None, timeout=10)

    def start(self, engine):
        self.env["AGENT_ENGINE"] = engine
        receipt = self.cli("start", "plan", self.folder, "--task-stdin", body={"prompt":"test", "body":"goal"})
        self.assertIn("TASK_PATH ", receipt, receipt)
        run = Path(receipt.split("TASK_PATH ", 1)[1].strip())
        self.addCleanup(self.cli, "stop", self.folder, run.name)
        return run

    def status(self, run):
        return json.loads(self.cli("task", self.folder, run.name, body={"op": "status"}))

    def wait(self, check):
        for _ in range(160):
            value = check()
            if value:
                return value
            time.sleep(.05)
        self.fail("timed out waiting for the task")

    def tearDown(self):
        self.assertFalse((self.home / "bwrap-called").exists())

    def test_direct_session_resumes_with_its_own_paths_and_ssh_environment(self):
        self.program("claude", '''test -z "${AGENT_KEY:-}"
command -v bwrap >/dev/null
printf '%s\\n' "$PWD" "$REDUCTION_TASK_DIR" "$REDUCTION_RESULT_DIR" "$HOST_TEST_SETTING" > "$REDUCTION_RESULT_DIR/context"
printf '%s\\n' '{"type":"result","result":"ok","session_id":"test-thread"}' ''')
        run = self.start("claude")
        second = self.start("claude")
        for task in (run, second):
            self.wait(lambda: self.status(task)["status"] == "idle")
            cwd, card, result, setting = (task / "result/context").read_text().splitlines()
            self.assertEqual(self.home / "reduction-agents/test", Path(cwd))
            self.assertEqual(task / "channel", Path(card))
            self.assertEqual(task / "result", Path(result))
            self.assertEqual("from-ssh", setting)
            self.assertNotIn("from-ssh", (task / "driver.json").read_text())
        self.env["HOST_TEST_SETTING"] = "next-ssh-login"
        self.cli("task", self.folder, run.name, body={"op":"send", "id":"msg", "text":"continue"})
        self.wait(lambda: self.status(run)["status"] == "idle")
        self.assertEqual("next-ssh-login", (run / "result/context").read_text().splitlines()[-1])
        self.assertEqual("from-ssh", (second / "result/context").read_text().splitlines()[-1])

    def test_engine_can_invoke_its_native_sandbox(self):
        self.program("bwrap", 'echo "$1" > "$REDUCTION_RESULT_DIR/native-call"')
        self.program("opencode", "bwrap from-executor")
        run = self.start("opencode")
        self.wait(lambda: self.status(run)["status"] == "idle")
        self.assertEqual("from-executor", (run / "result/native-call").read_text().strip())

    def test_mimo_startup_error_reaches_status_and_card(self):
        config = self.home / ".config/mimocode"
        config.mkdir(parents=True)
        (config / "mimocode.json").write_text("{}")
        self.program("mimo", '''test "$1" = serve
printf '%s\\n' 'Error: invalid setting server.port' 'Error: apiKey=private-fixture' >&2
exit 29''')
        run = self.start("mimocode")
        self.wait(lambda: self.status(run)["status"] == "failed")
        for text in (self.status(run)["detail"], (run / "channel/task.md").read_text()):
            self.assertIn("退出码 29", text)
            self.assertIn("invalid setting server.port", text)
            self.assertNotIn("private-fixture", text)

    def test_managed_stop_and_timeout_terminate_worker_children(self):
        # The child ignores TERM. Either it is reaped, or init may still own a zombie.
        child = "import os,signal,time; from pathlib import Path; signal.signal(signal.SIGTERM,signal.SIG_IGN); " \
                "Path(os.environ['REDUCTION_RESULT_DIR'],'child').write_text(str(os.getpid())); time.sleep(60)"
        self.program("opencode", "python3 -c " + shlex.quote(child) + " &\nwait")
        for stop in (True, False):
            with self.subTest(stop=stop):
                self.env["AGENT_TIMEOUT"] = "15" if stop else "2"
                run = self.start("opencode")
                marker = run / "result/child"
                self.wait(marker.exists)
                pid = int(marker.read_text())
                if stop:
                    self.cli("stop", self.folder, run.name)
                phase = "stopped" if stop else "failed"
                self.wait(lambda: self.status(run)["status"] == phase)
                stat = Path(f"/proc/{pid}/stat")
                self.wait(lambda: not stat.exists() or stat.read_text().split(") ")[1][0] == "Z")

    def test_rejects_unlisted_starting_directory_before_launching(self):
        self.env["AGENT_ENGINE"] = "opencode"
        project = self.home / "project"
        project.mkdir()
        result = self.cli("start", "build", str(project), "test")
        self.assertIn("工作目录未获授权", result)
