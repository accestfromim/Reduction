import Citadel
import Crypto
import Foundation
import NIOCore
import NIOSSH
import OSLog

struct PhoneAgentRun: Codable, Equatable {
    var id: Int
    var host: String
    var user: String
    var port: Int
    var folder: String
    var run: String
    var task: String
    var reported: Bool?
}

private struct ComputerProfile: Equatable {
    let host: String
    let user: String
    let port: Int
    var pin: String { "ssh-host/\(host):\(port)" }

    init?(_ settings: JSON) {
        host = settings["pcHost"].text.trimmed
        user = settings["pcUser"].text.trimmed
        port = settings["pcPort"].integer
        guard !host.isEmpty, !user.isEmpty, (1...65535).contains(port) else { return nil }
    }

    func owns(_ run: PhoneAgentRun) -> Bool { run.host == host && run.user == user && run.port == port }
}

private struct HostPin: NIOSSHClientServerAuthenticationDelegate {
    let name: String
    let store: SecretStore

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        do {
            let current = Data(String(openSSHPublicKey: hostKey).utf8)
            if let saved = try store.read(name) {
                guard saved == current else { throw ReductionError("电脑 SSH 主机密钥已变化，请核对后重新配置") }
            } else { try store.save(current, for: name) }
            validationCompletePromise.succeed(())
        } catch { validationCompletePromise.fail(error) }
    }
}

@MainActor
final class PhoneComputer {
    private let store: PhoneStore
    private let emit: PageEvent
    private let confirm: (String, String) async -> Bool
    private let log = Logger(subsystem: "com.mindtravel.reduction.ios", category: "Computer")
    private var ssh: SSHClient?
    private var profile: ComputerProfile?
    private var dialing: Task<SSHClient, Error>?
    private var dialingID = UUID()
    private var timer: Task<Void, Never>?
    private var debounced: Task<Void, Never>?
    private var syncing = false
    private var needsSync = false
    private var active = false
    private(set) var state = "disconnected"
    var afterSync: () -> Void = {}
    var agentFinished: (String, ToolAnswer) -> Bool = { _, _ in false }

    init(store: PhoneStore, emit: @escaping PageEvent, confirm: @escaping (String, String) async -> Bool) {
        self.store = store
        self.emit = emit
        self.confirm = confirm
    }

    private func setState(_ value: String) {
        if state != value {
            state = value
            emit("onPcConnection", [.string(value)])
        }
    }

    private func privateKey() throws -> Curve25519.Signing.PrivateKey {
        if let data = try store.secrets.read("ssh-ed25519") { return try Curve25519.Signing.PrivateKey(rawRepresentation: data) }
        let key = Curve25519.Signing.PrivateKey()
        try store.secrets.save(key.rawRepresentation, for: "ssh-ed25519")
        return key
    }

    func publicKey() throws -> String {
        let key = NIOSSHPrivateKey(ed25519Key: try privateKey())
        return String(openSSHPublicKey: key.publicKey) + " Reduction-iOS"
    }

    private func configured() throws -> ComputerProfile {
        guard let profile = try ComputerProfile(store.settings()) else { throw ReductionError("请填写电脑主机和 SSH 用户") }
        return profile
    }

    private func connection() async throws -> SSHClient {
        let target = try configured()
        if target == profile, let ssh, ssh.isConnected { return ssh }
        if target == profile, let dialing { return try await dialing.value }
        close()
        profile = target
        let key = try privateKey()
        let pin = HostPin(name: target.pin, store: store.secrets)
        var settings = SSHClientSettings(host: target.host, port: target.port,
                                         authenticationMethod: { .ed25519(username: target.user, privateKey: key) },
                                         hostKeyValidator: .custom(pin))
        settings.connectTimeout = .seconds(12)
        let configuration = settings
        setState("connecting")
        let id = UUID()
        dialingID = id
        let task = Task { try await SSHClient.connect(to: configuration) }
        dialing = task
        do {
            let client = try await task.value
            guard dialingID == id else { try await client.close(); throw CancellationError() }
            dialing = nil
            ssh = client
            setState("connected")
            return client
        } catch {
            if dialingID == id { dialing = nil; setState("disconnected") }
            throw error
        }
    }

    private func close() {
        dialingID = UUID()
        dialing?.cancel()
        dialing = nil
        if let ssh { Task { try? await ssh.close() } }
        ssh = nil
        profile = nil
        setState("disconnected")
    }

    func settingsChanged() {
        if let settings = try? store.settings(), ComputerProfile(settings) != profile { close() }
        changed()
    }

    func foreground() {
        guard !active else { return }
        active = true
        changed()
        timer = Task { [weak self] in
            var tick = 0
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(15))
                    tick += 1
                    await self?.watchAgents()
                    if tick.isMultiple(of: 4) { await self?.sync() }
                }
                catch { return }
            }
        }
    }

    func background() {
        active = false
        debounced?.cancel()
        timer?.cancel()
        timer = nil
    }

    func changed() {
        guard active else { return }
        if syncing { needsSync = true; return }
        debounced?.cancel()
        debounced = Task { [weak self] in
            do { try await Task.sleep(for: .milliseconds(2500)); await self?.sync() }
            catch { return }
        }
    }

    private func sync() async {
        guard active, !syncing, let settings = try? store.settings(), ComputerProfile(settings) != nil else { return }
        syncing = true
        defer {
            syncing = false
            if needsSync { needsSync = false; changed() }
        }
        do {
            let client = try await connection()
            let peer = try await exchange(store.syncPayload(), client: client)
            try Task.checkCancellation()
            guard client === ssh else { return }
            if try store.merge(peer) {
                emit("onCardsChanged", [""])
                emit("onHistoryChanged", [""])
                emit("onMemosChanged", [""])
                afterSync()
            }
            let files = try store.filesMissing(from: peer)
            if !files.isEmpty { _ = try await exchange(store.syncPayload(files: files), client: client) }
        } catch is CancellationError {
            return
        } catch { log.error("电脑同步失败 \(error.localizedDescription, privacy: .private)") }
    }

    private func exchange(_ body: JSON, client: SSHClient) async throws -> JSON {
        let data = try body.data()
        guard data.count <= 20 * 1024 * 1024 else { throw ReductionError("同步数据超出 20 MB") }
        let prepare = "umask 077; d=\"$HOME/.local/share/reduction/ios-sync\"; mkdir -p \"$d\" && mktemp \"$d/request.XXXXXX\""
        let target = try await exec(prepare, client: client).trimmed
        guard target.hasPrefix("/"), !target.contains(where: \.isNewline) else { throw ReductionError("电脑暂存目录无效") }
        let quoted = Self.quote(target)
        do {
            let sftp = try await client.openSFTP()
            do {
                try await sftp.withFile(filePath: target, flags: [.write, .truncate, .create]) { file in
                    try await file.write(ByteBuffer(bytes: data))
                }
                try await sftp.close()
            } catch { try? await sftp.close(); throw error }
            let output = try await exec("trap 'rm -f -- \"$1\"' EXIT; curl -fsS -m 35 -H 'X-Reduction: 1' -H 'Content-Type: application/json' --data-binary @\"$1\" http://127.0.0.1:8780/sync", arguments: [target], client: client)
            return try JSON(text: output)
        } catch {
            _ = try? await exec("rm -f -- \(quoted)", client: client)
            throw error
        }
    }

    private func exec(_ command: String, arguments: [String] = [], client: SSHClient, limit: Int = 20 * 1024 * 1024) async throws -> String {
        let sent = arguments.isEmpty ? command : "sh -c \(Self.quote(command)) sh " + arguments.map(Self.quote).joined(separator: " ")
        let bytes = try await client.executeCommand(sent, maxResponseSize: limit, mergeStreams: false)
        return String(decoding: bytes.readableBytesView, as: UTF8.self)
    }

    func connect() {
        guard let settings = try? store.settings(), ComputerProfile(settings) != nil else { return }
        Task {
            do { _ = try await connection(); changed() }
            catch { log.error("连接电脑失败 \(error.localizedDescription, privacy: .private)") }
        }
    }

    func info(_ id: String) {
        Task {
            do {
                let summary = try await information()
                emit("onPcInfo", [.string(id), .string(try summary.encoded())])
                changed()
            } catch { emit("onPcError", [.string(id), .string(error.localizedDescription)]) }
        }
    }

    func information() async throws -> JSON {
        let profile = try configured()
        let client = try await connection()
        let fields = try await exec("hostname; uname -srm; id -un; date", client: client, limit: 64 * 1024)
            .split(whereSeparator: \.isNewline).map(String.init)
        guard fields.count >= 4 else { throw ReductionError("电脑信息不完整") }
        let stored = try store.secrets.read(profile.pin)
        let key = stored.map { String(decoding: $0, as: UTF8.self) } ?? ""
        return ["hostname": .string(fields[0]), "os": .string(fields[1]), "user": .string(fields[2]),
                "time": .string(fields[3]), "hostKey": .string(key)]
    }

    func command(_ id: String, command: String) {
        Task {
            do {
                guard !command.trimmed.isEmpty else { throw ReductionError("请填写电脑命令") }
                let output = try await exec(command, client: connection(), limit: 1024 * 1024)
                emit("onPcExec", [.string(id), .string(output)])
            } catch { emit("onPcError", [.string(id), .string(error.localizedDescription)]) }
        }
    }

    func sessionStatus() async throws -> String {
        try await runScript(harness("session-status.py"), with: "python3 -", client: connection())
    }

    func agent(_ args: JSON, key: String) async throws -> ToolAnswer {
        let settings = try store.settings()
        let profile = try configured()
        guard settings["opencodeEnabled"].flag || settings["opencodeWriteEnabled"].flag else {
            throw ReductionError("请先在设置中启用子代理")
        }
        let action = args["action"].text
        let script = try harness("run-agent.sh")
        let client = try await connection()
        if action == "start" {
            let prompt = args["task"].text.trimmed
            guard !prompt.isEmpty, prompt.utf8.count <= 24 * 1024 else { throw ReductionError("任务描述为空或超出长度限制") }
            let write = args["access"].text == "write"
            if write {
                guard settings["opencodeWriteEnabled"].flag else { throw ReductionError("没有启用子代理写入") }
                guard await confirm("允许电脑子代理写入", prompt) else { throw ReductionError("已取消电脑写入") }
            }
            let named = args["folder"].text.isEmpty ? "research" : args["folder"].text
            let directories = settings["agentDirectories"]
            let allowed = Set(directories.values.map { $0["path"].text })
            let folder: String
            if named == "project" || allowed.contains(named) {
                folder = named == "project" ? settings["pcProject"].text : named
                guard allowed.contains(folder) else { throw ReductionError("请先在设置中授权此目录") }
            } else {
                guard named.wholeMatch(of: #/[a-zA-Z0-9_.-]{1,48}/#) != nil, !named.hasPrefix(".") else {
                    throw ReductionError("电脑任务文件夹名称无效")
                }
                folder = "~/reduction-agents/\(named)"
            }
            let mac = Data(try harness("macos-agent.py").utf8).base64EncodedString()
            let wrapped = "REDUCTION_MAC_AGENT_SCRIPT=\(Self.quote(mac))\n" + script
            let engine = settings["agentEngine"].text
            var environment = "AGENT_DIRECTORIES=\(Self.quote(try directories.encoded())) AGENT_ENGINE=\(Self.quote(engine)) "
            if engine == "opencode" {
                environment += "AGENT_KEY=\(Self.quote(key)) AGENT_MODEL=\(Self.quote("deepseek/" + settings["model"].text)) "
            }
            let returned = try await runScript(wrapped, with: "sh -s --", args: ["start", write ? "build" : "plan", folder, prompt],
                                               client: client, environment: environment)
            guard let line = returned.split(whereSeparator: \.isNewline).first(where: { $0.hasPrefix("RUN ") }) else {
                throw ReductionError(returned.trimmed.isEmpty ? "电脑子代理没有返回任务编号" : returned.trimmed)
            }
            let run = String(line.dropFirst(4)).trimmed
            guard run.wholeMatch(of: #/[A-Za-z0-9-]{1,80}/#) != nil else { throw ReductionError("电脑子代理编号无效") }
            let id = (store.state.agentRuns.map(\.id).max() ?? 0) + 1
            try store.change { $0.agentRuns.append(PhoneAgentRun(id: id, host: profile.host, user: profile.user, port: profile.port,
                                                                folder: folder, run: run, task: prompt)) }
            return ToolAnswer(text: "已派发任务 #\(id)")
        }
        guard action == "status" || action == "stop" else { throw ReductionError("电脑任务操作无效") }
        let number = args["run"].integer
        guard let run = store.state.agentRuns.last(where: { profile.owns($0) && (number == 0 || $0.id == number) }) else {
            throw ReductionError("没有此电脑任务")
        }
        if action == "stop" && number == 0 { throw ReductionError("结束任务前请提供任务编号") }
        let output = try await runScript(script, with: "sh -s --", args: [action, run.folder, run.run], client: client)
        if action == "stop" { try markReported(run.id) }
        return Self.agentResult("电脑任务 #\(run.id)", output: output)
    }

    private func watchAgents() async {
        guard active, let profile = try? configured(), let settings = try? store.settings(),
              settings["opencodeEnabled"].flag || settings["opencodeWriteEnabled"].flag else { return }
        let runs = Array(store.state.agentRuns.filter { profile.owns($0) && $0.reported != true }.prefix(3))
        guard !runs.isEmpty else { return }
        do {
            let client = try await connection()
            let script = try harness("run-agent.sh")
            for run in runs {
                guard active, !Task.isCancelled, client === ssh else { return }
                let output = try await runScript(script, with: "sh -s --", args: ["status", run.folder, run.run], client: client)
                guard let first = output.split(whereSeparator: \.isNewline).first,
                      first.hasPrefix("STATE done ") || first == "STATE lost" else { continue }
                let label = "电脑任务 #\(run.id) 已结束"
                if agentFinished(label, Self.agentResult(label, output: output)) { try markReported(run.id) }
            }
        } catch { log.error("电脑任务查询失败 \(error.localizedDescription, privacy: .private)") }
    }

    private func markReported(_ id: Int) throws {
        try store.change { next in
            if let index = next.agentRuns.firstIndex(where: { $0.id == id }) { next.agentRuns[index].reported = true }
        }
    }

    private static func agentResult(_ label: String, output: String) -> ToolAnswer {
        let blocks = output.components(separatedBy: "\n=====IMAGE ")
        let text = label + "\n" + (blocks.first ?? "")
        let pictures = blocks.dropFirst().prefix(4).compactMap { block -> ModelPicture? in
            let fields = block.split(separator: "\n", maxSplits: 1)
            guard fields.count == 2, let header = fields[0].wholeMatch(of: #/([^\s]+) (image\/(?:png|jpeg|webp|gif))=====/#),
                  let line = fields[1].split(whereSeparator: \.isWhitespace).first,
                  let bytes = Data(base64Encoded: String(line)), !bytes.isEmpty, bytes.count <= 3 * 1024 * 1024 else { return nil }
            return ModelPicture(name: String(header.1), mime: String(header.2), data: bytes)
        }
        return ToolAnswer(text: text, pictures: pictures)
    }

    private func harness(_ name: String) throws -> String {
        let path = Bundle.main.bundleURL.appendingPathComponent("Harness", isDirectory: true).appendingPathComponent(name)
        return try String(contentsOf: path, encoding: .utf8)
    }

    private func runScript(_ body: String, with interpreter: String, args: [String] = [], client: SSHClient,
                           environment: String = "") async throws -> String {
        let encoded = Data(body.utf8).base64EncodedString()
        let command = "printf '%s' \(Self.quote(encoded)) | base64 -d | " + environment + interpreter + " " + args.map(Self.quote).joined(separator: " ")
        return try await exec(command, client: client)
    }

    private static func quote(_ text: String) -> String { "'" + text.replacingOccurrences(of: "'", with: "'\\''") + "'" }
}
