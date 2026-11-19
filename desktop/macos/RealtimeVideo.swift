@preconcurrency import AVFoundation
import CoreImage
import ImageIO
import OSLog
import QuartzCore
import ScreenCaptureKit

func requestRealtimeMedia(_ type: AVMediaType) async -> Bool {
    switch AVCaptureDevice.authorizationStatus(for: type) {
    case .authorized: return true
    case .notDetermined: return await AVCaptureDevice.requestAccess(for: type)
    default: return false
    }
}

@MainActor
final class RealtimeVideo: NSObject, SCContentSharingPickerObserver, SCStreamDelegate {
    typealias Picture = RealtimeConnection.Picture

    var onChange: () -> Void = {}
    var onFrame: (Data?, Picture) -> Void = { _, _ in }
    var onError: (String) -> Void = { _ in }
    private(set) var picture: Picture?
    private(set) var isSelectingWindow = false
    var isChoosing: Bool { pending != nil }

    private let log = Logger(subsystem: "com.mindtravel.reduction.macos", category: "RealtimeCall")
    private var pending: UUID?
    private var observingPicker = false
    private var sampler: RealtimeFrameSampler?
    private var camera: AVCaptureSession?
    private var cameraObserver: NSObjectProtocol?
    private var screen: SCStream?
    private var screenStarted = false

    func chooseCamera() {
        guard !isChoosing else { return }
        if picture == .camera { stop(); return }
        let request = UUID()
        pending = request
        onChange()
        Task { [weak self] in
            let allowed = await requestRealtimeMedia(.video)
            guard let self, self.pending == request else { return }
            guard allowed else {
                self.completeWithError("请在系统设置的「摄像头」中允许 Reduction。")
                return
            }
            guard let device = AVCaptureDevice.default(for: .video) else {
                self.completeWithError("找不到可用的摄像头。")
                return
            }
            self.startCamera(device, request: request)
        }
    }

    func chooseWindow(excluding windowID: CGWindowID) {
        guard !isChoosing else { return }
        pending = UUID()
        isSelectingWindow = true
        let picker = SCContentSharingPicker.shared
        var configuration = SCContentSharingPickerConfiguration()
        configuration.allowedPickerModes = [.singleWindow]
        configuration.excludedWindowIDs = [Int(windowID)]
        configuration.allowsChangingSelectedContent = false
        picker.defaultConfiguration = configuration
        // 系统也计入桌宠的本地采集。换窗口时，已共享的窗口仍占一个名额。
        picker.maximumStreamCount = 3
        if !observingPicker {
            picker.add(self)
            observingPicker = true
        }
        picker.isActive = true
        picker.present(using: .window)
        log.notice("实时通话已打开窗口选择器")
        onChange()
    }

    func stop() {
        pending = nil
        isSelectingWindow = false
        stopCapture()
        stopPicker()
        onChange()
    }

    nonisolated func contentSharingPicker(_ picker: SCContentSharingPicker, didCancelFor stream: SCStream?) {
        Task { @MainActor [weak self] in
            guard let self, self.isSelectingWindow else { return }
            self.isSelectingWindow = false
            self.pending = nil
            if self.screen == nil { self.stopPicker() }
            self.onChange()
        }
    }

    nonisolated func contentSharingPicker(_ picker: SCContentSharingPicker, didUpdateWith filter: SCContentFilter, for stream: SCStream?) {
        Task { @MainActor [weak self] in
            guard let self, self.isSelectingWindow, let request = self.pending else { return }
            self.isSelectingWindow = false
            guard filter.style == .window else {
                self.completeWithError("请选择一个窗口进行共享。")
                return
            }
            self.startWindow(filter, request: request)
        }
    }

    nonisolated func contentSharingPickerStartDidFailWithError(_ error: Error) {
        Task { @MainActor [weak self] in
            guard let self, self.isSelectingWindow else { return }
            self.completeWithError("无法打开窗口选择器。\n\(error.localizedDescription)")
        }
    }

    nonisolated func stream(_ stream: SCStream, didStopWithError error: Error) {
        Task { @MainActor [weak self] in
            guard let self, self.screen === stream else { return }
            self.stop()
            self.onError("窗口共享已停止。\n\(error.localizedDescription)")
        }
    }

    private func startCamera(_ device: AVCaptureDevice, request: UUID) {
        stopCapture()
        stopPicker()
        onChange()
        let session = AVCaptureSession()
        let sampler = makeSampler(.camera)
        camera = session
        cameraObserver = NotificationCenter.default.addObserver(
            forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: .main
        ) { [weak self, weak session] _ in
            MainActor.assumeIsolated {
                guard let self, let session, self.camera === session else { return }
                self.stop()
                self.onError("摄像头已停止。请检查设备后重新开启。")
            }
        }
        sampler.queue.async { [weak self] in
            do {
                let input = try AVCaptureDeviceInput(device: device)
                let output = AVCaptureVideoDataOutput()
                output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
                output.alwaysDiscardsLateVideoFrames = true
                guard session.canAddInput(input), session.canAddOutput(output) else {
                    throw RealtimeCallError("摄像头无法提供通话画面。")
                }
                session.addInput(input)
                session.addOutput(output)
                output.setSampleBufferDelegate(sampler, queue: sampler.queue)
                session.startRunning()
                guard session.isRunning else { throw RealtimeCallError("摄像头无法启动。") }
                Task { @MainActor [weak self] in
                    guard let self, self.pending == request, self.camera === session else { return }
                    self.pending = nil
                    self.picture = .camera
                    self.onChange()
                    self.log.notice("实时通话已开启摄像头")
                }
            } catch {
                Task { @MainActor [weak self] in
                    guard let self, self.pending == request else { return }
                    self.stop()
                    self.onError(error.localizedDescription)
                }
            }
        }
    }

    private func startWindow(_ filter: SCContentFilter, request: UUID) {
        stopCapture()
        onChange()
        let resolution = RealtimeFrameSampler.resolution(filter.contentRect.size)
        let configuration = SCStreamConfiguration()
        configuration.width = Int(resolution.width)
        configuration.height = Int(resolution.height)
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: 2)
        configuration.queueDepth = 3
        configuration.capturesAudio = false
        configuration.showsCursor = false
        let stream = SCStream(filter: filter, configuration: configuration, delegate: self)
        let sampler = makeSampler(.window)
        screen = stream
        picture = .window
        onChange()
        Task { [weak self] in
            do {
                try stream.addStreamOutput(sampler, type: .screen, sampleHandlerQueue: sampler.queue)
                try await stream.startCapture()
                guard let self, self.pending == request, self.screen === stream else {
                    try await stream.stopCapture()
                    return
                }
                self.screenStarted = true
                self.pending = nil
                self.onChange()
                self.log.notice("实时通话已开启窗口共享")
            } catch {
                guard let self, self.pending == request else { return }
                self.stop()
                self.onError("无法共享此窗口。\n\(error.localizedDescription)")
            }
        }
    }

    private func makeSampler(_ kind: Picture) -> RealtimeFrameSampler {
        let sampler = RealtimeFrameSampler { [weak self] source, result in
            Task { @MainActor [weak self] in
                guard let self, self.sampler === source, self.picture == kind else { return }
                switch result {
                case .success(let jpeg): self.onFrame(jpeg, kind)
                case .failure(let error):
                    self.stop()
                    self.onError(error.localizedDescription)
                }
            }
        }
        self.sampler = sampler
        return sampler
    }

    private func stopCapture() {
        let queue = sampler?.queue
        sampler = nil
        picture = nil
        if let cameraObserver { NotificationCenter.default.removeObserver(cameraObserver) }
        cameraObserver = nil
        if let camera {
            self.camera = nil
            queue?.async { camera.stopRunning() }
        }
        if let screen {
            self.screen = nil
            if screenStarted {
                let log = self.log
                Task {
                    do { try await screen.stopCapture() }
                    catch { log.error("结束窗口采集时发生错误：\(error.localizedDescription, privacy: .public)") }
                }
            }
        }
        screenStarted = false
    }

    private func stopPicker() {
        guard observingPicker else { return }
        let picker = SCContentSharingPicker.shared
        picker.remove(self)
        picker.isActive = false
        observingPicker = false
    }

    private func completeWithError(_ message: String) {
        pending = nil
        isSelectingWindow = false
        if screen == nil { stopPicker() }
        onChange()
        onError(message)
    }
}

// 摄像头和窗口的采集回调都在各自的串行队列上编码。
private final class RealtimeFrameSampler: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, SCStreamOutput, @unchecked Sendable {
    let queue = DispatchQueue(label: "com.mindtravel.reduction.realtime-video")
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    private let consume: (RealtimeFrameSampler, Result<Data?, RealtimeCallError>) -> Void
    private var lastCameraFrame: CFTimeInterval = -.infinity

    init(consume: @escaping (RealtimeFrameSampler, Result<Data?, RealtimeCallError>) -> Void) { self.consume = consume }

    func captureOutput(_ output: AVCaptureOutput, didOutput buffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = CACurrentMediaTime()
        guard now - lastCameraFrame >= 0.5 else { return }
        lastCameraFrame = now
        encode(buffer)
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer buffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let code = attachments.first?[.status] as? Int, let status = SCFrameStatus(rawValue: code) else { return }
        switch status {
        case .started, .complete: encode(buffer)
        case .blank, .suspended, .stopped: consume(self, .success(nil))
        case .idle: break
        @unknown default: break
        }
    }

    static func resolution(_ size: CGSize) -> CGSize {
        guard size.width > 0, size.height > 0 else { return CGSize(width: 1, height: 1) }
        let scale = min(1, 1280 / max(size.width, size.height), 720 / min(size.width, size.height))
        return CGSize(width: max(1, (size.width * scale).rounded(.down)),
                      height: max(1, (size.height * scale).rounded(.down)))
    }

    private func encode(_ buffer: CMSampleBuffer) {
        guard buffer.isValid, let pixels = buffer.imageBuffer else { return }
        let image = CIImage(cvPixelBuffer: pixels)
        guard !image.extent.isEmpty else { return }
        let resolution = Self.resolution(image.extent.size)
        let scale = min(resolution.width / image.extent.width, resolution.height / image.extent.height)
        let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let quality = CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String)
        guard let jpeg = context.jpegRepresentation(of: scaled, colorSpace: colorSpace, options: [quality: 0.72]) else {
            consume(self, .failure(RealtimeCallError("无法编码共享画面。")))
            return
        }
        consume(self, .success(jpeg))
    }
}
