import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path


def sandbox_config(project, run, program, agent, domains, auth):
    codex_home = auth.get("CODEX_HOME")
    readable = [project, run / "home", run / "result", run / "prompt.txt", program if codex_home else program.parent]
    git_pointer = project / ".git"
    if git_pointer.is_file():
        common = subprocess.run(
            ["git", "-C", str(project), "rev-parse", "--path-format=absolute", "--git-common-dir"],
            check=True, capture_output=True, text=True,
        ).stdout.strip()
        readable.append(Path(common).resolve(strict=True))
    writable = [run / "home", run / "result"]
    if agent == "build":
        writable.append(project)
    if codex_home:
        readable.extend([Path(codex_home), Path(auth["CODEX_CA_CERTIFICATE"])])
        writable.append(Path(codex_home))
        if program.as_posix().endswith("/node_modules/@openai/codex/bin/codex.js"):
            node = shutil.which("node")
            if not node:
                sys.exit("harness：npm 安装的 Codex 需要 Node.js")
            readable.extend([program.parents[2], Path(node).resolve(strict=True).parent])

    def literal(paths):
        return [{"path": str(path), "literal": True} for path in paths]

    return {
        "filesystem": {
            "denyRead": literal([Path.home().resolve(), Path("/Users"), Path("/Volumes"), Path("/private/var/root")]),
            "allowRead": literal(readable),
            "allowWrite": literal(writable),
            "denyWrite": [],
        },
        "network": {"allowedDomains": domains, "deniedDomains": [], "allowLocalBinding": False},
    }


def backend(engine, model, agent, workspace, prompt, result_file):
    executable = shutil.which(engine)
    if not executable:
        sys.exit(f"harness：这台 Mac 上找不到 {engine}")
    program = Path(executable).resolve(strict=True)
    if engine == "opencode":
        key = os.environ.get("AGENT_KEY", "")
        if not re.fullmatch(r"[A-Za-z0-9_-]+", key):
            sys.exit("harness：请在 Reduction 中配置有效的 DeepSeek API Key")
        args = ["run", "--format", "json", "--model", model, "--agent", agent, "--dir", str(workspace), "--", prompt]
        return program, args, {"DEEPSEEK_API_KEY": key}, ["api.deepseek.com", "models.dev", "opencode.ai"]
    if engine == "codex":
        codex_home = Path.home() / ".local" / "share" / "reduction-codex"
        auth_file = codex_home / "auth.json"
        if not auth_file.is_file() or auth_file.stat().st_size == 0:
            sys.exit("harness：请在执行电脑上登录 Reduction 的 Codex，见 iOS 或桌面使用说明")
        # macOS 无法嵌套 Seatbelt。外层 srt 对 Codex 和所有子进程生效。
        args = [
            "exec", "--json", "--ephemeral", "--ignore-user-config", "--ignore-rules",
            "--skip-git-repo-check", "--dangerously-bypass-approvals-and-sandbox",
            "-c", 'cli_auth_credentials_store="file"', "--output-last-message", str(result_file),
            "--", prompt,
        ]
        # 沙箱里的 Codex 通过 PEM 文件验证 macOS 系统信任的证书。
        certificates = Path(os.environ.get("CODEX_CA_CERTIFICATE") or "/etc/ssl/cert.pem").expanduser().resolve(strict=True)
        auth = {"CODEX_HOME": str(codex_home), "CODEX_CA_CERTIFICATE": str(certificates)}
        return program, args, auth, ["api.openai.com", "auth.openai.com", "chatgpt.com"]
    if engine == "claude":
        auth = {name: os.environ[name] for name in ("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN") if os.environ.get(name)}
        if not auth:
            sys.exit("harness：请为 Claude Code 设置 ANTHROPIC_API_KEY 或 CLAUDE_CODE_OAUTH_TOKEN")
        args = ["-p", "--output-format", "stream-json", "--verbose", "--permission-mode", "auto", "--", prompt]
        return program, args, auth, ["api.anthropic.com", "claude.ai", "platform.claude.com"]
    sys.exit(f"harness：未知的执行者 {engine}")


def wait_for_run(process, seconds):
    try:
        status = process.wait(timeout=seconds)
        return status if status >= 0 else 128 - status
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        return 124


def background(run, command, cwd, env, seconds, socket_root):
    receipt, notify = os.pipe()
    if os.fork():
        os.close(notify)
        with os.fdopen(receipt, "rb") as started:
            if started.read(1) != b"1":
                sys.exit("harness：macOS 沙箱启动失败，见 " + str(run / "events.jsonl"))
        print("RUN " + run.name)
        return

    os.close(receipt)
    os.setsid()
    with open(os.devnull, "rb") as quiet, (run / "events.jsonl").open("ab", buffering=0) as events:
        os.dup2(quiet.fileno(), 0)
        os.dup2(events.fileno(), 1)
        os.dup2(events.fileno(), 2)
        # macOS 的 Unix socket 路径不能超过 103 字节，任务目录通常比这个长。
        with tempfile.TemporaryDirectory(prefix="r", dir=socket_root) as socket_dir:
            process = subprocess.Popen(command, cwd=cwd, env={**env, "TMPDIR": socket_dir}, start_new_session=True)
            (run / "pid").write_text(str(process.pid) + "\n", encoding="ascii")
            os.write(notify, b"1")
            os.close(notify)
            status = wait_for_run(process, seconds)
        # 手动停止已经记录状态时，保留调用方写入的状态。
        try:
            with (run / "exit").open("x", encoding="ascii") as result:
                result.write(str(status) + "\n")
        except FileExistsError:
            pass
    os._exit(0)


def main():
    state, folder, agent, prompt, engine, seconds, model = sys.argv[1:]
    seconds = int(seconds)
    if seconds <= 0:
        sys.exit("harness：运行时间必须大于零")
    srt = shutil.which("srt")
    if not srt:
        sys.exit("harness：macOS 需要 Anthropic Sandbox Runtime（srt），请按电脑端说明安装")

    project = Path(folder).resolve(strict=True)
    run = Path(state).resolve() / "runs" / uuid.uuid4().hex
    program, args, auth, domains = backend(engine, model, agent, project, prompt, run / "result" / "result.md")
    domains += os.environ.get("REDUCTION_AGENT_ALLOWED_DOMAINS", "").split()
    os.umask(0o077)
    home = run / "home"
    temp = home / "tmp"
    temp.mkdir(parents=True)
    (run / "result").mkdir()
    (home / ".reduction-result").symlink_to(run / "result", target_is_directory=True)
    (run / "prompt.txt").write_text(prompt + "\n", encoding="utf-8")
    settings = run / "sandbox.json"
    settings.write_text(json.dumps(sandbox_config(project, run, program, agent, domains, auth)), encoding="utf-8")
    socket_root = Path.home() / ".cache" / "reduction"
    socket_root.mkdir(parents=True, exist_ok=True)
    env = {
        "HOME": str(home), "LANG": "en_US.UTF-8", "TERM": "dumb",
        "PATH": os.pathsep.join(item for item in os.environ.get("PATH", "").split(os.pathsep) if os.path.isabs(item)),
        "OPENCODE_DISABLE_AUTOUPDATE": "true", **auth,
    }
    command = [srt, "--settings", str(settings), "--", "/usr/bin/env", f"TMPDIR={temp}", str(program), *args]
    background(run, command, project, env, seconds, socket_root)


if __name__ == "__main__":
    main()
