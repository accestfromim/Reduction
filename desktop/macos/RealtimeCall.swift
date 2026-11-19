import AppKit
import AVFoundation
import OSLog
import Security

@MainActor
final class RealtimeCall: NSObject, NSWindowDelegate {
    private let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 550, height: 630),
                                  styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
    private let keyField = NSSecureTextField()
    private let status = NSTextField(wrappingLabelWithString: "填写 API Key 后开始通话。")
    private let pictureStatus = NSTextField(labelWithString: "画面已关闭")
    private let preview = NSImageView()
    private let replyView = NSTextView()
    private let replyAttributes: [NSAttributedString.Key: Any] = {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineSpacing = 4
        return [.font: NSFont.systemFont(ofSize: 17), .foregroundColor: NSColor.labelColor,
                .paragraphStyle: paragraph]
    }()
    private let callButton = NSButton()
    private let muteButton = NSButton()
    private let cameraButton = NSButton()
    private let shareButton = NSButton()
    private let stopPictureButton = NSButton()
    private let video = RealtimeVideo()
    private let log = Logger(subsystem: "com.mindtravel.reduction.macos", category: "RealtimeCall")
    private var permissionTask: Task<Void, Never>?
    private var connection: RealtimeConnection?
    private var audio: RealtimeAudio?
    private var muted = false
    private var replyStart: Int?

    override init() {
        super.init()
        configureWindow()
        video.onChange = { [weak self] in self?.updatePicture() }
        video.onError = { [weak self] message in self?.showStatus(message, error: true) }
        video.onFrame = { [weak self] jpeg, picture in
            guard let self, let connection = self.connection, connection.isConnected else { return }
            let hadPreview = self.preview.image != nil
            self.preview.image = jpeg.flatMap { NSImage(data: $0) }
            if let jpeg {
                connection.updatePicture(jpeg, source: picture)
            } else {
                connection.discardPictures()
            }
            if hadPreview != (self.preview.image != nil) { self.updatePicture() }
        }
        do { keyField.stringValue = try RealtimeKeychain.load() }
        catch { showStatus(error.localizedDescription, error: true) }
        refreshControls()
    }

    func show() {
        NSApp.activate()
        window.makeKeyAndOrderFront(nil)
        log.notice("已打开实时通话窗口")
    }

    func shutdown() {
        permissionTask?.cancel()
        permissionTask = nil
        stopMedia()
        connection?.finish()
    }

    func windowWillClose(_ notification: Notification) {
        stopCall()
    }

    private func configureWindow() {
        window.title = "Reduction · 实时通话"
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.contentMinSize = NSSize(width: 480, height: 580)
        window.center()
        window.setFrameAutosaveName("ReductionRealtimeCall")

        let keyLabel = NSTextField(labelWithString: "火山引擎项目 API Key")
        keyLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        keyField.placeholderString = "输入已开通实时音视频模型的项目密钥"
        keyField.setAccessibilityLabel(keyLabel.stringValue)

        let notice = NSTextField(wrappingLabelWithString: "实时通话会将麦克风音频和你开启的摄像头或选中的窗口画面发送给火山引擎。")
        notice.font = .systemFont(ofSize: 12)
        notice.textColor = .secondaryLabelColor

        configure(callButton, title: "开始通话", action: #selector(toggleCall))
        configure(muteButton, title: "静音", action: #selector(toggleMute))
        configure(cameraButton, title: "开启摄像头", action: #selector(toggleCamera))
        configure(shareButton, title: "共享窗口…", action: #selector(shareWindow))
        configure(stopPictureButton, title: "停止画面", action: #selector(stopPicture))
        let callControls = NSStackView(views: [callButton, muteButton])
        let pictureControls = NSStackView(views: [cameraButton, shareButton, stopPictureButton])
        callControls.spacing = 10
        pictureControls.spacing = 10

        preview.imageScaling = .scaleProportionallyUpOrDown
        preview.wantsLayer = true
        preview.layer?.backgroundColor = NSColor.black.withAlphaComponent(0.12).cgColor
        preview.layer?.cornerRadius = 8
        preview.setAccessibilityLabel("当前共享画面")
        pictureStatus.textColor = .secondaryLabelColor

        let replyLabel = NSTextField(labelWithString: "豆包回复")
        replyLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        replyView.setAccessibilityLabel(replyLabel.stringValue)
        replyView.isEditable = false
        replyView.isSelectable = true
        replyView.isRichText = false
        replyView.backgroundColor = .textBackgroundColor
        replyView.typingAttributes = replyAttributes
        replyView.textContainerInset = NSSize(width: 12, height: 10)
        replyView.isVerticallyResizable = true
        replyView.autoresizingMask = [.width]
        replyView.textContainer?.widthTracksTextView = true
        let replyScroll = NSScrollView()
        replyScroll.borderType = .bezelBorder
        replyScroll.hasVerticalScroller = true
        replyScroll.autohidesScrollers = true
        replyScroll.documentView = replyView

        let stack = NSStackView(views: [keyLabel, keyField, notice, callControls, status,
                                        pictureControls, pictureStatus, preview, replyLabel, replyScroll])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false
        let content = NSView()
        content.addSubview(stack)
        for view in [keyField, notice, status, preview, replyScroll] {
            view.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true
        }
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: content.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -20),
            preview.heightAnchor.constraint(equalToConstant: 150),
            replyScroll.heightAnchor.constraint(greaterThanOrEqualToConstant: 96),
            status.heightAnchor.constraint(greaterThanOrEqualToConstant: 34)
        ])
        window.contentView = content
        window.initialFirstResponder = keyField
    }

    private func configure(_ button: NSButton, title: String, action: Selector) {
        button.title = title
        button.target = self
        button.action = action
        button.bezelStyle = .rounded
    }

    @objc private func toggleCall() {
        if connection != nil || permissionTask != nil { stopCall(); return }
        window.makeFirstResponder(nil)
        let key = keyField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty, key.utf8.allSatisfy({ $0 > 32 && $0 < 127 }) else {
            showStatus("请填写火山引擎的项目 API Key。", error: true)
            return
        }
        do { try RealtimeKeychain.save(key) }
        catch { showStatus(error.localizedDescription, error: true); return }
        showStatus("正在检查麦克风权限…")
        permissionTask = Task { [weak self] in
            let allowed = await requestRealtimeMedia(.audio)
            guard let self, !Task.isCancelled else { return }
            self.permissionTask = nil
            if allowed {
                self.connect(key)
            } else {
                self.showStatus("请在系统设置的「麦克风」中允许 Reduction。", error: true)
                self.refreshControls()
            }
        }
        refreshControls()
    }

    private func connect(_ key: String) {
        let connection = RealtimeConnection()
        self.connection = connection
        replyView.string = ""
        replyStart = nil
        connection.onConnected = { [weak self, weak connection] in
            guard let self, let connection else { return }
            self.audio = try RealtimeAudio(onFrame: { [weak connection] frame in
                connection?.sendAudio(frame)
            }, onError: { [weak connection] error in
                connection?.finish("麦克风采集失败。\n\(error.localizedDescription)")
            })
        }
        connection.onPhase = { [weak self] phase in
            if phase == .speaking { self?.replyStart = nil }
            self?.updateCallStatus()
        }
        connection.onAudio = { [weak self] data in try self?.audio?.play(data) }
        connection.onReplyText = { [weak self] text in self?.displayReply(text) }
        connection.onInterrupt = { [weak self] in self?.audio?.interruptPlayback() }
        connection.onEnded = { [weak self, weak connection] message in
            guard let self, let connection, self.connection === connection else { return }
            self.connection = nil
            self.stopMedia()
            self.showStatus(message ?? "通话已结束。", error: message != nil)
            self.refreshControls()
        }
        updateCallStatus()
        connection.start(apiKey: key)
    }

    @objc private func toggleMute() {
        guard let connection, connection.isConnected, let audio else { return }
        if muted {
            do { try audio.startInput() }
            catch { showStatus(error.localizedDescription, error: true); return }
        } else {
            audio.stopInput()
        }
        muted.toggle()
        connection.setMuted(muted)
        updateCallStatus()
    }

    @objc private func toggleCamera() {
        guard connection?.isConnected == true else { return }
        video.chooseCamera()
    }

    @objc private func shareWindow() {
        guard connection?.isConnected == true else { return }
        video.chooseWindow(excluding: CGWindowID(window.windowNumber))
    }

    @objc private func stopPicture() { video.stop() }

    private func stopCall() {
        permissionTask?.cancel()
        permissionTask = nil
        stopMedia()
        if let connection { connection.hangUp() }
        else { showStatus("通话已结束。") }
        refreshControls()
    }

    private func stopMedia() {
        audio?.stop()
        audio = nil
        video.stop()
        muted = false
    }

    private func updateCallStatus() {
        guard let connection else { return }
        switch connection.phase {
        case .connecting: showStatus("正在连接豆包…")
        case .listening: showStatus(muted ? "通话中，麦克风已静音。" : "通话中，可以说话。")
        case .speaking: showStatus(muted ? "豆包正在回答，麦克风已静音。" : "豆包正在回答，可以插话。")
        case .closing: showStatus("正在结束通话…")
        }
        refreshControls()
    }

    private func updatePicture() {
        switch video.picture {
        case .camera: pictureStatus.stringValue = preview.image == nil ? "正在等待摄像头画面…" : "正在发送摄像头画面"
        case .window: pictureStatus.stringValue = preview.image == nil ? "正在等待窗口画面…" : "正在共享选中的窗口"
        case nil:
            preview.image = nil
            connection?.discardPictures()
            if video.isSelectingWindow {
                pictureStatus.stringValue = "请在系统选择器中选中要共享的窗口。"
            } else {
                pictureStatus.stringValue = video.isChoosing ? "正在等待画面…" : "画面已关闭"
            }
        }
        refreshControls()
    }

    private func displayReply(_ text: String) {
        guard let storage = replyView.textStorage else { return }
        let followLatest = replyView.selectedRange().length == 0 &&
            replyView.visibleRect.maxY >= replyView.bounds.maxY - 16
        if replyStart == nil {
            if storage.length > 0 {
                storage.append(NSAttributedString(string: "\n\n", attributes: replyAttributes))
            }
            replyStart = storage.length
        }
        guard let start = replyStart else { return }
        storage.replaceCharacters(in: NSRange(location: start, length: storage.length - start),
                                  with: NSAttributedString(string: text, attributes: replyAttributes))
        if followLatest { replyView.scrollRangeToVisible(NSRange(location: storage.length, length: 0)) }
    }

    private func refreshControls() {
        let busy = connection != nil || permissionTask != nil
        let connected = connection?.isConnected == true
        callButton.title = busy ? "结束通话" : "开始通话"
        callButton.isEnabled = connection?.phase != .closing
        keyField.isEnabled = !busy
        muteButton.title = muted ? "取消静音" : "静音"
        muteButton.isEnabled = connected
        cameraButton.title = video.picture == .camera ? "关闭摄像头" : "开启摄像头"
        cameraButton.isEnabled = connected && !video.isChoosing
        shareButton.isEnabled = connected && !video.isChoosing
        stopPictureButton.isEnabled = connected && (video.picture != nil || video.isChoosing)
    }

    private func showStatus(_ message: String, error: Bool = false) {
        status.stringValue = message
        status.textColor = error ? .systemRed : .labelColor
        if error { log.notice("实时通话显示错误状态") }
    }
}

private enum RealtimeKeychain {
    private static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.mindtravel.reduction.macos",
        kSecAttrAccount as String: "doubao-realtime-api-key"
    ]

    static func load() throws -> String {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return "" }
        guard status == errSecSuccess, let data = result as? Data, let key = String(data: data, encoding: .utf8) else {
            throw RealtimeCallError("无法读取钥匙串中的实时通话 API Key（\(status)）。")
        }
        return key
    }

    static func save(_ key: String) throws {
        let value = Data(key.utf8)
        var status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: value] as CFDictionary)
        if status == errSecItemNotFound {
            var request = query
            request[kSecValueData as String] = value
            status = SecItemAdd(request as CFDictionary, nil)
        }
        guard status == errSecSuccess else {
            throw RealtimeCallError("无法将实时通话 API Key 写入钥匙串（\(status)）。")
        }
    }
}
