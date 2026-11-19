import Foundation

enum RelativeDates {
    private static let offsets = [
        "大前天": -3, "前天": -2, "昨天": -1, "昨日": -1, "昨晚": -1,
        "今天": 0, "今日": 0, "今早": 0, "今晚": 0,
        "明天": 1, "明日": 1, "明早": 1, "明晚": 1, "后天": 2, "大后天": 3
    ]
    private static let periodOffsets = ["上上": -2, "上": -1, "本": 0, "这": 0, "下": 1, "下下": 2]
    private static let period = "(上上|下下|上|下|本|这)"
    private static let weekdayPattern = "(?:周|星期|礼拜)([一二三四五六日天])"
    private static let count = "[0-9]{1,4}|[一二两三四五六七八九]?十[一二三四五六七八九]?|[一二两三四五六七八九]"
    private static let daysAway = try! NSRegularExpression(
        pattern: #"(?<![0-9.零〇一二两三四五六七八九十百千万第几])(\#(count))[ \t]*天(?:以|之)?([前后])"#
    )
    private static let weekday = try! NSRegularExpression(pattern: "(?:\(period)(?:个)?)?\(weekdayPattern)")
    private static let monthDay = try! NSRegularExpression(pattern: "\(period)(?:个)?月[ \t]*(\(count))[日号]")
    private static let weekRange = try! NSRegularExpression(pattern: "\(period)(?:个)?(?:周|星期|礼拜)(末)?(?![一二三四五六日天几末])")
    private static let verbatim = #"```[\s\S]*?```|`[^`\n]*`|“[^”]*”|‘[^’]*’|「[^」]*」|『[^』]*』|"[^"\n]*""#
    private static let days = ["一", "二", "三", "四", "五", "六", "日"]
    private static let expressions = try! NSRegularExpression(
        pattern: ([verbatim] + offsets.keys.sorted { $0.count > $1.count } +
                  ["\(period)(?:个)?\(weekdayPattern)", daysAway.pattern, monthDay.pattern, weekRange.pattern]).joined(separator: "|")
    )

    private static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        return calendar
    }()

    static func dayOf(_ expression: String, referenceDate: String) -> String? {
        guard let reference = MemoDays.date(referenceDate, timeZone: .gmt),
              let day = dayOf(expression, reference: reference) else { return nil }
        return MemoDays.name(day, timeZone: .gmt)
    }

    static func context(_ text: String, referenceDate: String?) -> String {
        guard let referenceDate, let reference = MemoDays.date(referenceDate, timeZone: .gmt) else {
            return "［原消息日期缺失］\n\(text)"
        }
        var resolved = ""
        var start = text.startIndex
        for match in expressions.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let range = Range(match.range, in: text) else { continue }
            resolved += text[start..<range.lowerBound]
            resolved += absolute(String(text[range]), reference: reference)
            start = range.upperBound
        }
        resolved += text[start...]
        return "［原消息日期 \(referenceDate)］\n\(resolved)"
    }

    static func display(_ date: String, relativeTo referenceDate: String) -> String {
        guard let day = MemoDays.date(date, timeZone: .gmt),
              let reference = MemoDays.date(referenceDate, timeZone: .gmt) else { return date }
        switch calendar.dateComponents([.day], from: reference, to: day).day {
        case -1: return "昨天"
        case 0: return "今天"
        case 1: return "明天"
        case 2: return "后天"
        default: break
        }
        if let start = monday(reference), let next = calendar.date(byAdding: .day, value: 7, to: start),
           let following = calendar.date(byAdding: .day, value: 7, to: next), day >= start, day < following {
            let prefix = day < next ? "这周" : "下周"
            return prefix + days[weekdayIndex(day)]
        }
        return calendar.component(.year, from: day) == calendar.component(.year, from: reference)
            ? String(date.suffix(5)) : date
    }

    private static func absolute(_ phrase: String, reference: Date) -> String {
        if let day = dayOf(phrase, reference: reference) {
            let date = MemoDays.name(day, timeZone: .gmt)
            switch phrase {
            case "今早", "明早": return "\(date) 早上"
            case "昨晚", "今晚", "明晚": return "\(date) 晚上"
            default: return date
            }
        }
        guard let parts = captures(weekRange, in: phrase),
              let offset = periodOffsets[parts[0]], let monday = monday(reference),
              let start = calendar.date(byAdding: .day, value: offset * 7, to: monday),
              let first = calendar.date(byAdding: .day, value: parts[1] == "末" ? 5 : 0, to: start),
              let last = calendar.date(byAdding: .day, value: 6, to: start) else { return phrase }
        return "\(MemoDays.name(first, timeZone: .gmt)) 至 \(MemoDays.name(last, timeZone: .gmt))"
    }

    private static func dayOf(_ expression: String, reference: Date) -> Date? {
        if let offset = offsets[expression] {
            return calendar.date(byAdding: .day, value: offset, to: reference)
        }
        if let parts = captures(daysAway, in: expression) {
            let count = number(parts[0])
            return calendar.date(byAdding: .day, value: parts[1] == "前" ? -count : count, to: reference)
        }
        if let parts = captures(weekday, in: expression) {
            guard let day = days.firstIndex(of: parts[1] == "天" ? "日" : parts[1]) else { return nil }
            let delta = day - weekdayIndex(reference)
            let offset = periodOffsets[parts[0]].map { $0 * 7 + delta } ?? (delta + 7) % 7
            return calendar.date(byAdding: .day, value: offset, to: reference)
        }
        if let parts = captures(monthDay, in: expression),
           let offset = periodOffsets[parts[0]],
           let firstDay = calendar.dateInterval(of: .month, for: reference)?.start,
           let month = calendar.date(byAdding: .month, value: offset, to: firstDay) {
            let day = number(parts[1])
            guard calendar.range(of: .day, in: .month, for: month)?.contains(day) == true else { return nil }
            return calendar.date(byAdding: .day, value: day - 1, to: month)
        }
        return nil
    }

    private static func weekdayIndex(_ day: Date) -> Int {
        (calendar.component(.weekday, from: day) + 5) % 7
    }

    private static func monday(_ day: Date) -> Date? {
        calendar.date(byAdding: .day, value: -weekdayIndex(day), to: day)
    }

    private static func captures(_ pattern: NSRegularExpression, in text: String) -> [String]? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = pattern.firstMatch(in: text, range: range), match.range == range else { return nil }
        return (1..<match.numberOfRanges).map { index in Range(match.range(at: index), in: text).map { String(text[$0]) } ?? "" }
    }

    private static func number(_ raw: String) -> Int {
        if let digit = Int(raw) { return digit }
        let digits = ["一", "二", "三", "四", "五", "六", "七", "八", "九"]
        func digit(_ value: Substring) -> Int {
            (digits.firstIndex(of: value == "两" ? "二" : String(value)) ?? -1) + 1
        }
        let parts = raw.split(separator: "十", omittingEmptySubsequences: false)
        if parts.count == 1 { return digit(parts[0]) }
        let tens = parts[0].isEmpty ? 1 : digit(parts[0])
        return tens * 10 + digit(parts[1])
    }
}
