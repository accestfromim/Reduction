# Reduction 可检索性清单

只列可执行动作，不含代码。所有条目都以公开仓库 https://github.com/mindtravel/Reduction 为前提。

## 1. 仓库 About 描述

推荐填写这一条（英文，56 字符，含空格）：

> Voice-first AI assistant that organizes work into cards.

理由：一句话同时覆盖 voice-first、AI assistant、cards 三个核心检索词，且不夸大未实现的能力。GitHub About 有长度限制，这条留有余量。

操作：GitHub 仓库首页右上角 About 齿轮 → Description 粘贴上面一行 → 保存。

## 2. GitHub topics

按 GitHub 话题的大致覆盖面从大到小排列；GitHub 不公布精确流量，这个顺序是估计值，不是指标。全部小写、用连字符、均未超过 50 字符。

| 顺序 | topic | 为什么选它 |
| --- | --- | --- |
| 1 | open-source | 覆盖面最大的通用话题之一，先进入开源项目浏览池；项目是 MIT，符合事实 |
| 2 | ai | 最宽的 AI 检索入口，几乎所有按 AI 找项目的人都会扫这个话题 |
| 3 | llm | 直接对应大模型应用开发者，是产品的核心技术词 |
| 4 | android | 平台级入口，能接住按 Android 应用筛选的人；项目以 Android 端为主 |
| 5 | ai-assistant | 与产品品类完全匹配，搜索意图最接近 |
| 6 | voice-assistant | 对应语音优先、免手操作的用法，是差异化入口 |
| 7 | kotlin | 实现语言，能触达 Android/Kotlin 开发者社区 |
| 8 | multi-agent | 对应 super agent–sub agent 架构，词更窄但意图更准 |
| 9 | deepseek | 接住按模型或 API 供应商搜索的人；项目明确依赖 DeepSeek |
| 10 | speech-recognition | 对应语音输入和本地语音检测能力 |
| 11 | text-to-speech | 对应朗读能力，是独立的搜索词 |
| 12 | wake-word | 对应 “Hi Reduction” 唤醒，属于长尾但高意图 |
| 13 | personal-assistant | 对应随身助手的使用场景，覆盖非技术用户视角 |
| 14 | local-first | 对应数据留在本机的设计，区别于云端助手 |
| 15 | privacy-first | 接住重视隐私的用户；与 local-first 相邻但不重复 |

操作：GitHub 仓库首页 About 齿轮 → Topics 逐个粘贴 → 保存。一次加满 15 个比只加 3 个更容易被话题页收录。

## 3. 英文 README 应出现的关键同义词与关键词

原则：自然写进句子，不堆砌。每个词至少出现一次，优先放在标题、第一段、能做什么、安装步骤和数据说明里。

### 产品与品类

- smart pocket assistant
- AI assistant
- voice assistant
- personal assistant
- hands-free
- voice-first
- screen-off
- wake word（同时保留 “Hi Reduction”）
- open source

### 技术与架构

- LLM
- large language model
- DeepSeek
- speech-to-text
- ASR
- text-to-speech
- TTS
- speech recognition
- multi-agent
- super agent
- sub agent
- tool calling
- SSH
- Android
- Kotlin
- arm64

### 卡片与数据

- card
- card protocol
- CARD
- CARD component
- note-taking
- knowledge base
- attachment
- export
- local-first
- on-device
- privacy
- data ownership
- no cloud sync

### 平台与安装

- APK
- Gradle
- JDK 17
- Android SDK
- Windows
- WSL
- Linux
- JVM
- desktop pet
- MIT license
- third-party components

操作：对照上面四组，逐项在 README.en.md 中搜索一次；搜不到的词，判断是否属实，属实就补进最相关的小节，不属实就不要加。

## 4. 可能收录本项目的 awesome 列表与社区入口

按可执行程度排序，优先做前面的。

1. **JStumpp/awesome-android** — https://github.com/JStumpp/awesome-android
   操作：Fork 后在其 “Other” 或应用类小节提交一行 PR，格式为项目名、仓库链接、一句英文简介；先读 CONTRIBUTING 确认是否接收应用类条目。

2. **offa/android-foss** — https://github.com/offa/android-foss
   操作：在对应分类（如 Productivity 或 Assistant）提交 PR，加入一行项目条目；该列表只收自由开源软件，提交前确认没有专有闭源依赖。

3. **KotlinBy/awesome-kotlin** — https://github.com/KotlinBy/awesome-kotlin
   操作：在其 Applications 或 Projects 小节提交 PR，一行项目名加链接；说明这是 Kotlin 编写的 Android 应用。

4. **steven2358/awesome-generative-ai** — https://github.com/steven2358/awesome-generative-ai
   操作：在 Applications 类小节提交 PR，一行英文简介；先读仓库的收录标准，确认应用类条目被接受。

5. **F-Droid 收录入口** — https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/
   操作：按指南提交打包请求，准备可复现构建和开源许可证说明；先确认应用是否满足 F-Droid 对依赖和反特性的要求。

## 5. 执行顺序

- [ ] 先填 About 描述
- [ ] 再加满 15 个 topics
- [ ] 用第 3 节的关键词表校对 README.en.md
- [ ] 依次提交第 4 节的 awesome 列表 PR
- [ ] 最后处理 F-Droid 收录（周期最长）
