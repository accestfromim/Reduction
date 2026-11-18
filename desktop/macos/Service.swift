import Foundation

final class LocalService {
    let url = URL(string: "http://127.0.0.1:8780/")!
    let microphoneToken = UUID().uuidString
    let logURL = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent(".local/share/reduction/macos.log")

    private let child = Process()
    private let lifetime = Pipe()
    private var output = Data()
    private var stopping = false
    private var ready = false

    func start(onReady: @escaping () -> Void, onExit: @escaping (Int32) -> Void) throws {
        let files = FileManager.default
        let data = logURL.deletingLastPathComponent()
        let scratch = data.appendingPathComponent("macos-cache", isDirectory: true)
        try files.createDirectory(at: scratch, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        if !files.fileExists(atPath: logURL.path) {
            guard files.createFile(atPath: logURL.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
                throw CocoaError(.fileWriteUnknown)
            }
        }
        let log = try FileHandle(forWritingTo: logURL)
        try log.seekToEnd()

        let resources = Bundle.main.resourceURL!
        let executable = Bundle.main.executableURL!.path
        try executable.write(to: data.appendingPathComponent("macos-renderer"), atomically: true, encoding: .utf8)
        child.executableURL = resources.appendingPathComponent("runtime/bin/java")
        child.arguments = [
            "-Djava.io.tmpdir=\(scratch.path)", "-Djava.net.preferIPv4Stack=true",
            "-cp", resources.appendingPathComponent("service/*").path,
            "com.mindtravel.reduction.desktop.MainKt", String(url.port!)
        ]
        var environment = ProcessInfo.processInfo.environment
        let home = files.homeDirectoryForCurrentUser
        let userPaths = [home.appendingPathComponent(".local/bin").path, "/opt/homebrew/bin", "/usr/local/bin"]
        environment["PATH"] = (userPaths + [environment["PATH"] ?? "/usr/bin:/bin:/usr/sbin:/sbin"]).joined(separator: ":")
        environment["REDUCTION_DESKTOP_PARENTED"] = "1"
        environment["REDUCTION_MAC_AUDIO"] = executable
        environment["REDUCTION_MAC_ARTICLE"] = executable
        environment["REDUCTION_MAC_MIC_TOKEN"] = microphoneToken
        child.environment = environment
        child.currentDirectoryURL = home
        child.standardInput = lifetime
        child.standardError = log

        let stdout = Pipe()
        child.standardOutput = stdout
        stdout.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let bytes = handle.availableData
            guard !bytes.isEmpty else {
                handle.readabilityHandler = nil
                return
            }
            DispatchQueue.main.async {
                guard let self, !self.stopping else { return }
                do {
                    try log.seekToEnd()
                    try log.write(contentsOf: bytes)
                } catch {
                    NSLog("Reduction: 无法写入桌面日志：%@", error.localizedDescription)
                }
                guard !self.ready else { return }
                self.output.append(bytes)
                if self.output.range(of: Data("Reduction 已启动：".utf8)) != nil {
                    self.ready = true
                    self.output.removeAll()
                    onReady()
                }
            }
        }
        child.terminationHandler = { [weak self] process in
            DispatchQueue.main.async {
                guard let self, !self.stopping else { return }
                onExit(process.terminationStatus)
            }
        }
        try child.run()
    }

    func stop() {
        guard !stopping else { return }
        stopping = true
        lifetime.fileHandleForWriting.closeFile()
    }

    func pet(_ action: String? = nil, dictate: Bool = false, completion: @escaping (PetState?) -> Void) {
        let base = url.appendingPathComponent("pet")
        var request = URLRequest(url: base, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 4)
        if let action {
            var target = URLComponents(url: base.appendingPathComponent("talk"), resolvingAgainstBaseURL: false)!
            target.queryItems = [URLQueryItem(name: "action", value: action)]
            if dictate { target.queryItems?.append(URLQueryItem(name: "mode", value: "dictate")) }
            request.url = target.url
            request.httpMethod = "POST"
            request.setValue("1", forHTTPHeaderField: "X-Reduction")
            request.setValue(microphoneToken, forHTTPHeaderField: "X-Reduction-Microphone")
        }
        URLSession.shared.dataTask(with: request) { data, response, _ in
            let state: PetState?
            if let data, (response as? HTTPURLResponse)?.statusCode == 200 {
                state = try? JSONDecoder().decode(PetState.self, from: data)
            } else {
                state = nil
            }
            DispatchQueue.main.async { completion(state) }
        }.resume()
    }
}

struct PetState: Decodable {
    let state: String
    let line: String
    let lineId: Int
    let kind: String
    let ready: Bool
}
