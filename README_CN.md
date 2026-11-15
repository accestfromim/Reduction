# DSAssist（Android）

一个直接调用 DeepSeek API 的安卓语音助手。当前版本 v1.0.21。

## 交互

- **免按住模式（实验）**：说“你好助手”唤醒，然后直接说话，停顿后自动发送；回答完保留 12 秒追问窗口。
- 底部中间麦克风：
  - 轻点：切换到文字输入并弹出键盘。
  - 长按：按住说话，松开发送（保留作为兜底）。
- 长按消息菜单出现在手指位置，上滑可直接选择“复制 / 朗读”；助手消息可朗读。
- 耳机输出时，播放/生成中可直接说“你好助手”打断当前回答；手机扬声器播放时暂停唤醒词，避免 TTS 自触发。

## 功能

- 黑白平面图标：白底 + 黑色圆弧标志，无渐变
- 全局黑白 UI：黑底白字、灰阶层次，无彩色强调
- 响应优化：滚动 ASR、唤醒拉起无额外延迟、TTS 首块提前、长按 320ms
- 语音结束：说完说“over”（也兼容“完毕”）；滚动 ASR 每轮返回后 1 秒再发，末尾结束词或连续 2 次空且无语音时收尾；20 秒上限，可点“结束”手动收尾
- DeepSeek 文字聊天、流式回复
- DeepSeek ASR 语音输入、TTS 朗读
- 流式朗读 + 连续 AudioTrack 播放 + 预合成下一句，减少短句停顿
- 音频缓存，相同文本和音色复用 PCM
- 联网搜索：DeepSeek Search API
- 计算器：本地精确计算
- 电脑连接：example-host 反向 SSH + WSL OpenSSH，RSA 密钥登录，host key 固定，持久 SSH 会话
- 自动更新：连接电脑时检查一次；连接期间电脑侧监听 manifest，版本变化后推送；APK SHA256 校验后在手机上弹安装确认
- 更新推送依赖 App 与电脑 SSH 连接存活；App 被杀或断线时，下次连接补查
- 播放中打断只认唤醒词“你好助手”，不再被任意语音触发；需要耳机输出
- opencode 工具：只读分析；开启高风险写权限后可在 worktree 修改代码，每次修改前手机弹窗确认；可填写已有 opencode 会话 ID 复用上下文
- 工具过程展示：工具名、参数摘要、状态、单次耗时
- 指令整理：用户说“整理一下/更新指令”时，update_brief 维护一个可复制、可编辑的指令框
- 语音确认：内置音频即时播放，唤醒后说“我在”，说 over 结束后说“好的”
- 免按住模式：
  - 前台服务 + 常驻通知
  - 手机麦克风常驻监听“你好助手”
  - 唤醒后自动录音，说 over 或手动/上限结束
  - 12 秒追问窗口
  - 通知栏可停止监听

## 安装

1. 把 `DSAssist-v1.0.21.apk` 传到手机。
2. 允许安装未知应用，点 APK 安装。
3. 首次打开填入 DeepSeek API Key，模型选 `deepseek-flash`，选音色，允许麦克风权限。

APK 约 39MB，只包含 `arm64-v8a`，适用于荣耀 500 Pro 等现代安卓手机。最低 Android 8.0（API 26）。

## 免按住模式怎么用

1. 设置 → 打开“免按住模式（实验）” → 保存。
2. 系统会请求通知权限，允许。
3. 通知栏出现“正在监听‘你好助手’”。
4. 说“你好助手”，听到提示后直接说话。
5. 停顿约 1.5 秒后自动识别并回答。
6. 回答完 12 秒内可以继续追问；超时后自动回到唤醒词监听。
7. 通知栏“停止监听”可以关闭常驻监听，同时在设置里会关闭该开关。

荣耀 500 Pro / MagicOS 需要设置：

- 设置 → 应用 → 应用启动管理 → DSAssist → 关闭自动管理 → 允许自启动、关联启动、后台活动
- 设置 → 电池 → 耗电管理 → 允许后台高耗电/不限制
- 最近任务里锁定 App，关闭省电模式

否则系统可能在锁屏或一段时间后杀掉常驻服务。

## TTS 连续播放

v0.9.8 使用单个 AudioTrack 连续播放整段回复，并提前合成后面的句子；播放缓冲约 0.3 秒。短句之间不再每句新建播放器，停顿会明显减少。日志里有 `TTS track done ... underruns=`，如果还断，可以据此判断是不是播放缓冲被掏空。

## Markdown 与数学

- 助手回复按 Markdown 渲染，支持标题、列表、代码块、表格、链接。
- 裸网址会显示为域名/短路径链接，完整 URL 放在 href/title 中，不直接展示。
- 数学公式使用 KaTeX 渲染，支持 `$...$`、`$$...$$`、`\(...\)`、`\[...\]`。
- TTS 会先剥离 Markdown 标记、代码块、数学块和网址，再朗读纯文本。
- v0.9.8 增加服务端/历史记录双重 null 清洗：回复开头不再出现 null，旧聊天记录里的重复 null 前缀也会自动清理。
- UI 预览和模拟测试：`tools/ui-preview/README.md`。

## 已知限制

- 播放中打断只在检测到耳机/蓝牙耳机输出时启用；手机扬声器播放时唤醒词会暂停，避免 TTS 自触发，可点麦克风或使用耳机打断。
- 打断检测触发后，当前回答可能只显示了一部分；这是预期行为。
- 语音结束后用最新滚动 ASR 结果发 LLM；手动“结束”或 20 秒上限会补一次最终整段 ASR。滚动 ASR 会重复识别增长中的整段音频，流量/费用高于流式接口。
- 不再用“文本无新增”自动结束；如果忘记说 over，只能在 20 秒上限或手动“结束”时收尾。
- 唤醒词使用**手机麦克风**常驻；设置里开启“耳机麦克风（实验）”后，唤醒后的录音会尝试走蓝牙耳机 SCO，失败自动回退手机麦克风。
- 蓝牙耳机麦克风需要授予“附近的设备/Bluetooth”权限；不同荣耀/耳机组合的 SCO 行为可能不同。
- 普通 App 无法做系统级锁屏热词；如果 App 进程被系统杀掉，唤醒会失效。
- 荣耀 MagicOS 的后台策略比较激进，必须做电池白名单，仍可能需要真机调。
- 唤醒词准确率受距离、噪声、口音影响；误唤醒可以在设置里关闭免按住模式。
- DeepSeek ASR 不是流式接口，不能返回逐字结果；这里用 1 秒间隔的滚动整段识别。
- DeepSeek TTS 只有 `stella`、`tide` 两个音色。
- 联网搜索和计算器默认开启；搜索会产生 DeepSeek Search API 的额外费用。
- 音频缓存存在 App 缓存目录，系统可能在空间不足时清理。
- API Key 存在手机本地，请使用单独的低额度 Key。
- 电脑端通过 example-host 反向 SSH 暴露 WSL OpenSSH；手机不需要 Tailscale。
- SSH 默认是公钥登录；首次连接会信任并固定 host key 指纹，未做人工指纹确认。
- PC 命令默认是 raw shell，需要二次点击确认；自动更新只在连接电脑期间监听。
- opencode 写入当前是任务级确认；严格 per-tool 审批还需要 opencode 权限客户端支持。

## 电脑连接（example-host 反向 SSH + OpenSSH）

1. WSL 安装并启动 OpenSSH Server，监听 `22`；example-host 开启 `GatewayPorts clientspecified`。
2. WSL 建立反向隧道：`ssh -R 0.0.0.0:443:127.0.0.1:22 example-host`。
3. 手机电脑页点“复制公钥”，追加到 WSL 的 `~/.ssh/authorized_keys`。
4. 手机填 `example.com`、端口 `443`、SSH 用户 `mindtravel`，点“连接测试”。
5. 首次连接会固定 host key 指纹；之后指纹变化会直接拒绝。

命令执行是 SSH raw shell，LLM 调用前仍需在手机上二次确认。连接测试成功后保持 SSH 会话，后续命令复用；连接异常会断开，下次自动重连。

## 代码结构

v1.0.21 采用功能优先（feature-first）+ API 层分离结构：

```text
api/                   外部 API 实现层，顶层 Controller 不直接依赖
  DeepSeekHttp         HTTP / SSE 传输与错误映射
  ApiGraph             API 实现组装点
  chat/                DeepSeekChatGateway、ChatStreamParser、DeepSeekSearchApi
  voice/               DeepSeekAudioApi
  pc/                  SshPcClient、SshKeyStore、PinnedHostKeyRepository

core/                  纯 Kotlin 基础能力
  audio/AudioGateway   平台音频接口
  events/EventSink     事件出口接口
  host/Hosts           权限 / 音频输出 / 更新 Host 接口
  settings/            AppSettings、SettingsGateway

feature/chat/
  model/               ChatSession、ChatEngine、ToolCallAccumulator、ChatGateway、SearchGateway
  model/tools/         ToolRegistry、Calculator、WebSearch、ChatToolFactory
  data/                HistoryRepository（本地历史）
  ui/                  ChatController

feature/voice/
  model/               TtsEngine、RollingAsr、TranscriptEndDetector
  data/                AndroidTtsEngine（适配 TtsPipeline + TtsCache）
  ui/                  PushToTalkSession、HandsFreeSession、RecordingController、TtsController

feature/pc/
  model/               PcGateway、PcInfo、PcProfile
  ui/                  PcController

feature/settings/
  data/                SettingsRepository

platform/
  audio/               PcmCapture、WavEncoder、WavRecorder、SpeechVad
                       HandsFreeRecorder、BargeInDetector、HeadsetMicController
                       TtsCache、TtsSynthesizer、TtsPlayback、TtsPipeline
  wake/                WakeWordBus、WakeWordDetector、WakeWordService
  update/              UpdateInstaller

ui/
  activity/            MainActivity
  bridge/              AndroidBridge（JS facade）
                       SettingsBridge、VoiceBridge、ChatBridge、PcBridge、SystemBridge
                       JsEmitter
  di/                  AppGraph（唯一对象组装点）
```

前端按功能拆分：

```text
assets/js/app.js
assets/js/core/bridge.js, state.js
assets/js/features/chat/chat.js, history-store.js, tts-text.js
assets/js/features/voice/voice.js
assets/js/features/settings/settings.js, settings-store.js
assets/js/features/pc/pc.js
assets/js/ui/ui.js, markdown.js
```

关键约束：

- SSE 中 `content: null` 不会变成字符串 `"null"`。
- 工具参数只接受字符串片段，缺 id/name 会直接失败。
- 协议层不把 `JSONObject` 传给业务层。
- 聊天、录音、TTS、电脑、更新各自独立 Controller。
- 滚动 ASR 收尾后，才发 LLM；支持“over/完毕”、空结果兜底、20 秒上限和手动结束。
- 设置升级会自动从旧版本迁移，不会丢 API Key。

前端也拆成了 `assets/js` 下的模块，`index.html` 只保留 DOM，CSS 在 `assets/css`。

测试：

```bash
./gradlew testReleaseUnitTest
```

覆盖 SSE null、工具调用分片、工具循环、消息序列化、计算器等。

## 从源码构建

需要 JDK 17、Android SDK（compileSdk 35、build-tools 35.0.0）、Gradle 8.9。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./build.sh
```

产物：`dist/DSAssist-v1.0.21.apk`

免按住模式依赖：

- `app/libs/sherpa-onnx-arm64.aar`
- `app/src/main/assets/kws/` 下的中文 KWS 模型
- 唤醒词：`你好助手`

签名配置在 `keystore.properties` 和 `keystore/release.jks`。升级安装必须使用同一个签名文件。

## 下一步

- 蓝牙耳机麦克风常驻唤醒
- 更低延迟：TTS WebSocket 流式合成
- 唤醒词误唤醒/功耗调优
