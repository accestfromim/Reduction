import AppKit
import OSLog
import ScreenCaptureKit

final class DesktopPet: NSObject {
    private let window = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 76, height: 76),
                                 styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
    private let reply = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 368, height: 164),
                                styleMask: [.titled, .closable, .resizable, .nonactivatingPanel], backing: .buffered, defer: false)
    private let glyph = PetGlyph()
    private let text = NSTextView()
    private let log = Logger(subsystem: "com.mindtravel.reduction.macos", category: "PetLens")
    private var lens: PetLens?
    private var screenSleeping = false
    private var hasCapturePermission = false
    var onClick: () -> Void = {}
    var isVisible: Bool { window.isVisible }

    override init() {
        super.init()
        window.title = "Reduction 桌宠"
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = false
        window.level = .floating
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        window.contentView = glyph
        window.isMovableByWindowBackground = true
        window.hidesOnDeactivate = false
        window.setFrameAutosaveName("ReductionPet")
        if !window.setFrameUsingName("ReductionPet"), let frame = NSScreen.main?.visibleFrame {
            window.setFrameOrigin(NSPoint(x: frame.maxX - window.frame.width - 24, y: frame.minY + 24))
        }
        glyph.onClick = { [weak self] in self?.onClick() }
        resizeLens(false)
        let notifications = NotificationCenter.default
        notifications.addObserver(self, selector: #selector(updateLensWindow), name: NSWindow.didMoveNotification, object: window)
        notifications.addObserver(self, selector: #selector(updateLensWindow), name: NSWindow.didChangeScreenNotification, object: window)
        notifications.addObserver(self, selector: #selector(updateLensWindow), name: NSApplication.didChangeScreenParametersNotification, object: nil)
        let workspace = NSWorkspace.shared.notificationCenter
        workspace.addObserver(self, selector: #selector(screenDidSleep), name: NSWorkspace.screensDidSleepNotification, object: nil)
        workspace.addObserver(self, selector: #selector(screenDidWake), name: NSWorkspace.screensDidWakeNotification, object: nil)

        reply.title = "Reduction"
        reply.isReleasedWhenClosed = false
        reply.hidesOnDeactivate = false
        reply.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary, .ignoresCycle]
        reply.becomesKeyOnlyIfNeeded = true
        reply.level = .floating
        reply.contentMinSize = NSSize(width: 260, height: 110)

        let copy = NSButton(title: "复制", target: self, action: #selector(copyReply))
        copy.bezelStyle = .rounded
        let scroll = NSScrollView()
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        text.font = .systemFont(ofSize: 14)
        text.textContainerInset = NSSize(width: 5, height: 5)
        text.isRichText = false
        text.drawsBackground = false
        text.isVerticallyResizable = true
        text.autoresizingMask = [.width]
        text.textContainer?.widthTracksTextView = true
        scroll.documentView = text

        let stack = NSStackView(views: [scroll, copy])
        stack.orientation = .vertical
        stack.alignment = .trailing
        stack.edgeInsets = NSEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
        stack.spacing = 4
        stack.translatesAutoresizingMaskIntoConstraints = false
        let view = NSView()
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stack.topAnchor.constraint(equalTo: view.topAnchor),
            stack.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -16)
        ])
        reply.contentView = view
    }

    func setVisible(_ visible: Bool) {
        if visible {
            window.orderFrontRegardless()
        } else {
            window.orderOut(nil)
            reply.orderOut(nil)
        }
        glyph.animate(visible && !glyph.usesLens)
        refreshLens()
    }

    @MainActor
    func requestScreenCapturePermission() async {
        if CGPreflightScreenCaptureAccess() {
            log.notice("屏幕录制权限已获授权")
            refreshLens()
            return
        }

        log.notice("Reduction 启动时请求 macOS 屏幕录制权限")
        do {
            _ = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            refreshLens()
        } catch let error as SCStreamError where error.code == .userDeclined {
            log.notice("macOS 尚未授权屏幕录制")
        } catch {
            log.error("请求屏幕录制权限失败 \(error.localizedDescription, privacy: .public)")
        }
    }

    func refreshLens() {
        hasCapturePermission = CGPreflightScreenCaptureAccess()
        guard isVisible, !screenSleeping, hasCapturePermission else {
            lens?.update(window: nil)
            return
        }

        do {
            if lens == nil {
                let lens = try PetLens(in: glyph)
                lens.pose = Self.lensPoses[glyph.pose, default: .asleep]
                lens.onActive = { [weak self] active in self?.resizeLens(active) }
                lens.onError = { [weak self] error in self?.lensFailed(error) }
                self.lens = lens
            }
            updateLensWindow()
        } catch {
            lensFailed(error)
        }
    }

    private func lensFailed(_ error: Error) {
        hasCapturePermission = CGPreflightScreenCaptureAccess()
        if hasCapturePermission && isVisible {
            log.error("桌宠折射不可用 \(error.localizedDescription, privacy: .public)")
            showReply("桌面折射无法运行。\n\(error.localizedDescription)")
        }
    }

    @objc private func updateLensWindow() {
        guard isVisible, !screenSleeping, hasCapturePermission else { return }
        lens?.update(window: window)
    }

    @objc private func screenDidSleep() {
        screenSleeping = true
        lens?.update(window: nil)
    }

    @objc private func screenDidWake() {
        screenSleeping = false
        refreshLens()
    }

    private func resizeLens(_ active: Bool) {
        glyph.usesLens = active
        glyph.animate(isVisible && !active)
        let size: CGFloat = active ? 200 : 76
        guard window.frame.width != size else { return }
        var point = NSPoint(x: window.frame.midX - size / 2, y: window.frame.midY - size / 2)
        if let screen = window.screen ?? NSScreen.main {
            let frame = screen.visibleFrame
            point.x = max(frame.minX, min(point.x, frame.maxX - size))
            point.y = max(frame.minY, min(point.y, frame.maxY - size))
        }
        window.setFrame(NSRect(origin: point, size: NSSize(width: size, height: size)), display: true)
    }

    func pose(_ state: String) {
        glyph.pose = state
        lens?.pose = Self.lensPoses[state, default: .asleep]
        glyph.setAccessibilityLabel("Reduction，" + (Self.labels[state] ?? "可用"))
        window.contentView?.toolTip = Self.labels[state]
    }

    func showReply(_ value: String) {
        guard !value.isEmpty else { return }
        text.string = value
        text.scrollRangeToVisible(NSRange(location: 0, length: 0))
        if !reply.isVisible, let screen = window.screen ?? NSScreen.main {
            let area = screen.visibleFrame
            let width = reply.frame.width
            let height = reply.frame.height
            let x = min(max(window.frame.maxX - width, area.minX), area.maxX - width)
            let y = min(window.frame.maxY + 6, area.maxY - height)
            reply.setFrameOrigin(NSPoint(x: x, y: max(y, area.minY)))
        }
        reply.orderFrontRegardless()
    }

    @objc private func copyReply() { Self.copy(text.string) }

    static func copy(_ value: String) {
        let clipboard = NSPasteboard.general
        clipboard.clearContents()
        clipboard.setString(value, forType: .string)
    }

    private static let labels = [
        "idle": "待命", "asleep": "服务未连接", "listening": "正在听", "speaking": "正在朗读", "thinking": "正在处理"
    ]
    private static let lensPoses: [String: PetLens.Pose] = [
        "idle": .idle, "asleep": .asleep, "listening": .listening, "speaking": .speaking, "thinking": .thinking
    ]
}

private final class PetGlyph: NSView {
    var pose = "asleep" { didSet { needsDisplay = true } }
    var usesLens = false { didSet { needsDisplay = true } }
    var onClick: () -> Void = {}
    private var animation: Timer?
    private var dragStarted = false

    override var mouseDownCanMoveWindow: Bool { false }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func mouseDown(with event: NSEvent) { dragStarted = false }

    override func mouseDragged(with event: NSEvent) {
        dragStarted = true
        guard let window else { return }
        var position = window.frame.origin
        position.x += event.deltaX
        position.y -= event.deltaY
        window.setFrameOrigin(position)
    }

    override func mouseUp(with event: NSEvent) {
        if !dragStarted { onClick() }
    }

    func animate(_ visible: Bool) {
        animation?.invalidate()
        animation = nil
        if visible {
            let timer = Timer(timeInterval: 1.0 / 30, repeats: true) { [weak self] _ in self?.needsDisplay = true }
            RunLoop.main.add(timer, forMode: .common)
            animation = timer
        }
    }

    override func draw(_ dirtyRect: NSRect) {
        if usesLens {
            NSColor.clear.setFill()
            dirtyRect.fill(using: .copy)
            return
        }
        let center = NSPoint(x: bounds.midX, y: bounds.midY)
        let time = ProcessInfo.processInfo.systemUptime
        let distance: Double
        let radius: Double
        let angle: Double
        switch pose {
        case "listening": (distance, radius, angle) = (15 + sin(time * 3), 16, 0)
        case "thinking": (distance, radius, angle) = (12, 15, time * 2.6)
        case "speaking": (distance, radius, angle) = (11, 15 + sin(time * 9) * 2, 0)
        case "asleep": (distance, radius, angle) = (7, 14, 0)
        default: (distance, radius, angle) = (10 + sin(time * 2), 15, 0)
        }
        let ring = bounds.insetBy(dx: 5, dy: 5)
        NSColor(white: 0.045, alpha: 0.93).setFill()
        NSBezierPath(ovalIn: ring).fill()
        NSColor(white: 1, alpha: 0.19).setStroke()
        NSBezierPath(ovalIn: ring).stroke()

        NSColor(white: 1, alpha: pose == "asleep" ? 0.38 : 0.93).setFill()
        for offset in [-distance, distance] {
            let x = center.x + cos(angle) * offset
            let y = center.y + sin(angle) * offset
            NSBezierPath(ovalIn: NSRect(x: x - radius, y: y - radius, width: 2 * radius, height: 2 * radius)).fill()
        }
        NSColor(white: 0.045, alpha: 1).setFill()
        NSBezierPath(ovalIn: NSRect(x: center.x - radius, y: center.y - radius, width: 2 * radius, height: 2 * radius)).fill()
    }
}
