import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


source = Path(__file__).resolve().parents[2] / "main/assets/harness/macos-agent.py"
spec = importlib.util.spec_from_file_location("macos_agent", source)
macos = importlib.util.module_from_spec(spec)
spec.loader.exec_module(macos)


class MacMiMoProfileTest(unittest.TestCase):
    def test_account_inputs_are_live_and_read_only_while_sessions_are_private(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            account = root / "account"
            project = root / "project"
            project.mkdir()
            run = root / "task"
            for directory in ("config", "data", "state"):
                (account / directory).mkdir(parents=True)
            inputs = [account / "config", account / "data/auth.json", account / "state/model.json"]
            for file in inputs[1:]:
                file.write_text("first")
            (account / "config/relative-key").write_text("configured")
            (account / "data/personal.db").touch()
            env = {"HOME": str(root), "MIMOCODE_HOME": str(account), "REDUCTION_MIMO_BIN": "/bin/sh",
                   "REDUCTION_AGENT_MOUNTS": ""}
            with patch.dict(os.environ, env, clear=True):
                program, _, auth, domains = macos.backend("mimocode", "", "plan", project, "test", run / "result/result.md")
                settings = macos.sandbox_config(project, run, program, "plan", domains, auth)["filesystem"]
            profile = Path(auth["MIMOCODE_HOME"])
            replacement = account / "new-auth"
            replacement.write_text("rotated")
            replacement.replace(inputs[1])
            self.assertEqual("rotated", (profile / "data/auth.json").read_text())
            self.assertEqual("configured", (profile / "config/relative-key").read_text())
            self.assertEqual("first", (profile / "state/model.json").read_text())
            self.assertFalse((profile / "data/personal.db").exists())
            read = {Path(p["path"]) for p in settings["allowRead"]}
            protected = {Path(p["path"]) for p in settings["denyWrite"]}
            self.assertTrue(set(inputs) <= read & protected)
            self.assertNotIn(account, read)
