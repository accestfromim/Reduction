import AVFoundation
import CryptoKit
import Foundation

typealias PageEvent = (String, [JSON]) -> Void

@MainActor
final class PhoneAudio: NSObject, AVAudioRecorderDelegate, AVAudioPlayerDelegate {
    private struct ActiveRecording {
        let id: String
        let file: URL
        let recorder: AVAudioRecorder
    }

    private struct Playback {
        let id: UUID
        let player: AVAudioPlayer
        let completion: CheckedContinuation<Void, Error>
    }

    private let directory: URL
    private let session = AVAudioSession.sharedInstance()
    private var active: ActiveRecording?
    private var playback: Playback?
    private var interrupted: NSObjectProtocol?
    var recorded: (String, Bool, Result<Data, Error>) -> Void = { _, _, _ in }

    init(directory: URL) throws {
        self.directory = directory
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        super.init()
        interrupted = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification,
                                                               object: session, queue: .main) { [weak self] note in
            guard note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt == AVAudioSession.InterruptionType.began.rawValue else { return }
            Task { @MainActor in self?.interrupt() }
        }
    }

    deinit {
        if let interrupted { NotificationCenter.default.removeObserver(interrupted) }
    }

    func permission(interactive: Bool) async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return true
        case .undetermined where interactive: return await AVAudioApplication.requestRecordPermission()
        default: return false
        }
    }

    func start(_ id: String) throws {
        guard !id.isEmpty, active == nil else { throw ReductionError("已有正在进行的录音") }
        guard AVAudioApplication.shared.recordPermission == .granted else { throw ReductionError("请允许麦克风权限") }
        let file = directory.appendingPathComponent(UUID().uuidString + ".wav")
        let recorder = try AVAudioRecorder(url: file, settings: [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false
        ])
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true)
        recorder.delegate = self
        guard recorder.record(forDuration: 120) else {
            try? FileManager.default.removeItem(at: file)
            deactivate()
            throw ReductionError("无法启动录音")
        }
        active = ActiveRecording(id: id, file: file, recorder: recorder)
    }

    func stop(_ id: String) {
        guard let active, active.id == id else { return }
        self.active = nil
        active.recorder.delegate = nil
        active.recorder.stop()
        finish(active, limited: false, successful: true)
    }

    func cancel() {
        guard let active else { return }
        self.active = nil
        active.recorder.delegate = nil
        active.recorder.stop()
        try? FileManager.default.removeItem(at: active.file)
        deactivate()
    }

    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        let identifier = ObjectIdentifier(recorder)
        Task { @MainActor in
            guard let active, ObjectIdentifier(active.recorder) == identifier else { return }
            self.active = nil
            finish(active, limited: true, successful: flag)
        }
    }

    private func finish(_ recording: ActiveRecording, limited: Bool, successful: Bool) {
        defer { try? FileManager.default.removeItem(at: recording.file); deactivate() }
        let result = Result {
            guard successful else { throw ReductionError("录音提前中断") }
            let bytes = try Data(contentsOf: recording.file)
            guard bytes.count > 3200 else { throw ReductionError("录音太短，请重试") }
            return bytes
        }
        recorded(recording.id, limited, result)
    }

    private func interrupt() {
        if let active {
            cancel()
            recorded(active.id, false, .failure(ReductionError("录音被系统中断")))
        }
        stopPlayback()
    }

    func play(_ bytes: Data) async throws {
        guard active == nil, playback == nil else { throw ReductionError("当前音频会话正在使用") }
        try Task.checkCancellation()
        let player = try AVAudioPlayer(data: bytes)
        try session.setCategory(.playback, mode: .spokenAudio)
        try session.setActive(true)
        player.delegate = self
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                playback = Playback(id: id, player: player, completion: continuation)
                if !player.play() { finishPlayback(.failure(ReductionError("无法播放朗读音频"))) }
            }
        } onCancel: { [weak self] in
            Task { @MainActor in
                if self?.playback?.id == id { self?.stopPlayback() }
            }
        }
    }

    func stopPlayback() {
        playback?.player.stop()
        finishPlayback(.failure(CancellationError()))
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard playback?.player === player else { return }
        finishPlayback(flag ? .success(()) : .failure(ReductionError("朗读音频提前停止")))
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: (any Error)?) {
        guard playback?.player === player else { return }
        finishPlayback(.failure(error ?? ReductionError("无法解码朗读音频")))
    }

    private func finishPlayback(_ result: Result<Void, Error>) {
        guard let playback else { return }
        self.playback = nil
        playback.completion.resume(with: result)
        deactivate()
    }

    private func deactivate() {
        guard active == nil, playback == nil else { return }
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }
}

@MainActor
final class PhoneSpeech {
    private let audio: PhoneAudio
    private let directory: URL
    private let emit: PageEvent
    private var generation = UUID()
    private var task: Task<Void, Never>?
    private var speech: (id: String, api: DeepSeek, voice: String)?
    private var pending = ""
    private var pieces: [String] = []
    private var sealed = false
    private var started = false

    init(audio: PhoneAudio, directory: URL, emit: @escaping PageEvent) throws {
        self.audio = audio
        self.directory = directory
        self.emit = emit
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    func begin(id: String, api: DeepSeek, voice: String) {
        stop(notify: false)
        speech = (id, api, voice)
    }

    func append(_ text: String) {
        guard speech != nil else { return }
        pending += text
        while let edge = pending.firstIndex(where: { "。！？!?\n".contains($0) }) ?? (pending.count > 180 ? pending.index(pending.startIndex, offsetBy: 179) : nil) {
            let text = String(pending[...edge]).trimmed
            pending.removeSubrange(...edge)
            if !text.isEmpty { pieces.append(text) }
        }
        drain()
    }

    func finish() {
        guard speech != nil else { return }
        if !pending.trimmed.isEmpty { pieces.append(pending.trimmed) }
        pending = ""
        sealed = true
        drain()
    }

    func stop(notify: Bool = true) {
        let wasActive = speech != nil
        generation = UUID()
        task?.cancel()
        task = nil
        audio.stopPlayback()
        speech = nil
        pending = ""
        pieces.removeAll()
        sealed = false
        started = false
        if wasActive && notify { emit("onTtsStopped", []) }
    }

    private func drain() {
        guard task == nil, let speech else { return }
        if pieces.isEmpty {
            if sealed { self.speech = nil; emit("onTtsDone", []) }
            return
        }
        let generation = generation
        task = Task {
            do {
                while self.generation == generation, !pieces.isEmpty {
                    let next = pieces.removeFirst()
                    let bytes = try await load(next, api: speech.api, voice: speech.voice)
                    guard self.generation == generation else { return }
                    if !started { started = true; emit("onTtsStart", []) }
                    try await audio.play(bytes)
                }
                if self.generation == generation { task = nil; drain() }
            } catch {
                guard self.generation == generation else { return }
                stop(notify: false)
                emit("onTtsError", [.string(speech.id), .string(error.localizedDescription)])
            }
        }
    }

    private func load(_ text: String, api: DeepSeek, voice: String) async throws -> Data {
        let digest = SHA256.hash(data: Data("\(voice)\n\(text)".utf8)).map { String(format: "%02x", $0) }.joined()
        let file = directory.appendingPathComponent(digest + ".wav")
        if FileManager.default.fileExists(atPath: file.path) { return try Data(contentsOf: file) }
        let bytes = try await api.speech(text, voice: voice)
        try bytes.write(to: file, options: .atomic)
        return bytes
    }

    func clearCache() throws {
        for file in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) where file.pathExtension == "wav" {
            try FileManager.default.removeItem(at: file)
        }
    }
}
