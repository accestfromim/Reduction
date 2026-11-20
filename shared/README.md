# KMP 日期映射与四端构建

日期换算、消息日期标注和待办日期标签由 `shared` 模块提供。Android、macOS 和 Windows 使用它的 JVM 版本，iOS 在 Xcode 构建时链接 `ReductionShared` 框架。

以下命令都从仓库根目录执行。Gradle 构建使用 JDK 17，直接运行 `./gradlew` 前在终端设置 `JAVA_HOME`。Xcode 和 `desktop/install.sh` 也会读取根目录的 `local.env`。macOS 的 `JAVA_HOME` 路径指向 JDK 的 `Contents/Home`。

## Android

安装 Android SDK Platform 35 和 Build Tools 34.0.0，将 `ANDROID_HOME` 指向 SDK 目录。

```bash
bash tools/models/fetch-models.sh
./gradlew --configure-on-demand :app:assembleDebug
```

生成 `app/build/outputs/apk/debug/app-debug.apk`。

## iOS

在 Mac 上安装 Xcode 和 iOS 模拟器运行环境。Xcode 的构建阶段会调用仓库的 Gradle Wrapper 编译共享框架。

```bash
xcodebuild -project ios/Reduction.xcodeproj -scheme Reduction \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/ios-derived CODE_SIGNING_ALLOWED=NO build
```

模拟器应用包位于 `build/ios-derived/Build/Products/Debug-iphonesimulator/Reduction.app`。真机安装时，在 Xcode 的 Signing & Capabilities 中选择开发团队并选中设备运行。

## macOS

在装有 Xcode 的 Mac 上运行以下命令。应用包会包含 Java 运行时。

```bash
bash desktop/install.sh
```

生成 `dist/Reduction.app`。在 `local.env` 中设置 `REDUCTION_MACOS_SIGNING_IDENTITY` 可选用钥匙串里的证书。同一签名身份可让更新后的应用沿用录屏授权。

## Windows

在 Windows 的 WSL 中打开仓库。WSL 内需要 JDK 17，Windows 宿主需要 .NET Framework 和 WebView2 Runtime。

```bash
bash desktop/install.sh
```

脚本构建 JVM 服务，用 Windows 的 `csc.exe` 编译桌宠，并安装启动快捷方式。单独构建服务可运行 `./gradlew --configure-on-demand :desktop:installDist`，产物位于 `desktop/build/install/reduction`。

## 跨日测试

JVM 端运行以下命令。

```bash
./gradlew --configure-on-demand :shared:jvmTest
```

Apple Silicon Mac 安装 iOS 模拟器运行环境后，可在 iOS 目标上运行同一组测试。

```bash
./gradlew --configure-on-demand :shared:iosSimulatorArm64Test
```

用例覆盖跨日、跨周、跨年、闰年和旧消息缺失日期。历史消息里的「明天」始终按发送时的日期换算，待办则按查看当天显示为「今天」或「明天」。
