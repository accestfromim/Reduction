# iOS TODO

## 平台限制

- [ ] **TODO(iOS)** 锁屏后长期待命，随时响应「Hi Reduction」。iOS 会挂起第三方应用，应用退出后也无法保证继续监听。当前支持应用内主动录音、朗读，以及用 Siri 或操作按钮运行「打开 Reduction」快捷指令。 [Apple 后台执行说明](https://developer.apple.com/forums/thread/685525) · [App Intents](https://developer.apple.com/documentation/appintents)
- [ ] **TODO(iOS)** 用耳机播放键从任意应用全局开麦。iOS 把耳机媒体按键交给当前或最近播放媒体的应用。录音和朗读可使用系统分配的蓝牙音频路由。 [Apple 媒体按键文档](https://developer.apple.com/documentation/mediaplayer/handling-external-player-events-notifications)
- [ ] **TODO(iOS)** 统一读取 AirPods 和其它蓝牙耳机的电量。可评估为主动提供标准电量服务的配件单独接入。 [Apple Core Bluetooth](https://developer.apple.com/documentation/corebluetooth)
- [ ] **TODO(iOS)** 在其它应用上方任意放置和拖动卡片。iOS 的公开入口是小组件、锁屏和灵动岛的实时活动。 [Apple ActivityKit](https://developer.apple.com/documentation/activitykit)
- [ ] **TODO(iOS)** 手机挂起时即时播报电脑的任务进度和完成情况。电脑可以独立运行任务，iOS 前台可以查询。即时完成提醒需要电脑或中转服务接入 APNs。 [Apple 推送通知说明](https://developer.apple.com/documentation/usernotifications/setting-up-a-remote-notification-server)
- [ ] **TODO(iOS)** 平移 APK 差分更新和会改变应用功能的网页热更新。iOS 应用包通过 TestFlight 或 App Store 发新版。 [App Store 审核指南 2.5.2](https://developer.apple.com/app-store/review/guidelines/#software-requirements)

## 待接入与验证

- [ ] **TODO(iOS)** 在主动开启的语音会话内本地识别「Hi Reduction」和「完毕」，以及用唤醒词打断朗读。需接入 iOS 关键词模型，完成误触和延迟测试。
- [ ] **TODO(iOS)** 真机验收电脑子代理的派发、结果附图和前台自动汇报。需要一台装好 OpenCode 沙箱的电脑。
- [ ] **TODO(iOS)** 与原计算器一致的高精度十进制运算。当前使用本地表达式库求值，数值精度受 `Double` 限制。
- [ ] **TODO(iOS)** 诊断录音归档和手动分享。当前主动录音读取后即清理临时文件。
- [ ] **TODO(iOS)** 验证真机上的锁屏录放音、来电中断、蓝牙路由切换和操作按钮。模拟器使用 Mac 的音频设备，具体行为需在 iPhone 上确认。
