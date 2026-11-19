import AppKit
import AVFoundation
import ServiceManagement
import WebKit

@main
struct Reduction {
    static func main() {
        switch CommandLine.arguments.dropFirst().first {
        case "--render-article":
            MacArticle.render(CommandLine.arguments.dropFirst(2))
        case "--audio-play", "--audio-record":
            do {
                if CommandLine.arguments[1] == "--audio-play" { try MacAudio.play() }
                else { try MacAudio.record() }
            } catch {
                fail(error.localizedDescription)
            }
        default:
            let app = NSApplication.shared
            let controller = AppController()
            app.delegate = controller
            app.setActivationPolicy(.regular)
            withExtendedLifetime(controller) { app.run() }
        }
    }

    static func fail(_ message: String) -> Never {
        FileHandle.standardError.write(Data((message + "\n").utf8))
        exit(EXIT_FAILURE)
    }
}

final class AppController: NSObject, NSApplicationDelegate, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    private let service = LocalService()
    private let pet = DesktopPet()
    private let shortcuts = DesktopShortcuts()
    private let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 550, height: 720),
                                  styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
    private var web: WKWebView?
    private var realtimeCall: RealtimeCall?
    private var menuBar: NSStatusItem?
    private var petItem: NSMenuItem?
    private var shortcutItem: NSMenuItem?
    private var loginItem: NSMenuItem?
    private var timer: Timer?
    private var polling = false
    private var ready = false
    private var lastLine: Int?
    private var pressed: DesktopShortcuts.Action?
    private var recording: DesktopShortcuts.Action?
    private var starting = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        window.title = "Reduction"
        window.minSize = NSSize(width: 370, height: 460)
        window.isReleasedWhenClosed = false
        window.center()
        window.setFrameAutosaveName("ReductionWindow")
        setMessage("正在启动 Reduction…")
        configureMenus()
        pet.onClick = { [weak self] in self?.togglePanel() }
        pet.setVisible(!UserDefaults.standard.bool(forKey: "reductionPetHidden"))
        petItem?.state = pet.isVisible ? .on : .off
        shortcuts.onEvent = { [weak self] action, down in self?.shortcut(action, down: down) }
        if !UserDefaults.standard.bool(forKey: "reductionShortcutsOff") { enableShortcuts() }
        showPanel()
        do {
            try service.start(onReady: { [weak self] in self?.loadPanel() }, onExit: { [weak self] status in
                self?.serviceFailed("本地服务已退出（\(status)）。\n\n查看菜单中的运行日志。")
            })
        } catch {
            serviceFailed("无法启动本地服务。\n\n\(error.localizedDescription)")
        }
        Task { @MainActor [weak self] in
            await self?.pet.requestScreenCapturePermission()
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationDidBecomeActive(_ notification: Notification) {
        pet.refreshLens()
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showPanel()
        return true
    }

    func applicationWillTerminate(_ notification: Notification) {
        realtimeCall?.shutdown()
        timer?.invalidate()
        pet.setVisible(false)
        shortcuts.stop()
        web?.configuration.userContentController.removeScriptMessageHandler(forName: "reduction")
        service.stop()
    }

    private func loadPanel() {
        let controller = WKUserContentController()
        controller.add(self, name: "reduction")
        controller.addUserScript(WKUserScript(source: "window.reductionMicrophoneToken = '\(service.microphoneToken)';",
                                               injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        configuration.defaultWebpagePreferences.preferredContentMode = .desktop
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.underPageBackgroundColor = .black
        view.navigationDelegate = self
        view.uiDelegate = self
        web = view
        window.contentView = view
        view.load(URLRequest(url: service.url))
        timer = Timer.scheduledTimer(withTimeInterval: 0.35, repeats: true) { [weak self] _ in self?.pollPet() }
        pollPet()
    }

    private func serviceFailed(_ description: String) {
        ready = false
        timer?.invalidate()
        pet.pose("asleep")
        setMessage(description)
    }

    private func setMessage(_ text: String) {
        let content = NSView()
        let label = NSTextField(wrappingLabelWithString: text)
        label.alignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: content.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: content.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(lessThanOrEqualTo: content.trailingAnchor, constant: -24)
        ])
        window.contentView = content
    }

    private func configureMenus() {
        let main = NSMenu()
        let application = NSMenuItem()
        let menu = NSMenu()
        menu.addItem(item("打开对话与卡片", #selector(showPanel)))
        menu.addItem(item("实时通话…", #selector(showRealtimeCall)))
        menu.addItem(item("查看运行日志", #selector(openLog)))
        menu.addItem(.separator())
        menu.addItem(withTitle: "退出 Reduction", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        application.submenu = menu
        main.addItem(application)

        let edit = NSMenuItem(title: "编辑", action: nil, keyEquivalent: "")
        let editing = NSMenu(title: "编辑")
        editing.addItem(withTitle: "撤销", action: Selector(("undo:")), keyEquivalent: "z")
        editing.addItem(withTitle: "剪切", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        editing.addItem(withTitle: "复制", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        editing.addItem(withTitle: "粘贴", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        editing.addItem(withTitle: "全选", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        edit.submenu = editing
        main.addItem(edit)
        let windows = NSMenuItem(title: "窗口", action: nil, keyEquivalent: "")
        let windowMenu = NSMenu(title: "窗口")
        windowMenu.addItem(withTitle: "关闭窗口", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        windowMenu.addItem(withTitle: "最小化", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        windows.submenu = windowMenu
        main.addItem(windows)
        NSApp.mainMenu = main
        NSApp.windowsMenu = windowMenu

        let status = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        let icon = NSImage(systemSymbolName: "waveform", accessibilityDescription: "Reduction")
        status.button?.image = icon
        status.button?.toolTip = "Reduction"
        let controls = NSMenu()
        controls.addItem(item("打开对话与卡片", #selector(showPanel)))
        controls.addItem(item("实时通话…", #selector(showRealtimeCall)))
        let pet = item("显示桌宠", #selector(togglePet))
        petItem = pet
        controls.addItem(pet)
        let shortcuts = item("启用全局快捷键", #selector(toggleShortcuts))
        shortcutItem = shortcuts
        controls.addItem(shortcuts)
        controls.addItem(NSMenuItem(title: "按住 ⌃⇧空格说话，⌃⌥⇧空格听写", action: nil, keyEquivalent: ""))
        controls.addItem(NSMenuItem(title: "⌃⇧R 打开或收起面板", action: nil, keyEquivalent: ""))
        let login = item("登录时打开", #selector(toggleLogin))
        loginItem = login
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        controls.addItem(login)
        controls.addItem(.separator())
        controls.addItem(item("查看运行日志", #selector(openLog)))
        controls.addItem(withTitle: "退出 Reduction", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "")
        status.menu = controls
        menuBar = status
    }

    private func item(_ title: String, _ action: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        return item
    }

    @objc private func showPanel() {
        NSApp.activate()
        window.makeKeyAndOrderFront(nil)
    }

    @MainActor @objc private func showRealtimeCall() {
        if realtimeCall == nil { realtimeCall = RealtimeCall() }
        realtimeCall?.show()
    }

    private func togglePanel() {
        if window.isVisible, window.isKeyWindow { window.orderOut(nil) }
        else { showPanel() }
    }

    @objc private func openLog() { NSWorkspace.shared.open(service.logURL) }

    @objc private func togglePet() {
        pet.setVisible(!pet.isVisible)
        petItem?.state = pet.isVisible ? .on : .off
        UserDefaults.standard.set(!pet.isVisible, forKey: "reductionPetHidden")
    }

    @objc private func toggleShortcuts() {
        if shortcutItem?.state == .on {
            shortcuts.stop()
            pressed = nil
            endVoice(cancel: true)
            shortcutItem?.state = .off
            UserDefaults.standard.set(true, forKey: "reductionShortcutsOff")
        } else {
            enableShortcuts()
        }
    }

    private func enableShortcuts() {
        do {
            try shortcuts.start()
            shortcutItem?.state = .on
            UserDefaults.standard.set(false, forKey: "reductionShortcutsOff")
        } catch {
            pet.showReply("无法注册 Reduction 的全局快捷键。请检查这些组合键是否已被其他应用使用。")
        }
    }

    @objc private func toggleLogin() {
        do {
            let login = SMAppService.mainApp
            if login.status == .enabled || login.status == .requiresApproval { try login.unregister() }
            else { try login.register() }
            loginItem?.state = login.status == .enabled || login.status == .requiresApproval ? .on : .off
            if login.status == .requiresApproval { SMAppService.openSystemSettingsLoginItems() }
        } catch {
            pet.showReply("无法更改登录项。\n\(error.localizedDescription)")
        }
    }

    private func pollPet() {
        guard !polling else { return }
        polling = true
        service.pet { [weak self] state in
            guard let self else { return }
            self.polling = false
            guard let state else {
                self.ready = false
                self.pet.pose("asleep")
                return
            }
            self.updatePet(state)
        }
    }

    private func updatePet(_ state: PetState) {
        ready = state.ready
        pet.pose(state.state)
        if let previous = lastLine, state.lineId > previous {
            if state.kind == "dictation" { DesktopPet.copy(state.line) }
            if state.kind == "dictation" || !window.isKeyWindow { pet.showReply(state.line) }
        }
        lastLine = max(lastLine ?? 0, state.lineId)
    }

    private func shortcut(_ action: DesktopShortcuts.Action, down: Bool) {
        if action == .panel {
            if down { togglePanel() }
            return
        }
        if !down {
            guard pressed == action else { return }
            pressed = nil
            endVoice(cancel: false)
            return
        }
        guard pressed == nil, recording == nil, !starting else { return }
        guard ready else {
            showPanel()
            pet.showReply("请先在设置里填写 DeepSeek API Key。")
            return
        }
        pressed = action
        microphone { [weak self] granted in
            guard let self, self.pressed == action else { return }
            guard granted else {
                self.pressed = nil
                self.pet.showReply("请在系统设置的「隐私与安全性 → 麦克风」允许 Reduction 录音。")
                return
            }
            self.starting = true
            self.service.pet("start") { state in
                self.starting = false
                guard let state else {
                    self.service.pet("cancel") { _ in }
                    self.pet.showReply("未能启动录音，请重试。")
                    return
                }
                self.updatePet(state)
                guard state.state == "listening" else {
                    self.pet.showReply(state.line)
                    return
                }
                self.recording = action
                if self.pressed == action {
                    self.pet.showReply(action == .dictate ? "正在听，松开后整理文字并复制" : "正在听，松开发送")
                } else {
                    self.endVoice(cancel: true)
                }
            }
        }
    }

    private func endVoice(cancel: Bool) {
        guard let action = recording else { return }
        recording = nil
        service.pet(cancel ? "cancel" : "stop", dictate: action == .dictate) { [weak self] state in
            if let state { self?.updatePet(state) }
        }
    }

    private func microphone(_ completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: completion(true)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { allowed in
                DispatchQueue.main.async { completion(allowed) }
            }
        default: completion(false)
        }
    }

    private func local(_ url: URL?) -> Bool {
        url?.scheme == "http" && url?.host == service.url.host && url?.port == service.url.port
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame, local(message.frameInfo.request.url),
              let data = message.body as? [String: Any], let action = data["action"] as? String else { return }
        switch action {
        case "microphone":
            microphone { [weak self] allowed in
                self?.web?.callAsyncJavaScript("window.Native?.onMicPermission(granted)", arguments: ["granted": allowed],
                                               in: nil, in: .page, completionHandler: nil)
            }
        case "copy":
            if let text = data["text"] as? String { DesktopPet.copy(text) }
        default: break
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { return decisionHandler(.cancel) }
        if local(url) || (action.targetFrame?.isMainFrame == false && url.scheme == "about") {
            decisionHandler(.allow)
        } else {
            decisionHandler(.cancel)
            if action.navigationType == .linkActivated, ["http", "https", "mailto"].contains(url.scheme ?? "") {
                NSWorkspace.shared.open(url)
            }
        }
    }

    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping ([URL]?) -> Void) {
        guard frame.isMainFrame, local(frame.request.url) else { return completionHandler(nil) }
        let picker = NSOpenPanel()
        picker.canChooseFiles = true
        picker.canChooseDirectories = parameters.allowsDirectories
        picker.allowsMultipleSelection = parameters.allowsMultipleSelection
        picker.beginSheetModal(for: window) { result in completionHandler(result == .OK ? picker.urls : nil) }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        guard frame.isMainFrame, local(frame.request.url) else { return completionHandler(false) }
        let alert = NSAlert()
        alert.messageText = "Reduction"
        alert.informativeText = message
        alert.addButton(withTitle: "确认")
        alert.addButton(withTitle: "取消")
        showPanel()
        alert.beginSheetModal(for: window) { result in completionHandler(result == .alertFirstButtonReturn) }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        serviceFailed("无法加载 Reduction 面板。\n\n\(error.localizedDescription)")
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        NSLog("Reduction 面板已加载")
    }
}
