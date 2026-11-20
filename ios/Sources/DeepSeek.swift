import Foundation

struct ModelPicture: Sendable {
    let name: String
    let mime: String
    let data: Data

    var part: JSON {
        ["type": "image_url", "image_url": ["url": .string("data:\(mime);base64,\(data.base64EncodedString())")]]
    }
}

struct ModelToolCall: Sendable {
    var id = ""
    var name = ""
    var arguments = ""

    var message: JSON {
        ["id": .string(id), "type": "function", "function": ["name": .string(name), "arguments": .string(arguments)]]
    }
}

struct ModelReply: Sendable {
    var text = ""
    var calls: [ModelToolCall] = []
}

struct DeepSeek: Sendable {
    private static let base = URL(string: "https://api.deepseek.com")!
    let key: String

    private func request(_ path: String, body: Data, contentType: String = "application/json") throws -> URLRequest {
        guard !key.isEmpty else { throw ReductionError("请在设置中填写 DeepSeek API Key") }
        var request = URLRequest(url: Self.base.appendingPathComponent(path), timeoutInterval: 90)
        request.httpMethod = "POST"
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        return request
    }

    private func check(_ response: URLResponse, error: Data) throws {
        guard let response = response as? HTTPURLResponse else { throw ReductionError("DeepSeek 返回了无效的 HTTP 响应") }
        guard (200..<300).contains(response.statusCode) else {
            let message = (try? JSON(data: error))?["error"]["message"].text ?? ""
            throw ReductionError(message.isEmpty ? "DeepSeek 请求失败（HTTP \(response.statusCode)）" : message)
        }
    }

    private func post(_ path: String, _ json: JSON) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request(path, body: json.data()))
        try check(response, error: data)
        return data
    }

    func chat(model: String, messages: [JSON], tools: [JSON], token: (String) async -> Void) async throws -> ModelReply {
        var payload: [String: JSON] = ["model": .string(model), "messages": .array(messages), "stream": true,
                                      "thinking": ["type": "disabled"]]
        if !tools.isEmpty { payload["tools"] = .array(tools) }
        let (stream, response) = try await URLSession.shared.bytes(for: request("v1/chat/completions", body: JSON.object(payload).data()))
        if let response = response as? HTTPURLResponse, !(200..<300).contains(response.statusCode) {
            var bytes = Data()
            for try await byte in stream {
                bytes.append(byte)
                if bytes.count == 16 * 1024 { break }
            }
            try check(response, error: bytes)
        }
        var text = "", ended = false
        var calls: [Int: ModelToolCall] = [:]
        for try await line in stream.lines {
            try Task.checkCancellation()
            guard line.hasPrefix("data:") else { continue }
            let raw = String(line.dropFirst(5)).trimmed
            if raw == "[DONE]" { ended = true; break }
            let event = try JSON(text: raw)
            if !event["error"]["message"].text.isEmpty { throw ReductionError(event["error"]["message"].text) }
            let choice = event["choices"][0]
            let delta = choice["delta"]
            let next = delta["content"].text
            if !next.isEmpty {
                text += next
                await token(next)
            }
            for item in delta["tool_calls"].values {
                let index = item["index"].integer
                var call = calls[index, default: ModelToolCall()]
                call.id += item["id"].text
                call.name += item["function"]["name"].text
                call.arguments += item["function"]["arguments"].text
                calls[index] = call
            }
            if choice["finish_reason"].text == "content_filter" { throw ReductionError("DeepSeek 中止了当前回复") }
        }
        guard ended else { throw ReductionError("DeepSeek 回复连接提前结束") }
        let ordered = calls.sorted { $0.key < $1.key }.map(\.value)
        guard ordered.allSatisfy({ !$0.id.isEmpty && !$0.name.isEmpty }) else { throw ReductionError("DeepSeek 返回了不完整的工具调用") }
        return ModelReply(text: text, calls: ordered)
    }

    func transcribe(_ audio: Data) async throws -> String {
        let boundary = "Reduction" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
        var body = Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".utf8)
        body.append(audio)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        let (data, response) = try await URLSession.shared.data(for: request("v1/tools/asr", body: body,
                                                                          contentType: "multipart/form-data; boundary=\(boundary)"))
        try check(response, error: data)
        var text = try JSON(data: data)["text"].text.trimmed
        if let ending = text.firstMatch(of: #/\s*(?:完毕|over)[。！!.,，\s]*$/#) { text.removeSubrange(ending.range) }
        guard !text.isEmpty else { throw ReductionError("没有识别到文字，请重说") }
        return text.trimmed
    }

    func search(_ query: String) async throws -> String {
        guard !query.trimmed.isEmpty else { throw ReductionError("请填写搜索词") }
        let response = try JSON(data: await post("v1/tools/search", ["query": .string(String(query.prefix(128))), "count": 5,
                                                                      "need_content": false]))
        let results = response["results"].values.filter { !$0["url"].text.isEmpty }
        guard !results.isEmpty else { return "没有找到搜索结果" }
        return results.enumerated().map { index, item in
            let url = item["url"].text
            let title = item["title"].text.isEmpty ? url : item["title"].text
            let snippet = item["summary"].text.isEmpty ? item["snippet"].text : item["summary"].text
            return "\(index + 1). \(title)\n\(url)\n\(snippet)"
        }.joined(separator: "\n\n")
    }
}
