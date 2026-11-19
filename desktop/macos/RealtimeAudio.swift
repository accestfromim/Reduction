import AVFoundation
import CoreAudio
import Foundation

@MainActor
final class RealtimeAudio {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let playbackFormat = AVAudioFormat(standardFormatWithSampleRate: 24_000, channels: 1)!
    private let onFrame: (Data) -> Void
    private let onError: (Error) -> Void
    private var input: PCMFrames?
    private var timer: Timer?

    init(onFrame: @escaping (Data) -> Void, onError: @escaping (Error) -> Void) throws {
        self.onFrame = onFrame
        self.onError = onError
        let useVoiceProcessing = try !Self.outputUsesBluetooth()
        let microphone = engine.inputNode
        if useVoiceProcessing {
            try microphone.setVoiceProcessingEnabled(true)
            microphone.voiceProcessingOtherAudioDuckingConfiguration =
                AVAudioVoiceProcessingOtherAudioDuckingConfiguration(enableAdvancedDucking: true, duckingLevel: .min)
        }
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: playbackFormat)
        if useVoiceProcessing {
            let format = microphone.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else {
                throw MacAudio.AudioError.inputUnavailable
            }
            // 语音处理要求两端格式一致。蓝牙耳机走系统混音，保留设备的输出格式和音量。
            engine.connect(engine.mainMixerNode, to: engine.outputNode, format: format)
        }
        try startInput()
        do { try engine.start() }
        catch {
            stop()
            throw error
        }
    }

    private static func outputUsesBluetooth() throws -> Bool {
        var property = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                                                 mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        var device = AudioDeviceID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout.size(ofValue: device))
        let system = AudioObjectID(kAudioObjectSystemObject)
        guard AudioObjectGetPropertyData(system, &property, 0, nil, &size, &device) == noErr,
              device != kAudioObjectUnknown else {
            throw MacAudio.AudioError.outputUnavailable
        }

        property.mSelector = kAudioDevicePropertyTransportType
        var transport: UInt32 = kAudioDeviceTransportTypeUnknown
        size = UInt32(MemoryLayout.size(ofValue: transport))
        guard AudioObjectGetPropertyData(device, &property, 0, nil, &size, &transport) == noErr else {
            throw MacAudio.AudioError.outputUnavailable
        }
        return transport == kAudioDeviceTransportTypeBluetooth || transport == kAudioDeviceTransportTypeBluetoothLE
    }

    func startInput() throws {
        guard input == nil else { return }
        let node = engine.inputNode
        let actual = node.outputFormat(forBus: 0)
        let mono = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: false)!
        guard actual.sampleRate > 0, actual.channelCount > 0,
              let converter = AVAudioConverter(from: actual, to: mono) else { throw MacAudio.AudioError.inputUnavailable }
        let frames = PCMFrames(converter: converter)
        input = frames
        node.installTap(onBus: 0, bufferSize: 1024, format: actual) { [weak self] buffer, _ in
            do { try frames.append(buffer) }
            catch {
                DispatchQueue.main.async {
                    guard let self, self.input === frames else { return }
                    self.onError(error)
                }
            }
        }
        let timer = Timer(timeInterval: 0.020, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let frame = self.input?.take() else { return }
                self.onFrame(frame)
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func stopInput() {
        timer?.invalidate()
        timer = nil
        guard input != nil else { return }
        input = nil
        engine.inputNode.removeTap(onBus: 0)
    }

    func play(_ bytes: Data) throws {
        guard !bytes.isEmpty else { return }
        let buffer = try MacAudio.playbackBuffer(bytes, format: playbackFormat)
        player.scheduleBuffer(buffer)
        if !player.isPlaying { player.play() }
    }

    func interruptPlayback() { player.stop() }

    func stop() {
        stopInput()
        interruptPlayback()
        engine.stop()
    }
}

private final class PCMFrames {
    private let converter: AVAudioConverter
    private let lock = NSLock()
    private var remainder = Data()
    private var ready: [Data] = []

    init(converter: AVAudioConverter) { self.converter = converter }

    func append(_ buffer: AVAudioPCMBuffer) throws {
        remainder.append(try MacAudio.pcmBytes(from: buffer, using: converter))
        let count = remainder.count / 640
        guard count > 0 else { return }
        let frames = (0..<count).map { index in
            let start = remainder.startIndex + index * 640
            return remainder.subdata(in: start..<(start + 640))
        }
        remainder.removeFirst(count * 640)
        lock.withLock {
            ready.append(contentsOf: frames)
            if ready.count > 10 { ready.removeFirst(ready.count - 10) }
        }
    }

    func take() -> Data? {
        lock.withLock { ready.isEmpty ? nil : ready.removeFirst() }
    }
}
