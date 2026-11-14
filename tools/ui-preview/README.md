# UI 预览 / 模拟测试

用 Puppeteer 在桌面 Chrome 里以 412×915 手机视口运行 WebView 前端，注入模拟 Android bridge，截图到 `screenshots/`。

## 运行

```bash
cd tools/ui-preview
npm install
npm run preview
```

需要本机有 Chrome，默认路径 `/usr/bin/google-chrome`。可用 `CHROME_PATH` 覆盖。

## 截图

- `01-empty.png`
- `02-markdown-math.png`
- `03-settings.png`
- `04-pc.png`
- `05-wake-listening.png`

脚本同时会检查前端 JS 运行时错误，并打印传给 TTS 的纯文本片段。它不是 Android 真机测试，只用于快速看 UI、Markdown/数学渲染和交互状态。
