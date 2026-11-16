# Reduction（Android）

一个直接调用 DeepSeek API 的安卓语音助手。当前版本 v1.0.55。

## 交互

- **免按住模式（实验）**：说“Hi Reduction”唤醒，然后直接说话，停顿后自动发送；回答完回到待唤醒，再说话先说“Hi Reduction”。
- 底部中间麦克风：
  - 轻点：切换到文字输入并弹出键盘。
  - 长按：按住说话，松开发送（保留作为兜底）。
- 长按消息菜单出现在手指位置，上滑可直接选择“复制 / 朗读”；助手消息可朗读。
- 耳机输出时，播放/生成中可直接说“Hi Reduction”打断当前回答；手机扬声器播放时暂停唤醒词，避免 TTS 自触发。

## 功能

- 黑白平面图标：白底 + 黑色圆弧标志，无渐变
- 全局黑白 UI：黑底白字、灰阶层次，无彩色强调
- 响应优化：滚动 ASR、唤醒拉起无额外延迟、TTS 首块提前、长按 320ms
- 语音起止在手机本地逐帧判定：神经网络人声检测（Silero VAD）判断有没有人说话；说“完毕”结束一轮（本地关键词检测，不经网络，立刻生效）；中途停顿多久都不会结束，录音期间一直响着的乐音提醒该说“完毕”；也可点“结束”手动收尾，单轮 60 秒上限；唤醒后 12 秒没人开口则退出。云端的实时字幕只用来显示
- DeepSeek 文字聊天、流式回复
- DeepSeek ASR 语音输入、TTS 朗读
- 流式朗读 + 连续 AudioTrack 播放 + 预合成下一句，减少短句停顿
- 音频缓存，相同文本和音色复用 PCM
- 联网搜索：DeepSeek Search API
- 计算器：本地精确计算
- 电脑连接：example-host 反向 SSH + WSL OpenSSH，RSA 密钥登录，host key 固定，持久 SSH 会话
- 自动更新：连接电脑时检查一次；连接期间电脑侧监听 manifest，版本变化后推送；优先应用 APK 补丁，失败回退完整 APK
- 资源热更新：JS/CSS/HTML 可下载小资源包更新，不重装 APK
- 更新推送依赖 App 与电脑 SSH 连接存活；App 被杀或断线时，下次连接补查
- 播放中打断只认唤醒词“Hi Reduction”；需要耳机输出
- sub agent：super agent 只做对话、任务卡、派发、验收；所有 sub agent 经 run_agent 一个接口派发，在电脑上的沙箱里后台运行，可随时查询进度；它们用手机设置里的 DeepSeek API Key 和模型
- 诊断日志：连接电脑期间，手机每 3 秒把运行日志追加到电脑的 `~/reduction-agents/logs/phone.log`
- 录音留存：每轮说的话、每次按住说话、每次唤醒词触发前 5 秒的音频（听到名字但没有唤醒的那几秒也留）各存成一个 WAV，识别出的文字放在同名的文本文件里；连接电脑期间送到电脑的 `~/reduction-agents/recordings/`，电脑上的文件大小核对无误后从手机删除；没连上时留在手机上，最多 300 MB，满了先丢最早的
- 会话状态：助手可以查看电脑上 Claude、codex、opencode 编程会话各自的状态（session_status）。电脑上的脚本读会话记录，只交回摘要：最后活动时间、在做什么、是否卡住或在等用户、最后一句话的开头；会话原文不离开电脑
- 工具过程展示：每次工具调用在朗读流里占一行，显示助手写的一句摘要、完成时间和状态；点这一行展开工具名、完整的输入输出和用时。派发 sub agent 时摘要会念出来，其它调用不出声
- 备忘卡片：一张卡片是一份 Markdown 文本，收一类信息、随时间累积（比如几个方向的科研想法各一张，再加一张备忘录）；每张有一句用途，助手据此判断口述的内容该改哪一张；助手像编辑文档一样改卡片：写一份 git patch 风格的补丁（patch_cards），一次可以新建、修改、删除多张，整体生效或整体不动；read_card 读全文；平时只拿目录
- 图标与启动动画：图标是三个一样大的圆，中间一个，另外两个在它身后各露出一道弧。启动时中间的圆先在，另外两个从两侧飞入、滑到它身后，拼成图标，然后缩小落进左上角的图标框。系统自带的启动页设为纯黑、不放图标，所以打开应用先看到的就是这段动画
- 唤醒词“Hi Reduction”：一口气说完，或者“Hi”之后停顿再说“Reduction”（停顿最长 3 秒），都能唤醒。单独的“Reduction”、句子里出现的“reduction”不唤醒
- 界面两页：「朗读」是流动的对话，回复全部念出来；「卡片」是固定下来的内容，列表只显示名称，点开后正文铺满整页，顶栏“复制全文”一点即复制，另可编辑、删除
- 耳机开麦：在耳机上单击（播放/暂停键）和唤醒词一样开始一轮录音，手机可以留在口袋里；录音中再单击是说完了，回答播放中单击是打断并开始新的一轮。安卓把这个键交给最近出声的应用，所以刚在别的应用里听过歌时，单击仍是那个应用的播放/暂停。耳机的“语音助手”手势同样有效。这样开始的录音走耳机麦克风
- sub agent 的结果自己回来：任务结束后，手机把结果交给助手，助手用一轮回复汇报；正在说话或回答时等到空下来再报
- sub agent 任务记录：卡片「sub agent 任务」由程序按固定版式直接写入，每个任务一节：状态、派发与结束时间、文件夹与权限、任务卡原文、交回的结果原文、改动的文件。助手和界面都改不了它，也删不掉，所以它是可以直接采信的原始记录
- 悬浮卡片：在卡片阅读页点“悬浮”，离开应用后这张卡片浮在其它应用上面，平时是一个写着卡片名的小条，点一下展开正文，可以拖到任何位置，点“×”收起；卡片内容变了它跟着变。需要先在系统设置里允许“显示在其他应用上层”，应用会带你到那个页面
- 响度一致：每一段朗读和“好的”都归一到同一响度再播放；“好的”用当前朗读音色合成一次后留在手机上，没网时用随包自带的那份
- 电量提醒：手机或已连接的蓝牙耳机电量降到 20% 时，助手在回答之后提醒一句；充过电再降下来才会再提醒。耳机不上报电量时不提醒
- 输入清洗：助手先去掉语气词、合并重复、拼起零散的句子，再判断意图；有听不懂的部分就复述整体的理解回问一次
- 不静默：提问后请求没发出去会自动重发，共三次，第一次重发时说“网络不太好，正在重试”；三次都不通就说“网络不通，没能回答，请稍后再说一遍”。没听清、出错、麦克风打不开也各有一句。这些话预先备好，没有网络也能播：用当前音色合成后留在手机上，合成不了时用随包自带的录音
- 录音提示：开麦的同时响起一个微弱、缓慢起伏的和弦（C 六九和弦的开放排列，五度叠置：C4、G4、D5、A5、E6，下面垫着低八度的根音 C3 和它上方的 G3；一道响度的波峰每四秒从最低音走到最高音，每个音由两根相差极小的弦叠成，缓缓拍动），它持续就表示麦克风仍在收音，录音中断时它会自己停下；每次关麦播“好的”（说完、没人说话、取消、出错都一样）
- 流式朗读：首段在第一个逗号处切出先合成，之后按句切，单段最多 200 字，播放时预合成后续片段
- 免按住模式：
  - 前台服务 + 常驻通知
  - 手机麦克风常驻监听“Hi Reduction”
  - 唤醒后自动录音，说“完毕”、点“结束”或到 60 秒上限时结束
  - 麦克风只由唤醒词或在耳机上按一下开启：回答播完、没听清、取消之后都回到待唤醒
  - 通知栏可停止监听
  - 开启时弹出系统的“忽略电池优化”授权；允许后锁屏也不会被系统休眠
  - 麦克风被系统收回时（服务在后台被重启、被别的应用占用），通知栏显示“麦克风被系统收回，点此恢复监听”，回到 App 即恢复

## 安装

1. 把 `Reduction-v1.0.55.apk` 传到手机。
2. 允许安装未知应用，点 APK 安装。
3. 首次打开填入 DeepSeek API Key，模型选 `deepseek-flash`，选音色，允许麦克风权限。

APK 约 38MB，只包含 `arm64-v8a`，适用于荣耀 500 Pro 等现代安卓手机。最低 Android 8.0（API 26）。

## 免按住模式怎么用

1. 设置 → 打开“免按住模式（实验）” → 保存。
2. 系统会请求通知权限，允许。
3. 通知栏出现“正在监听‘Hi Reduction’”。
4. 说“Hi Reduction”，听到乐音后直接说话。
5. 停顿约 1.5 秒后自动识别并回答。
6. 回答完自动回到唤醒词监听；继续说话先说“Hi Reduction”。回答播放中说“Hi Reduction”可以打断并开始新的一轮。
7. 通知栏“停止监听”可以关闭常驻监听，同时在设置里会关闭该开关。

荣耀 500 Pro / MagicOS 需要设置：

- 设置 → 应用 → 应用启动管理 → Reduction → 关闭自动管理 → 允许自启动、关联启动、后台活动
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
- 语音结束后用最新滚动 ASR 结果发 LLM；手动“结束”或 60 秒上限会补一次最终整段 ASR。滚动 ASR 会重复识别增长中的整段音频，流量/费用高于流式接口。
- 没说“完毕”时，在 60 秒上限或手动“结束”时收尾。
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

四层结构，只允许向下调用、向上发事件。架构图：`docs/interaction-states-simple.html`；agent 分工与判断准则：`docs/agent-principles.md`。

```text
ui/                    UI 层：只画状态、只发意图
  activity/            MainActivity（Android 宿主）
  bridge/              AndroidBridge（JS facade）、VoiceBridge、ChatBridge、
                       SettingsBridge、PcBridge、UpdateBridge、SystemBridge、JsEmitter
  di/                  AppGraph（界面自己的接线，挂接到 AppCore）
  overlay/             CardOverlay（浮在其它应用上面的卡片）

app/                   应用核心：随进程存活，不依赖界面
  AppCore              组装硬件、模型、功能三层；唤醒词直接由它应答
  UiLink               核心眼里的界面：在就转发事件、请求授权；不在就丢弃事件、拒绝需要点按的请求
  ReductionApp           进程启动时创建核心

feature/               功能层：决定什么时候做什么；状态机在这里
  voice/               VoiceMachine（免按住状态机，纯函数：状态 + 事件 → 新状态 + 要做的事）
                       VoiceRuntime（执行器：照清单办事，把结果作为事件送回）、VoiceState
                       AckCue（提示音播放与超时）、SpokenCues（预先备好的几句话：“好的”和各种没能回答的原因）
                       TurnListener、TurnRecorder、TurnEndpointer、EndWords（一轮用户语音）
                       ReplyStreamer、SentenceSplitter、SpeechTextExtractor（流式朗读）
                       SpeechController、PushToTalkSession、RecordingController
  voice/wake/          WakeWordService、WakeWordDetector、PausedPhrase（带停顿的唤醒词）、WakeWordBus
  chat/model/          ChatSession、ChatEngine、ChatCallbacks、ToolCallAccumulator
  chat/model/tools/    ToolRegistry、ChatToolFactory、AgentHarness、RunAgentTool 与 AgentRuns（沙箱里的 sub agent；任务编号由手机分配和记住）、
                       SessionStatusTool（电脑上的会话状态）、CardTools（备忘卡片）、WebSearchTool、CalculatorTool、
                       AgentLedger（把任务记录按固定版式写成卡片）
  chat/ui/             Conversation（对话本身：历史、请求、说的和打字的都走它）、ChatController、ReplyObserver、
                       AgentWatch（sub agent 结束后把结果送进对话）
  chat/model/          SystemPrompt（系统提示）
  chat/data/           HistoryRepository、CardRepository、CardPatch（卡片补丁的解析与应用）
  pc/ui/               PcController
  update/ui/           UpdateController
  settings/data/       SettingsRepository

model/                 模型层：输入进、结果出，无状态
  DeepSeekHttp         HTTP / SSE 传输与错误映射
  ModelGraph           远端模型组装点
  chat/                ChatGateway、DeepSeekChatGateway、ChatStreamParser、
                       SearchGateway、DeepSeekSearchApi、ChatModel（消息与请求类型）
  voice/               DeepSeekAudioApi（ASR / TTS）、RollingAsr、
                       TtsSynthesizer、TtsCache、VoiceDetector（Silero VAD）
  wake/                KeywordSpotterModel（sherpa-onnx：英文模型认唤醒词，中文模型认结束词）

hardware/              硬件层：设备与系统的薄封装，不含策略
  audio/               PcmCapture、WavRecorder、WavEncoder、HeadsetMicController、
                       AudioQueue（唯一的播放队列）、AudioTrackSink（唯一的输出流）、PcmPlayer（朗读入队）、WavPcm、
                       RecordingArchive（等待送往电脑的录音）、Loudness（响度归一）、MediaButtons（耳机播放键）
  power/               DeviceBattery（手机与蓝牙耳机电量）
  pc/                  SshPcClient、SshKeyStore、PinnedHostKeyRepository、PcGateway
  update/              UpdateClient、UpdateInstaller、AssetUpdater、BsPatch
  web/                 HotAssetPathHandler

core/                  各层共用的接口与基础类型
  audio/AudioGateway   语音识别 / 合成接口
  events/EventSink     事件出口接口
  host/Hosts           权限 / 音频输出 / 更新 Host 接口
  settings/            AppSettings、SettingsGateway
```

前端是 UI 层的一部分，`index.html` 只保留 DOM，CSS 在 `assets/css`：

```text
assets/js/app.js                       Native 事件入口与 DOM 事件绑定
assets/js/core/bridge.js, state.js
assets/js/features/chat/chat.js, history-store.js   显示对话；历史和请求都在 App 里
assets/js/features/cards/cards.js       卡片页：名称列表、整页阅读与编辑
assets/js/features/voice/voice.js      渲染 onVoiceState；按住说话
assets/js/features/settings/settings.js, settings-store.js
assets/js/features/pc/pc.js
assets/js/ui/ui.js, markdown.js, splash.js（启动动画落进图标框）
```

免按住语音的桥接协议：

| 方向 | 名称 | 含义 |
| --- | --- | --- |
| App → JS | `onVoiceState(state)` | 状态变化：`IDLE` `PREPARING` `LISTENING` `TURN_ACK` `REPLYING` |
| App → JS | `onVoicePartial(text)` | 聆听中的实时字幕 |
| App → JS | `onReplyBegin(id, text)` | 一次新的问答开始了（说的或打字的），界面画出用户气泡和回复占位 |
| JS → App | `submitText(text)` | 提交打字或按住说话得到的文字 |
| App → JS | `onChatAborted(id)` | 回复被唤醒词打断 |
| JS → App | `voiceFinish()` / `voiceCancel()` | 结束本轮 / 退出免按住对话 |
| JS → App | `voiceState()` | 页面加载时读取当前状态 |
| JS → App | `speak(id, text)` / `stopSpeaking()` | 朗读一条消息 / 停止朗读 |

关键约束：

- 语音状态只有 `VoiceMachine` 一份，是纯函数；每次转换在日志里是一行 `Voice 状态 + 事件 -> 新状态 [要做的事]`。JS 不计时、不排队、不决定迁移。
- 对话核心不依赖界面：唤醒、录音、请求、朗读都在 `AppCore` 里，熄屏或界面被回收时照常工作；界面回来后读取当前状态和历史。
- 所有声音（提示音、录音中的乐音、朗读）都排进 `AudioQueue`，写入同一条输出流：先入队的先响，后一段的首个采样紧接前一段的末个采样。没有第二个播放器。
- 会打断声音输出的操作（蓝牙耳机在听歌模式和通话模式之间切换）只发生在乐音响起之前和“好的”播完之后，不在任何声音播放期间。
- `VoiceMachineTest` 逐条检查每个转换，并用任意事件顺序验证：每次开始的录音恰好以一个“好的”结束，空闲时一定在听唤醒词。
- 回复文本由 App 同时送给界面和朗读，JS 不参与朗读分句。
- 回复只有一种：要念出来的话。App 把它整条朗读，界面原样显示；需要留下来看的证据由助手写进卡片。每个工具都多带一个 `summary` 参数，由助手填写，是界面上那一行摘要的来源。系统提示在 `feature/chat/model/SystemPrompt.kt`，改动后用 `REDUCTION_APPROVE_PROMPTS=app/src/test/resources` 跑 `SystemPromptTest` 更新参考文本，diff 即审阅对象。
- 下层不 import 上层：`hardware/` 与 `model/` 不依赖 `feature/`、`ui/`。
- sub agent 一律经 `assets/harness/run-agent.sh` 在电脑上的 `bwrap` 沙箱里运行，只能写自己的文件夹；电脑需装有 `bwrap`。
- 会话状态由 `assets/harness/session-status.py` 在沙箱之外提取，只读脚本开头 `SOURCES` 列出的三处会话记录；电脑需装有 `python3`。
- SSE 中 `content: null` 不会变成字符串 `"null"`。
- 工具参数只接受字符串片段，缺 id/name 会直接失败。
- 协议层不把 `JSONObject` 传给业务层。
- 资源热更新包的 `assetsMinVersionCode` 必须不低于 85（当前桥接协议的起始版本），否则不会被使用。
- 设置升级会自动从旧版本迁移，不会丢 API Key。

测试：

```bash
./gradlew testReleaseUnitTest          # Kotlin 单测
cd tools/ui-preview && node run.js     # 前端截图 + 免按住状态渲染检查
```

发版：

```bash
./build.sh                                   # dist/Reduction-v<版本>.apk
tools/release/make_update.py \
  --from dist/Reduction-v1.0.25.apk --from-code 55 \
  --to dist/Reduction-v1.0.26.apk --to-code 56 --to-name 1.0.26
# 生成 patch-55-56.bin、manifest.json、SHA256SUMS.txt，并先在本机验证补丁能还原新包
# 上传顺序：先 APK 和补丁，最后 manifest.json
REDUCTION_UPDATE_MANIFEST=http://example.com/dsassist/manifest.json \
REDUCTION_INSTALLED_APK=dist/Reduction-v1.0.25.apk \
  ./gradlew testReleaseUnitTest --tests '*UpdateFlowTest*'   # 用 App 的更新代码验证线上文件
```

单测覆盖 SSE null、工具调用分片、工具循环、消息序列化、计算器、结束词检测、朗读文本清洗、朗读分句、提示音保证、工具摘要、会话状态脚本、录音留存等。

## 从源码构建

需要 JDK 17、Android SDK（compileSdk 35、build-tools 35.0.0）、Gradle 8.9。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./build.sh
```

产物：`dist/Reduction-v1.0.55.apk`

免按住模式依赖：

- `app/libs/sherpa-onnx-arm64.aar`
- `app/src/main/assets/kws-en/` 下的英文 KWS 模型（唤醒词 Hi Reduction，`keywords.txt` 里是它的子词编码 `▁HI ▁ ENT RO P Y`）
- `app/src/main/assets/kws/` 下的中文 KWS 模型（结束词“完毕”）
- `app/src/main/assets/vad/silero_vad.onnx` 人声检测模型
- 唤醒词：`Hi Reduction`

签名配置在 `keystore.properties` 和 `keystore/release.jks`。升级安装必须使用同一个签名文件。

## 下一步

- 蓝牙耳机麦克风常驻唤醒
- 更低延迟：TTS WebSocket 流式合成
- 唤醒词误唤醒/功耗调优
