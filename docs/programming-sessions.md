# 本机编程会话驱动

入口为 `tools/session/drive.py`，使用 Python 3 标准库。调用方通过 stdin 发送每行一个 JSON，通过 stdout 逐行接收事件。每一行立即刷新，ms 为从启动算起的单调时间。session 提供可恢复的 id，delta 提供增量文本。exit 提供最终回复全文、调用方退出码和原始子进程退出码。调用方的中止码为 130，超时码为 124。发起端通过已有 CLI 的用户配置读取登录态。

| 结论 | 本机出处 |
|---|---|
| Codex CLI 0.163.0，单次任务可用 JSONL 输出，后续可恢复会话 | `codex --version`、`codex exec --help`、`codex exec resume --help` |
| 双向控制使用 app-server 的标准输入输出。线程支持 start/resume，活跃 turn 支持 steer/interrupt。文本增量与完成通知独立送达 | `codex app-server --help`、`codex app-server generate-ts --out /tmp/reduction-protocol`。查生成的 ClientRequest.ts 和 ServerNotification.ts |
| Claude Code 2.1.325 支持 stream-json 输入输出、部分消息、工具白名单、恢复会话 | `claude --version`、`claude --help`，检索 input-format、output-format、include-partial-messages、tools、permission-mode、resume |
| Claude 在一次推理期间收到的第二条 user 消息，在本次实测中进入同一会话的下一轮。Codex 能在当前 turn 接受 steer | 下方两个客户端的实测时间序列 |

工作目录和工作区访问模式由调用方指定。Codex 使用原生 sandbox 和 never 审批策略。Claude 使用 dontAsk、显式工具列表和空 MCP 配置，只读模式开放 Read、Glob、Grep，可写模式另开 Edit 和 Write。Claude 的工具权限由本机 CLI 实施，cwd 指定起始目录，此驱动没有建立独立的操作系统文件隔离。使用可信的本机用户配置。stdout 是给调用方的实时内容，调用方自行决定是否保存。

在仓库根目录运行以下命令。先创建空目录作为工作区。将 CLI 改为 claude 即可重复。用上一次 session 事件中的 id 配合 `--resume ID` 恢复。CLI 可执行文件须在 PATH 中。

```bash
export PATH="$HOME/.local/bin:$PATH"
mkdir -p /tmp/reduction-session-demo
(sleep 1; echo '{"send":"Reply with only SECOND instead."}') |
  python3 tools/session/drive.py codex 'Reply with only FIRST.' --cwd /tmp/reduction-session-demo --mode read-only --timeout 65
(sleep 3; echo '{"stop":true}') |
  python3 tools/session/drive.py codex 'Count slowly from one to a hundred.' --cwd /tmp/reduction-session-demo
```

## 实测时间线

以下为固定任务终端输出摘录。时间单位为毫秒。CLI 自行记录的会话可用各自原有的恢复机制访问。

### codex / live

```jsonl
{"ms": 399, "event": "session", "id": "01a1f7f0-6632-7a81-af2a-36e963f8bd16"}
{"ms": 669, "event": "turn/started", "kind": null}
{"ms": 971, "event": "input_sent"}
{"ms": 972, "event": "steered"}
{"ms": 7842, "event": "delta", "text": "SECOND"}
{"ms": 7973, "event": "exit", "code": 0, "process_code": 0, "result": "SECOND"}
```

### codex / stop

```jsonl
{"ms": 401, "event": "session", "id": "01a1f7f0-6634-7372-a16a-c8b361e12fb9"}
{"ms": 668, "event": "turn/started", "kind": null}
{"ms": 2973, "event": "interrupt"}
{"ms": 3005, "event": "exit", "code": 130, "process_code": 0, "result": ""}
```

### codex / resume

```jsonl
{"ms": 641, "event": "session", "id": "01a1f7f0-6632-7a81-af2a-36e963f8bd16"}
{"ms": 651, "event": "turn/started", "kind": null}
{"ms": 3345, "event": "delta", "text": "RES"}
{"ms": 3345, "event": "delta", "text": "UM"}
{"ms": 3345, "event": "delta", "text": "ED"}
{"ms": 3380, "event": "exit", "code": 0, "process_code": 0, "result": "RESUMED"}
```

### claude / live

```jsonl
{"ms": 970, "event": "input_sent"}
{"ms": 1783, "event": "session", "id": "e21acfc9-aeb5-4f36-920c-543969e10b95"}
{"ms": 11585, "event": "delta", "text": "FIRST"}
{"ms": 11765, "event": "turn_result", "success": true}
{"ms": 11774, "event": "session", "id": "e21acfc9-aeb5-4f36-920c-543969e10b95"}
{"ms": 14483, "event": "delta", "text": "SECOND"}
{"ms": 14505, "event": "turn_result", "success": true}
{"ms": 15072, "event": "exit", "code": 0, "process_code": 0, "result": "SECOND"}
```

### claude / stop

```jsonl
{"ms": 1785, "event": "session", "id": "209da231-e95b-49fc-82c6-97e8c3f13637"}
{"ms": 2974, "event": "interrupt"}
{"ms": 4541, "event": "exit", "code": 130, "process_code": 143, "result": ""}
```

### claude / resume

```jsonl
{"ms": 1919, "event": "session", "id": "e21acfc9-aeb5-4f36-920c-543969e10b95"}
{"ms": 7497, "event": "delta", "text": "RESUMED"}
{"ms": 7731, "event": "turn_result", "success": true}
{"ms": 8297, "event": "exit", "code": 0, "process_code": 0, "result": "RESUMED"}
```
