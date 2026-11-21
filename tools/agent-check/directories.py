"""Exercise the actual Bubblewrap launcher with a deterministic local test program."""
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import time

LAUNCHER = Path(__file__).resolve().parents[2] / "app/src/main/assets/harness/run-agent.sh"

with TemporaryDirectory(prefix="reduction-directory-check-", dir="/var/tmp") as temp:
    base = Path(temp)
    home = base / "home"
    readable = base / "root with spaces"
    writable = base / "another root"
    leaf = readable / "drafts"
    ancestor = writable / "parent"
    protected = ancestor / "archive"
    outside = base / "unlisted"
    workspace = home / "reduction-agents" / "research"
    engine = home / ".opencode" / "bin" / "opencode"
    for directory in (leaf, protected, outside, engine.parent):
        directory.mkdir(parents=True)
        (directory / "sample.txt").write_text("fixture")
    (readable / "outside-link").symlink_to(outside)
    engine.write_text("""#!/bin/sh
set -eu
root=$HOME/../root\\ with\\ spaces
other=$HOME/../another\\ root
private=$HOME/../unlisted
test -d "$root"
test -d "$other"
test ! -e "$private/sample.txt"
test ! -e "$root/outside-link/sample.txt"
test ! -e /var/log
test -f "$other/parent/archive/sample.txt"
denied() {
  if "$@" 2>/dev/null; then
    echo "unexpectedly allowed: $*" >&2
    exit 1
  fi
}
case "$*" in
  *'--agent build'*)
    touch "$root/drafts/ok" "$other/ok"
    denied touch "$root/forbidden"
    denied touch "$other/parent/archive/forbidden"
    denied mv "$other/parent" "$other/renamed"
    ;;
  *'--agent plan'*)
    denied touch "$root/drafts/forbidden"
    denied touch "$other/forbidden"
    denied touch ./forbidden
    ;;
esac
printf 'permissions verified' > "$HOME/.reduction-result/result.md"
""")
    engine.chmod(0o700)
    rules = [
        {"path": str(leaf), "writable": True},
        {"path": str(protected), "writable": False},
        {"path": str(readable), "writable": False},
        {"path": str(writable), "writable": True},
    ]
    env = dict(os.environ, HOME=str(home), AGENT_KEY="local-fixture",
               AGENT_ENGINE="opencode", AGENT_DIRECTORIES=json.dumps(rules))

    def launcher(*args, overrides=None):
        return subprocess.check_output(
            ["sh", str(LAUNCHER), *map(str, args)],
            env=env | (overrides or {}), text=True, timeout=10,
        ).strip()

    for access, cwd in (("build", readable), ("plan", workspace)):
        started = launcher("start", access, cwd, "permissions smoke test")
        assert started.startswith("RUN "), started
        run = started.split()[1]
        stop_at = time.monotonic() + 8
        while True:
            status = launcher("status", cwd, run)
            if not status.startswith("STATE running"):
                break
            assert time.monotonic() < stop_at, status
            time.sleep(0.05)
        assert status.startswith("STATE done 0"), status
        assert status.endswith("permissions verified"), status
    assert (leaf / "ok").is_file() and (writable / "ok").is_file()
    assert "工作目录未获授权" in launcher("start", "build", outside, "blocked")
    bad_rules = rules + [{"path": str(readable / "outside-link"), "writable": True}]
    assert "实际绝对路径" in launcher(
        "start", "build", readable, "blocked",
        overrides={"AGENT_DIRECTORIES": json.dumps(bad_rules)},
    )
    print("Bubblewrap directory permissions passed")
