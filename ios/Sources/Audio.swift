import AVFoundation
import Foundation

typealias PageEvent = (String, [JSON]) -> Void

@MainActor
final class PhoneAudio: NSObject, AVAudioRecorderDelegate {
    private struct ActiveRecording {
        let id: String
        let file: URL
        let recorder: AVAudioRecorder
    }

    private let directory: URL
    private let session = AVAudioSession.sharedInstance()
    private var active: ActiveRecording?
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
    }

    private func deactivate() {
        guard active == nil else { return }
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }
}

@MainActor
final class PhoneSpeech: NSObject, AVSpeechSynthesizerDelegate {
    private let emit: PageEvent
    private let legacyCache: URL
    private var synthesizer: AVSpeechSynthesizer?
    private var pending = ""
    private var queued = [ObjectIdentifier: AVSpeechUtterance]()
    private var sealed = false
    private var started = false

    init(legacyCache: URL, emit: @escaping PageEvent) {
        self.emit = emit
        self.legacyCache = legacyCache
        super.init()
    }

    func begin() {
        stop(notify: false)
        let voice = AVSpeechSynthesizer()
        voice.usesApplicationAudioSession = false
        voice.delegate = self
        synthesizer = voice
    }

    func append(_ text: String) {
        guard synthesizer != nil else { return }
        pending += text
        while let edge = pending.firstIndex(where: { "。！？!?\n".contains($0) }) ?? (pending.count > 180 ? pending.index(pending.startIndex, offsetBy: 179) : nil) {
            enqueue(String(pending[...edge]))
            pending.removeSubrange(...edge)
        }
    }

    private func enqueue(_ text: String) {
        guard let synthesizer, !text.trimmed.isEmpty else { return }
        let utterance = AVSpeechUtterance(string: text.trimmed)
        utterance.voice = AVSpeechSynthesisVoice(language: "zh-CN")
        queued[ObjectIdentifier(utterance)] = utterance
        synthesizer.speak(utterance)
    }

    func finish() {
        guard synthesizer != nil else { return }
        enqueue(pending)
        pending = ""
        sealed = true
        completeIfDrained()
    }

    private func completeIfDrained() {
        if sealed && queued.isEmpty {
            stop(notify: false)
            emit("onTtsDone", [])
        }
    }

    func stop(notify: Bool = true) {
        let previous = synthesizer
        synthesizer = nil
        previous?.delegate = nil
        previous?.stopSpeaking(at: .immediate)
        pending = ""
        queued.removeAll()
        sealed = false
        started = false
        if previous != nil && notify { emit("onTtsStopped", []) }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in
            guard queued[id] != nil, !started else { return }
            started = true
            emit("onTtsStart", [])
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in
            guard queued.removeValue(forKey: id) != nil else { return }
            completeIfDrained()
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in
            if queued[id] != nil { stop() }
        }
    }

    func clearCache() throws {
        if FileManager.default.fileExists(atPath: legacyCache.path) {
            try FileManager.default.removeItem(at: legacyCache)
        }
    }
}
