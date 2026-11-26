"""A managed Reduction session. The SSH client and worker share a versioned Markdown card.

The driver runs outside the existing agent sandbox. The worker sees a read-only copy
of this script and task.md, and can submit card operations through its result folder.
"""
import contextlib
import fcntl
import hashlib
import json
import os
import queue
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from pathlib import Path

LIMIT = 60000


def atomic(path, data):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as tmp:
        try:
            tmp.write(data.encode())
            tmp.flush()
            os.fsync(tmp.fileno())
            os.replace(tmp.name, path)
        finally:
            Path(tmp.name).unlink(missing_ok=True)


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


@contextlib.contextmanager
def transaction(run):
    with (run / "card.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        path = run / "task.json"
        data = json.loads(path.read_text()) if path.exists() else {}
        before = encode(data)
        rev = data.get("rev")
        yield data
        after = encode(data)
        if before != after:
            atomic(path, after)
            if data.get("rev") != rev:
                view = run / "channel/task.md"
                if data["text"] is None:
                    view.unlink(missing_ok=True)
                else:
                    atomic(view, data["text"])


def revise(s, text):
    if text is not None and len(text.encode("utf-16-le")) // 2 > LIMIT:
        raise ValueError("任务卡超过 60000 字，请先整理正文")
    s.update(text=text, rev=max(s.get("rev", 0) + 1, time.time_ns() // 1000))


def note(s, author, text):
    if s["text"] is not None and not s.get("locked"):
        revise(s, s["text"].rstrip() + "\n\n**" + author + "**\n\n" + text + "\n")


def init(run, body):
    (run / "channel").mkdir(exist_ok=True)
    (run / "result").mkdir(exist_ok=True)
    shutil.copyfile(__file__, run / "channel/card.py")
    with transaction(run) as s:
        if s:
            raise ValueError("任务已登记")
        s.update(status="running", session="", detached=False, pending={}, outbox=[], seen={})
        revise(s, body)


def view(s, base):
    result = {"rev": s["rev"], "deleted": s["text"] is None, "locked": bool(s.get("locked"))}
    if base != s["rev"] and s["text"] is not None:
        result["text"] = s["text"]
    return result


def ask(s, qid, text, native=False):
    if not s["detached"] and s["text"] is not None and not s.get("locked") and qid not in s["pending"]:
        note(s, "执行器提问", text)
        s["pending"][qid] = {"text": text, "native": native}
        return True
    return False


def registered_run(folder, run_id):
    if not re.fullmatch(r"[A-Za-z0-9-]{1,80}", run_id):
        raise ValueError("无效的运行编号")
    home = Path.home()
    work = home / folder[2:] if folder.startswith("~/") else Path(folder)
    if not work.is_absolute():
        raise ValueError("工作目录需要绝对路径")
    root = home / ".local/share/reduction-agents"
    if work.parent == home / "reduction-agents" and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", work.name):
        location = root / ("reduction-agents_" + work.name)
    else:
        location = root / "by-path" / hashlib.sha256(os.fsencode(work)).hexdigest()
    run = location / "runs" / run_id
    if run.resolve() != run or not (run / "task.json").is_file():
        raise ValueError("找不到登记的任务")
    return run


def batch(edits):
    # Hold every affected card lock in the same order and validate every base before writing.
    paths = [registered_run(e["folder"], e["id"]) for e in edits]
    if len(set(paths)) != len(paths):
        raise ValueError("同一任务重复出现")
    with contextlib.ExitStack() as stack:
        states = {p: stack.enter_context(transaction(p)) for p in sorted(paths)}
        conflict = any(states[p]["detached"] or states[p].get("locked") or states[p]["text"] is None or
                       (states[p]["rev"] != e["base"] and states[p]["text"] != e.get("text"))
                       for p, e in zip(paths, edits))
        for path, edit in zip(paths, edits):
            state, body = states[path], edit.get("text")
            if not conflict and state["text"] != body:
                revise(state, body)
                if body is None:
                    state["pending"].clear()
        return {"conflict": conflict, "replies": [view(states[p], None if conflict else states[p]["rev"]) for p in paths]}


def operate(run, req, worker=False):
    op = req["op"]
    if op == "batch" and not worker:
        return batch(req["edits"])
    with transaction(run) as s:
        if not s:
            raise ValueError("任务不存在")
        if s["detached"]:
            return {"detached": True, "deleted": True, "rev": s["rev"]}
        if op == "read":
            return view(s, req.get("base"))
        if op == "lock" and not worker:
            if s["text"] is None or (s["rev"] != req.get("base") and bool(s.get("locked")) != req["locked"]):
                return dict(conflict=True, **view(s, None))
            if bool(s.get("locked")) != req["locked"]:
                s["locked"] = req["locked"]
                revise(s, s["text"])
            return view(s, s["rev"])
        if op == "save" or (op == "delete" and not worker):
            body = req.get("text") if op == "save" else None
            if op == "save" and not isinstance(body, str):
                raise ValueError("缺少任务正文")
            if s.get("locked"):
                return dict(conflict=True, **view(s, None))
            if body == s["text"]:
                return view(s, s["rev"])
            if req.get("base") != s["rev"] or s["text"] is None:
                return dict(conflict=True, **view(s, None))
            revise(s, body)
            if body is None:
                s["pending"].clear()
            return view(s, s["rev"])
        if op == "ask" and worker:
            if not ask(s, req["id"], req["text"]):
                raise ValueError("任务卡已删除")
            return {"ok": True}
        if op == "done" and worker:
            s["completionRequested"] = True
            return {"ok": True}
        if worker:
            raise ValueError("未知的任务卡操作")
        if op == "remove":
            if s.get("locked"):
                raise ValueError("请先解锁任务卡")
            revise(s, None)
            s.update(detached=True, pending={}, outbox=[], seen={})
            return {"detached": True, "deleted": True, "rev": s["rev"]}
        if op == "status":
            if s["status"] == "running" and s.get("pid") and not active(run):
                s["status"] = "failed"
                s["error"] = "执行器意外退出"
                s["pending"] = {k: q for k, q in s["pending"].items() if not q["native"]}
            pending = next((dict(id=k, text=v["text"]) for k, v in s["pending"].items() if "answer" not in v), None)
            return {"status": s["status"], "question": pending, "session": s["session"], "detail": s.get("error", "")}
        if op == "send":
            mid, text = req["id"], req["text"].strip()
            if mid in s["seen"]:
                return {"delivery": s["seen"][mid]}
            if not text:
                raise ValueError("消息为空")
            qid = req.get("question")
            if qid:
                question = s["pending"].get(qid)
                if not question or "answer" in question:
                    return {"delivery": "expired"}
                question.update(answer=text, messageId=mid)
            note(s, "手机助手", text)
            native = bool(qid and s["pending"][qid]["native"])
            if not native:
                s["outbox"].append({"id": mid, "text": text})
                if qid:
                    del s["pending"][qid]
            if s["status"] != "running":
                s["status"] = "running"
            s["seen"][mid] = "queued"
            return {"delivery": "queued"}
        raise ValueError("未知的会话操作")


def worker_cli():
    op = sys.argv[1]
    req = {"op": op, "id": uuid.uuid4().hex}
    if op == "save":
        req.update(base=int(sys.argv[2]), text=sys.stdin.read())
    elif op == "ask":
        req["text"] = " ".join(sys.argv[2:]).strip() or sys.stdin.read().strip()
    elif op not in ("read", "done"):
        sys.exit("card.py read | save VERSION < file | ask QUESTION | done")
    folder = Path.home() / ".reduction-result"
    atomic(folder / ("req-" + req["id"]), encode(req))
    reply = folder / ("res-" + req["id"])
    for _ in range(300):
        if reply.is_file():
            result = json.loads(reply.read_text())
            reply.unlink()
            print(encode(result))
            return 1 if result.get("error") or result.get("conflict") or result.get("detached") else 0
        time.sleep(.1)
    sys.exit("任务卡服务未响应")


def mailbox(run):
    for path in (run / "result").glob("req-*"):
        rid = path.name[4:]
        if not re.fullmatch("[0-9a-f]{32}", rid):
            continue
        try:
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, "rb") as f:
                if not stat.S_ISREG(os.fstat(fd).st_mode):
                    raise ValueError("无效请求")
                req = json.loads(f.read(LIMIT * 12 + 1024))
            req["id"] = rid
            result = operate(run, req, worker=True)
        except (OSError, ValueError, KeyError, TypeError) as exc:
            result = {"error": str(exc)}
        atomic(path.with_name("res-" + rid), encode(result))
        path.unlink(missing_ok=True)


def inputs(text):
    return [{"type": "text", "text": text, "text_elements": []}]


class CodexTurn:
    def __init__(self, process, run, options, prompt, mid):
        self.proc, self.run, self.options, self.prompt = process, run, options, prompt
        self.mid = mid
        self.defer_steer = False
        self.turn = self.thread = None
        self.requests, self.questions = {}, {}
        self.submitted = set()
        self.seq = 0
        self.final = ""
        self.rpc("initialize", {"clientInfo": {"name": "reduction", "version": "1"},
                                "capabilities": {"experimentalApi": True}})

    def write(self, data):
        self.proc.stdin.write(encode(data) + "\n")
        self.proc.stdin.flush()

    def rpc(self, method, params, message=None):
        self.seq += 1
        self.requests[self.seq] = method, message
        self.write({"id": self.seq, "method": method, "params": params})

    def event(self, event):
        method, p = event.get("method"), event.get("params", {})
        if not method and "id" in event:
            method, message = self.requests.pop(event["id"], (None, None))
            if "error" in event:
                if method == "turn/steer":
                    self.defer_steer = True
                    return
                raise ValueError(str(event["error"].get("message", "Codex RPC 失败")))
            result = event.get("result", {})
            if method == "initialize":
                self.write({"method": "initialized"})
                with transaction(self.run) as s:
                    session = s["session"]
                opts = dict(cwd=self.options["cwd"], approvalPolicy="never", sandbox=self.options["sandbox"])
                if session:
                    opts["threadId"] = session
                self.rpc("thread/resume" if session else "thread/start", opts)
            elif method in ("thread/start", "thread/resume"):
                self.thread = result["thread"]["id"]
                with transaction(self.run) as s:
                    s["session"] = self.thread
                self.rpc("turn/start", {"threadId": self.thread, "input": inputs(self.prompt)})
            elif method == "turn/start":
                self.turn = result["turn"]["id"]
                if self.mid:
                    delivered(self.run, self.mid, "next")
            elif method == "turn/steer":
                delivered(self.run, message, "current")
        elif method == "item/tool/requestUserInput" and "id" in event:
            group = {}
            with transaction(self.run) as s:
                for q in p["questions"]:
                    key = p["turnId"] + "/" + p["itemId"] + "/" + q["id"]
                    options = "、".join(x["label"] for x in q.get("options") or [])
                    if ask(s, key, q["question"] + ("（" + options + "）" if options else ""), native=True):
                        group[q["id"]] = key
            if group:
                self.questions[event["id"]] = group
            else:
                self.write({"id": event["id"], "error": {"code": -32000, "message": "Task card unavailable"}})
        elif method and "id" in event:
            self.write({"id": event["id"], "error": {"code": -32601, "message": "Unsupported request"}})
        elif method == "serverRequest/resolved":
            self.close_question(p.get("requestId"), resolved=True)
        elif method == "item/completed" and p["item"].get("type") == "agentMessage":
            if p["item"].get("phase") != "commentary":
                self.final = p["item"].get("text", "")
        elif method == "turn/completed":
            if p["turn"]["status"] != "completed":
                raise ValueError("Codex 回合" + p["turn"]["status"])
            return True
        return False

    def close_question(self, rpc_id, resolved=False):
        group = self.questions.pop(rpc_id, {})
        with transaction(self.run) as s:
            for key in group.values():
                q = s["pending"].pop(key, None)
                if resolved and q and "messageId" in q:
                    s["seen"][q["messageId"]] = "answered"

    def tick(self):
        with transaction(self.run) as s:
            pending = s["pending"].copy()
            msg = next(iter(s["outbox"]), None)
        for rpc_id, group in list(self.questions.items()):
            if rpc_id in self.submitted: continue
            if any(k not in pending for k in group.values()):
                self.write({"id": rpc_id, "error": {"code": -32000, "message": "Task card removed"}})
                self.close_question(rpc_id)
            elif all("answer" in pending[k] for k in group.values()):
                self.write({"id": rpc_id, "result": {"answers": {
                    native: {"answers": [pending[k]["answer"]]} for native, k in group.items()}}})
                self.submitted.add(rpc_id)
        if self.turn and msg and not self.defer_steer and not any(m == "turn/steer" for m, _ in self.requests.values()):
            self.rpc("turn/steer", {"threadId": self.thread, "expectedTurnId": self.turn,
                                    "input": inputs(msg["text"])}, msg["id"])


def delivered(run, mid, mode):
    with transaction(run) as s:
        s["outbox"] = [m for m in s["outbox"] if m["id"] != mid]
        if not s["detached"]:
            s["seen"][mid] = mode


def read_events(pipe, events):
    try:
        for line in pipe:
            try:
                events.put(json.loads(line))
            except ValueError:
                pass
    finally:
        events.put(None)


def one_turn(run, opts, prompt, mid):
    engine = opts["engine"]
    cmd = list(opts["command"])
    with transaction(run) as s:
        session = s["session"]
    if engine != "codex":
        if session:
            cmd += ["--session" if engine == "opencode" else "--resume", session]
        cmd += ["--", prompt]
    proc = subprocess.Popen(cmd, cwd=opts["cwd"], env=opts.get("env"), stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace")
    events = queue.Queue()
    threading.Thread(target=read_events, args=(proc.stdout, events), daemon=True).start()
    codex = None
    final = ""
    try:
        if engine == "codex":
            codex = CodexTurn(proc, run, opts, prompt, mid)
        else:
            proc.stdin.close()
        if mid and not codex:
            delivered(run, mid, "next")
        end = time.monotonic() + opts["timeout"]
        while time.monotonic() < end:
            mailbox(run)
            if codex:
                codex.tick()
            try:
                event = events.get(timeout=.12)
            except queue.Empty:
                continue
            if event is None:
                if proc.wait() != 0 or codex:
                    raise ValueError(engine + " 进程中断")
                break
            if codex:
                if codex.event(event):
                    final = codex.final
                    break
            else:
                sid = event.get("sessionID") or event.get("session_id")
                if sid and sid != session:
                    session = sid
                    with transaction(run) as s:
                        s["session"] = sid
                if event.get("type") == "result":
                    if event.get("is_error"):
                        raise ValueError("执行器报告错误")
                    final = event.get("result", "")
                if event.get("type") == "text":
                    final = event.get("part", {}).get("text", "")
                if event.get("type") == "error":
                    raise ValueError("执行器报告错误")
        else:
            raise ValueError("执行超时")
        with transaction(run) as s:
            if final and not s["detached"]:
                note(s, "执行器", final)
            if s["status"] == "running":
                s["status"] = "done" if s.pop("completionRequested", False) and not s["pending"] else "idle"
    finally:
        if codex:
            for rpc_id in list(codex.questions):
                codex.close_question(rpc_id)
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        proc.stdout.close()
        if not proc.stdin.closed:
            proc.stdin.close()


def active(run):
    with (run / "driver.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return False
        except BlockingIOError:
            return True


def spawn(run):
    subprocess.Popen([sys.executable, str(run / "bridge.py"), "serve", str(run)],
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True, close_fds=True)


def serve(run):
    # A send racing a turn's exit launches another waiter. The lock serializes native sessions.
    with (run / "driver.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        opts = json.loads((run / "driver.json").read_text())
        while True:
            with transaction(run) as s:
                if s["detached"] or s["status"] == "stopped":
                    s.pop("pid", None)
                    return
                initial = s.pop("initial", None)
                message = next(iter(s["outbox"]), None)
                if initial is None and not message:
                    s.pop("pid", None)
                    return
                prompt = initial if initial is not None else message["text"]
                s["status"] = "running"
                s["pid"] = os.getpid()
            try:
                one_turn(run, opts, prompt, None if initial is not None else message["id"])
            except Exception as exc:
                with transaction(run) as s:
                    s.pop("pid", None)
                    s["status"] = "failed"
                    s["error"] = str(exc)[:400]
                    if message:
                        s["outbox"] = [m for m in s["outbox"] if m["id"] != message["id"]]
                return


def main():
    if Path(__file__).name == "card.py":
        return worker_cli()
    mode, run = sys.argv[1], Path(sys.argv[2]).resolve()
    if mode == "init":
        init(run, sys.stdin.read())
    elif mode == "launch":
        engine, cwd, seconds = sys.argv[3:6]
        outer = Path(sys.argv[6]).name
        keys = {"PATH", "HOME", "TERM", "LANG", "TMPDIR"}
        if outer != "bwrap":
            keys.update({"HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY"})
            keys.update({"codex": {"CODEX_HOME", "CODEX_CA_CERTIFICATE"},
                         "claude": {"ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN"},
                         "opencode": {"DEEPSEEK_API_KEY", "OPENCODE_DISABLE_AUTOUPDATE"}}[engine])
        opts = dict(engine=engine, cwd=cwd, timeout=int(seconds), command=sys.argv[6:],
                    sandbox="danger-full-access" if outer in ("bwrap", "srt") else "read-only",
                    env={k: os.environ[k] for k in keys if k in os.environ})
        atomic(run / "driver.json", encode(opts))
        with transaction(run) as s:
            s["initial"] = (run / "prompt.txt").read_text()
        spawn(run)
    elif mode == "serve":
        serve(run)
    elif mode == "rpc":
        req = json.load(sys.stdin)
        result = operate(run, req)
        if req["op"] == "send" and result.get("delivery") == "queued":
            # A waiter closes the race with a driver exiting just as a new turn is queued.
            with transaction(run) as s:
                queued = any(m["id"] == req["id"] for m in s["outbox"])
            if queued:
                spawn(run)
            for _ in range(6):
                with transaction(run) as s:
                    result["delivery"] = s["seen"].get(req["id"], "expired")
                if result["delivery"] != "queued":
                    break
                time.sleep(.05)
        print(encode(result))
    elif mode == "stop":
        with transaction(run) as s:
            s.update(status="stopped", outbox=[], pending={})
            pid = s.pop("pid", None)
            if pid and active(run):
                with contextlib.suppress(ProcessLookupError):
                    if os.getpgid(pid) == pid:
                        os.killpg(pid, signal.SIGTERM)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError) as err:
        print(encode({"error": str(err)}))
        sys.exit(1)
