#!/bin/sh
# Runs sub agents with the directory policy supplied by Reduction, in the background.
#
#   run-agent.sh start  <build|plan> <folder> <prompt>   prints: RUN <id>
#   run-agent.sh status <folder> <id>                    prints: STATE <running|done N|lost>,
#                                                        then a line "=====RESULT FILE=====" and what the agent
#                                                        wrote into its result file, if anything
#
# AGENT_ENGINE 选择执行器，可用 opencode（默认）、codex 或 claude。
# A run is ended after AGENT_TIMEOUT seconds; its exit code is then 124.
#
# Every run is given a folder of its own, ~/.reduction-result, to leave its final result in:
# result.md there is the second way a result comes back, beside what the agent prints.
# Pictures left in that folder (png, jpg, jpeg, webp, gif) come back with the result, to be
# looked at by whoever receives it: after the result file, for each picture, a line
# "=====IMAGE <name> <media type>=====" and the file in base64. At most 4 pictures of at
# most 3 MB each, in the order of their names; the rest are named on a line
# "=====IMAGE SKIPPED <name>=====" and not sent.
#   run-agent.sh stop   <folder> <id>                    prints: STOPPED
#
# AGENT_DIRECTORIES is the saved list of absolute or ~/ paths with read/write permissions.
# Only configured user directories and the selected private workspace are mounted.
# A plan run mounts all work directories read-only. The result directory stays writable.
#
# The one credential an agent can read is the one it runs on. For opencode that is the
# model key the phone sends with each start (AGENT_KEY), the phone's own DeepSeek key.
# Claude Code runs on this computer's own login: it is given its settings folder, with
# the records of other sessions in it hidden, and nothing else of the home directory.
# Codex 使用执行电脑上专供 Reduction 的登录目录。
set -eu

home=$HOME
model=${AGENT_MODEL:-deepseek/deepseek-flash}
limit=${AGENT_TIMEOUT:-900}
engine=${AGENT_ENGINE:-opencode}
opencode=$home/.opencode/bin/opencode

# Older single-shot clients also receive the native final answer. Consume stdout once
# at execution time so status never fetches or parses a transcript.
legacy_capture='
import json, subprocess, sys
from pathlib import Path
target, engine = Path(sys.argv[1]), sys.argv[2]
proc = subprocess.Popen(sys.argv[3:], stdout=subprocess.PIPE, text=True, errors="replace")
final = ""
for line in proc.stdout:
    try:
        item = json.loads(line)
    except ValueError:
        continue
    if engine == "claude" and item.get("type") == "result":
        final = item.get("result", "")
    elif engine == "opencode" and item.get("type") == "text":
        final = item.get("part", {}).get("text", "")
if final:
    try:
        with target.open("x", encoding="utf-8") as result:
            result.write(final)
    except FileExistsError:
        pass
sys.exit(proc.wait())
'

say() { printf '%s\n' "$1"; exit 0; }

# Resolve <folder> without creating arbitrary host paths. Only a named private workspace may be created.
locate() {
  dir=$1
  case "$dir" in "~/"*) dir=$home/${dir#"~/"} ;; esac
  case "$dir" in /*) ;; *) say "harness：工作目录需要绝对路径或 ~/ 路径" ;; esac
  workspace=
  case "$dir" in
    "$home"/reduction-agents/*)
      leaf=${dir#"$home"/reduction-agents/}
      case "$leaf" in
        ''|.*|*[!A-Za-z0-9._-]*) ;;
        *)
          [ ! -L "$home/reduction-agents" ] && [ ! -L "$dir" ] || say "harness：独立工作目录不能是符号链接"
          [ "$cmd" != start ] || mkdir -p -- "$dir" || say "harness：无法创建工作目录"
          workspace=$dir ;;
      esac ;;
  esac
  if [ -d "$dir" ]; then
    resolved=$(cd -- "$dir" && pwd -P)
    [ "$cmd" != start ] || [ "$dir" = "$resolved" ] || say "harness：工作目录需要使用实际路径：$resolved"
    dir=$resolved
  elif [ "$cmd" = start ]; then
    say "harness：找不到工作目录：$dir"
  fi
  # Preserve the location of the records of existing runs.
  name=$(printf '%s' "${dir#"$home"/}" | tr -c 'A-Za-z0-9._-' '_')
  state=$home/.local/share/reduction-agents/$name
  legacy_state=$state
  if [ -z "$workspace" ]; then
    digest=$(python3 -c 'import hashlib, os, sys; print(hashlib.sha256(os.fsencode(sys.argv[1])).hexdigest())' "$dir")
    state=$home/.local/share/reduction-agents/by-path/${digest%% *}
  fi
}

mount_plan() {
  python3 - "$home" "$dir" "$workspace" "$agent" <<'PYMOUNT'
import json
import os
import sys
from pathlib import Path

home, workdir, workspace, agent = sys.argv[1:]
system = {"/usr", "/bin", "/sbin", "/lib", "/lib64", "/proc", "/dev", "/tmp", "/etc",
          "/sys", "/run", "/boot"}
private = {home + "/" + part for part in (
    ".opencode", ".claude", ".claude.json", ".config/opencode", ".cache/opencode",
    ".local/share/opencode", ".local/share/reduction", ".local/share/reduction-agents",
    ".config/reduction", ".local/share/reduction-codex", ".reduction-result", "reduction-agents",
)}
if os.environ.get("REDUCTION_CLAUDE_ROOT"):
    private.add(os.environ["REDUCTION_CLAUDE_ROOT"])

def within(path, parent):
    return path == parent or path.startswith(parent.rstrip("/") + "/")

try:
    entries = json.loads(os.environ.get("AGENT_DIRECTORIES", "[]"))
    rules = {}
    for entry in entries:
        raw = entry["path"]
        path = home + raw[1:] if raw.startswith("~/") else raw
        if (not path.startswith("/") or not path.isprintable()
                or os.path.normpath(path) != path or os.path.realpath(path) != path):
            raise ValueError(f"请填写目录的实际绝对路径：{raw!r}")
        if path in {"/mnt", "/media", "/var", "/opt"} or within(home, path) or any(
                within(path, reserved) or within(reserved, path) for reserved in system | private):
            raise ValueError(f"不能授权系统或应用内部目录：{raw}")
        if not os.path.isdir(path):
            raise ValueError(f"目录不存在：{raw}")
        if path in rules:
            raise ValueError(f"重复授权同一目录：{raw}")
        rules[path] = agent == "build" and entry["writable"] is True

    if workspace:
        rules[workspace] = agent == "build"
    if workdir not in rules:
        raise ValueError(f"工作目录未获授权：{workdir}")

    mounts = dict(rules)
    for path in rules:
        parents = [str(parent) for parent in Path(path).parents]
        owner = next((parent for parent in parents if parent in rules), None)
        if owner:
            # Pin intermediate directories so a writable parent cannot rename one
            # and thereby expose the original directory below a read-only mount.
            for parent in parents[:parents.index(owner)]:
                mounts.setdefault(parent, rules[owner])

    for path, writable in sorted(mounts.items(), key=lambda entry: entry[0].count("/")):
        print("--bind" if writable else "--ro-bind", path, sep="\t")
except (ValueError, KeyError, TypeError, AttributeError) as exc:
    sys.exit(str(exc))
PYMOUNT
}

run_dir() {
  case "$1" in
    *[!A-Za-z0-9-]*|'') say "harness：运行编号无效" ;;
  esac
  run=$state/runs/$1
  [ -d "$run" ] || run=$legacy_state/runs/$1
  [ -d "$run" ] || say "STATE lost"
}

cmd=${1:?command}
shift

case "$cmd" in
start)
  agent=${1:?agent}
  locate "${2:?folder}"
  prompt=${3:?prompt}
  body=${4:-}
  umask 077
  payload_dir=
  if [ "$prompt" = --task-stdin ] && [ -n "${REDUCTION_TASK_DRIVER:-}" ]; then
    payload_dir=$(mktemp -d)
    trap 'rm -f -- "$payload_dir/prompt" "$payload_dir/body"; rmdir -- "$payload_dir"' EXIT
    python3 -c 'import json, pathlib, sys
data = json.load(sys.stdin)
for key in ("prompt", "body"):
    (pathlib.Path(sys.argv[1]) / key).write_text(data[key], encoding="utf-8")' "$payload_dir"
  fi
  case "$agent" in build|plan) ;; *) say "harness：未知的 agent 类型 $agent" ;; esac
  case "$engine" in opencode|codex|claude) ;; *) say "harness：未知的执行者 $engine" ;; esac
  if [ "$(uname -s)" = Darwin ]; then
    export PATH="$home/.local/bin:$home/.opencode/bin:/opt/homebrew/bin:/usr/local/bin:$PATH:/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS"
    command -v python3 >/dev/null 2>&1 || say "harness：macOS 需要 Python 3"
    [ -n "${REDUCTION_MAC_AGENT_SCRIPT:-}" ] || say "harness：没有收到 macOS 沙箱启动程序，请更新发出任务的 Reduction"
    mounts=$(mount_plan 2>&1) || say "harness：$mounts"
    printf '%s' "$REDUCTION_MAC_AGENT_SCRIPT" | base64 -d | REDUCTION_TASK_INPUT="$payload_dir" REDUCTION_LEGACY_CAPTURE="$legacy_capture" REDUCTION_AGENT_MOUNTS="$mounts" python3 - "$state" "$dir" "$agent" "$prompt" "$engine" "$limit" "$model" "$body"
    exit
  fi
  command -v bwrap >/dev/null 2>&1 || say "harness：电脑上没有 bwrap，无法隔离运行"
  case "$engine" in
  codex)
    codex=$(PATH="$home/.local/bin:$PATH" command -v codex) || say "harness：这台电脑上找不到 Codex CLI"
    codex=$(readlink -f "$codex")
    codex_home=$home/.local/share/reduction-codex
    [ -s "$codex_home/auth.json" ] || say "harness：请在执行电脑上登录 Reduction 的 Codex，见 iOS 或桌面使用说明"
    umask 077
    ;;
  claude)
    claude=$(readlink -f "$(PATH="$home/.local/bin:$PATH" command -v claude 2>/dev/null || echo /nonexistent)")
    [ -x "$claude" ] || say "harness：这台电脑上找不到 Claude Code"
    [ -d "$home/.claude" ] || say "harness：这台电脑上的 Claude Code 还没有登录"
    ;;
  opencode)
    [ -x "$opencode" ] || say "harness：找不到 opencode"
    mkdir -p "$state/opencode"
    # The agent runs on the key the phone sent. Without one it falls back to this computer's.
    key=${AGENT_KEY:-}
    if [ -n "$key" ]; then
      case "$key" in *[!A-Za-z0-9_-]*) say "harness：模型密钥格式无效" ;; esac
      ( umask 077; printf '{"deepseek":{"type":"api","key":"%s"}}\n' "$key" > "$state/opencode/auth.json" )
    else
      cp -p "$home/.local/share/opencode/auth.json" "$state/opencode/auth.json" 2>/dev/null \
        || say "harness：没有收到模型密钥，这台电脑的 opencode 也没有登录"
    fi
    ;;
  esac

  id=$(date +%Y%m%d-%H%M%S)-$$
  run=$state/runs/$id
  mkdir -p "$run/result"
  if [ -n "$payload_dir" ]; then
    cp "$payload_dir/prompt" "$run/prompt.txt"
  else
    printf '%s\n' "$prompt" > "$run/prompt.txt"
  fi

  if [ -n "${REDUCTION_TASK_DRIVER:-}" ]; then
    printf '%s' "$REDUCTION_TASK_DRIVER" | base64 -d > "$run/bridge.py"
    if [ -n "$payload_dir" ]; then
      python3 "$run/bridge.py" init "$run" < "$payload_dir/body"
    else
      printf '%s' "$body" | python3 "$run/bridge.py" init "$run"
    fi
  fi

  if [ "$engine" = claude ]; then
    REDUCTION_CLAUDE_ROOT=$(dirname "$(dirname "$claude")")
    export REDUCTION_CLAUDE_ROOT
  fi
  mounts=$(mount_plan 2>&1) || say "harness：$mounts"
  set -- \
    --clearenv --setenv HOME "$home" --setenv PATH /usr/local/bin:/usr/bin:/bin \
    --setenv LANG C.UTF-8 --setenv TERM dumb \
    --ro-bind /usr /usr --dev /dev --proc /proc --tmpfs /tmp \
    --ro-bind-try /etc/ssl/certs /etc/ssl/certs \
    --ro-bind-try /etc/resolv.conf /etc/resolv.conf --ro-bind-try /etc/hosts /etc/hosts \
    --ro-bind-try /etc/nsswitch.conf /etc/nsswitch.conf --ro-bind-try /etc/ld.so.cache /etc/ld.so.cache \
    --ro-bind-try /etc/passwd /etc/passwd --ro-bind-try /etc/group /etc/group \
    --bind "$run/result" "$home/.reduction-result" \
    --unshare-user --disable-userns --unshare-pid --unshare-ipc --die-with-parent --new-session
  # Carry the host's network route into the otherwise empty sandbox environment.
  for proxy in HTTPS_PROXY HTTP_PROXY NO_PROXY https_proxy http_proxy no_proxy; do
    value=$(printenv "$proxy") || continue
    case "$value" in *@*) continue ;; esac
    set -- "$@" --setenv "$proxy" "$value"
  done
  if [ -f "$run/bridge.py" ]; then
    set -- "$@" --ro-bind "$run/channel" "$home/.reduction-task"
  fi
  for system_dir in /bin /sbin /lib /lib64; do
    if [ -L "$system_dir" ]; then
      set -- "$@" --symlink "$(readlink "$system_dir")" "$system_dir"
    elif [ -d "$system_dir" ]; then
      set -- "$@" --ro-bind "$system_dir" "$system_dir"
    fi
  done
  tab=$(printf '\t')
  while IFS="$tab" read -r binding path; do
    set -- "$@" "$binding" "$path" "$path"
  done <<MOUNTS
$mounts
MOUNTS
  set -- "$@" --chdir "$dir"

  case "$engine" in
  codex)
    set -- "$@" --setenv CODEX_HOME "$codex_home" --bind "$codex_home" "$codex_home"
    case "$codex" in
      */node_modules/@openai/codex/bin/codex.js)
        packages=${codex%/codex/bin/codex.js}
        node=$(command -v node) || say "harness：npm 安装的 Codex 需要 Node.js"
        node=$(readlink -f "$node")
        set -- "$@" --ro-bind "$packages" "$packages" --ro-bind "$(dirname "$node")" "$(dirname "$node")" \
          --setenv PATH "$(dirname "$node"):/usr/local/bin:/usr/bin:/bin"
        ;;
      "$home"/*)
        set -- "$@" --ro-bind "$codex" "$codex"
        ;;
    esac
    # bwrap 负责隔离工作目录。Codex 在这个已隔离的进程里执行。
    if [ -f "$run/bridge.py" ]; then
      set -- "$@" "$codex" app-server --enable default_mode_request_user_input -c 'cli_auth_credentials_store="file"'
    else
      set -- "$@" timeout "$limit" "$codex" exec --json --ephemeral --ignore-user-config --ignore-rules \
      --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -c 'cli_auth_credentials_store="file"' \
      --output-last-message "$home/.reduction-result/result.md" -
    fi
    input=$run/prompt.txt
    ;;
  claude)
    # Claude Code itself, its settings and login, and nothing of what other sessions said.
    set -- "$@" --ro-bind "$(dirname "$(dirname "$claude")")" "$(dirname "$(dirname "$claude")")" --bind "$home/.claude" "$home/.claude"
    if [ -f "$run/bridge.py" ]; then
      mkdir -p "$run/claude-projects"
      set -- "$@" --bind "$run/claude-projects" "$home/.claude/projects"
    else
      set -- "$@" --tmpfs "$home/.claude/projects"
    fi
    [ -f "$home/.claude.json" ] && set -- "$@" --bind "$home/.claude.json" "$home/.claude.json"
    # Whatever this computer tells Claude Code through its environment goes with it; nothing else does.
    for name in $(env | sed -n 's/^\(ANTHROPIC_[A-Z0-9_]*\|CLAUDE_CODE_[A-Z0-9_]*\)=.*/\1/p'); do
      eval "value=\${$name}"
      set -- "$@" --setenv "$name" "$value"
    done
    # Auto mode: nobody is there to answer a prompt, so Claude Code's own safety check decides each
    # step, allowing ordinary work and refusing what looks dangerous. The walls around it hold either way.
    set -- "$@" timeout "$limit" "$claude" -p --output-format stream-json --verbose --permission-mode auto
    input=$run/prompt.txt
    ;;
  opencode)
    set -- "$@" --ro-bind "$home/.opencode" "$home/.opencode" --bind "$state/opencode" "$home/.local/share/opencode"
    [ -d "$home/.config/opencode" ] && set -- "$@" --ro-bind "$home/.config/opencode" "$home/.config/opencode"
    [ -d "$home/.cache/opencode" ] && set -- "$@" --bind "$home/.cache/opencode" "$home/.cache/opencode"
    set -- "$@" timeout "$limit" "$opencode" run --format json --model "$model" --agent "$agent" --auto --dir "$dir"
    [ -f "$run/bridge.py" ] || set -- "$@" -- "$prompt"
    input=/dev/null
    ;;
  esac

  if [ -f "$run/bridge.py" ]; then
    python3 "$run/bridge.py" launch "$run" "$engine" "$dir" "$limit" bwrap "$@"
    printf 'RUN %s\nTASK_PATH %s\n' "$id" "$run"
    exit
  fi

  set -- python3 -c "$legacy_capture" "$run/result/result.md" "$engine" bwrap "$@"
  # Detached from this shell, so the run outlives the SSH command that started it.
  nohup setsid sh -c '
    out=$1; input=$2; shift 2
    "$@" > "$out/events.jsonl" 2>&1 < "$input"
    echo $? > "$out/exit"
  ' sh "$run" "$input" "$@" > /dev/null 2>&1 < /dev/null &
  echo $! > "$run/pid"
  printf 'RUN %s\n' "$id"
  ;;

status|pictures)
  locate "${1:?folder}"
  run_dir "${2:?id}"
  if [ "$cmd" = pictures ]; then
    printf 'STATE done 0\n'
  elif [ -f "$run/exit" ]; then
    printf 'STATE done %s\n' "$(cat "$run/exit")"
  elif [ -s "$run/pid" ] && kill -0 "$(cat "$run/pid")" 2>/dev/null; then
    printf 'STATE running\n'
  else
    printf 'STATE lost\n'
  fi
  if [ "$cmd" = status ] && [ ! -L "$run/result" ] && [ -f "$run/result/result.md" ] && [ ! -L "$run/result/result.md" ]; then
    printf '\n=====RESULT FILE=====\n'
    cat "$run/result/result.md"
  fi
  # Pictures are handed over once the run is over, not while it may still be drawing them.
  if { [ "$cmd" = pictures ] || [ -f "$run/exit" ]; } && [ ! -L "$run/result" ]; then
    sent=0
    for picture in "$run/result"/*; do
      [ -f "$picture" ] && [ ! -L "$picture" ] || continue
      name=$(basename "$picture")
      case "$name" in *[!A-Za-z0-9._-]*) continue ;; esac
      case "$name" in
        *.png|*.PNG) kind=image/png ;;
        *.jpg|*.jpeg|*.JPG|*.JPEG) kind=image/jpeg ;;
        *.webp|*.WEBP) kind=image/webp ;;
        *.gif|*.GIF) kind=image/gif ;;
        *) continue ;;
      esac
      if [ "$sent" -ge 4 ] || [ "$(wc -c < "$picture")" -gt 3145728 ]; then
        printf '\n=====IMAGE SKIPPED %s=====\n' "$name"
        continue
      fi
      sent=$((sent + 1))
      printf '\n=====IMAGE %s %s=====\n' "$name" "$kind"
      base64 < "$picture" | tr -d '\r\n'
    done
  fi
  ;;

task)
  locate "${1:?folder}"
  run_dir "${2:?id}"
  [ -f "$run/bridge.py" ] || say '{"error":"此旧会话没有任务卡通道"}'
  exec python3 "$run/bridge.py" rpc "$run"
  ;;

stop)
  locate "${1:?folder}"
  run_dir "${2:?id}"
  if [ -f "$run/bridge.py" ]; then
    python3 "$run/bridge.py" stop "$run"
    say "STOPPED"
  fi
  if [ ! -f "$run/exit" ]; then
    pid=$(cat "$run/pid" 2>/dev/null || echo 0)
    [ "$pid" -gt 1 ] 2>/dev/null && kill -TERM -- "-$pid" 2>/dev/null || true
    (set -C; echo 143 > "$run/exit") 2>/dev/null || true
  fi
  printf 'STOPPED\n'
  ;;

*)
  say "harness：未知命令 $cmd"
  ;;
esac
