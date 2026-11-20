import Foundation

enum CardPatch {
    enum Operation {
        case add(String, String, String)
        case update(String, String?, [[String]])
        case delete(String)
        case move(String, String)
    }

    private static let header = #/(?i)^\*\*\*\s*(Add|Update|Delete|Move)\s+(?:Card|File)\s*[:：]\s*(.+?)\s*$/#
    private static let envelope = #/(?i)^\*\*\*\s*(?:Begin|End) Patch$/#
    private static let purpose = #/(?i)^(?:Purpose|用途)\s*[:：]\s*(.*)$/#

    static func parse(_ text: String) throws -> [Operation] {
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var index = 0
        var operations: [Operation] = []
        while index < lines.count {
            let line = lines[index].trimmed
            guard let heading = line.wholeMatch(of: header) else {
                guard line.isEmpty || line.hasPrefix("```") || line.wholeMatch(of: envelope) != nil else {
                    throw ReductionError("补丁第 \(index + 1) 行不在卡片段落内")
                }
                index += 1
                continue
            }
            let verb = heading.1.lowercased()
            let name = String(heading.2)
            index += 1
            var body: [String] = []
            while index < lines.count {
                let next = lines[index].trimmed
                if next.wholeMatch(of: header) != nil || next.wholeMatch(of: envelope) != nil { break }
                body.append(lines[index]); index += 1
            }
            var description: String?
            if let first = body.first?.trimmed.wholeMatch(of: purpose) {
                description = String(first.1).trimmed
                body.removeFirst()
            }
            while let last = body.last, last.trimmed.isEmpty || last.trimmed == "```" { body.removeLast() }
            switch verb {
            case "add":
                guard let description, !description.isEmpty else { throw ReductionError("新建卡片需注明用途") }
                let body = body.map { $0.hasPrefix("+") ? String($0.dropFirst()) : $0 }.joined(separator: "\n")
                operations.append(.add(try PhoneStore.cleanName(name), description, body))
            case "update":
                var hunks: [[String]] = [[]]
                for line in body {
                    if line.hasPrefix("@@") { hunks.append([]) }
                    else { hunks[hunks.count - 1].append(line) }
                }
                operations.append(.update(try PhoneStore.cleanName(name), description, hunks.filter { !$0.isEmpty }))
            case "move":
                guard let arrow = name.firstMatch(of: #/\s*(?:->|→|=>)\s*/#) else {
                    throw ReductionError("改名格式为旧名称 -> 新名称")
                }
                operations.append(.move(try PhoneStore.cleanName(String(name[..<arrow.range.lowerBound])),
                                        try PhoneStore.cleanName(String(name[arrow.range.upperBound...]))))
            default:
                operations.append(.delete(try PhoneStore.cleanName(name)))
            }
        }
        guard !operations.isEmpty else { throw ReductionError("补丁没有卡片改动") }
        return operations
    }

    static func replace(_ text: String, hunks: [[String]], in name: String) throws -> String {
        var lines = text.components(separatedBy: "\n")
        while lines.last == "" { lines.removeLast() }
        var cursor = 0
        for (index, hunk) in hunks.enumerated() {
            var old: [String] = [], new: [String] = []
            for line in hunk {
                switch line.first {
                case "+": new.append(String(line.dropFirst()))
                case "-": old.append(String(line.dropFirst()))
                default:
                    let context = line.hasPrefix(" ") ? String(line.dropFirst()) : line
                    old.append(context); new.append(context)
                }
            }
            guard old != new else { continue }
            if old.isEmpty {
                if let last = lines.last, !last.trimmed.isEmpty, new.first?.trimmed.isEmpty == false { lines.append("") }
                lines.append(contentsOf: new)
                cursor = lines.count
                continue
            }
            let target = old.map(\.trimmed)
            let count = lines.count - old.count
            guard count >= 0 else { throw ReductionError("卡片「\(name)」第 \(index + 1) 处改动对不上原文") }
            let starts = 0..<(count + 1)
            let ranges = [min(cursor, count + 1)..<(count + 1), starts]
            let found = ranges.lazy.flatMap { $0 }.first { at in
                lines[at..<(at + old.count)].map(\.trimmed) == target
            }
            guard let found else { throw ReductionError("卡片「\(name)」第 \(index + 1) 处改动对不上原文") }
            lines.replaceSubrange(found..<(found + old.count), with: new)
            cursor = found + new.count
        }
        while let last = lines.last, last.trimmed.isEmpty { lines.removeLast() }
        return lines.isEmpty ? "" : lines.joined(separator: "\n") + "\n"
    }
}

extension PhoneStore {
    func applyCards(_ patch: String) throws -> String {
        let edits = try CardPatch.parse(patch)
        var touched: [String] = []
        try change { next in
            for edit in edits {
                switch edit {
                case let .add(name, purpose, text):
                    _ = try Self.editable(name, in: next.cards)
                    guard next.cards[name] == nil else { throw ReductionError("卡片「\(name)」已存在") }
                    try validateContent(text)
                    next.cards[name] = PhoneCard(name: name, text: text, purpose: Self.cleanPurpose(purpose), prompt: false,
                                                 at: max(nowMilliseconds(), (next.cardDeletions[name] ?? 0) + 1))
                    next.cardDeletions.removeValue(forKey: name)
                    touched.append(name)
                case let .update(name, purpose, hunks):
                    _ = try Self.editable(name, in: next.cards)
                    guard var card = next.cards[name] else { throw ReductionError("卡片「\(name)」不存在") }
                    card.text = try CardPatch.replace(card.text, hunks: hunks, in: name)
                    try validateContent(card.text)
                    if let purpose, !purpose.isEmpty { card.purpose = Self.cleanPurpose(purpose) }
                    card.at = max(card.at + 1, nowMilliseconds())
                    next.cards[name] = card
                    touched.append(name)
                case let .delete(name):
                    _ = try Self.editable(name, in: next.cards)
                    guard let removed = next.cards.removeValue(forKey: name) else { throw ReductionError("卡片「\(name)」不存在") }
                    next.cardDeletions[name] = max(removed.at + 1, nowMilliseconds())
                    touched.append(name)
                case let .move(name, to):
                    _ = try Self.editable(name, in: next.cards)
                    _ = try Self.editable(to, in: next.cards)
                    guard var card = next.cards[name], next.cards[to] == nil else { throw ReductionError("无法移动卡片「\(name)」") }
                    next.cards.removeValue(forKey: name)
                    card.name = to
                    card.at = max(card.at + 1, nowMilliseconds(), (next.cardDeletions[to] ?? 0) + 1)
                    next.cardDeletions[name] = card.at
                    next.cardDeletions.removeValue(forKey: to)
                    next.cards[to] = card
                    touched.append(to)
                }
            }
        }
        return "已更新 \(touched.joined(separator: "、"))"
    }
}
