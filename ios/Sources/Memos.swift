import Foundation
import OSLog
import UserNotifications

enum MemoDays {
    static let parts = ["EARLY": "早上", "MORNING": "上午", "AFTERNOON": "下午", "EVENING": "晚上"]

    private static func formatter() -> DateFormatter {
        let date = DateFormatter()
        date.locale = Locale(identifier: "en_US_POSIX")
        date.calendar = Calendar(identifier: .gregorian)
        date.timeZone = .current
        date.dateFormat = "yyyy-MM-dd"
        return date
    }

    static func name(_ date: Date = Date()) -> String { formatter().string(from: date) }
    static func date(_ name: String) -> Date? { formatter().date(from: name) }

    static func parse(_ raw: String) throws -> String? {
        let value = raw.trimmed
        if value.isEmpty || value.lowercased() == "empty" { return nil }
        let offsets = ["今天": 0, "明天": 1, "后天": 2, "大后天": 3]
        let day = Date()
        if let count = offsets[value], let date = Calendar.current.date(byAdding: .day, value: count, to: day) {
            return name(date)
        }
        if let match = value.wholeMatch(of: #/([0-9]{1,4})\s*天后/#), let count = Int(match.1),
           let date = Calendar.current.date(byAdding: .day, value: count, to: day) { return name(date) }
        let format = formatter()
        guard let date = format.date(from: value), format.string(from: date) == value else {
            throw ReductionError("请用 yyyy-MM-dd 指定待办日期")
        }
        return value
    }

    static func part(_ raw: String) throws -> String? {
        let value = raw.trimmed
        if value.isEmpty || value == "none" { return nil }
        if parts[value.uppercased()] != nil { return value.uppercased() }
        guard let key = parts.first(where: { $0.value == value })?.key else { throw ReductionError("待办时段无效") }
        return key
    }

    static func label(_ memo: PhoneMemo, today: String) -> String {
        let part = memo.part.flatMap { parts[$0] } ?? ""
        guard let due = memo.due else { return part.isEmpty ? "没有标记时间" : part }
        return (due == today ? "今天" : due) + (part.isEmpty ? "" : " \(part)")
    }

    static func groups(_ memos: [PhoneMemo], today: String = name()) -> (today: [PhoneMemo], future: [PhoneMemo], undated: [PhoneMemo]) {
        let order = ["EARLY": 0, "MORNING": 1, "AFTERNOON": 2, "EVENING": 3]
        let sorted = memos.sorted {
            let asc = ($0.due ?? "9999", order[$0.part ?? "", default: 4], $0.id)
            let desc = ($1.due ?? "9999", order[$1.part ?? "", default: 4], $1.id)
            return asc < desc
        }
        return (sorted.filter { $0.due == today || (!$0.done && ($0.due.map { $0 < today } ?? false)) },
                sorted.filter { $0.due.map { $0 > today } ?? false }, sorted.filter { $0.due == nil })
    }
}

extension PhoneStore {
    func memoView(authorized: Bool) throws -> String {
        let now = MemoDays.name()
        let groups = MemoDays.groups(state.memos, today: now)
        func items(_ memos: [PhoneMemo]) -> JSON {
            .array(memos.map { memo in
                ["id": .number(Double(memo.id)), "text": .string(memo.text), "done": .bool(memo.done),
                 "due": .string(MemoDays.label(memo, today: now)), "part": .string(memo.part.flatMap { MemoDays.parts[$0] } ?? ""),
                 "empty": .bool(memo.due == nil), "overdue": .bool(!memo.done && (memo.due.map { $0 < now } ?? false))]
            })
        }
        return try JSON.object(["today": items(groups.today), "future": items(groups.future), "undated": items(groups.undated),
                               "exact": true, "notify": .bool(authorized)]).encoded()
    }

    func memoText() -> String {
        let today = MemoDays.name()
        let groups = MemoDays.groups(state.memos, today: today)
        return zip(["今天", "未来", "没日期"], [groups.today, groups.future, groups.undated]).map { title, memos in
            let lines = memos.map { "- #\($0.id) \($0.text) · \(MemoDays.label($0, today: today))\($0.done ? " · 已完成" : "")" }
            return "# \(title)\n" + (lines.isEmpty ? "（没有）" : lines.joined(separator: "\n"))
        }.joined(separator: "\n\n")
    }

    @discardableResult
    func memoDone(_ id: Int, done: Bool) throws -> PhoneMemo {
        guard let index = state.memos.firstIndex(where: { $0.id == id }) else { throw ReductionError("待办不存在") }
        try change { next in
            next.memos[index].done = done
            next.memos[index].updatedAt = max(nowMilliseconds(), next.memos[index].updatedAt + 1)
            next.memos[index].doneAt = done ? next.memos[index].updatedAt : 0
        }
        return state.memos[index]
    }

    func applyMemo(_ args: JSON) throws -> String {
        let action = args["action"].text
        if action == "list" { return memoText() }
        if action == "add" {
            let text = args["text"].text.trimmed
            guard !text.isEmpty else { throw ReductionError("请填写待办内容") }
            let due = try MemoDays.parse(args["date"].text)
            let part = try MemoDays.part(args["part"].text)
            let at = nowMilliseconds()
            let id = state.nextMemo
            try change {
                $0.memos.append(PhoneMemo(id: id, uid: UUID().uuidString, text: text, due: due, part: part,
                                        done: false, createdAt: at, updatedAt: at))
                $0.nextMemo += 1
            }
            return "已登记 #\(id) \(text)"
        }
        let id = args["id"].integer == 0 ? Int(args["id"].text.trimmed) ?? 0 : args["id"].integer
        guard let index = state.memos.firstIndex(where: { $0.id == id }) else { throw ReductionError("待办 #\(id) 不存在") }
        switch action {
        case "done":
            let done = args["done"] != .bool(false) && args["done"].text.lowercased() != "false"
            let memo = try memoDone(id, done: done)
            return "\(done ? "已完成" : "已重新打开") #\(id) \(memo.text)"
        case "delete":
            try change { next in
                let memo = next.memos.remove(at: index)
                next.memoDeletions[memo.uid] = max(nowMilliseconds(), memo.updatedAt + 1)
            }
            return "已删除待办 #\(id)"
        case "update":
            let newText = args["text"].text.trimmed
            let newDate = args["date"].text.trimmed
            let newPart = args["part"].text.trimmed
            guard !newText.isEmpty || !newDate.isEmpty || !newPart.isEmpty else { throw ReductionError("缺少待办改动内容") }
            try change { next in
                var memo = next.memos[index]
                if !newText.isEmpty { memo.text = newText }
                if !newDate.isEmpty { memo.due = try MemoDays.parse(newDate); memo.remindedOn = "" }
                if !newPart.isEmpty { memo.part = try MemoDays.part(newPart) }
                memo.updatedAt = max(memo.updatedAt + 1, nowMilliseconds())
                next.memos[index] = memo
            }
            return "已修改待办 #\(id) \(state.memos[index].text)"
        default:
            throw ReductionError("待办操作无效")
        }
    }
}

@MainActor
final class MemoNotifications {
    private let center = UNUserNotificationCenter.current()
    private let log = Logger(subsystem: "com.mindtravel.reduction.ios", category: "Reminders")
    private var wanted: [PhoneMemo]?
    private var task: Task<Void, Never>?

    func authorized() async -> Bool {
        let status = await center.notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional || status == .ephemeral
    }

    func request() async throws -> Bool { try await center.requestAuthorization(options: [.alert, .sound, .badge]) }

    func update(_ items: [PhoneMemo]) {
        wanted = items
        guard task == nil else { return }
        task = Task {
            while let items = wanted {
                wanted = nil
                do { try await schedule(items) }
                catch { log.error("安排待办提醒失败 \(error.localizedDescription, privacy: .public)") }
            }
            task = nil
        }
    }

    private func schedule(_ items: [PhoneMemo]) async throws {
        let existing = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix("reduction-memo-") }
        center.removePendingNotificationRequests(withIdentifiers: existing)
        guard await authorized() else { return }
        let calendar = Calendar.current
        let pending = items.filter { !$0.done }
        let now = Date()
        let near = (0..<45).compactMap { calendar.date(byAdding: .day, value: $0, to: now) }
        let horizon = near.last.map(MemoDays.name) ?? MemoDays.name(now)
        let distantNames = Set(pending.compactMap(\.due).filter { $0 > horizon }).sorted().prefix(16)
        let dates = near + distantNames.compactMap(MemoDays.date)
        for date in dates {
            guard let alarm = calendar.date(bySettingHour: 9, minute: 0, second: 0, of: date), alarm > now else { continue }
            let day = MemoDays.name(date)
            let today = pending.filter { $0.due.map { $0 <= day } == true }
            let undated = pending.filter { $0.due == nil }.count
            let isFuture = !calendar.isDate(date, inSameDayAs: now)
            guard !today.isEmpty || (isFuture && undated > 0) else { continue }
            let content = UNMutableNotificationContent()
            content.title = today.isEmpty ? "为待办补日期" : "Reduction 待办"
            var lines = today.prefix(4).map(\.text)
            if today.count > 4 { lines.append("另外还有 \(today.count - 4) 项") }
            if undated > 0 && isFuture { lines.append("有 \(undated) 项待办还未设置日期") }
            content.body = lines.joined(separator: "\n")
            content.sound = .default
            var components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: alarm)
            components.calendar = calendar
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            try await center.add(UNNotificationRequest(identifier: "reduction-memo-\(day)", content: content, trigger: trigger))
        }
    }
}
