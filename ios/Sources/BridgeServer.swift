import FlyingFox
import FlyingSocks
import Foundation
import OSLog
import UniformTypeIdentifiers

struct BridgeServer: HTTPHandler {
    private static let log = Logger(subsystem: "com.mindtravel.reduction.ios", category: "Bridge")
    let root: URL
    let core: PhoneCore
    let token = UUID().uuidString

    func run(port: UInt16, didStart: @escaping @MainActor (URL) -> Void) async throws {
        let address = try sockaddr_in.inet(ip4: "127.0.0.1", port: port)
        let server = HTTPServer(address: address, logger: .disabled, handler: self)
        try await withThrowingTaskGroup(of: UInt16.self) { group in
            group.addTask {
                try await server.run()
                throw SocketError.disconnected
            }
            group.addTask {
                try await server.waitUntilListening()
                guard case .ip4(_, let boundPort)? = await server.listeningAddress else {
                    throw SocketError.disconnected
                }
                return boundPort
            }
            guard let boundPort = try await group.next() else { throw SocketError.disconnected }
            try Task.checkCancellation()
            await didStart(url(port: boundPort))
            _ = try await group.next()
        }
    }

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard request.headers[.host]?.split(separator: ":").first == "127.0.0.1" else {
            return HTTPResponse(statusCode: .forbidden)
        }
        if request.method == .GET && request.path == "/" && request.query.first(where: { $0.name == "session" })?.value == token {
            let html = try String(contentsOf: root.appendingPathComponent("index.html"), encoding: .utf8)
            let page = html.replacingOccurrences(of: "</head>", with: "<link rel=\"stylesheet\" href=\"./ios.css\"><script src=\"./ios.js\"></script></head>")
            let headers: HTTPHeaders = [
                .contentType: "text/html; charset=utf-8",
                .setCookie: "ReductionIOS=\(token); Path=/; HttpOnly; SameSite=Strict",
                .cacheControl: "no-store",
                HTTPHeader("Referrer-Policy"): "no-referrer",
                HTTPHeader("Content-Security-Policy"): "default-src 'self'; script-src 'self' 'unsafe-inline' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; media-src 'self' blob:; font-src 'self' data:; frame-src 'self' about: blob:; worker-src 'self' blob:; base-uri 'self'; object-src 'none'; form-action 'none'"
            ]
            return HTTPResponse(statusCode: .ok, headers: headers, body: Data(page.utf8))
        }
        let cookie = request.headers[.cookie]?.split(separator: ";").contains { String($0).trimmed == "ReductionIOS=\(token)" } ?? false
        guard cookie else { return HTTPResponse(statusCode: .forbidden) }

        if request.method == .POST && request.path.hasPrefix("/bridge/") {
            guard request.headers[HTTPHeader("X-Reduction")] == "1",
                  let length = request.headers[.contentLength].flatMap(Int.init), (0...(28 * 1024 * 1024)).contains(length) else {
                return HTTPResponse(statusCode: .forbidden)
            }
            let method = String(request.path.dropFirst("/bridge/".count))
            do {
                let args = try JSON(data: await request.bodyData)
                guard case .array = args else { throw ReductionError("请求参数无效") }
                let result = try await core.handle(method, args)
                return try reply(["result": result])
            } catch {
                Self.log.error("iOS 桥接操作失败 \(method, privacy: .public) \(error.localizedDescription, privacy: .private)")
                return try reply(["result": false, "error": .string(error.localizedDescription)])
            }
        }
        guard request.method == .GET || request.method == .HEAD else { return HTTPResponse(statusCode: .methodNotAllowed) }
        let path = request.path.split(separator: "/").map(String.init)
        guard !path.isEmpty, !path.contains(".."), !path.contains("."),
              !path.contains(where: { $0.contains("\\") }) else { return HTTPResponse(statusCode: .notFound) }

        let url: URL
        if path.first == "card-media", path.count == 2, let file = await core.mediaURL(path[1]) { url = file }
        else if ["vendor", "article", "js", "css", "ios.js", "ios.css"].contains(path[0]) {
            url = path.reduce(root) { folder, component in folder.appendingPathComponent(component) }
        } else { return HTTPResponse(statusCode: .notFound) }
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory), !isDirectory.boolValue else {
            return HTTPResponse(statusCode: .notFound)
        }
        var response = try await FileHTTPHandler(path: url, contentType: Self.mime(url), cacheControl: [.noStore]).handleRequest(request)
        if path.first == "card-media" && url.pathExtension.lowercased() == "svg" {
            response.headers[HTTPHeader("Content-Security-Policy")] = "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:"
        }
        return response
    }

    private func reply(_ value: JSON) throws -> HTTPResponse {
        HTTPResponse(statusCode: .ok, headers: [.contentType: "application/json; charset=utf-8", .cacheControl: "no-store"],
                     body: try value.data())
    }

    private static func mime(_ url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "js", "mjs": return "text/javascript; charset=utf-8"
        case "html": return "text/html; charset=utf-8"
        case "css": return "text/css; charset=utf-8"
        case "woff": return "font/woff"
        case "woff2": return "font/woff2"
        default: return UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        }
    }

    func url(port: UInt16) -> URL {
        var components = URLComponents()
        components.scheme = "http"
        components.host = "127.0.0.1"
        components.port = Int(port)
        components.path = "/"
        components.queryItems = [URLQueryItem(name: "session", value: token)]
        return components.url!
    }
}
