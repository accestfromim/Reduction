import Foundation
import OSLog

struct RealtimeCallError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

@MainActor
final class RealtimeConnection {
    enum Phase { case connecting, listening, speaking, closing }
    enum Picture: String, Hashable { case camera = "摄像头画面", window = "共享窗口画面" }

    var onPhase: (Phase) -> Void = { _ in }
    var onConnected: () throws -> Void = {}
    var onAudio: (Data) throws -> Void = { _ in }
    var onReplyText: (String) -> Void = { _ in }
    var onInterrupt: () -> Void = {}
    var onEnded: (String?) -> Void = { _ in }
    private(set) var phase = Phase.connecting
    var isConnected: Bool { phase == .listening || phase == .speaking }

    private enum Kind: Hashable { case control, audio, picture(Picture) }
    private struct OutboxItem {
        let text: String
        let kind: Kind
    }
    private struct PictureFrame {
        let jpeg: Data
        let source: Picture
        var isNew = true
    }

    private let log = Logger(subsystem: "com.mindtravel.reduction.macos", category: "RealtimeCall")
    private var session: URLSession?
    private var socket: URLSessionWebSocketTask?
    private var reader: Task<Void, Never>?
    private var writer: Task<Void, Never>?
    private var deadline: Task<Void, Never>?
    private var pictureSender: Task<Void, Never>?
    private var outbox: [OutboxItem] = []
    private var reported: Set<Kind> = []
    private var muted = false
    private var receivedAudio = false
    private var questionID: String?
    private var replyText = ""
    private var latestPicture: PictureFrame?
    private var lastPictureTime: TimeInterval = -.infinity

    func start(apiKey: String) {
        let session = URLSession(configuration: .ephemeral)
        var request = URLRequest(url: URL(string: "wss://openspeech.bytedance.com/api/v3/realtime")!)
        request.setValue(apiKey, forHTTPHeaderField: "X-Api-Key")
        let socket = session.webSocketTask(with: request)
        self.session = session
        self.socket = socket
        socket.resume()
        setDeadline(seconds: 15, message: "连接豆包超时。请检查网络和该项目的模型开通状态。")
        reader = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    let message = try await socket.receive()
                    guard let self, self.socket === socket else { return }
                    try self.receive(message)
                } catch {
                    guard let self, self.socket === socket else { return }
                    self.failed(error)
                    return
                }
            }
        }
        send([
            "type": "session.create",
            "session": [
                "model": "1.3.0.0",
                "instructions": "你是 Reduction 的实时通话助手。用自然简洁的中文回答，结合用户主动分享的画面理解问题。",
                "audio": [
                    "input": ["format": ["type": "pcm", "rate": 16_000]],
                    "output": ["format": ["type": "pcm_s16le", "rate": 24_000], "voice": "zh_female_vv_jupiter_bigtts"]
                ]
            ],
            "extension": ["video": ["frame_format": "JPEG", "frame_rate": 2]]
        ])
    }

    func sendAudio(_ frame: Data) {
        guard isConnected, !muted else { return }
        send(["type": "input_audio_buffer.append", "audio": frame.base64EncodedString()], kind: .audio)
    }

    func updatePicture(_ jpeg: Data, source: Picture) {
        guard isConnected else { return }
        latestPicture = PictureFrame(jpeg: jpeg, source: source)
        guard pictureSender == nil else { return }
        pictureSender = Task { [weak self] in
            while !Task.isCancelled {
                self?.sendLatestPicture()
                do { try await Task.sleep(for: .milliseconds(520)) }
                catch { return }
            }
        }
    }

    func discardPictures() {
        pictureSender?.cancel()
        pictureSender = nil
        latestPicture = nil
        discardQueuedPictures()
    }

    private func sendLatestPicture() {
        guard let frame = latestPicture, isConnected else { return }
        let now = ProcessInfo.processInfo.systemUptime
        // 静态窗口也要定期更新，避免提问时模型只收到音频。
        guard now - lastPictureTime >= (frame.isNew ? 0.5 : 1) else { return }
        lastPictureTime = now
        latestPicture?.isNew = false
        discardQueuedPictures()
        send(["type": "input_image_buffer.append", "image": frame.jpeg.base64EncodedString()],
             kind: .picture(frame.source))
    }

    private func discardQueuedPictures() {
        outbox.removeAll { item in
            if case .picture = item.kind { return true }
            return false
        }
    }

    func setMuted(_ muted: Bool) {
        guard isConnected, self.muted != muted else { return }
        self.muted = muted
        if muted { outbox.removeAll { $0.kind == .audio } }
        send(["type": muted ? "input_audio_mute.commit" : "input_audio_unmute.commit"])
    }

    func hangUp() {
        guard phase != .closing else { return }
        guard isConnected else { finish(); return }
        phase = .closing
        onPhase(phase)
        discardPictures()
        outbox.removeAll()
        setDeadline(seconds: 2, message: nil)
        send(["type": "session.close"])
    }

    func finish(_ message: String? = nil) {
        guard let socket else { return }
        self.socket = nil
        discardPictures()
        deadline?.cancel()
        reader?.cancel()
        writer?.cancel()
        deadline = nil
        reader = nil
        writer = nil
        outbox.removeAll()
        socket.cancel(with: .normalClosure, reason: nil)
        session?.invalidateAndCancel()
        session = nil
        log.notice("实时通话已结束")
        onEnded(message)
    }

    private func setDeadline(seconds: UInt64, message: String?) {
        deadline?.cancel()
        deadline = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: seconds * 1_000_000_000) }
            catch { return }
            self?.finish(message)
        }
    }

    private func send(_ payload: [String: Any], kind: Kind = .control) {
        guard let socket else { return }
        guard outbox.count < 50 else {
            finish("网络无法及时发送音视频，请重新通话。")
            return
        }
        do {
            let data = try JSONSerialization.data(withJSONObject: payload)
            outbox.append(OutboxItem(text: String(decoding: data, as: UTF8.self), kind: kind))
        } catch {
            failed(error)
            return
        }
        guard writer == nil else { return }
        writer = Task { [weak self] in
            guard let self else { return }
            defer { self.writer = nil }
            while self.socket === socket, !self.outbox.isEmpty {
                let item = self.outbox.removeFirst()
                do {
                    try await socket.send(.string(item.text))
                    guard self.socket === socket else { return }
                    self.report(item.kind)
                } catch {
                    if self.socket === socket { self.failed(error) }
                    return
                }
            }
        }
    }

    private func report(_ kind: Kind) {
        guard reported.insert(kind).inserted else { return }
        switch kind {
        case .control: break
        case .audio: log.notice("实时通话已发送麦克风音频")
        case .picture(let source): log.notice("实时通话已发送\(source.rawValue, privacy: .public)")
        }
    }

    private func receive(_ message: URLSessionWebSocketTask.Message) throws {
        let data: Data
        switch message {
        case .string(let text): data = Data(text.utf8)
        case .data(let bytes): data = bytes
        @unknown default: throw RealtimeCallError("豆包返回了无法识别的消息。")
        }
        guard let payload = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = payload["type"] as? String else {
            throw RealtimeCallError("豆包返回了无效的会话消息。")
        }
        if type == "session.closed" { finish(); return }
        if phase == .closing { return }
        switch type {
        case "session.created":
            guard phase == .connecting else { return }
            deadline?.cancel()
            deadline = nil
            try onConnected()
            phase = .listening
            onPhase(phase)
            log.notice("豆包实时会话已连接")
        case "response.created":
            guard isConnected else { return }
            questionID = payload["question_id"] as? String
            replyText = ""
            phase = .speaking
            onPhase(phase)
        case "response.output_text.delta", "response.output_text.done":
            guard phase == .speaking else { return }
            if let questionID, let id = payload["question_id"] as? String, id != questionID { return }
            let text: String
            if type == "response.output_text.delta" {
                guard let delta = payload["delta"] as? String else { return }
                text = replyText + delta
            } else {
                guard let finalText = payload["text"] as? String, !finalText.isEmpty else { return }
                text = finalText
            }
            guard text != replyText else { return }
            replyText = text
            onReplyText(text)
        case "response.output_audio.delta":
            guard phase == .speaking else { return }
            guard let encoded = payload["delta"] as? String, let bytes = Data(base64Encoded: encoded) else {
                throw RealtimeCallError("豆包返回的播报音频格式无效。")
            }
            try onAudio(bytes)
            if !receivedAudio && !bytes.isEmpty {
                receivedAudio = true
                log.notice("实时通话已收到播报音频")
            }
        case "response.canceled", "response.done":
            guard isConnected else { return }
            if type == "response.canceled" { onInterrupt() }
            phase = .listening
            onPhase(phase)
        case "error":
            let error = payload["error"] as? [String: Any]
            let code = error?["code"] ?? error?["status_code"] ?? payload["status_code"]
            let suffix = code.map { "（\($0)）" } ?? ""
            finish("豆包无法继续通话\(suffix)。请核对 API Key、模型开通状态和账户额度。")
        default: break
        }
    }

    private func failed(_ error: Error) {
        if phase == .closing { finish(); return }
        if let response = socket?.response as? HTTPURLResponse, response.statusCode >= 400 {
            finish("豆包连接失败（HTTP \(response.statusCode)）。请核对项目 API Key 和模型开通状态。")
        } else {
            finish(error.localizedDescription)
        }
    }
}
