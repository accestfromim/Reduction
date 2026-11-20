import UIKit
import WebKit
import XCTest
import ImageIO
import Citadel
import Crypto
@testable import Reduction

final class PhonePageTests: XCTestCase {
    @MainActor
    func testOpenSSHComputerWhenConfigured() async throws {
        let fm = FileManager.default
        let caches = try fm.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let shared = caches.appendingPathComponent("ReductionSSHTest")
        let file = shared.appendingPathComponent("settings.json")
        guard fm.fileExists(atPath: file.path) else { throw XCTSkip("未配置本机 OpenSSH 测试服务") }
        var preferences = PhoneStore.defaults
        for (key, value) in try JSON(data: Data(contentsOf: file)).fields { preferences[key] = value }
        preferences["apiKey"] = .string(PhoneStore.maskedKey)
        let root = shared.appendingPathComponent(UUID().uuidString)
        defer { try? fm.removeItem(at: root) }
        let store = try PhoneStore(directory: root)
        try store.saveSettings(.object(preferences))
        let pin = "ssh-host/\(preferences["pcHost"]!.text):\(preferences["pcPort"]!.integer)"
        let previousPin = try store.secrets.read(pin)
        let previousIdentity = try store.secrets.read("ssh-ed25519")
        defer {
            try? store.secrets.save(previousPin, for: pin)
            try? store.secrets.save(previousIdentity, for: "ssh-ed25519")
        }
        let keyFile = shared.appendingPathComponent("test_operator")
        let identity = try Curve25519.Signing.PrivateKey(sshEd25519: Data(contentsOf: keyFile))
        try store.secrets.save(identity.rawRepresentation, for: "ssh-ed25519")
        let pc = PhoneComputer(store: store, emit: { _, _ in },
                               confirm: { _, _ in XCTFail("读取电脑信息不需要写入确认"); return false })
        XCTAssertTrue(try pc.publicKey().hasPrefix("ssh-ed25519 "))
        let remote = try await pc.information()
        XCTAssertEqual(remote["user"].text, preferences["pcUser"]?.text)
        XCTAssertTrue(remote["hostKey"].text.hasPrefix("ssh-ed25519 "))
    }

    @MainActor
    func testSimulatorKeychainIsUsable() throws {
        let keychain = SecretStore()
        let name = "reduction-verification-\(UUID().uuidString)"
        let entry = Data("Reduction iOS 测试会话".utf8)
        defer { try? keychain.save(nil, for: name) }
        XCTAssertNil(try keychain.read(name))
        try keychain.save(entry, for: name)
        XCTAssertEqual(try keychain.read(name), entry)
        try keychain.save(nil, for: name)
        XCTAssertNil(try keychain.read(name))
    }

    @MainActor
    func testSyncProtocolAppliesEditsAndDeletions() throws {
        let fm = FileManager.default
        let caches = try fm.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let path = caches.appendingPathComponent("Sync-\(UUID().uuidString)")
        defer { try? fm.removeItem(at: path) }
        let source = try PhoneStore(directory: path.appendingPathComponent("first"))
        let target = try PhoneStore(directory: path.appendingPathComponent("second"))
        let title = "Reduction/构建"
        try source.saveCard(title, purpose: "记录真实构建步骤", content: "用 Xcode 打开 Reduction.xcodeproj")
        try source.remember("user", "查看 iOS 的构建步骤")
        _ = try source.applyMemo(["action": "add", "text": "查看 Reduction 的构建步骤", "date": .string(MemoDays.name())])
        XCTAssertTrue(try target.merge(source.syncPayload()))
        XCTAssertEqual(target.state.cards[title]?.text, "用 Xcode 打开 Reduction.xcodeproj")
        XCTAssertEqual(target.state.history.last?.content, "查看 iOS 的构建步骤")
        XCTAssertEqual(target.state.memos.first?.text, "查看 Reduction 的构建步骤")
        try source.deleteCard(title)
        XCTAssertTrue(try target.merge(source.syncPayload()))
        XCTAssertNil(target.state.cards[title])
    }

    @MainActor
    func testMessageDatesSurviveReloadAndSync() throws {
        let fm = FileManager.default
        let caches = try fm.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let root = caches.appendingPathComponent("MessageDates-\(UUID().uuidString)")
        defer { try? fm.removeItem(at: root) }
        let sourcePath = root.appendingPathComponent("source")
        let source = try PhoneStore(directory: sourcePath)
        let text = "明天档案归档，下周二核对。这周整理资料。用「明天」当标签。"
        try source.remember("user", text, referenceDate: "2026-11-18")

        let reloaded = try PhoneStore(directory: sourcePath)
        let target = try PhoneStore(directory: root.appendingPathComponent("target"))
        XCTAssertTrue(try target.merge(reloaded.syncPayload()))
        let line = try XCTUnwrap(target.state.history.last)
        XCTAssertEqual(line.content, text)
        XCTAssertEqual(line.modelContent,
                       "［原消息日期 2026-11-18］\n2026-11-19档案归档，2026-11-24核对。2026-11-16 至 2026-11-22整理资料。用「明天」当标签。")
        XCTAssertEqual(try MemoDays.parse("下周二", referenceDate: "2026-11-19"), "2026-11-24")
        XCTAssertEqual(try MemoDays.parse("这周二", referenceDate: "2026-11-19"), "2026-11-17")
        XCTAssertEqual(try MemoDays.parse("两天后", referenceDate: "2026-11-18"), "2026-11-20")
        XCTAssertEqual(try MemoDays.parse("下周二", referenceDate: "2026-12-31"), "2027-01-05")
        XCTAssertEqual(RelativeDates.display("2026-11-24", relativeTo: "2026-11-19"), "下周二")
    }

    @MainActor
    func testMemoDatesUseTheTurnDateAndRenderForEachView() throws {
        let fm = FileManager.default
        let caches = try fm.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let root = caches.appendingPathComponent("MemoDates-\(UUID().uuidString)")
        defer { try? fm.removeItem(at: root) }
        let store = try PhoneStore(directory: root)
        let result = try store.applyMemo(["action": "add", "text": "档案归档", "date": "明天", "part": "早上"],
                                         referenceDate: "2026-11-18")
        let memo = try XCTUnwrap(store.state.memos.first)
        XCTAssertEqual(memo.due, "2026-11-19")
        XCTAssertTrue(result.contains("2026-11-19 早上"))
        XCTAssertTrue(try PhoneTools.read(["name": "计划"], store: store).text.contains("档案归档 · 2026-11-19 早上"))
        XCTAssertEqual(MemoDays.label(memo, today: "2026-11-18"), "明天 早上")
        XCTAssertEqual(MemoDays.label(memo, today: "2026-11-19"), "今天 早上")
        XCTAssertFalse(store.memoText().contains("# 没日期"))
        _ = try store.applyMemo(["action": "add", "text": "整理归档清单"])
        XCTAssertTrue(store.memoText().contains("# 没日期\n- #2 整理归档清单"))
    }

    @MainActor
    func testSharedPlanShowsUndatedGroupWhenItHasTasks() async throws {
        let controller = PhonePageController()
        controller.loadViewIfNeeded()
        let view = try XCTUnwrap(controller.view.subviews.compactMap { $0 as? WKWebView }.first)
        try await ready(view)

        let result = try await view.callAsyncJavaScript("""
            const cards = await import('/js/features/cards/cards.js');
            document.getElementById('tabCards').click();
            cards.openCard('计划');
            const headings = Array.from(document.querySelectorAll('#cardPlan .plan-head'), node => node.textContent);
            return { headings, hasUndated: JSON.parse(window.Android.memoView()).undated.length > 0 };
            """, arguments: [:], in: nil, contentWorld: .page)
        let values = try XCTUnwrap(result as? [String: Any])
        let expected = try XCTUnwrap(values["hasUndated"] as? Bool) ? ["今天", "未来", "没日期"] : ["今天", "未来"]
        XCTAssertEqual(values["headings"] as? [String], expected)
    }

    @MainActor
    func testSharedPageKeepsCardEdits() async throws {
        let controller = PhonePageController()
        controller.loadViewIfNeeded()
        let view = try XCTUnwrap(controller.view.subviews.compactMap { $0 as? WKWebView }.first)
        try await ready(view)

        let title = "iOS 构建/\(UUID().uuidString.prefix(8))"
        let text = "# Reduction\n\n对话、卡片和待办使用同一套网页界面。"
        let saved = try await view.callAsyncJavaScript("return window.Android.saveCard(title, '模拟器构建验证', body);",
                                                      arguments: ["title": title, "body": text], in: nil, contentWorld: .page)
        XCTAssertEqual(saved as? Bool, true)

        let reader = PhonePageController()
        reader.loadViewIfNeeded()
        let reopened = try XCTUnwrap(reader.view.subviews.compactMap { $0 as? WKWebView }.first)
        try await ready(reopened)
        let content = try await reopened.callAsyncJavaScript("return window.Android.readCard(title);", arguments: ["title": title],
                                                            in: nil, contentWorld: .page)
        XCTAssertEqual(content as? String, text)

        let entries = try await reopened.callAsyncJavaScript("return JSON.parse(window.Android.listCards()).map((card) => card.name);",
                                                            arguments: [:], in: nil, contentWorld: .page)
        XCTAssertTrue((entries as? [String])?.contains(title) == true)
        let deleted = try await reopened.callAsyncJavaScript("return window.Android.deleteCard(title);", arguments: ["title": title],
                                                            in: nil, contentWorld: .page)
        XCTAssertEqual(deleted as? Bool, true)
    }

    @MainActor
    func testSharedSettingsSaveAgentEngine() async throws {
        let controller = PhonePageController()
        controller.loadViewIfNeeded()
        let view = try XCTUnwrap(controller.view.subviews.compactMap { $0 as? WKWebView }.first)
        try await ready(view)
        let raw = try await view.evaluateJavaScript("window.Android.loadSettings()")
        let previous = try XCTUnwrap(raw as? String)
        addTeardownBlock { @MainActor in
            defer { withExtendedLifetime(controller) {} }
            let saved = try await view.callAsyncJavaScript("return window.Android.saveSettings(previous);",
                                                           arguments: ["previous": previous], in: nil, contentWorld: .page)
            XCTAssertEqual(saved as? Bool, true)
        }

        for engine in ["codex", "opencode"] {
            let saved = try await view.callAsyncJavaScript("""
                document.getElementById('settingsBtn').click();
                const picker = document.getElementById('agentEngineSelect');
                picker.value = engine;
                picker.dispatchEvent(new Event('change'));
                document.getElementById('saveSettings').click();
                return JSON.parse(window.Android.loadSettings()).agentEngine;
                """, arguments: ["engine": engine], in: nil, contentWorld: .page)
            XCTAssertEqual(saved as? String, engine)
        }
    }

    @MainActor
    func testSharedPageReconnectsAfterBackground() async throws {
        let controller = PhonePageController()
        controller.loadViewIfNeeded()
        let view = try XCTUnwrap(controller.view.subviews.compactMap { $0 as? WKWebView }.first)
        try await ready(view)
        let page = try XCTUnwrap(view.url)
        let draft = "记录 Reduction 从后台返回时的连接恢复过程"
        _ = try await view.callAsyncJavaScript("""
            document.getElementById('tabCards').click();
            document.getElementById('cardNew').click();
            document.getElementById('cardText').value = draft;
            """, arguments: ["draft": draft], in: nil, contentWorld: .page)

        let request = URLRequest(url: page, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 2)
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let (_, response) = try await session.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)

        controller.enterBackground()
        XCTAssertFalse(view.isUserInteractionEnabled)
        var disconnected = false
        for _ in 0..<30 {
            do { _ = try await session.data(for: request) }
            catch let error as URLError where error.code == .cannotConnectToHost {
                disconnected = true
                break
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(disconnected)

        controller.enterForeground()
        try await ready(view)
        controller.enterBackground()
        controller.enterForeground()
        try await ready(view)
        XCTAssertEqual(view.url, page)
        let restored = try await view.evaluateJavaScript("document.getElementById('cardText').value")
        XCTAssertEqual(restored as? String, draft)
        XCTAssertTrue(controller.view.subviews.compactMap { $0 as? UILabel }.allSatisfy(\.isHidden))
    }

    @MainActor
    func testSharedArticleRendersLongPNG() async throws {
        let controller = PhonePageController()
        controller.loadViewIfNeeded()
        let view = try XCTUnwrap(controller.view.subviews.compactMap { $0 as? WKWebView }.first)
        try await ready(view)
        let loaded = try XCTUnwrap(view.url)
        let html = try await view.callAsyncJavaScript("""
            const { page } = await import('/js/features/article/article.js');
            const markdown = '# Reduction\\n\\n对话、卡片和待办使用同一套网页界面。';
            return (await page(markdown)).html;
            """, arguments: [:], in: nil, contentWorld: .page)
        let rendered = try XCTUnwrap(html as? String)
        let data = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let file = data.appendingPathComponent("Article-\(UUID().uuidString).png")
        defer { try? FileManager.default.removeItem(at: file) }
        let renderer = ArticleExport(height: 420, dataStore: view.configuration.websiteDataStore)
        try await renderer.write(html: rendered, base: loaded, to: file)
        let image = try XCTUnwrap(CGImageSourceCreateWithURL(file as CFURL, nil))
        let page = try XCTUnwrap(CGImageSourceCreateImageAtIndex(image, 0, nil))
        XCTAssertEqual(page.width, 1080)
        XCTAssertEqual(page.height, 840)
        XCTAssertGreaterThan(try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0, 8_000)
    }

    @MainActor
    private func ready(_ view: WKWebView) async throws {
        for _ in 0..<100 {
            if view.isUserInteractionEnabled,
               let result = try? await view.evaluateJavaScript("Boolean(window.Native && window.Android && document.getElementById('tabCards') && typeof window.Android.loadSettings() === 'string')"),
               result as? Bool == true { return }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw ReductionError("共享页面未初始化")
    }
}
