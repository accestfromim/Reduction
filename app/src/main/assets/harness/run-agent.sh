#!/bin/sh
# Runs opencode sub agents, each confined to a single folder, in the background.
#
#   run-agent.sh start  <build|plan> <folder> <prompt>   prints: RUN <id>
#   run-agent.sh status <folder> <id>                    prints: STATE <running|done N|lost>, then the run's events
#   run-agent.sh stop   <folder> <id>                    prints: STOPPED
#
# The agent may do anything opencode can do — run commands, use the network, write
# files — but the only part of the file system it can change is <folder>. The rest of
# the home directory, other projects, keys and Windows drives are not visible to it;
# the system is read-only. The environment is emptied so no host secret leaks in.
# "plan" agents do not change files at all.
#
# The one credential an agent can read is the model key it runs on: the one the phone
# sends with each start (AGENT_KEY), which is the phone's own DeepSeek key.
set -eu

home=$HOME
model=${AGENT_MODEL:-deepseek/deepseek-flash}
limit=${AGENT_TIMEOUT:-900}
opencode=$home/.opencode/bin/opencode

say() { printf '%s\n' "$1"; exit 0; }

# Checks <folder>, creates it, and sets $dir (resolved) and $state (opencode's own state
# for this folder, kept outside every agent folder).
locate() {
  dir=$1
  case "$dir" in
    "$home"/?*) ;;
    *) say "harness：文件夹必须在 $home 之下：$dir" ;;
  esac
  mkdir -p "$dir" 2>/dev/null || say "harness：无法创建文件夹：$dir"
  dir=$(cd "$dir" && pwd -P)
  case "$dir" in
    "$home"/?*) ;;
    *) say "harness：文件夹解析后不在 $home 之下：$dir" ;;
  esac
  name=$(printf '%s' "${dir#"$home"/}" | tr -c 'A-Za-z0-9._-' '_')
  state=$home/.local/share/entropy-agents/$name
}

run_dir() {
  case "$1" in
    *[!A-Za-z0-9-]*|'') say "harness：运行编号无效" ;;
  esac
  run=$state/runs/$1
  [ -d "$run" ] || say "STATE lost"
}

cmd=${1:?command}
shift

case "$cmd" in
start)
  agent=${1:?agent}
  locate "${2:?folder}"
  prompt=${3:?prompt}
  case "$agent" in build|plan) ;; *) say "harness：未知的 agent 类型 $agent" ;; esac
  command -v bwrap >/dev/null 2>&1 || say "harness：电脑上没有 bwrap，无法隔离运行"
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

  id=$(date +%Y%m%d-%H%M%S)-$$
  run=$state/runs/$id
  mkdir -p "$run"
  printf '%s\n' "$prompt" > "$run/prompt.txt"

  set -- \
    --clearenv --setenv HOME "$home" --setenv PATH /usr/local/bin:/usr/bin:/bin \
    --setenv LANG C.UTF-8 --setenv TERM dumb \
    --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp --tmpfs /mnt --tmpfs "$home" \
    --ro-bind "$home/.opencode" "$home/.opencode" \
    --bind "$state" "$home/.local/share" \
    --bind "$dir" "$dir" --chdir "$dir" \
    --unshare-pid --die-with-parent --new-session
  [ -d /mnt/wsl ] && set -- "$@" --ro-bind /mnt/wsl /mnt/wsl
  [ -d "$home/.config/opencode" ] && set -- "$@" --ro-bind "$home/.config/opencode" "$home/.config/opencode"
  [ -d "$home/.cache/opencode" ] && set -- "$@" --bind "$home/.cache/opencode" "$home/.cache/opencode"

  # A git worktree keeps its history in the main repository; the agent needs that to use git.
  if [ -f "$dir/.git" ]; then
    common=$(git -C "$dir" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)
    [ -n "$common" ] && [ -d "$common" ] && set -- "$@" --bind "$common" "$common"
  fi

  # Detached from this shell, so the run outlives the SSH command that started it.
  nohup setsid sh -c '
    out=$1; shift
    "$@" > "$out/events.jsonl" 2>&1 < /dev/null
    echo $? > "$out/exit"
  ' sh "$run" bwrap "$@" timeout "$limit" "$opencode" run --format json --model "$model" \
    --agent "$agent" --auto --dir "$dir" "$prompt" > /dev/null 2>&1 < /dev/null &
  echo $! > "$run/pid"
  printf 'RUN %s\n' "$id"
  ;;

status)
  locate "${1:?folder}"
  run_dir "${2:?id}"
  if [ -f "$run/exit" ]; then
    printf 'STATE done %s\n' "$(cat "$run/exit")"
  elif kill -0 "$(cat "$run/pid" 2>/dev/null || echo 0)" 2>/dev/null; then
    printf 'STATE running\n'
  else
    printf 'STATE lost\n'
  fi
  [ -f "$run/events.jsonl" ] && tail -c 400000 "$run/events.jsonl"
  ;;

stop)
  locate "${1:?folder}"
  run_dir "${2:?id}"
  pid=$(cat "$run/pid" 2>/dev/null || echo 0)
  [ "$pid" -gt 1 ] 2>/dev/null && kill -TERM -- "-$pid" 2>/dev/null || true
  [ -f "$run/exit" ] || echo 143 > "$run/exit"
  printf 'STOPPED\n'
  ;;

*)
  say "harness：未知命令 $cmd"
  ;;
esac
