# DeepSeek 助手（Android）

一个直接调用 DeepSeek API 的安卓语音助手。当前版本 v0.9.1。

## 交互

- **免按住模式（实验）**：说“你好助手”唤醒，然后直接说话，停顿后自动发送；回答完保留 12 秒追问窗口。
- 底部中间麦克风：
  - 轻点：切换到文字输入并弹出键盘。
  - 长按：按住说话，松开发送（保留作为兜底）。
- 每条助手回复下面有“朗读”按钮。

## 功能

- DeepSeek 文字聊天、流式回复
- DeepSeek ASR 语音输入、TTS 朗读
- 流式朗读 + 连续 AudioTrack 播放 + 预合成下一句，减少短句停顿
- 音频缓存，相同文本和音色复用 PCM
- 联网搜索：DeepSeek Search API
- 计算器：本地精确计算
- 电脑连接：Windows `pc-agent.exe`
- 免按住模式：
  - 前台服务 + 常驻通知
  - 手机麦克风常驻监听“你好助手”
  - 唤醒后自动录音，静音约 1.5 秒自动结束
  - 12 秒追问窗口
  - 通知栏可停止监听

## 安装

1. 把 `DeepSeekAssistant-v0.9.1.apk` 传到手机。
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

- 设置 → 应用 → 应用启动管理 → DeepSeek 助手 → 关闭自动管理 → 允许自启动、关联启动、后台活动
- 设置 → 电池 → 耗电管理 → 允许后台高耗电/不限制
- 最近任务里锁定 App，关闭省电模式

否则系统可能在锁屏或一段时间后杀掉常驻服务。

## TTS 连续播放

v0.9.1 使用单个 AudioTrack 连续播放整段回复，并提前合成后面的句子；播放缓冲约 0.3 秒。短句之间不再每句新建播放器，停顿会明显减少。日志里有 `TTS track done ... underruns=`，如果还断，可以据此判断是不是播放缓冲被掏空。

## Markdown 与数学

- 助手回复按 Markdown 渲染，支持标题、列表、代码块、表格、链接。
- 数学公式使用 KaTeX 渲染，支持 `$...$`、`$$...$$`、`\(...\)`、`\[...\]`。
- TTS 会先剥离 Markdown 标记、代码块、数学块和网址，再朗读纯文本。
- v0.9.1 增加服务端/历史记录双重 null 清洗：回复开头不再出现 null，旧聊天记录里的重复 null 前缀也会自动清理。
- UI 预览和模拟测试：`tools/ui-preview/README.md`。

## 已知限制

- 播放中打断是实验功能，只在检测到耳机/蓝牙耳机输出时启用；手机扬声器播放时容易自触发，会自动跳过。
- 打断检测触发后，当前回答可能只显示了一部分；这是预期行为。
- 只有麦克风完全停止录音并释放后，才会把 WAV 发到 DeepSeek ASR；ASR 返回后才发 LLM。
- 唤醒词使用**手机麦克风**常驻；蓝牙耳机目前只负责播放。蓝牙耳机麦克风常驻唤醒后续再试。
- 普通 App 无法做系统级锁屏热词；如果 App 进程被系统杀掉，唤醒会失效。
- 荣耀 MagicOS 的后台策略比较激进，必须做电池白名单，仍可能需要真机调。
- 唤醒词准确率受距离、噪声、口音影响；误唤醒可以在设置里关闭免按住模式。
- DeepSeek ASR 不支持流式，必须等一句话停顿后再识别。
- DeepSeek TTS 只有 `stella`、`tide` 两个音色。
- 联网搜索和计算器默认开启；搜索会产生 DeepSeek Search API 的额外费用。
- 音频缓存存在 App 缓存目录，系统可能在空间不足时清理。
- API Key 存在手机本地，请使用单独的低额度 Key。
- 电脑端是未签名 exe，Windows SmartScreen 可能提示。

## 电脑连接

1. Windows 解压 `pc-agent-windows.zip`。
2. 双击 `start.bat`，允许防火墙“专用网络”。
3. 窗口显示地址和 Token；Token 也可以在同目录 `pc-agent-config.json` 里复制。
4. 手机右上角电脑图标，填地址和 Token，连接测试。

## 代码结构

v0.9.1 采用功能优先（feature-first）结构：

```text
core/                  纯 Kotlin：JSON、文本、日志、错误
network/DeepSeekHttp   HTTP/SSE 传输与错误映射
feature/chat/
  model/               消息、工具、ChatSession、ChatGateway
  data/                DeepSeekChatGateway、SSE 解析、搜索、历史
  ui/                  ChatController
feature/voice/
  model/               AudioGateway
  data/                DeepSeekAudioApi、TtsCache
  ui/                  RecordingController、TtsController
feature/pc/
  data/                PcClient
  ui/                  PcController、UpdateController
feature/settings/
  model/               AppSettings
  data/                SettingsRepository
platform/
  audio/               WavEncoder、WavRecorder、SpeechVad、HandsFreeRecorder、BargeInDetector、TtsPlayer
  wake/                WakeWordBus、WakeWordDetector、WakeWordService
  update/              UpdateInstaller
ui/
  activity/            MainActivity
  bridge/              AndroidBridge、JsEmitter
  di/                  AppGraph
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
- 录音释放后才发 ASR，ASR 返回后才发 LLM。
- 设置升级会自动从旧版本迁移，不会丢 API Key。

前端也拆成了 `assets/js` 下的模块，`index.html` 只保留 DOM，CSS 在 `assets/css`。

关键约束：

- SSE 中 `content: null` 不会变成字符串 `"null"`。
- 工具参数只接受字符串片段，缺 id/name 会直接失败。
- 协议层不把 `JSONObject` 传给业务层。
- 聊天、音频、电脑、更新各自独立 Controller，不再集中在一个 NativeBridge。
- 设置升级会自动从旧版本迁移，不会丢 API Key。

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

产物：`dist/DeepSeekAssistant-v0.9.1.apk`

免按住模式依赖：

- `app/libs/sherpa-onnx-arm64.aar`
- `app/src/main/assets/kws/` 下的中文 KWS 模型
- 唤醒词：`你好助手`

电脑端需要 Go 1.23：

```bash
cd pc-agent
GOOS=windows GOARCH=amd64 go build -buildvcs=false -trimpath -ldflags "-s -w" -o pc-agent.exe .
```

签名配置在 `keystore.properties` 和 `keystore/release.jks`。升级安装必须使用同一个签名文件。

## 下一步

- 播放中打断（先手机麦克风监听，蓝牙耳机播放）
- 蓝牙耳机麦克风常驻唤醒
- 更低延迟：TTS WebSocket 流式合成
- 唤醒词误唤醒/功耗调优
