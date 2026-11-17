# Reduction：智能随身助手

Reduction回答一个问题：我们有可能在生活更轻松的同时、可靠地控制更多的智能吗？

回答是肯定的，Reduction致力于通过最大化人接受信息、验证AI结果的效率（熵减率）来达到这一点

为此，Reduction提出了由随身设备、手机、电脑、云服务器协作完成的解决方案：

- 耳机和麦克风：实时简短的聊天，说一声 “Hi Reduction” 就能交代事情，并收听简短的语音报告
- 手机：随身助手把事务归类、整理成卡片，平时可以不拿出手机，空闲时刻可以一次性审核
- 电脑：运行涉及个人隐私的subagent，提供工具调用的接口
- 云服务器：24h常驻，在手机和电脑间转发消息，运行不涉及个人隐私的subagent

<p>
  <img src="docs/opening-animation.gif" width="200" alt="启动动画" />
  <img src="tools/ui-preview/screenshots/08-tool-trace.png" width="200" alt="对话" />
  <img src="tools/ui-preview/screenshots/13-card-list.png" width="200" alt="卡片" />
  <img src="docs/pet.png" width="200" alt="电脑上的桌宠" />
</p>

## 能做什么

- **熄屏语音交互**：说 “Hi Reduction” 唤醒，或在蓝牙耳机上按一下；说完说“完毕”。录音时有一段轻柔的和弦提示麦克风开着，结束时应一声“好的”。回答边生成边朗读，播放中可以打断。断网、没听清、出错都会出声告诉你，不会沉默。
- **卡片式备忘录**：一张卡片是一份 Markdown，收一类信息，比如某个方向的想法、待办、一份任务卡。口述即可，它像编辑文档一样增、改、删、归类；卡片可以分文件夹，可以放图片和录音，可以浮在其它应用上面。
- **多 agent 调度**：它自己只负责对话、整理和验收；查资料、读代码、改代码交给电脑上的 sub agent，在沙箱里后台运行，只能写自己所属的文件夹。做完的结果自动回来，原文记在一张它改不了的卡片上。
- **电脑端**：同一套核心也能跑在电脑上，是桌面上的一个小黑洞桌宠：按住它说话，点一下弹出完整面板。
- 其它：联网搜索、计算器、查看电脑上其它编程 agent 会话的状态、手机和耳机电量提醒。

## 怎么用

需要一个 [DeepSeek](https://platform.deepseek.com/) 的 API Key：对话、语音识别和朗读都调用它的接口，费用由你自己的 Key 承担。

**手机（Android 8.0 及以上，arm64）**

```bash
cp local.env.example local.env     # 填 JDK 17 和 Android SDK 的位置
tools/models/fetch-models.sh       # 取语音运行库和唤醒词模型（有几十 MB，不入库）
./build.sh                         # 得到 dist/Reduction-v<版本>.apk
```

装上后在设置里填 API Key，打开“免按住模式”。要让它在熄屏后一直能被叫醒，需要允许它忽略电池优化。

**电脑（Windows + WSL，或任何能跑 JVM 的 Linux）**

```bash
desktop/install.sh     # 装成应用：桌宠、开机自启、桌面图标
./desktop.sh           # 或者只在终端里启动，浏览器打开它给出的地址
```

**让手机调度电脑上的 agent（可选）**

电脑上要有 SSH 服务、`bwrap` 和 [opencode](https://opencode.ai/)。在 App 里点电脑图标，复制公钥加到电脑的 `~/.ssh/authorized_keys`，填上地址即可。sub agent 用的是你手机里那个 DeepSeek Key。

## 你的数据在哪

- **API Key** 只存在你的设备上（手机上加密保存，电脑上是只有本人可读的文件），只发给 DeepSeek。
- **对话、卡片、图片和录音** 存在本机。手机和电脑各存各的，不同步，也没有任何云端。
- **发给 DeepSeek 的**：你说的话（录音用于识别）、对话内容、要朗读的文字。
- **发给你自己电脑的**（仅在你配置了电脑连接之后）：运行日志和你每一轮说话的录音，用于排查问题和改进唤醒。不想留就不要配置电脑连接。
- 仓库里没有任何密钥、服务器地址或个人路径；这些写在不入库的 `local.env` 和 `keystore.properties` 里。

## 现状

这是一个人自用并持续在改的项目，接口和行为都还在变。唤醒词模型、耳机手势、悬浮卡片在不同手机上的表现差别很大，目前只在一台荣耀手机上长期使用过。

## 更多

- [开发说明](docs/DEVELOPMENT.md)：功能细节、代码结构、构建、测试、发版
- [架构图](docs/interaction-states-simple.png)：硬件、模型、功能、界面四层，以及语音状态机
- [Agent 工作原则](docs/agent-principles.md)：super agent、sub agent 和人如何分工
- [用到的第三方组件](docs/THIRD_PARTY.md)

## 许可证

[MIT](LICENSE)。用到的第三方组件各自的许可证见[这里](docs/THIRD_PARTY.md)。
