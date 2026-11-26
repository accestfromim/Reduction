<p align="center">
  <img src="docs/banner.png" alt="Reduction" width="100%" />
</p>

<h1 align="center">Reduction-智能随身助手</h1>

中文 · [English](README.en.md)

<p align="center">
  <img src="docs/badges/android.svg" alt="Android 8.0 及以上" />
  <img src="docs/badges/arm64.svg" alt="arm64" />
  <img src="docs/badges/windows.svg" alt="Windows + WSL" />
  <img src="docs/badges/mit.svg" alt="MIT" />
</p>

Reduction回答一个问题：我们有可能在生活更轻松的同时、可靠地控制更多的智能吗？

回答是肯定的，Reduction致力于通过最大化信息效率，也就是人接受信息、验证AI结果的效率来达到这一点

为此，Reduction提出了由随身设备、手机、电脑、云服务器协作完成的解决方案：

| | |
| :-- | :-- |
| **耳机和麦克风** | 实时简短的聊天，纯语音控制 |
| **手机** | 随身助手把事务归类、整理成卡片，平时可以不拿出手机，空闲时刻可以一次性审核 |
| **电脑** | 运行涉及个人隐私的subagent，提供工具调用的接口 |
| **云服务器** | 24h常驻，在手机和电脑间转发消息，运行不涉及个人隐私的subagent |

<table>
  <tr>
    <td align="center" valign="bottom"><img src="docs/opening-animation.gif" width="130" alt="启动动画" /></td>
    <td align="center" valign="bottom"><img src="tools/ui-preview/screenshots/08-tool-trace.png" width="130" alt="对话" /></td>
    <td align="center" valign="bottom"><img src="tools/ui-preview/screenshots/13-card-list.png" width="130" alt="卡片" /></td>
    <td align="center" valign="bottom"><img src="docs/pet.png" width="130" alt="电脑桌宠" /></td>
  </tr>
  <tr>
    <td align="center"><sub>启动动画</sub></td>
    <td align="center"><sub>对话</sub></td>
    <td align="center"><sub>卡片</sub></td>
    <td align="center"><sub>电脑上的桌宠</sub></td>
  </tr>
</table>

## 能做什么

[**CARD · Reduction 卡片协议**](docs/html-components.md) 包含组件写法、AI 调用示例、附件与导出约定。

| | |
| :-- | :-- |
| **熄屏语音交互** | 说 “Hi Reduction” 唤醒助手，说“完毕”结束录音 |
| **卡片式记忆** | 一切皆卡片，卡片即整理。基于超级协议CARD，用户直接AI+语音+少量手动编辑交付级别文件，不再关心文件类型 |
| **多 agent 调度** | Reduction是可以控制一群AI，采用super agent-sub agent架构：super agent分发任务、理解意图；sub agent具体执行、反馈结果 |
| **工具使用** | 正在不断支持你能想到的一切：联网搜索、计算器、查看电脑上其它编程 agent 会话的状态、手机和耳机电量提醒。 |

## 全栈方案

对话、整段语音转写和联网搜索使用 DeepSeek API Key。手机朗读优先使用系统已安装的中文离线音色，系统音色不可用时使用随应用安装的普通话模型。两种朗读方式都可以断网使用。在手机设置中搜索「文字转语音」可试听或安装音色，在应用设置中点「测试朗读」可试听实际效果。 [DeepSeek 开放平台](https://platform.deepseek.com/)

[四端构建和跨日测试](shared/README.md)

### 蓝牙耳机

拥有麦克风功能

### 手机（Android 8.0 及以上，arm64）

```bash
cp local.env.example local.env     # 填 JDK 17 和 Android SDK 的位置
tools/models/fetch-models.sh       # 取语音运行库、唤醒词和本地朗读模型
./build.sh --debug                 # 本机签名调试包，保存在 dist/
```

每次打更新包都会递增末位版本号和 Android versionCode。配置发布签名后，运行 `./build.sh` 可生成发布包。升级同一台手机时使用相同的包类型和签名。

需要授予录音机常驻、悬浮窗等较高权限

### 手机（iPhone 和 iPad，iOS 17.4 及以上）

用 Xcode 打开 `ios/Reduction.xcodeproj`，选择 `Reduction` 方案后运行。iOS 版支持 DeepSeek 对话和发图、按住录音松开识别、流式朗读、卡片和附件、待办提醒。填入电脑的 SSH 信息后可以同步数据和查询电脑任务。 [iOS 使用说明](ios/README.md) · [iOS TODO](ios/TODO.md)

### 电脑（macOS、Windows + WSL、Linux）

macOS 14.4 及以上可以直接运行 `dist/Reduction.app`。从源码构建需要 Xcode 和 JDK 17，应用包内含运行时。

```bash
./desktop/install.sh
open dist/Reduction.app
```

菜单栏可以打开对话和卡片、显示桌宠和设置登录时启动。按住 `Control + Shift + 空格` 说话，按住 `Control + Option + Shift + 空格` 听写，松开后把整理好的文字放入剪贴板。

Windows + WSL 和 Linux 可以这样启动：

```bash
desktop/install.sh     # 装成应用：桌宠、开机自启、桌面图标
./desktop.sh           # 或者只在终端里启动，浏览器打开它给出的地址
```

### 云服务器

### 让手机调度电脑上的 agent

如果目标主机在跳板机后面，在手机「电脑」面板的「SSH 跳板机」填写 `用户名@跳板机:端口`，例如 `ops@bastion.example.com:2222`。省略用户名会沿用目标 SSH 用户，省略端口时使用 22，留空则直连。把手机的同一份公钥加入两端相应用户的 `authorized_keys`，目标主机名由跳板机解析。

在电脑上开启 SSH 服务，在手机 App 里点电脑图标，复制公钥加到电脑的 `~/.ssh/authorized_keys`，再填入电脑地址。在「设置 → 子代理执行器」选择 OpenCode、Codex、Claude Code 或 MiMo Code。执行电脑需要 Python 3。Linux 和 WSL 直接以登录用户运行，macOS 还需要 Anthropic Sandbox Runtime。[执行器登录和依赖](docs/DEVELOPMENT.md#子代理执行器)

## 你的数据在哪

| | |
| :-- | :-- |
| **API Key** | 手机上加密保存，电脑上是只有本人可读的文件。连接 DeepSeek 时用于认证，派发子代理时会交给你配置的电脑。 |
| **对话、卡片和待办** | 手机与电脑各保留一份，连上电脑后通过 SSH 双向同步。 |
| **发给 DeepSeek 的** | 提交识别的录音、对话内容、联网搜索请求。朗读在本机完成。 |
| **发给你自己电脑的** | 仅在你配置了电脑连接之后：运行日志和你每一轮说话的录音，用于排查问题和改进唤醒。不想留就不要配置电脑连接。 |

## 更多

- [开发说明](docs/DEVELOPMENT.md)：功能细节、代码结构、构建、测试、发版
- [架构图](docs/interaction-states-simple.png)：硬件、模型、功能、界面四层，以及语音状态机
- [Agent 工作原则](docs/agent-principles.md)：super agent、sub agent 和人如何分工
- [用到的第三方组件](docs/THIRD_PARTY.md)

## 许可证

[MIT](LICENSE)。用到的第三方组件各自的许可证见[这里](docs/THIRD_PARTY.md)。
