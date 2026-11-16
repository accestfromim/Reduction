# 用到的第三方组件

仓库里直接带着或在构建时取用的别人的东西。各自的许可证以其上游为准；下表是整理时的理解，发布前请对照上游核实。

| 组件 | 用在哪 | 上游 | 许可证 |
| --- | --- | --- | --- |
| sherpa-onnx（`app/libs/sherpa-onnx-arm64.aar`） | 本地唤醒词、结束词、人声检测的运行库 | k2-fsa/sherpa-onnx | Apache-2.0 |
| 关键词模型（`assets/kws`、`assets/kws-en`） | 认“完毕”和“Hi Reduction” | sherpa-onnx 发布的 zipformer KWS 模型（wenetspeech、gigaspeech） | 随 sherpa-onnx 发布，需核实 |
| Silero VAD（`assets/vad/silero_vad.onnx`） | 判断有没有人在说话 | snakers4/silero-vad | MIT |
| marked | Markdown 渲染 | markedjs/marked | MIT |
| DOMPurify | 渲染前清洗 HTML | cure53/DOMPurify | Apache-2.0 或 MPL-2.0 |
| KaTeX | 数学公式 | KaTeX/KaTeX | MIT |
| mermaid（`docs/mermaid.min.js`） | 架构图里的状态图 | mermaid-js/mermaid | MIT |
| OkHttp | 网络请求 | square/okhttp | Apache-2.0 |
| JSch（mwiede 分支） | SSH | mwiede/jsch | BSD |
| Apache Commons Compress | 解更新包 | apache/commons-compress | Apache-2.0 |
| org.json、kotlinx.coroutines、AndroidX | 基础库 | 各自上游 | 见各自上游 |

随包自带的提示音里，“好的”和几句失败提示是用开源中文语音模型（piper 的 huayan）合成的备用录音；乐音是程序生成的。
