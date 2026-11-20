import Foundation
import Security
import UniformTypeIdentifiers

struct SecretStore {
    private var service: String { Bundle.main.bundleIdentifier ?? "com.mindtravel.reduction.ios" }

    private func query(_ name: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: name]
    }

    func read(_ name: String) throws -> Data? {
        var key = query(name)
        key[kSecReturnData as String] = true
        key[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(key as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw ReductionError("读取钥匙串失败（\(status)）")
        }
        return data
    }

    func save(_ data: Data?, for name: String) throws {
        let key = query(name)
        guard let data else {
            let status = SecItemDelete(key as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw ReductionError("清除钥匙串项目失败（\(status)）")
            }
            return
        }
        let values = [kSecValueData as String: data]
        var status = SecItemUpdate(key as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound {
            var entry = key
            entry[kSecValueData as String] = data
            entry[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(entry as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw ReductionError("保存钥匙串项目失败（\(status)）") }
    }
}

struct PhoneCard: Codable, Equatable {
    var name: String
    var text: String
    var purpose: String
    var prompt: Bool
    var at: Int

    var directoryEntry: JSON {
        ["name": .string(name), "purpose": .string(purpose), "locked": false, "prompt": .bool(prompt), "updatedAt": .number(Double(at))]
    }
}

struct HistoryLine: Codable, Equatable {
    var id: String
    var role: String
    var content: String
    var at: Int
    var referenceDate: String? = nil

    var modelContent: String {
        var day = referenceDate
        if day == nil, at > 0 {
            day = MemoDays.name(Date(timeIntervalSince1970: Double(at) / 1000))
        }
        return RelativeDates.context(content, referenceDate: day)
    }
}

struct PhoneMemo: Codable, Equatable {
    var id: Int
    var uid: String
    var text: String
    var due: String?
    var part: String?
    var done: Bool
    var createdAt: Int
    var updatedAt: Int
    var doneAt: Int = 0
    var remindedOn: String = ""
}

struct PhoneSnapshot: Codable, Equatable {
    var preferences: [String: JSON] = [:]
    var cards: [String: PhoneCard] = [:]
    var cardDeletions: [String: Int] = [:]
    var history: [HistoryLine] = []
    var historyClearedAt = 0
    var memos: [PhoneMemo] = []
    var memoDeletions: [String: Int] = [:]
    var nextMemo = 1
    var agentRuns: [PhoneAgentRun] = []
}

final class PhoneStore {
    static let mediaKinds: Set<String> = ["jpg", "jpeg", "png", "webp", "gif", "svg", "wav", "m4a", "mp3", "ogg", "aac",
                                           "webm", "flac", "pdf", "mp4", "mov"]
    static let managedCards: Set<String> = ["计划", "sub agent 任务"]
    static let maskedKey = "••••••••••••••••"
    static let maximumAttachment = 20 * 1024 * 1024
    static let defaults: [String: JSON] = [
        "model": "deepseek-flash", "autoSpeak": true, "webSearchEnabled": true, "calculatorEnabled": true,
        "pcHost": "", "pcUser": "", "pcPort": 22, "pcProject": "~/Reduction-iterate",
        "agentEngine": "opencode", "agentDirectories": [], "opencodeEnabled": false, "opencodeWriteEnabled": false
    ]

    private let file: URL
    let media: URL
    let secrets = SecretStore()
    private(set) var state: PhoneSnapshot

    init(directory: URL) throws {
        file = directory.appendingPathComponent("reduction.json")
        media = directory.appendingPathComponent("media", isDirectory: true)
        let fm = FileManager.default
        try fm.createDirectory(at: media, withIntermediateDirectories: true)
        if fm.fileExists(atPath: file.path) {
            state = try JSONDecoder().decode(PhoneSnapshot.self, from: Data(contentsOf: file))
        } else {
            state = PhoneSnapshot()
        }
    }

    func change(_ edit: (inout PhoneSnapshot) throws -> Void) throws {
        var next = state
        try edit(&next)
        if next != state {
            let data = try JSONEncoder().encode(next)
            try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            state = next
        }
    }

    func apiKey() throws -> String {
        guard let data = try secrets.read("deepseek") else { return "" }
        guard let key = String(data: data, encoding: .utf8) else { throw ReductionError("DeepSeek 密钥格式无效") }
        return key
    }

    func settings() throws -> JSON {
        var values = Self.defaults.merging(state.preferences) { _, stored in stored }
        values["apiKey"] = .string(try apiKey().isEmpty ? "" : Self.maskedKey)
        return .object(values)
    }

    func saveSettings(_ input: JSON) throws {
        guard case .object = input, case .string(let newKey) = input["apiKey"] else {
            throw ReductionError("设置格式无效")
        }
        var next = Self.defaults
        for key in Self.defaults.keys where input[key] != .null { next[key] = input[key] }
        guard ["opencode", "codex"].contains(next["agentEngine"]?.text ?? "") else {
            throw ReductionError("子代理执行器无效")
        }
        guard ["deepseek-flash", "deepseek-v4-pro"].contains(next["model"]?.text ?? ""),
              (1...65535).contains(next["pcPort"]?.integer ?? 0) else { throw ReductionError("模型或 SSH 端口无效") }
        let previous = try secrets.read("deepseek")
        let replacesKey = newKey != Self.maskedKey
        if replacesKey { try secrets.save(newKey.trimmed.isEmpty ? nil : Data(newKey.trimmed.utf8), for: "deepseek") }
        do { try change { $0.preferences = next } }
        catch {
            if replacesKey { try secrets.save(previous, for: "deepseek") }
            throw error
        }
    }

    static func cleanName(_ raw: String) throws -> String {
        let parts = raw.trimmed.components(separatedBy: "/").map(\.trimmed)
        let name = parts.joined(separator: "/")
        guard !parts.contains(""), name.utf16.count <= 60,
              !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw ReductionError("卡片名称无效")
        }
        return name
    }

    static func editable(_ raw: String, in cards: [String: PhoneCard]) throws -> String {
        let name = try cleanName(raw)
        guard !managedCards.contains(name) else { throw ReductionError("这张卡片由系统管理") }
        guard cards[name]?.prompt != true else { throw ReductionError("请先解锁卡片「\(name)」") }
        return name
    }

    func saveCard(_ raw: String, purpose: String, content: String) throws {
        try change { next in
            let name = try Self.editable(raw, in: next.cards)
            try validateContent(content)
            let at = max(nowMilliseconds(), (next.cardDeletions[name] ?? 0) + 1, (next.cards[name]?.at ?? 0) + 1)
            next.cards[name] = PhoneCard(name: name, text: content, purpose: Self.cleanPurpose(purpose), prompt: false, at: at)
            next.cardDeletions.removeValue(forKey: name)
        }
    }

    func renameCard(_ raw: String, to target: String) throws {
        try change { next in
            let name = try Self.editable(raw, in: next.cards)
            let to = try Self.editable(target, in: next.cards)
            guard name != to, var card = next.cards[name], next.cards[to] == nil else {
                throw ReductionError("源卡片不存在或新名称已被使用")
            }
            let at = max(nowMilliseconds(), card.at + 1, (next.cardDeletions[to] ?? 0) + 1)
            next.cardDeletions[name] = at
            next.cards.removeValue(forKey: name)
            card.name = to
            card.at = at
            next.cards[to] = card
            next.cardDeletions.removeValue(forKey: to)
        }
    }

    func deleteCard(_ raw: String) throws {
        try change { next in
            let name = try Self.editable(raw, in: next.cards)
            guard let card = next.cards.removeValue(forKey: name) else { throw ReductionError("卡片不存在") }
            next.cardDeletions[name] = max(nowMilliseconds(), card.at + 1)
        }
    }

    func lockCard(_ raw: String, locked: Bool) throws {
        let name = try Self.cleanName(raw)
        guard !Self.managedCards.contains(name) else { throw ReductionError("这张卡片由系统管理") }
        try change { next in
            guard var card = next.cards[name] else { throw ReductionError("卡片不存在") }
            guard card.prompt != locked else { return }
            card.prompt = locked
            card.at = max(nowMilliseconds(), card.at + 1)
            next.cards[name] = card
        }
    }

    static func cleanPurpose(_ value: String) -> String {
        String((value.split(whereSeparator: \.isNewline).first.map(String.init) ?? "").trimmed.prefix(120))
    }

    func validateContent(_ text: String) throws {
        guard text.utf16.count <= 60_000 else { throw ReductionError("卡片超出 60,000 字上限") }
        for id in Self.references(text) where !FileManager.default.fileExists(atPath: media.appendingPathComponent(id).path) {
            throw ReductionError("找不到附件 \(id)")
        }
    }

    static func references(_ text: String) -> [String] {
        let pattern = #/media:(m[0-9a-f]{12}\.[a-z0-9]{2,5})/#
        var seen = Set<String>()
        return text.matches(of: pattern).map { String($0.1) }.filter { seen.insert($0).inserted }
    }

    func saveMedia(_ data: Data, kind: String) throws -> String {
        let kind = kind.lowercased()
        guard Self.mediaKinds.contains(kind), !data.isEmpty, data.count <= Self.maximumAttachment else {
            throw ReductionError("附件格式不支持或超出 20 MB")
        }
        let id = "m" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12) + "." + kind
        try data.write(to: media.appendingPathComponent(id), options: [.withoutOverwriting, .completeFileProtectionUntilFirstUserAuthentication])
        return id
    }

    static func isMediaID(_ value: String) -> Bool {
        if let item = value.wholeMatch(of: #/m[0-9a-f]{12}\.([a-z0-9]{2,5})/#) { return mediaKinds.contains(String(item.1)) }
        if let page = value.wholeMatch(of: #/m[0-9a-f]{12}\.pdf\.p([0-9]{1,3})\.jpg/#), let number = Int(page.1) {
            return (1...200).contains(number)
        }
        return false
    }

    func mediaURL(_ id: String) -> URL? {
        guard Self.isMediaID(id) else { return nil }
        let url = media.appendingPathComponent(id)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func mediaNames() throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: media.path).filter(Self.isMediaID).sorted()
    }

    func savePage(_ id: String, page: Int, data: Data) throws {
        let name = "\(id).p\(page).jpg"
        guard Self.isMediaID(name), mediaURL(id) != nil, !data.isEmpty, data.count <= 4 * 1024 * 1024 else {
            throw ReductionError("PDF 页码或图片大小无效")
        }
        try data.write(to: media.appendingPathComponent(name), options: .atomic)
    }

    func pageCount(_ id: String) -> Int {
        guard id.hasSuffix(".pdf"), mediaURL(id) != nil else { return 0 }
        return (1...200).prefix { mediaURL("\(id).p\($0).jpg") != nil }.count
    }

    func remember(_ role: String, _ text: String, referenceDate: String = MemoDays.name()) throws {
        guard !text.trimmed.isEmpty else { return }
        try change { next in
            let at = max(nowMilliseconds(), next.historyClearedAt + 1, (next.history.last?.at ?? 0) + 1)
            next.history.append(HistoryLine(id: UUID().uuidString, role: role, content: text, at: at, referenceDate: referenceDate))
            next.history = Array(next.history.suffix(100))
        }
    }

    func clearHistory() throws {
        try change { next in
            next.historyClearedAt = max(nowMilliseconds(), next.history.last?.at ?? 0)
            next.history.removeAll()
        }
    }

    func historyJSON() throws -> String {
        String(decoding: try JSONEncoder().encode(state.history), as: UTF8.self)
    }
}
