"""Run with python3 -m unittest discover -s app/src/test/python."""
import concurrent.futures
import importlib.util
import io
import json
import multiprocessing
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[2] / "main/assets/harness/task-session.py"
spec = importlib.util.spec_from_file_location("task_session", SOURCE)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


def edit(args):
    root, revision, text = args
    return bridge.operate(root, {"op": "save", "base": revision, "text": text})


class TaskSessionTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.run_dir = Path(tmp.name)
        bridge.init(self.run_dir, "项目目标")

    def rpc(self, op, **args):
        return bridge.operate(self.run_dir, dict(op=op, **args))

    def test_full_document_concurrent_writers_and_conditional_reads(self):
        revision = self.rpc("read")["rev"]
        with concurrent.futures.ProcessPoolExecutor(2, mp_context=multiprocessing.get_context("fork")) as pool:
            replies = list(pool.map(edit, [(self.run_dir, revision, text) for text in ("来自手机", "来自执行器")]))
        self.assertEqual(1, sum(bool(r.get("conflict")) for r in replies))
        current = self.rpc("read")
        self.assertNotIn("text", self.rpc("read", base=current["rev"]))
        self.assertEqual(current["text"], (self.run_dir / "channel/task.md").read_text())
        self.assertEqual(current["text"], next(r["text"] for r in replies if r.get("conflict")))

    def test_batch_conflict_changes_neither_card_and_user_lock_protects_the_worker_copy(self):
        with patch.object(Path, "home", return_value=self.run_dir):
            root = self.run_dir / ".local/share/reduction-agents/reduction-agents_test/runs"
            edits = []
            for rid in ("one", "two"):
                r = root / rid
                r.mkdir(parents=True)
                bridge.init(r, rid)
                edits.append(dict(folder="~/reduction-agents/test", id=rid, base=bridge.operate(r, {"op":"read"})["rev"], text="edited"))
            second = root / "two"
            bridge.operate(second, {"op":"lock", "locked":True, "base":edits[1]["base"]})
            self.assertTrue(bridge.batch(edits)["conflict"])
            self.assertEqual("one", bridge.operate(root / "one", {"op":"read"})["text"])
            rev = bridge.operate(second, {"op":"read"})["rev"]
            self.assertTrue(bridge.operate(second, {"op":"save", "base":rev,"text":"worker"}, worker=True)["conflict"])
            edits[1]["base"] = bridge.operate(second, {"op":"lock","locked":False,"base":rev})["rev"]
            self.assertFalse(bridge.batch(edits)["conflict"])
            self.assertEqual(["edited","edited"], [bridge.operate(root / rid, {"op":"read"})["text"] for rid in ("one","two")])

    def test_native_questions_and_unrelated_send_keep_separate_routes(self):
        pipe = type("Process", (), {"stdin": io.StringIO()})()
        turn = bridge.CodexTurn(pipe, self.run_dir, {}, "", None)
        turn.event({"method": "item/tool/requestUserInput", "id": 53, "params": {
            "turnId": "turn", "itemId": "tool", "questions": [{"id": "a", "question": "范围？"},
                                                             {"id": "b", "question": "时间？"}]}})
        self.rpc("send", id="instruction", text="另外一条补充")
        for answer in ("尚未确定", "明天"):
            question = self.rpc("status")["question"]
            msg = dict(question=question["id"], text=answer, id="reply/" + question["id"])
            self.rpc("send", **msg)
            self.rpc("send", **msg)
        turn.tick()
        response = json.loads(pipe.stdin.getvalue().splitlines()[-1])
        self.assertEqual(53, response["id"])
        self.assertEqual({"a": {"answers": ["尚未确定"]}, "b": {"answers": ["明天"]}}, response["result"]["answers"])
        turn.event({"method": "serverRequest/resolved", "params": {"requestId": 53}})
        self.assertIsNone(self.rpc("status")["question"])
        self.assertEqual(1, self.rpc("read")["text"].count("尚未确定"))
        with bridge.transaction(self.run_dir) as state:
            self.assertEqual(["instruction"], [m["id"] for m in state["outbox"]])

    def test_deleting_card_preserves_send_and_removal_rejects_late_updates(self):
        base = self.rpc("read")["rev"]
        self.assertTrue(self.rpc("delete", base=base)["deleted"])
        self.assertIn("delivery", self.rpc("send", id="new", text="continue"))
        self.assertTrue(self.rpc("save", text="obsolete", base=base)["conflict"])
        self.assertTrue(self.rpc("remove")["detached"])
        self.assertTrue(self.rpc("remove")["detached"])
        self.assertTrue(self.rpc("send", id="late", text="late")["detached"])
        self.assertFalse((self.run_dir / "channel/task.md").exists())
