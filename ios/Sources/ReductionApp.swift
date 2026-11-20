import AppIntents
import FlyingSocks
import OSLog
import SwiftUI
import UserNotifications
import WebKit

@main
struct ReductionPhoneApp: App {
    @UIApplicationDelegateAdaptor(PhoneNotifications.self) private var notifications

    var body: some Scene {
        WindowGroup {
            PhonePage()
                .ignoresSafeArea(.container, edges: .all)
                .preferredColorScheme(.dark)
        }
    }
}

final class PhoneNotifications: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}

struct OpenReduction: AppIntent {
    static let title: LocalizedStringResource = "打开 Reduction"
    static let description = IntentDescription("打开 Reduction 开始对话或查看待办。")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult { .result() }
}

struct ReductionShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: OpenReduction(), phrases: ["打开 \(.applicationName)", "和 \(.applicationName) 对话"],
                    shortTitle: "打开 Reduction", systemImageName: "mic.fill")
    }
}

struct PhonePage: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> PhonePageController { PhonePageController() }
    func updateUIViewController(_ controller: PhonePageController, context: Context) {}
}

final class PhonePageController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    private let web: WKWebView
    private let errorLabel = UILabel()
    private let log = Logger(subsystem: "com.mindtravel.reduction.ios", category: "App")
    private var core: PhoneCore?
    private var bridge: BridgeServer?
    private var serverTask: Task<Void, Never>?
    private var pageURL: URL?
    private var pageNavigation: WKNavigation?
    private var needsPageLoad = true
    private var isInBackground = false
    private var bridgeReady = false
    private var exporting: Task<Void, Never>?

    init() {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.websiteDataStore = .nonPersistent()
        web = WKWebView(frame: .zero, configuration: config)
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("Use init()") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        web.isOpaque = false
        web.isUserInteractionEnabled = false
        web.underPageBackgroundColor = .black
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.navigationDelegate = self
        web.uiDelegate = self
        #if DEBUG
        web.isInspectable = true
        #endif
        web.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(web)

        errorLabel.textColor = .white
        errorLabel.textAlignment = .center
        errorLabel.numberOfLines = 0
        errorLabel.isHidden = true
        errorLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(errorLabel)
        NSLayoutConstraint.activate([
            web.leadingAnchor.constraint(equalTo: view.leadingAnchor), web.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            web.topAnchor.constraint(equalTo: view.topAnchor), web.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            errorLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            errorLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            errorLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor)
        ])

        isInBackground = UIApplication.shared.applicationState == .background
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(enterForeground), name: UIApplication.didBecomeActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(enterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        loadPage()
    }

    deinit {
        serverTask?.cancel()
        NotificationCenter.default.removeObserver(self)
    }

    private func loadPage() {
        do {
            let fm = FileManager.default
            let data = try fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            let cache = try fm.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            let root = Bundle.main.bundleURL.appendingPathComponent("WebAssets", isDirectory: true)
            let core = try PhoneCore(directory: data.appendingPathComponent("Reduction"), cache: cache.appendingPathComponent("Reduction"),
                                     emit: { [weak self] name, args in self?.send(name, args) },
                                     confirm: { [weak self] title, content in await self?.confirm(title, content) ?? false },
                                     exportArticle: { [weak self] html, height, title in self?.exportArticle(html, height: height, title: title) })
            self.core = core
            bridge = BridgeServer(root: root, core: core)
            startBridge()
        } catch { showError(error.localizedDescription) }
    }

    @objc func enterBackground() {
        isInBackground = true
        core?.background()
        pausePage()
        serverTask?.cancel()
    }

    @objc func enterForeground() {
        isInBackground = false
        if bridgeReady { resumePage() }
        else { startBridge() }
    }

    private func startBridge(port: UInt16? = nil, retryDisconnect: Bool = true) {
        guard !isInBackground, serverTask == nil, let bridge else { return }
        let requestedPort = port ?? UInt16(pageURL?.port ?? 0)
        serverTask = Task { [weak self] in
            do {
                try await bridge.run(port: requestedPort) { [weak self] url in
                    guard !Task.isCancelled else { return }
                    self?.bridgeStarted(url)
                }
            } catch {
                self?.bridgeStopped(error, port: requestedPort, retryDisconnect: retryDisconnect, cancelled: Task.isCancelled)
            }
        }
    }

    private func bridgeStarted(_ url: URL) {
        bridgeReady = true
        if pageURL != url { needsPageLoad = true }
        pageURL = url
        resumePage()
        log.notice("Reduction iOS 本机界面已启动 \(url.port ?? 0, privacy: .public)")
    }

    private func bridgeStopped(_ error: Error, port: UInt16, retryDisconnect: Bool, cancelled: Bool) {
        serverTask = nil
        pausePage()
        guard !isInBackground else { return }
        if cancelled {
            startBridge()
            return
        }
        switch error as? SocketError {
        case .disconnected? where retryDisconnect:
            startBridge(retryDisconnect: false)
        case .failed(_, EADDRINUSE, _)? where port != 0:
            startBridge(port: 0, retryDisconnect: retryDisconnect)
        default:
            showError(error.localizedDescription)
        }
    }

    private func pausePage() {
        bridgeReady = false
        web.isUserInteractionEnabled = false
        if pageNavigation != nil {
            pageNavigation = nil
            needsPageLoad = true
            web.stopLoading()
        }
    }

    private func resumePage() {
        guard !isInBackground, bridgeReady, let pageURL else { return }
        if needsPageLoad {
            needsPageLoad = false
            pageNavigation = web.load(URLRequest(url: pageURL))
        } else if pageNavigation == nil {
            errorLabel.isHidden = true
            web.isUserInteractionEnabled = true
            core?.foreground()
        }
    }

    private func send(_ name: String, _ args: [JSON]) {
        do {
            let event = try JSON.array([.string(name), .array(args)]).encoded()
            web.evaluateJavaScript("((event) => window.Native?.[event[0]]?.(...event[1]))(\(event))", completionHandler: nil)
        } catch { log.error("通知网页失败 \(name, privacy: .public)") }
    }

    private func showError(_ message: String) {
        log.error("无法打开 iOS 界面 \(message, privacy: .public)")
        errorLabel.text = "无法打开 Reduction\n\n\(message)"
        errorLabel.isHidden = false
    }

    private func local(_ url: URL) -> Bool { url.scheme == "http" && url.host == "127.0.0.1" && url.port == pageURL?.port }

    func webView(_ webView: WKWebView, decidePolicyFor navigation: WKNavigationAction) async -> WKNavigationActionPolicy {
        guard let url = navigation.request.url else { return .cancel }
        if local(url) || (navigation.targetFrame?.isMainFrame == false && url.scheme == "about") { return .allow }
        if navigation.navigationType == .linkActivated, ["https", "http", "mailto"].contains(url.scheme ?? "") {
            await UIApplication.shared.open(url)
        }
        return .cancel
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let pageNavigation, navigation === pageNavigation else { return }
        self.pageNavigation = nil
        resumePage()
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        navigationFailed(navigation, error: error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        navigationFailed(navigation, error: error)
    }

    private func navigationFailed(_ navigation: WKNavigation?, error: Error) {
        guard let pageNavigation, navigation === pageNavigation else { return }
        self.pageNavigation = nil
        needsPageLoad = true
        if !isInBackground, bridgeReady, (error as? URLError)?.code != .cancelled {
            showError(error.localizedDescription)
        }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        pageNavigation = nil
        needsPageLoad = true
        web.isUserInteractionEnabled = false
        resumePage()
    }

    private func confirm(_ title: String, _ message: String) async -> Bool {
        guard view.window != nil, UIApplication.shared.applicationState == .active else { return false }
        return await withCheckedContinuation { continuation in
            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "取消", style: .cancel) { _ in continuation.resume(returning: false) })
            alert.addAction(UIAlertAction(title: "允许", style: .default) { _ in continuation.resume(returning: true) })
            present(alert, animated: true)
        }
    }

    private func exportArticle(_ html: String, height: Int, title: String) {
        guard exporting == nil, let port = pageURL?.port, view.window != nil else { return }
        let base = URL(string: "http://127.0.0.1:\(port)/")!
        let dataStore = web.configuration.websiteDataStore
        exporting = Task { [weak self] in
            guard let self else { return }
            defer { exporting = nil }
            do {
                let fm = FileManager.default
                let cache = try fm.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                let directory = cache.appendingPathComponent("Reduction/Export/\(UUID().uuidString)", isDirectory: true)
                try fm.createDirectory(at: directory, withIntermediateDirectories: true)
                let allowed = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/:\\"))
                let safe = String(title.components(separatedBy: allowed).joined(separator: "-").trimmed.prefix(48))
                let file = directory.appendingPathComponent((safe.isEmpty ? "Reduction 文章" : safe) + ".png")
                do {
                    let renderer = ArticleExport(height: height, dataStore: dataStore)
                    try await renderer.write(html: html, base: base, to: file)
                    let share = UIActivityViewController(activityItems: [file], applicationActivities: nil)
                    share.popoverPresentationController?.sourceView = view
                    share.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
                    share.completionWithItemsHandler = { _, _, _, _ in try? fm.removeItem(at: directory) }
                    present(share, animated: true)
                } catch {
                    try? fm.removeItem(at: directory)
                    throw error
                }
            } catch { send("onError", ["", .string(error.localizedDescription)]) }
        }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo) async -> Bool {
        guard frame.isMainFrame, let url = frame.request.url, local(url) else { return false }
        return await confirm("Reduction", message)
    }
}
