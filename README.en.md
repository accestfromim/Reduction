<p align="center">
  <img src="docs/banner.png" alt="Reduction" width="100%" />
</p>

<h1 align="center">Reduction-Everyone's JARVIS</h1>

[中文](README.md) · English

<p align="center">
  <img src="docs/badges/android.svg" alt="Android 8.0 and later" />
  <img src="docs/badges/arm64.svg" alt="arm64" />
  <img src="docs/badges/windows.svg" alt="Windows + WSL" />
  <img src="docs/badges/mit.svg" alt="MIT" />
</p>

Say “Hi Reduction” through your headset while your phone stays in your pocket. Tell it what you need to remember or get done. When you have a moment, open the cards on your phone, check the results, and edit them in place.

Reduction asks a question: can we reliably put more AI to work while making everyday life easier? We measure that in terms of information efficiency: how much you can take in and verify for the effort you spend interacting with the assistant.

Here’s how the devices work together.

| | |
| :-- | :-- |
| **Headset and microphone** | Short real-time chats, controlled by voice alone |
| **Phone** | The pocket assistant sorts affairs and turns them into cards. You can leave the phone in your pocket and review them all at once when you have time |
| **Computer** | Runs the sub agents (AI workers that a super agent delegates tasks to) that involve personal privacy, and provides the tool-calling interface |
| **Cloud server** | Runs around the clock, relays messages between the phone and the computer, and runs sub agents that do not involve personal privacy |

<table>
  <tr>
    <td align="center" valign="bottom"><img src="docs/opening-animation.gif" width="130" alt="Startup animation" /></td>
    <td align="center" valign="bottom"><img src="tools/ui-preview/screenshots/08-tool-trace.png" width="130" alt="Chat" /></td>
    <td align="center" valign="bottom"><img src="tools/ui-preview/screenshots/13-card-list.png" width="130" alt="Cards" /></td>
    <td align="center" valign="bottom"><img src="docs/pet.png" width="130" alt="Desktop pet" /></td>
  </tr>
  <tr>
    <td align="center"><sub>Startup animation</sub></td>
    <td align="center"><sub>Chat</sub></td>
    <td align="center"><sub>Cards</sub></td>
    <td align="center"><sub>Desktop pet on the computer</sub></td>
  </tr>
</table>

## What it can do

[**CARD · the Reduction card protocol**](docs/html-components.md) defines how to write CARD components (the building blocks of a card), with AI usage examples and the rules for attachments and export.

| | |
| :-- | :-- |
| **Screen-off voice control** | Say “Hi Reduction” to wake the assistant and “完毕” (*wánbì*, “finished”) to end the recording. Give it tasks while your phone stays in your pocket. |
| **Card-based memory** | A card is where you collect, organize, and revisit an everyday task or idea. The CARD protocol handles the underlying files. Use voice to direct the AI, edit the rendered result in place, and export a shareable file with private annotations removed. |
| **Coordinating agents** | The lead agent works out what you want and delegates tasks. Subagents carry out the work and report their results. |
| **Tool use** | Steadily adding everything you can think of: web search, a calculator, the status of other programming agent sessions on your computer, and low-battery alerts for the phone and headset |

### Cards and attachments

Drop an image between two paragraphs to insert it there. Documents such as Markdown, TXT, and CSV files are attached under their original names. The assistant can read Markdown and TXT attachments when it reads the card. The **Export** menu produces a full-length PNG, a PDF, or a ZIP containing the original files.

Cards inherit their sharing settings from the top-level folder. Cards under `个人/` (Personal) and `实验室/` (Lab) are private by default. Under `Reduction/`, you can write for a public audience and mark personal relationships or reminders as private annotations. The author can see those annotations. Public exports omit them.

### Manage GitHub by voice (desktop)

Ask “Refresh the GitHub repository activity card” to collect issues, pull requests, and releases in `Reduction/GitHub/仓库动态`. You can also ask Reduction to create or update an issue, post a comment, or publish the public version of a card to an HTML file in the repository. Writes use your GitHub CLI login in WSL (`gh auth login`). Reduction shows you the destination and content for confirmation before submitting.

## Full-stack setup

[DeepSeek](https://platform.deepseek.com/) API key: provides the large language model, speech-to-text and text-to-speech, and web search services.

### Bluetooth headset

A headset with a microphone.

### Phone (Android 8.0 and later, arm64)

```bash
cp local.env.example local.env     # fill in the JDK 17 and Android SDK locations
tools/models/fetch-models.sh       # fetch the speech runtime and wake-word models (tens of MB, not committed)
./build.sh                         # produces dist/Reduction-v<version>.apk
```

You need to grant elevated permissions such as an always-on recorder and overlay windows.

### Computer (Windows + WSL, or any Linux that can run a JVM)

```bash
desktop/install.sh     # install as an app: desktop pet, start on boot, desktop icon
./desktop.sh           # or launch it only in a terminal; open the address it prints in your browser
```

### Cloud server

An optional relay lets the phone reach the computer over the internet. The computer merges changes from both devices.

### Let the phone schedule agents on your computer

The computer needs an SSH service, `bwrap`, and [opencode](https://opencode.ai/). In the app, tap the computer icon, copy the public key into the computer's `~/.ssh/authorized_keys`, and enter the address. Sub agents use the DeepSeek key on your phone.

## Where your data lives

| | |
| :-- | :-- |
| **API key** | Stored only on your devices (encrypted on the phone; a file only you can read on the computer), and sent only to DeepSeek |
| **Conversations, cards, images, and recordings** | Once the phone and computer are connected, they can sync conversations, cards, to-dos, and missing card media. Each device keeps a local copy and stores its own settings. |
| **Sent to DeepSeek** | What you say (recordings are used for recognition), conversation content, and the text to be read aloud |
| **Sent to your own computer** | Only after you configure the computer connection: run logs and the recording of each of your turns, for troubleshooting and wake-word improvement. If you do not want to keep them, do not configure the computer connection |

## More

- [Development guide](docs/DEVELOPMENT.md): feature details, code structure, build, test, and release
- [Architecture diagram](docs/interaction-states-simple.png): the four layers of hardware, models, features, and UI, plus the voice state machine
- [Agent working principles](docs/agent-principles.md): how the super agent, sub agents, and people divide the work
- [Third-party components used](docs/THIRD_PARTY.md)
- [Source repository](https://github.com/mindtravel/Reduction)

## License

[MIT](LICENSE). The licenses of the third-party components used are listed [here](docs/THIRD_PARTY.md).
