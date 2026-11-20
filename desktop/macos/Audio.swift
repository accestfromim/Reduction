import AVFoundation
import Darwin
import Foundation

enum MacAudio {
    private static let playRate = 24_000.0
    private static let recordRate = 16_000.0

    static func play() throws {
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let format = AVAudioFormat(standardFormatWithSampleRate: playRate, channels: 1)!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        try engine.start()
        player.play()

        let playing = DispatchGroup()
        let available = DispatchSemaphore(value: 6)
        var extra = Data()
        var chunk = [UInt8](repeating: 0, count: 4096)
        while true {
            let length = chunk.withUnsafeMutableBytes { read(STDIN_FILENO, $0.baseAddress, $0.count) }
            if length == 0 { break }
            if length < 0 && errno == EINTR { continue }
            guard length > 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
            var bytes = extra
            bytes.append(contentsOf: chunk.prefix(length))
            let count = bytes.count / 2
            extra = Data(bytes.dropFirst(count * 2))
            guard count > 0 else { continue }

            let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(count))!
            buffer.frameLength = AVAudioFrameCount(count)
            let samples = buffer.floatChannelData![0]
            bytes.withUnsafeBytes { raw in
                for index in 0..<count {
                    let value = raw.loadUnaligned(fromByteOffset: index * 2, as: Int16.self)
                    samples[index] = Float(Int16(littleEndian: value)) / 32768
                }
            }
            available.wait()
            playing.enter()
            player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { _ in
                playing.leave()
                available.signal()
            }
        }
        guard extra.isEmpty else { throw AudioError.incompleteSample }
        playing.wait()
        engine.stop()
    }

    static func record() throws {
        guard AVCaptureDevice.authorizationStatus(for: .audio) == .authorized else {
            throw AudioError.microphonePermission
        }
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let actual = input.outputFormat(forBus: 0)
        let pcm = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: recordRate, channels: 1, interleaved: false)!
        guard actual.sampleRate > 0, actual.channelCount > 0,
              let converter = AVAudioConverter(from: actual, to: pcm) else { throw AudioError.inputUnavailable }

        input.installTap(onBus: 0, bufferSize: 1024, format: actual) { buffer, _ in
            let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * recordRate / actual.sampleRate)) + 64
            let output = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: capacity)!
            var providedInput = false
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { _, state in
                if providedInput {
                    state.pointee = .noDataNow
                    return nil
                }
                providedInput = true
                state.pointee = .haveData
                return buffer
            }
            guard status != .error else { Reduction.fail(error?.localizedDescription ?? "无法转换麦克风音频") }
            guard let samples = output.int16ChannelData?[0], output.frameLength > 0 else { return }
            do {
                let bytes = Data(bytes: samples, count: Int(output.frameLength) * MemoryLayout<Int16>.size)
                try FileHandle.standardOutput.write(contentsOf: bytes)
            } catch {
                Reduction.fail(error.localizedDescription)
            }
        }
        try engine.start()
        defer {
            engine.stop()
            input.removeTap(onBus: 0)
        }
        _ = try FileHandle.standardInput.readToEnd()
    }

    enum AudioError: LocalizedError {
        case incompleteSample, inputUnavailable, microphonePermission

        var errorDescription: String? {
            switch self {
            case .incompleteSample: return "收到不完整的 PCM 采样"
            case .inputUnavailable: return "当前输入设备无法提供麦克风音频"
            case .microphonePermission: return "请在系统设置中允许 Reduction 使用麦克风"
            }
        }
    }
}
