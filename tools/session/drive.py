"""Drive a local coding CLI using newline-delimited JSON controls on stdin."""
import argparse
import json
import os
import queue
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path


def receive(source, stream, inbox):
    try:
        for line in stream:
            try:
                value = json.loads(line)
            except ValueError:
                value = None
            inbox.put((source, value))
    finally:
        inbox.put((source + "_eof", None))


def run(args):
    started = time.monotonic()
    def emit(event, **data):
        print(json.dumps(dict(ms=round((time.monotonic()-started)*1000), event=event, **data),
                         ensure_ascii=False), flush=True)

    codex = args.cli == "codex"
    if codex:
        command = ["codex", "app-server"]
    else:
        allowed = "Read,Glob,Grep" + (",Write,Edit" if args.mode == "workspace-write" else "")
        command = ["claude", "-p", "--verbose", "--input-format", "stream-json",
                   "--output-format", "stream-json", "--include-partial-messages",
                   "--permission-mode", "dontAsk", "--tools", allowed, "--allowedTools", allowed,
                   "--strict-mcp-config", "--mcp-config", '{"mcpServers":{}}',
                   "--disable-slash-commands", "--setting-sources", "user"]
        if args.resume:
            command += ["--resume", args.resume]
    proc = subprocess.Popen(command, cwd=args.cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, text=True, start_new_session=True)
    inbox = queue.Queue()
    for source, stream in (("cli", proc.stdout), ("control", sys.stdin)):
        threading.Thread(target=receive, args=(source, stream, inbox), daemon=True).start()

    def write(obj):
        proc.stdin.write(json.dumps(obj) + "\n")
        proc.stdin.flush()

    requests = {}
    seq = 0
    def rpc(method, params):
        nonlocal seq
        seq += 1
        requests[seq] = method
        write(dict(id=seq, method=method, params=params))

    def user(text):
        write(dict(type="user", message=dict(role="user", content=text)))

    def inputs(text):
        return [dict(type="text", text=text)]

    thread = turn = None
    pending = []
    messages = {}
    result = ""
    outstanding = 0
    status = 1
    try:
        if codex:
            rpc("initialize", dict(clientInfo=dict(name="reduction", version="1")))
        else:
            user(args.prompt)
            outstanding = 1
        while True:
            remaining = args.timeout - (time.monotonic()-started)
            if remaining <= 0:
                raise queue.Empty
            source, event = inbox.get(timeout=remaining)
            if source == "cli_eof":
                break
            if source == "control_eof":
                continue
            if not isinstance(event, dict):
                if source == "control":
                    emit("invalid_control")
                continue
            if source == "control":
                if event.get("stop") is True:
                    if codex and turn:
                        rpc("turn/interrupt", dict(threadId=thread, turnId=turn))
                    elif not codex:
                        write(dict(type="control_request", request_id="stop", request=dict(subtype="interrupt")))
                    status = 130
                    emit("interrupt")
                    break
                addition = event.get("send")
                if isinstance(addition, str) and addition.strip():
                    if codex and turn:
                        rpc("turn/steer", dict(threadId=thread, expectedTurnId=turn, input=inputs(addition)))
                    elif codex:
                        pending.append(addition)
                    else:
                        user(addition)
                        outstanding += 1
                    emit("input_sent" if not codex or turn else "input_queued")
                continue
            if codex:
                method = event.get("method")
                data = event.get("params", {})
                if "id" in event and not method:
                    request = requests.pop(event["id"], None)
                    if "error" in event:
                        emit("rpc_error", request=request, code=event["error"].get("code"))
                        break
                    response = event.get("result", {})
                    if request == "initialize":
                        write(dict(method="initialized"))
                        opts = dict(cwd=str(args.cwd), sandbox=args.mode, approvalPolicy="never")
                        if args.resume:
                            opts["threadId"] = args.resume
                        rpc("thread/resume" if args.resume else "thread/start", opts)
                    elif request in ("thread/start", "thread/resume"):
                        thread = response["thread"]["id"]
                        emit("session", id=thread)
                        rpc("turn/start", dict(threadId=thread, input=inputs(args.prompt)))
                    elif request == "turn/start":
                        turn = response["turn"]["id"]
                        for addition in pending:
                            rpc("turn/steer", dict(threadId=thread, expectedTurnId=turn, input=inputs(addition)))
                        pending.clear()
                    elif request == "turn/steer":
                        emit("steered")
                elif method:
                    if "id" in event:
                        write(dict(id=event["id"], error=dict(code=-32601, message="Unsupported request")))
                    elif method == "item/agentMessage/delta":
                        emit("delta", text=data["delta"])
                    elif method == "item/completed" and data["item"]["type"] == "agentMessage":
                        item = data["item"]
                        messages[item["id"]] = item["text"]
                    elif method == "turn/completed":
                        status = 0 if data["turn"]["status"] == "completed" else 1
                        result = "\n".join(messages.values())
                        break
                    elif method in ("turn/started", "item/started", "item/completed", "error"):
                        emit(method, kind=data.get("item", {}).get("type"))
            else:
                kind = event.get("type")
                if kind == "system" and event.get("subtype") == "init":
                    emit("session", id=event.get("session_id"))
                elif kind == "stream_event":
                    part = event.get("event", {})
                    delta = part.get("delta", {})
                    if delta.get("type") == "text_delta":
                        emit("delta", text=delta["text"])
                    else:
                        emit(part.get("type", "progress"))
                elif kind == "result":
                    outstanding -= 1
                    result = event.get("result", "")
                    emit("turn_result", success=not event.get("is_error"))
                    if event.get("is_error") or outstanding == 0:
                        status = int(bool(event.get("is_error")))
                        break
    except queue.Empty:
        status = 124
        emit("timeout")
    except (BrokenPipeError, KeyboardInterrupt):
        status = 130 if sys.exc_info()[0] is KeyboardInterrupt else 1
    finally:
        try:
            proc.stdin.close()
        except BrokenPipeError:
            pass
        try:
            proc.wait(timeout=1)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait()
        if not status and proc.returncode > 0:
            status = proc.returncode
        emit("exit", code=status, process_code=proc.returncode, result=result)
    return status


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cli", choices=["codex", "claude"])
    parser.add_argument("prompt")
    parser.add_argument("--cwd", required=True, type=lambda s: Path(s).resolve(strict=True))
    parser.add_argument("--mode", choices=["read-only", "workspace-write"], default="read-only")
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--resume")
    sys.exit(run(parser.parse_args()))
