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

            let buffer = try playbackBuffer(Data(bytes.prefix(count * 2)), format: format)
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
            do {
                let bytes = try pcmBytes(from: buffer, using: converter)
                if !bytes.isEmpty { try FileHandle.standardOutput.write(contentsOf: bytes) }
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

    static func playbackBuffer(_ bytes: Data, format: AVAudioFormat) throws -> AVAudioPCMBuffer {
        guard bytes.count >= 2, bytes.count.isMultiple(of: 2),
              let count = AVAudioFrameCount(exactly: bytes.count / 2),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: count),
              let samples = buffer.floatChannelData?[0] else { throw AudioError.incompleteSample }
        buffer.frameLength = count
        bytes.withUnsafeBytes { raw in
            for index in 0..<Int(count) {
                let value = raw.loadUnaligned(fromByteOffset: index * 2, as: Int16.self)
                samples[index] = Float(Int16(littleEndian: value)) / 32768
            }
        }
        return buffer
    }

    static func pcmBytes(from buffer: AVAudioPCMBuffer, using converter: AVAudioConverter) throws -> Data {
        let ratio = converter.outputFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * ratio)) + 64
        guard let output = AVAudioPCMBuffer(pcmFormat: converter.outputFormat, frameCapacity: capacity) else {
            throw AudioError.inputUnavailable
        }
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
        if status == .error {
            if let error { throw error }
            throw AudioError.inputUnavailable
        }
        guard output.frameLength > 0 else { return Data() }
        guard let samples = output.int16ChannelData?[0] else { throw AudioError.inputUnavailable }
        return Data(bytes: samples, count: Int(output.frameLength) * MemoryLayout<Int16>.size)
    }

    enum AudioError: LocalizedError {
        case incompleteSample, inputUnavailable, outputUnavailable, microphonePermission

        var errorDescription: String? {
            switch self {
            case .incompleteSample: return "收到不完整的 PCM 采样"
            case .inputUnavailable: return "当前输入设备无法提供麦克风音频"
            case .outputUnavailable: return "当前无法连接系统默认的声音输出设备"
            case .microphonePermission: return "请在系统设置中允许 Reduction 使用麦克风"
            }
        }
    }
}
