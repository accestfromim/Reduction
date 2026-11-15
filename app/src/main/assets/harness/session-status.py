#!/usr/bin/env python3
"""Reports how the coding-agent sessions on this computer are doing.

Runs on the computer, outside any sandbox, and prints a short status summary per
session: when it last moved, what it is doing, and the opening words of the last
things said. The assistant on the phone is given this summary and nothing else: the
session records themselves never leave the computer.

Only the places listed in SOURCES are read. To watch another kind of session, add a
line there.
"""
import argparse
import glob
import json
import os
import re
import sqlite3
import sys
import time

HOME = os.path.expanduser("~")

# The whole whitelist: what is read, and by which reader. Nothing else is opened.
SOURCES = [
    ("claude", os.path.join(HOME, ".claude", "projects"), "read_claude"),
    ("codex", os.path.join(HOME, ".codex", "sessions"), "read_codex"),
    ("opencode", os.path.join(HOME, ".local", "share", "opencode", "opencode.db"), "read_opencode"),
]

# How much of the end of a record file is looked at.
TAIL_BYTES = 3_000_000
# A step that has not moved for this long is worth a look.
STUCK_SECONDS = 600

USER_CHARS = 80
REPLY_CHARS = 160

SECRETS = [
    re.compile(r"\b(?:sk|pk|rk|ghp|gho|ghu|ghs|glpat|xox[abprs])[-_][A-Za-z0-9_\-]{12,}"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{5,}"),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*", re.S),
    re.compile(r"(?i)\b(password|passwd|secret|token|api[_-]?key|authorization)\b(\s*[:=]\s*)\S+"),
    re.compile(r"\b[A-Za-z0-9_\-]{40,}\b"),
]


def clip(text, limit):
    """One line, secrets blanked, cut to `limit` characters."""
    text = " ".join(str(text or "").split())
    for pattern in SECRETS:
        if pattern.groups >= 2:
            text = pattern.sub(lambda m: m.group(1) + m.group(2) + "[已隐去]", text)
        else:
            text = pattern.sub("[已隐去]", text)
    return text if len(text) <= limit else text[:limit] + "…"


def ago(seconds):
    seconds = max(0, int(seconds))
    if seconds < 90:
        return "%d 秒前" % seconds
    if seconds < 5400:
        return "%d 分钟前" % (seconds // 60)
    if seconds < 172800:
        return "%d 小时前" % (seconds // 3600)
    return "%d 天前" % (seconds // 86400)


def tail_lines(path):
    """The complete lines within the last TAIL_BYTES of a file."""
    size = os.path.getsize(path)
    with open(path, "rb") as handle:
        if size > TAIL_BYTES:
            handle.seek(size - TAIL_BYTES)
            handle.readline()
        data = handle.read()
    return data.decode("utf-8", "replace").splitlines()


def first_line(path):
    with open(path, "rb") as handle:
        return handle.readline(2_000_000).decode("utf-8", "replace")


def iso_seconds(stamp):
    """Seconds since the epoch for a UTC time like 2026-11-15T17:47:20.604Z."""
    try:
        import calendar
        return calendar.timegm(time.strptime(str(stamp)[:19], "%Y-%m-%dT%H:%M:%S"))
    except (ValueError, TypeError):
        return None


def recent_files(root, pattern, since):
    paths = glob.glob(os.path.join(root, pattern), recursive=True)
    stamped = []
    for path in paths:
        try:
            moved = os.path.getmtime(path)
        except OSError:
            continue
        if moved >= since:
            stamped.append((moved, path))
    return sorted(stamped, reverse=True)


def session(kind, where, moved, state, user="", reply="", doing=""):
    return {"kind": kind, "where": where, "moved": moved, "state": state,
            "user": user, "reply": reply, "doing": doing}


def judge(now, moved, running, waiting_for_user, tool=""):
    """Words for what a session is doing, from its last record and how old that is."""
    idle = now - moved
    if waiting_for_user:
        return "等用户输入"
    if running and tool:
        if idle > STUCK_SECONDS:
            return "工具 %s 已运行 %s 没有动静：可能卡住，或在等用户批准" % (tool, ago(idle).replace("前", ""))
        return "工具运行中：%s" % tool
    if running:
        if idle > STUCK_SECONDS:
            return "这一步 %s 没有动静：可能已中断" % ago(idle).replace("前", "")
        return "模型处理中"
    return "空闲"


def read_claude(root, since, now):
    found = []
    for moved, path in recent_files(root, os.path.join("*", "*.jsonl"), since):
        where, user, reply, last = "", "", "", None
        open_tools = {}
        for line in tail_lines(path):
            try:
                item = json.loads(line)
            except ValueError:
                continue
            if item.get("type") not in ("user", "assistant") or item.get("isSidechain") or item.get("isMeta"):
                continue
            where = item.get("cwd") or where
            message = item.get("message") or {}
            content = message.get("content")
            blocks = content if isinstance(content, list) else [{"type": "text", "text": content}]
            for block in blocks:
                if not isinstance(block, dict):
                    continue
                if block.get("type") == "tool_use":
                    open_tools[block.get("id")] = block.get("name", "")
                elif block.get("type") == "tool_result":
                    open_tools.pop(block.get("tool_use_id"), None)
                elif block.get("type") == "text" and str(block.get("text") or "").strip():
                    text = block["text"]
                    if item["type"] == "assistant":
                        reply = text
                    elif not text.lstrip().startswith("<"):
                        user = text
            last = item
        if last is None:
            continue
        at = iso_seconds(last.get("timestamp")) or moved
        stop = (last.get("message") or {}).get("stop_reason")
        tool = next(iter(open_tools.values()), "")
        waiting = last["type"] == "assistant" and not open_tools and stop in ("end_turn", "stop_sequence")
        running = bool(open_tools) or last["type"] == "user" or not waiting
        found.append(session("claude", where or os.path.basename(os.path.dirname(path)), at,
                             judge(now, at, running, waiting, tool), user, reply))
    return found


def read_codex(root, since, now):
    found = []
    for moved, path in recent_files(root, os.path.join("**", "*.jsonl"), since):
        where, user, reply, event, at = "", "", "", "", moved
        try:
            meta = json.loads(first_line(path))
            where = (meta.get("payload") or {}).get("cwd", "")
        except ValueError:
            pass
        for line in tail_lines(path):
            try:
                item = json.loads(line)
            except ValueError:
                continue
            payload = item.get("payload") if isinstance(item.get("payload"), dict) else {}
            at = iso_seconds(item.get("timestamp")) or at
            if item.get("type") == "event_msg":
                event = payload.get("type", event)
            elif item.get("type") == "response_item" and payload.get("type") == "message":
                text = " ".join(str(block.get("text") or "") for block in payload.get("content") or []
                                if isinstance(block, dict))
                if payload.get("role") == "assistant" and text.strip():
                    reply = text
                elif payload.get("role") == "user" and text.strip() and not text.lstrip().startswith("<"):
                    user = text
        if event == "turn_aborted":
            state = "上一步被中止，等用户输入"
        else:
            finished = event in ("task_complete", "turn_complete")
            state = judge(now, at, running=not finished, waiting_for_user=finished)
        found.append(session("codex", where, at, state, user, reply))
    return found


def read_opencode(path, since, now):
    found = []
    # Read-only, and only the session tables: the same file also holds account tokens.
    db = sqlite3.connect("file:" + path + "?mode=ro", uri=True, timeout=3)
    try:
        rows = db.execute(
            "select id, title, directory, time_updated from session "
            "where time_updated >= ? and parent_id is null order by time_updated desc limit 40",
            (int(since * 1000),)).fetchall()
        for sid, title, directory, updated in rows:
            user, reply, tool, error = "", "", "", ""
            running = False
            last = db.execute(
                "select id, data from message where session_id = ? order by time_created desc limit 1",
                (sid,)).fetchone()
            if last:
                data = json.loads(last[1])
                done = (data.get("time") or {}).get("completed")
                running = data.get("role") == "user" or not done
                problem = data.get("error") or {}
                error = (problem.get("data") or {}).get("message") or problem.get("name") or ""
            parts = db.execute(
                "select p.data, m.data from part p join message m on m.id = p.message_id "
                "where p.session_id = ? order by p.time_created desc limit 80", (sid,))
            for raw, owner in parts:
                part = json.loads(raw)
                by_user = json.loads(owner).get("role") == "user"
                if part.get("type") == "tool" and not tool:
                    if (part.get("state") or {}).get("status") in ("running", "pending"):
                        tool = part.get("tool", "")
                elif part.get("type") == "text" and str(part.get("text") or "").strip():
                    if by_user and not user:
                        user = part["text"]
                    elif not by_user and not reply:
                        reply = part["text"]
            at = updated / 1000.0
            if error:
                state = "出错停下：" + clip(error, 80)
            else:
                state = judge(now, at, running or bool(tool), not running and not tool, tool)
            found.append(session("opencode", directory, at, state, user, reply, title))
    finally:
        db.close()
    return found


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--hours", type=float, default=24.0, help="only sessions that moved this recently")
    parser.add_argument("--limit", type=int, default=8, help="how many sessions to report")
    args = parser.parse_args()

    now = time.time()
    since = now - max(0.1, min(args.hours, 24 * 30)) * 3600
    sessions, notes = [], []
    for kind, path, reader in SOURCES:
        if not os.path.exists(path):
            continue
        try:
            sessions += globals()[reader](path, since, now)
        except Exception as problem:  # one unreadable source must not hide the others
            notes.append("%s 读取失败：%s" % (kind, clip(problem, 100)))

    sessions.sort(key=lambda item: item["moved"], reverse=True)
    shown = sessions[:max(1, args.limit)]
    hours = ("%g" % args.hours)
    if not shown:
        print("最近 %s 小时内没有活动的会话。" % hours)
    else:
        print("最近 %s 小时内有 %d 个会话，列出最近的 %d 个：" % (hours, len(sessions), len(shown)))
    for item in shown:
        clock = time.strftime("%m-%d %H:%M", time.localtime(item["moved"]))
        print()
        print("[%s] %s" % (item["kind"], item["where"] or "（目录未知）"))
        if item["doing"]:
            print("  标题：%s" % clip(item["doing"], 60))
        print("  最后活动：%s（%s）" % (ago(now - item["moved"]), clock))
        print("  状态：%s" % item["state"])
        if item["user"]:
            print("  用户最后说：%s" % clip(item["user"], USER_CHARS))
        if item["reply"]:
            print("  助手最后说：%s" % clip(item["reply"], REPLY_CHARS))
    for note in notes:
        print()
        print(note)


if __name__ == "__main__":
    sys.exit(main())
