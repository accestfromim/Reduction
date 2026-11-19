import Foundation
import OSLog
import UIKit

@MainActor
final class PhoneCore {
    let store: PhoneStore
    private let audio: PhoneAudio
    private let notifications = MemoNotifications()
    private let speech: PhoneSpeech
    let computer: PhoneComputer
    private let pages = ToolPages()
    private let emit: PageEvent
    private let exportArticle: (String, Int, String) -> Void
    private let log = Logger(subsystem: "com.mindtravel.reduction.ios", category: "Phone")
    private var chat: (id: String, task: Task<Void, Never>)?
    private var transcription: Task<Void, Never>?
    private var pictures: [String: [ModelPicture]] = [:]
    private var mutedReply: String?

    init(directory: URL, cache: URL, emit: @escaping PageEvent,
         confirm: @escaping (String, String) async -> Bool, exportArticle: @escaping (String, Int, String) -> Void) throws {
        self.emit = emit
        self.exportArticle = exportArticle
        store = try PhoneStore(directory: directory)
        audio = try PhoneAudio(directory: cache.appendingPathComponent("Recording"))
        speech = try PhoneSpeech(audio: audio, directory: cache.appendingPathComponent("Speech"), emit: emit)
        computer = PhoneComputer(store: store, emit: emit, confirm: confirm)
        audio.recorded = { [weak self] id, limited, result in self?.receivedAudio(id, limited: limited, result: result) }
        computer.afterSync = { [weak self] in
            guard let self else { return }
            self.notifications.update(self.store.state.memos)
        }
        computer.agentFinished = { [weak self] title, result in
            guard let self, self.chat == nil else { return false }
            do {
                let limited = self.pages.store(result.text)
                let media = JSON.array(result.pictures.map { image in
                    ["mediaType": .string(image.mime), "base64": .string(image.data.base64EncodedString())]
                })
                return try self.submit(id: UUID().uuidString,
                                       raw: title + "\n请简要汇报这项任务的结果。\n\n" + limited, images: media)
            } catch {
                self.log.error("电脑任务汇报失败 \(error.localizedDescription, privacy: .private)")
                return false
            }
        }
    }

    func foreground() {
        notifications.update(store.state.memos)
        emit("onMemosChanged", [""])
        computer.foreground()
    }

    func background() { computer.background() }

    func handle(_ method: String, _ arguments: JSON) async throws -> JSON {
        switch method {
        case "pageReady": log.notice("Reduction iOS 已载入对话和卡片界面"); return .null
        case "loadSettings": return .string(try store.settings().encoded())
        case "saveSettings":
            try store.saveSettings(JSON(text: arguments[0].text))
            computer.settingsChanged()
            return true
        case "loadHistory": return .string(try store.historyJSON())
        case "clearHistory":
            if let chat { chat.task.cancel(); emit("onChatAborted", [.string(chat.id)]) }
            chat = nil
            speech.stop()
            pictures.removeAll()
            try store.clearHistory()
            computer.changed()
            return .null
        case "listCards": return .string(try directory().encoded())
        case "readCard": return .string(cardText(arguments[0].text))
        case "cardPurpose": return .string(store.state.cards[arguments[0].text]?.purpose ?? "")
        case "saveCard":
            try store.saveCard(arguments[0].text, purpose: arguments[1].text, content: arguments[2].text)
            cardsChanged(arguments[0].text)
            return true
        case "renameCard":
            try store.renameCard(arguments[0].text, to: arguments[1].text)
            cardsChanged(arguments[1].text)
            return true
        case "deleteCard":
            try store.deleteCard(arguments[0].text)
            cardsChanged(arguments[0].text)
            return true
        case "lockCard":
            try store.lockCard(arguments[0].text, locked: arguments[1].flag)
            cardsChanged(arguments[0].text)
            return true
        case "saveCardMedia":
            guard let bytes = Data(base64Encoded: arguments[0].text) else { throw ReductionError("附件编码无效") }
            return .string(try store.saveMedia(bytes, kind: arguments[1].text))
        case "saveCardPage":
            guard let bytes = Data(base64Encoded: arguments[2].text) else { throw ReductionError("PDF 图像编码无效") }
            try store.savePage(arguments[0].text, page: arguments[1].integer, data: bytes)
            computer.changed()
            return true
        case "cardPages": return .number(Double(store.pageCount(arguments[0].text)))
        case "articleTemplate": return .string(try articleTemplate().encoded())
        case "exportArticle":
            let html = arguments[0].text
            let height = arguments[1].integer
            guard !html.isEmpty, html.utf8.count <= 4 * 1024 * 1024, (1...18_000).contains(height) else {
                emit("onError", ["", "请等待预览加载，或把过长的文章分开导出"])
                return false
            }
            exportArticle(html, height, arguments[2].text)
            return true
        case "memoView": return .string(try store.memoView(authorized: await notifications.authorized()))
        case "setMemoDone":
            try store.memoDone(arguments[0].integer, done: arguments[1].flag)
            memosChanged()
            return true
        case "askForNotifications":
            Task {
                do {
                    let accepted = try await notifications.request()
                    if !accepted, let settings = URL(string: UIApplication.openSettingsURLString) { await UIApplication.shared.open(settings) }
                    memosChanged()
                } catch { emit("onError", ["", .string(error.localizedDescription)]) }
            }
            return .null
        case "requestMicPermission":
            Task { emit("onMicPermission", [.bool(await audio.permission(interactive: arguments[0].flag))]) }
            return .null
        case "startRecording":
            let id = arguments[0].text
            speech.stop()
            transcription?.cancel()
            do { try audio.start(id); emit("onRecordingStart", [.string(id)]) }
            catch { emit("onAsrResult", [.string(id), false, .string(error.localizedDescription)]) }
            return .null
        case "stopAndTranscribe": audio.stop(arguments[0].text); return .null
        case "cancelRecording": audio.cancel(); transcription?.cancel(); return .null
        case "submitText", "submitPictures":
            let id = UUID().uuidString
            do { return .bool(try submit(id: id, raw: arguments[0].text, images: arguments[1])) }
            catch { emit("onChatError", [.string(id), .string(error.localizedDescription)]); return false }
        case "speak":
            speech.begin(id: arguments[0].text, api: try DeepSeek(key: store.apiKey()), voice: try store.settings()["voice"].text)
            speech.append(arguments[1].text)
            speech.finish()
            return .null
        case "stopSpeaking": mutedReply = chat?.id; speech.stop(); return .null
        case "clearAudioCache": try speech.clearCache(); return true
        case "pcState": return .string(computer.state)
        case "pcConnected": return .bool(computer.state == "connected")
        case "pcConnect": computer.connect(); return .null
        case "pcInfo": computer.info(arguments[0].text); return .null
        case "pcExec": computer.command(arguments[0].text, command: arguments[1].text); return .null
        case "pcPublicKey": return .string(try computer.publicKey())
        case "copyText": UIPasteboard.general.string = arguments[0].text; return .null
        case "vibrate": UIImpactFeedbackGenerator(style: .light).impactOccurred(); return .null
        case "getAppInfo": return "Reduction iOS · 本地保存对话、卡片和待办"
        case "voiceState": return "{\"state\":\"IDLE\"}"
        case "updateState": return "idle"
        case "updateVersion", "floatingCard": return ""
        case "setWakeWordEnabled", "floatCard": return false
        default: throw ReductionError("iOS 不支持此操作")
        }
    }

    private func directory() -> JSON {
        var items = store.state.cards.values.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }.map(\.directoryEntry)
        items.insert(["name": "计划", "purpose": "待办和日期", "locked": true, "prompt": false], at: 0)
        return .array(items)
    }

    func mediaURL(_ id: String) -> URL? { store.mediaURL(id) }

    private func cardText(_ name: String) -> String {
        name == "计划" ? store.memoText() : store.state.cards[name]?.text ?? ""
    }

    private func articleTemplate() throws -> JSON {
        let folder = Bundle.main.bundleURL.appendingPathComponent("WebAssets/article", isDirectory: true)
        let template: String
        let style: String
        if let custom = store.state.cards["文章模板/template.html"]?.text { template = custom }
        else { template = try String(contentsOf: folder.appendingPathComponent("template.html"), encoding: .utf8) }
        if let custom = store.state.cards["文章模板/article.css"]?.text { style = custom }
        else { style = try String(contentsOf: folder.appendingPathComponent("article.css"), encoding: .utf8) }
        return ["template": .string(template), "style": .string(style), "problem": .null]
    }

    private func cardsChanged(_ name: String = "") {
        emit("onCardsChanged", [.string(name)])
        computer.changed()
    }

    private func memosChanged() {
        notifications.update(store.state.memos)
        emit("onMemosChanged", [""])
        computer.changed()
    }

    private func receivedAudio(_ id: String, limited: Bool, result: Result<Data, Error>) {
        if limited { emit("onRecordingLimit", [.string(id)]) }
        transcription = Task {
            do {
                let audio = try result.get()
                emit("onStatus", [.string(id), "识别中"])
                let api = try DeepSeek(key: store.apiKey())
                let text = try await api.transcribe(audio)
                try Task.checkCancellation()
                emit("onAsrResult", [.string(id), true, .string(text)])
            } catch is CancellationError {
                return
            } catch {
                if !Task.isCancelled { emit("onAsrResult", [.string(id), false, .string(error.localizedDescription)]) }
            }
        }
    }

    private func messagePictures(_ input: JSON) throws -> [ModelPicture] {
        let list = input.text.isEmpty ? input.values : try JSON(text: input.text).values
        guard list.count <= 4 else { throw ReductionError("一条消息最多发送四张图片") }
        let images = try list.map { item -> (String, String, Data) in
            let mime = item["mediaType"].text
            let kind = ["image/jpeg": "jpg", "image/png": "png", "image/webp": "webp", "image/gif": "gif"][mime]
            guard let kind, let bytes = Data(base64Encoded: item["base64"].text), !bytes.isEmpty, bytes.count <= 4 * 1024 * 1024 else {
                throw ReductionError("对话图片格式无效或超出大小限制")
            }
            return (mime, kind, bytes)
        }
        return try images.map { mime, kind, bytes in ModelPicture(name: try store.saveMedia(bytes, kind: kind), mime: mime, data: bytes) }
    }

    func submit(id: String, raw: String, images: JSON = .null) throws -> Bool {
        guard chat == nil else { throw ReductionError("请等待当前回复完成") }
        let referenceDate = MemoDays.name()
        let key = try store.apiKey()
        guard !key.isEmpty else { throw ReductionError("请在设置中填写 DeepSeek API Key") }
        let attached = try messagePictures(images)
        let text = raw.trimmed.isEmpty && !attached.isEmpty ? "已发送图片" : raw.trimmed
        guard !text.isEmpty else { return false }
        try store.remember("user", text, referenceDate: referenceDate)
        if !attached.isEmpty, let id = store.state.history.last?.id { pictures[id] = attached }
        let keep = Set(store.state.history.suffix(6).map(\.id))
        pictures = pictures.filter { keep.contains($0.key) }
        mutedReply = nil
        computer.changed()
        emit("onReplyBegin", [.string(id), .string(text)])
        let task = Task { await answer(id: id, api: DeepSeek(key: key), referenceDate: referenceDate) }
        chat = (id, task)
        return true
    }

    private func answer(id: String, api: DeepSeek, referenceDate: String) async {
        defer { if chat?.id == id { chat = nil } }
        do {
            let settings = try store.settings()
            let tools = PhoneTools.available(settings: settings, online: computer.state == "connected")
            let allowed = Set(tools.map { $0["function"]["name"].text })
            let model = settings["model"].text
            var messages = historyForModel(referenceDate: referenceDate)
            for _ in 0..<12 {
                try Task.checkCancellation()
                if settings["autoSpeak"].flag && mutedReply != id { speech.begin(id: id, api: api, voice: settings["voice"].text) }
                let reply = try await api.chat(model: model, messages: messages, tools: tools) { token in
                    guard self.chat?.id == id, !Task.isCancelled else { return }
                    self.emit("onChatToken", [.string(id), .string(token)])
                    self.speech.append(token)
                }
                try Task.checkCancellation()
                if reply.calls.isEmpty {
                    guard !reply.text.trimmed.isEmpty else { throw ReductionError("DeepSeek 没有返回文字") }
                    try store.remember("assistant", reply.text, referenceDate: referenceDate)
                    emit("onChatDone", [.string(id), .string(reply.text)])
                    speech.finish()
                    computer.changed()
                    return
                }
                speech.stop()
                messages.append(["role": "assistant", "content": .string(reply.text), "tool_calls": .array(reply.calls.map(\.message))])
                var attachments: [JSON] = []
                for call in reply.calls {
                    try Task.checkCancellation()
                    let started = nowMilliseconds()
                    emit("onToolStarted", [.string(id), .string(call.name), .string(call.name), .string(call.arguments)])
                    let text: String
                    let success: Bool
                    do {
                        guard allowed.contains(call.name) else { throw ReductionError("工具当前未启用") }
                        let result = try await run(call.name, args: JSON(text: call.arguments.isEmpty ? "{}" : call.arguments),
                                                   api: api, referenceDate: referenceDate)
                        text = pages.store(result.text)
                        attachments.append(contentsOf: result.pictures.map(\.part))
                        success = true
                    } catch {
                        text = error.localizedDescription
                        success = false
                    }
                    emit("onToolFinished", [.string(id), .string(call.name), .number(Double(nowMilliseconds() - started)), .bool(success), .string(text)])
                    messages.append(["role": "tool", "tool_call_id": .string(call.id), "content": .string(text)])
                }
                if !attachments.isEmpty {
                    messages.append(["role": "user", "content": .array([["type": "text", "text": "这些是工具读取到的图片或 PDF 页。"]] + attachments)])
                }
            }
            throw ReductionError("本轮工具调用次数达到上限")
        } catch is CancellationError {
            return
        } catch {
            guard chat?.id == id else { return }
            speech.stop()
            emit("onChatError", [.string(id), .string(error.localizedDescription)])
            log.error("对话请求失败 \(error.localizedDescription, privacy: .private)")
        }
    }

    private func historyForModel(referenceDate: String) -> [JSON] {
        let cards = store.state.cards.values.sorted { $0.name < $1.name }
        let outline = cards.map { "- \($0.name) \($0.purpose)" }.joined(separator: "\n")
        let prompts = cards.filter(\.prompt).map(\.text).joined(separator: "\n\n")
        let system = """
        你是 Reduction 随身助手。回复要自然简短，给出可执行的信息。今天是 \(referenceDate)。
        程序按原消息日期换算相对时间，给出具体日期或日期范围。引用旧话时，按被引用消息的日期理解。日程日期不明确时先确认。写入待办和派发子代理时使用具体日期。跟用户说话时可以按本轮日期说今天、明天或下周几。
        按用户要求使用工具。用户要求记待办时用 todo，date 填 yyyy-MM-dd。用户要求记资料时使用卡片。
        用 read_card 取原文后再修改已有卡片。放入图片时用 ![说明](media:编号)，只用上下文提供的附件编号。
        子代理在用户自己的电脑运行，手机负责派发、验收并告知结果。工具失败要明确说明。
        当前卡片目录
        \(outline)
        \(prompts)
        """
        return [["role": "system", "content": .string(system)]] + store.state.history.suffix(24).map { line in
            let content = line.modelContent
            guard let attached = pictures[line.id], !attached.isEmpty else { return ["role": .string(line.role), "content": .string(content)] }
            let ids = attached.map(\.name).joined(separator: "、")
            let parts = [JSON.object(["type": "text", "text": .string(content + "\n附件编号 \(ids)")])] + attached.map(\.part)
            return ["role": .string(line.role), "content": .array(parts)]
        }
    }

    private func run(_ name: String, args: JSON, api: DeepSeek, referenceDate: String) async throws -> ToolAnswer {
        switch name {
        case "calculator": return ToolAnswer(text: try PhoneTools.calculator(args["expression"].text))
        case "web_search": return ToolAnswer(text: try await api.search(args["query"].text))
        case "patch_cards":
            let text = try store.applyCards(args["patch"].text)
            cardsChanged()
            return ToolAnswer(text: text)
        case "read_card": return try PhoneTools.read(args, store: store)
        case "search_cards": return ToolAnswer(text: try PhoneTools.search(args, store: store))
        case "todo":
            let text = try store.applyMemo(args, referenceDate: referenceDate)
            memosChanged()
            return ToolAnswer(text: text)
        case "read_more": return ToolAnswer(text: try pages.read(args))
        case "session_status": return ToolAnswer(text: try await computer.sessionStatus())
        case "run_agent": return try await computer.agent(args, key: api.key)
        default: throw ReductionError("工具不存在")
        }
    }
}
