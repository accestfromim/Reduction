DSAssist PC Agent（Windows）

用途
- 让手机 App 连接 Windows 电脑。
- 提供一键更新的安装包。
- 默认只提供电脑信息和更新，不执行任意命令。

运行
1. 把整个文件夹复制到本地 Windows 目录，例如 C:\Users\你\DSAssist\
   不要在 \\wsl.localhost\... 这类 UNC 路径里直接运行。
2. 双击 start.bat，或运行 DSAssistAgent.exe。
3. Windows 首次运行会弹防火墙提示，勾选“专用网络”并允许。
4. 窗口会显示地址和 Token。
5. 手机 App 右上角点电脑图标，填地址和 Token，连接测试。

命令执行
默认关闭。确认你理解风险后，这样启动：
  DSAssistAgent.exe -allow-exec

命令会在当前 Windows 账户下执行。Token 等于电脑操作权限，不要外传。
只允许在可信局域网使用，不要把端口映射到公网。

更新
- 把新 APK 和 update.json 放到本目录，替换旧文件。
- 手机 App 电脑页点“检查更新”。

安全软件提示
- 这是自己编译的未签名程序，没有商业代码签名。
- 旧版本可能被 Defender 启发式误报为 HEUR:VirTool/Obfuscator.a。
- 不要关闭 Defender，也不要加目录排除。
- 如果新版仍被拦截：
  1. 确认文件 SHA256 与发布页一致。
  2. 把文件提交给 Microsoft 误报申诉：
     https://www.microsoft.com/en-us/wdsi/filesubmission
  3. 选择“我认为此文件被错误检测”，附上本项目源码地址。
- 如果不放心，改用 Windows OpenSSH + Tailscale 的方案，不再运行这个 exe。

常见问题
- 手机连不上：确认同一 Wi-Fi、防火墙允许专用网络、用 ipconfig 查 IPv4。
- 端口冲突：DSAssistAgent.exe -port 8766，手机地址同步改。
- 换 Token：删除 pc-agent-config.json，重新运行。
