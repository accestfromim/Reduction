import AppIntents
import FlyingFox
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
    private let log = Logger(subsystem: "com.mindtravel.reduction.ios", category: "App")
    private var core: PhoneCore?
    private var serverTask: Task<Void, Never>?
    private var localPort: Int?
    private var exporting: Task<Void, Never>?
    private var notifications: [NSObjectProtocol] = []

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
        web.underPageBackgroundColor = .black
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.navigationDelegate = self
        web.uiDelegate = self
        #if DEBUG
        web.isInspectable = true
        #endif
        web.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(web)
        NSLayoutConstraint.activate([
            web.leadingAnchor.constraint(equalTo: view.leadingAnchor), web.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            web.topAnchor.constraint(equalTo: view.topAnchor), web.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        let center = NotificationCenter.default
        notifications = [
            center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.core?.foreground() }
            },
            center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.core?.background() }
            }
        ]
        Task { await loadPage() }
    }

    deinit {
        serverTask?.cancel()
        notifications.forEach(NotificationCenter.default.removeObserver)
    }

    private func loadPage() async {
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
            let handler = BridgeServer(root: root, core: core)
            let address = try sockaddr_in.inet(ip4: "127.0.0.1", port: 0)
            let server = HTTPServer(address: address, logger: .disabled, handler: handler)
            serverTask = Task { [weak self] in
                do { try await server.run() }
                catch is CancellationError { return }
                catch { self?.showError(error.localizedDescription) }
            }
            try await server.waitUntilListening()
            guard case .ip4(_, let port)? = await server.listeningAddress else { throw ReductionError("iOS 界面无法监听本机地址") }
            localPort = Int(port)
            web.load(URLRequest(url: handler.url(port: port)))
            log.notice("Reduction iOS 本机界面已启动 \(port, privacy: .public)")
        } catch { showError(error.localizedDescription) }
    }

    private func send(_ name: String, _ args: [JSON]) {
        do {
            let event = try JSON.array([.string(name), .array(args)]).encoded()
            web.evaluateJavaScript("((event) => window.Native?.[event[0]]?.(...event[1]))(\(event))", completionHandler: nil)
        } catch { log.error("通知网页失败 \(name, privacy: .public)") }
    }

    private func showError(_ message: String) {
        log.error("无法打开 iOS 界面 \(message, privacy: .public)")
        let label = UILabel()
        label.text = "无法打开 Reduction\n\n\(message)"
        label.textColor = .white
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
                                     label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
                                     label.centerYAnchor.constraint(equalTo: view.centerYAnchor)])
    }

    private func local(_ url: URL) -> Bool { url.scheme == "http" && url.host == "127.0.0.1" && url.port == localPort }

    func webView(_ webView: WKWebView, decidePolicyFor navigation: WKNavigationAction) async -> WKNavigationActionPolicy {
        guard let url = navigation.request.url else { return .cancel }
        if local(url) || (navigation.targetFrame?.isMainFrame == false && url.scheme == "about") { return .allow }
        if navigation.navigationType == .linkActivated, ["https", "http", "mailto"].contains(url.scheme ?? "") {
            await UIApplication.shared.open(url)
        }
        return .cancel
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { core?.foreground() }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        showError(error.localizedDescription)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { web.reload() }

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
        guard exporting == nil, let port = localPort, view.window != nil else { return }
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
