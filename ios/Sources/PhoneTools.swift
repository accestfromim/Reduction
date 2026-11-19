import Expression
import Foundation

struct ToolAnswer {
    let text: String
    var pictures: [ModelPicture] = []
}

final class ToolPages {
    private var results: [Int: [String]] = [:]
    private var next = 1

    func store(_ value: String) -> String {
        guard value.count > 6_000 else { return value }
        let id = next
        next += 1
        let chunks = value.chunks(of: 6_000)
        results[id] = chunks
        if results.count > 12 { results.removeValue(forKey: id - 12) }
        return chunks[0] + "\n\n长结果 \(id) 共 \(chunks.count) 片。可用 read_more 查看其它片段。"
    }

    func read(_ args: JSON) throws -> String {
        let id = args["id"].integer == 0 ? Int(args["id"].text) ?? 0 : args["id"].integer
        guard let chunks = results[id] else { throw ReductionError("长结果不存在或已清除") }
        var offset = args["piece"].integer
        if offset == 0 { offset = Int(args["piece"].text.split(separator: "-").first ?? "2") ?? 2 }
        guard (1...chunks.count).contains(offset) else { throw ReductionError("长结果片段超出范围") }
        return "长结果 \(id) 第 \(offset)/\(chunks.count) 片\n" + chunks[offset - 1]
    }
}

private extension String {
    func chunks(of length: Int) -> [String] {
        var cursor = startIndex
        var chunks: [String] = []
        while cursor < endIndex {
            let end = index(cursor, offsetBy: length, limitedBy: endIndex) ?? endIndex
            chunks.append(String(self[cursor..<end]))
            cursor = end
        }
        return chunks
    }
}

enum PhoneTools {
    static func specification(_ name: String, _ description: String, _ properties: [(String, String, String)], required: [String]) -> JSON {
        ["type": "function", "function": [
            "name": .string(name), "description": .string(description),
            "parameters": ["type": "object", "properties": .object(Dictionary(uniqueKeysWithValues: properties.map { name, type, description in
                (name, ["type": .string(type), "description": .string(description)])
            })), "required": .array(required.map(JSON.string))]
        ]]
    }

    static func available(settings: JSON, online: Bool) -> [JSON] {
        var tools = [
            specification("patch_cards", "以补丁一次修改多张卡片。原子应用，操作包括 Add、Update、Delete、Move Card。Add 后另写 Purpose:，正文行以 + 开头；Update 用 @@ 和以空格、+、- 开头的上下文。", [("patch", "string", "完整卡片补丁")], required: ["patch"]),
            specification("read_card", "读取卡片原文、附带的图片和 PDF 页图。", [("name", "string", "卡片名称"), ("pages", "string", "可指定页码，如 5-8")], required: ["name"]),
            specification("search_cards", "按词搜索卡片名称和正文。", [("query", "string", "用空格分隔的关键词"), ("folder", "string", "可选文件夹"), ("match", "string", "all 或 any")], required: ["query"]),
            specification("todo", "登记、改动或查询用户自己的待办。date 用 yyyy-MM-dd，未指定时可留空。part 可填早上、上午、下午、晚上、none。", [
                ("action", "string", "add、update、done、delete 或 list"), ("id", "integer", "待办编号"), ("text", "string", "待办内容"),
                ("date", "string", "具体日期 yyyy-MM-dd"), ("part", "string", "时段"), ("done", "boolean", "是否完成")
            ], required: ["action"]),
            specification("read_more", "读取分片的长结果。", [("id", "integer", "长结果编号"), ("piece", "integer", "片号")], required: ["id"])
        ]
        if settings["calculatorEnabled"].flag {
            tools.append(specification("calculator", "本地计算数学表达式，乘方使用 pow(a,b)。", [("expression", "string", "数学表达式")], required: ["expression"]))
        }
        if settings["webSearchEnabled"].flag {
            tools.append(specification("web_search", "使用 DeepSeek 搜索网页。", [("query", "string", "搜索词")], required: ["query"]))
        }
        if online && (settings["opencodeEnabled"].flag || settings["opencodeWriteEnabled"].flag) {
            tools.append(specification("session_status", "查看自己电脑上的编程会话摘要。", [], required: []))
            tools.append(specification("run_agent", "在用户自己的电脑上用设置中选定的子代理执行器派发沙箱任务，或查询、结束已有任务。", [
                ("action", "string", "start、status 或 stop"), ("task", "string", "任务描述"),
                ("access", "string", "read 或 write"), ("folder", "string", "project 或实际任务文件夹名"), ("run", "integer", "任务编号")
            ], required: ["action"]))
        }
        return tools
    }

    static func calculator(_ expression: String) throws -> String {
        let input = expression.trimmed.replacingOccurrences(of: "×", with: "*").replacingOccurrences(of: "÷", with: "/")
        guard !input.isEmpty, input.count <= 512 else { throw ReductionError("计算表达式为空或过长") }
        let result = try Expression(input).evaluate()
        guard result.isFinite else { throw ReductionError("运算结果超出有效范围") }
        return String(format: "%.15g", locale: Locale(identifier: "en_US_POSIX"), result)
    }

    static func read(_ args: JSON, store: PhoneStore) throws -> ToolAnswer {
        let name = try PhoneStore.cleanName(args["name"].text)
        if name == "计划" { return ToolAnswer(text: store.memoText(absoluteDates: true)) }
        guard let card = store.state.cards[name] else { throw ReductionError("卡片「\(name)」不存在") }
        let parts = args["pages"].text.split(separator: "-", maxSplits: 1)
        let first = parts.first.flatMap { Int($0) } ?? 1
        let last = parts.dropFirst().first.flatMap { Int($0) } ?? (args["pages"].text.isEmpty ? 4 : first)
        guard (1...200).contains(first), last >= first, last <= 200 else { throw ReductionError("PDF 页码无效") }
        var media: [ModelPicture] = []
        for id in PhoneStore.references(card.text) {
            let names = id.hasSuffix(".pdf") ? (first...min(last, first + 3)).map { "\(id).p\($0).jpg" } : [id]
            for file in names where media.count < 6 {
                guard let url = store.mediaURL(file), ["jpg", "jpeg", "png", "webp", "gif"].contains(url.pathExtension),
                      let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 4 * 1024 * 1024 else { continue }
                let mime = url.pathExtension == "jpg" ? "image/jpeg" : "image/\(url.pathExtension)"
                media.append(ModelPicture(name: file, mime: mime, data: try Data(contentsOf: url)))
            }
        }
        return ToolAnswer(text: "卡片「\(name)」\n\n\(card.text)", pictures: media)
    }

    static func search(_ args: JSON, store: PhoneStore) throws -> String {
        let terms = args["query"].text.lowercased().split(whereSeparator: \.isWhitespace).map(String.init)
        guard !terms.isEmpty else { throw ReductionError("请填写搜索词") }
        let folder = args["folder"].text.trimmed
        let any = args["match"].text == "any"
        func matches(_ text: String) -> Bool {
            let lower = text.lowercased()
            return any ? terms.contains(where: lower.contains) : terms.allSatisfy(lower.contains)
        }
        let entries = store.state.cards.values.sorted { $0.name < $1.name }.flatMap { card -> [String] in
            guard folder.isEmpty || card.name.hasPrefix(folder + "/") else { return [] }
            let lines = card.text.components(separatedBy: "\n").enumerated().compactMap { number, line in
                matches(line) ? "\(card.name) 第 \(number + 1) 行 \(line)" : nil
            }
            return matches(card.name) ? ["卡片名 \(card.name)"] + lines : lines
        }
        return entries.isEmpty ? "没有匹配的卡片" : entries.joined(separator: "\n")
    }
}
