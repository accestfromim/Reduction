# Reduction iOS

支持 iOS 17.4 及以上。用 Xcode 打开 `Reduction.xcodeproj`，选择 `Reduction` 方案，在 iPhone 或 iPad 模拟器中运行。真机运行需要在 Signing & Capabilities 中选自己的开发团队。

首次打开在设置中填入 DeepSeek API Key。可以打字或发图，按住麦克风录音，松开后转成文字。麦克风授权在首次使用时请求，待办通知授权可在「卡片 → 计划」中打开。

对话、卡片、附件和待办保存在应用容器内。DeepSeek API Key 和 SSH 私钥保存在 Keychain。对话、发给助手的图片和主动录下的语音在使用对应功能时发送给 DeepSeek。

## 连接自己的电脑

1. 在电脑打开 SSH 服务。Mac 的入口是「系统设置 → 通用 → 共享 → 远程登录」。
2. 在 iOS 应用的「电脑」面板复制公钥，把它加入该电脑用户的 `~/.ssh/authorized_keys`，再填写主机、端口和用户名。
3. 点「连接测试」。首次连接会固定服务器公钥。电脑打开 Reduction 桌面服务时，iOS 应用会在前台同步对话、卡片、附件和待办。沿用现有同步协议，单轮附件数据上限为 6 MiB，单个更大的文件可在本机保留。
4. 在电脑配置沙箱和执行器后，在「设置 → 子代理执行器」选择 OpenCode 或 Codex，并开启需要的权限。手机负责派发、查询和结束任务，写入任务会在手机上请求确认。[执行器登录和依赖](../docs/DEVELOPMENT.md#子代理执行器)

## 构建

在仓库根目录运行下面的命令。构建需要 Xcode 和 JDK 17，可在根目录的 `local.env` 中用 `JAVA_HOME` 指定 JDK 的 `Contents/Home`。Xcode 会通过项目的 Gradle Wrapper 构建 `ReductionShared` 框架，并由 Swift Package Manager 按 `Package.resolved` 获取依赖。

```bash
xcodebuild -project ios/Reduction.xcodeproj -scheme Reduction \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/ios-derived build
```

界面继续使用 Android 和 macOS 已使用的 HTML、CSS、Markdown、公式、PDF 和文章模板。iOS 通过绑定在本机回环地址的受保护接口把网页连接到 Swift 实现。应用进程负责录放音、模型请求、文件和通知，电脑通过原有同步协议交换数据。
消息日期换算和待办日期标签由 `shared` 模块提供，iOS 和 JVM 端使用同一份规则。

在卡片的文章预览中点「导出长图」，即可把长图交给系统分享菜单。超过单张图可用内存范围的文章可拆成几张卡片导出。

[暂时无法平移和待实现的功能](TODO.md)
