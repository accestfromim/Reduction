import AppKit
import MetalKit
import OSLog
import ScreenCaptureKit

final class PetLens: NSObject, SCStreamOutput, SCStreamDelegate, MTKViewDelegate {
    enum Pose: UInt32 { case asleep, idle, listening, thinking, speaking }

    private struct Target: Equatable {
        let displayID: CGDirectDisplayID
        let windowID: CGWindowID
        let frame: CGRect
        let scale: CGFloat

        init?(window: NSWindow) {
            guard window.windowNumber > 0, let screen = window.screen,
                  let id = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber else { return nil }
            displayID = id.uint32Value
            windowID = CGWindowID(window.windowNumber)
            frame = screen.frame
            scale = screen.backingScaleFactor
        }
    }

    struct Parameters {
        var desktopRect: SIMD4<Float>
        var time: Float
        var transition: Float
        var pose: UInt32

        static func desktopRect(for pet: CGRect, on display: CGRect) -> SIMD4<Float> {
            SIMD4(Float((pet.minX - display.minX) / display.width),
                  Float((display.maxY - pet.maxY) / display.height),
                  Float(pet.width / display.width), Float(pet.height / display.height))
        }
    }

    private struct Frame {
        let pixels: CVPixelBuffer
        let reference: CVMetalTexture
        let texture: MTLTexture
        let number: Int
    }

    private final class Surface: MTKView {
        override var isOpaque: Bool { false }
        override func hitTest(_ point: NSPoint) -> NSView? { nil }
    }

    private static let log = Logger(subsystem: "com.mindtravel.reduction.macos", category: "PetLens")
    private let surface: Surface
    private let pipeline: MTLRenderPipelineState
    private let queue: MTLCommandQueue
    private let cache: CVMetalTextureCache
    private let began = CACurrentMediaTime()
    private var poseBegan = CACurrentMediaTime()
    private var desired: Target?
    private var current: Target?
    private var pending: Task<Void, Never>?
    private var stream: SCStream?
    private var frame: Frame?
    private var inFlight = 0
    private var captured = 0
    private var rendered = 0
    private var loggedUpdate = false
    private var active = false
    var onActive: (Bool) -> Void = { _ in }
    var onError: (Error) -> Void = { _ in }

    var pose = Pose.asleep {
        didSet { if oldValue != pose { poseBegan = CACurrentMediaTime() } }
    }

    init(in container: NSView) throws {
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue(),
              let source = Bundle.main.url(forResource: "PetLens", withExtension: "metal") else {
            throw LensError.graphicsUnavailable
        }
        var cache: CVMetalTextureCache?
        guard CVMetalTextureCacheCreate(nil, nil, device, nil, &cache) == kCVReturnSuccess, let cache else {
            throw LensError.graphicsUnavailable
        }
        self.pipeline = try Self.makePipeline(device: device, source: source)
        self.queue = queue
        self.cache = cache
        surface = Surface(frame: container.bounds, device: device)
        super.init()
        surface.colorPixelFormat = .bgra8Unorm
        surface.colorspace = CGColorSpace(name: CGColorSpace.sRGB)
        surface.clearColor = MTLClearColorMake(0, 0, 0, 0)
        surface.layer?.isOpaque = false
        surface.preferredFramesPerSecond = 60
        surface.autoresizingMask = [.width, .height]
        surface.isHidden = true
        surface.isPaused = true
        surface.delegate = self
        container.addSubview(surface)
    }

    static func makePipeline(device: MTLDevice, source: URL) throws -> MTLRenderPipelineState {
        let library = try device.makeLibrary(source: String(contentsOf: source, encoding: .utf8), options: nil)
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = library.makeFunction(name: "petLensVertex")
        descriptor.fragmentFunction = library.makeFunction(name: "petLensFragment")
        descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm
        return try device.makeRenderPipelineState(descriptor: descriptor)
    }

    func update(window: NSWindow?) {
        let target = window.flatMap(Target.init)
        guard target != desired else { return }
        desired = target
        setActive(false)
        scheduleReconcile()
    }

    private func scheduleReconcile() {
        if pending == nil {
            pending = Task { @MainActor [weak self] in await self?.reconcile() }
        }
    }

    @MainActor
    private func reconcile() async {
        defer { pending = nil }
        while true {
            if let previous = stream {
                stream = nil
                current = nil
                do { try await previous.stopCapture() }
                catch { Self.log.error("结束屏幕采集时发生错误：\(error.localizedDescription, privacy: .public)") }
            }
            guard let target = desired else { return }
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
                guard desired == target else { continue }
                guard let display = content.displays.first(where: { $0.displayID == target.displayID }),
                      let window = content.windows.first(where: { $0.windowID == target.windowID }) else {
                    throw LensError.windowUnavailable
                }
                let filter = SCContentFilter(display: display, excludingWindows: [window])
                let config = SCStreamConfiguration()
                config.width = Int((filter.contentRect.width * CGFloat(filter.pointPixelScale)).rounded())
                config.height = Int((filter.contentRect.height * CGFloat(filter.pointPixelScale)).rounded())
                guard config.width > 0, config.height > 0 else { throw LensError.windowUnavailable }
                config.pixelFormat = kCVPixelFormatType_32BGRA
                config.colorSpaceName = CGColorSpace.sRGB
                config.minimumFrameInterval = CMTime(value: 1, timescale: 60)
                config.queueDepth = 3
                config.showsCursor = false
                config.capturesAudio = false
                let next = SCStream(filter: filter, configuration: config, delegate: self)
                try next.addStreamOutput(self, type: .screen, sampleHandlerQueue: .main)
                stream = next
                current = target
                captured = 0
                rendered = 0
                loggedUpdate = false
                try await next.startCapture()
                if desired == target { return }
            } catch {
                stream = nil
                current = nil
                if desired != target { continue }
                fail(error)
                return
            }
        }
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sample: CMSampleBuffer, of type: SCStreamOutputType) {
        guard self.stream === stream, desired == current, type == .screen,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = attachments.first?[.status] as? Int, let status = SCFrameStatus(rawValue: raw) else { return }
        switch status {
        case .complete:
            guard let pixels = sample.imageBuffer else { return }
            var reference: CVMetalTexture?
            let width = CVPixelBufferGetWidth(pixels), height = CVPixelBufferGetHeight(pixels)
            let result = CVMetalTextureCacheCreateTextureFromImage(nil, cache, pixels, nil, .bgra8Unorm, width, height, 0, &reference)
            guard result == kCVReturnSuccess, let reference, let texture = CVMetalTextureGetTexture(reference) else {
                fail(LensError.textureUnavailable(result))
                return
            }
            captured += 1
            frame = Frame(pixels: pixels, reference: reference, texture: texture, number: captured)
            setActive(true)
            if captured == 1 { Self.log.notice("桌宠收到屏幕采集帧：\(width, privacy: .public)×\(height, privacy: .public)") }
        case .blank, .suspended, .stopped:
            setActive(false)
        default:
            break
        }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.stream === stream else { return }
            self.stream = nil
            self.current = nil
            self.fail(error)
        }
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        guard inFlight < 2, let frame, let display = current, let window = view.window,
              let drawable = view.currentDrawable, let pass = view.currentRenderPassDescriptor,
              let command = queue.makeCommandBuffer(), let encoder = command.makeRenderCommandEncoder(descriptor: pass) else { return }
        let time = CACurrentMediaTime()
        var params = Parameters(desktopRect: Parameters.desktopRect(for: window.frame, on: display.frame),
                                time: Float(time - began), transition: min(1, Float((time - poseBegan) * 2.4)), pose: pose.rawValue)
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(frame.texture, index: 0)
        encoder.setFragmentBytes(&params, length: MemoryLayout<Parameters>.stride, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        inFlight += 1
        let stream = self.stream
        command.addCompletedHandler { [weak self, frame] command in
            let succeeded = command.status == .completed
            let error = command.error
            DispatchQueue.main.async {
                guard let self else { return }
                self.inFlight -= 1
                guard self.stream === stream else { return }
                if let error {
                    self.fail(error)
                } else if succeeded && frame.number > self.rendered {
                    if self.rendered == 0 { Self.log.notice("桌宠已用 Metal 渲染实时折射") }
                    else if !self.loggedUpdate {
                        self.loggedUpdate = true
                        Self.log.notice("桌宠折射已跟随桌面画面实时更新")
                    } else if frame.number / 60 > self.rendered / 60 {
                        Self.log.info("桌宠已渲染到第 \(frame.number, privacy: .public) 个屏幕帧")
                    }
                    self.rendered = frame.number
                }
            }
        }
        command.present(drawable)
        command.commit()
    }

    private func setActive(_ active: Bool) {
        if !active {
            frame = nil
            CVMetalTextureCacheFlush(cache, 0)
        }
        guard self.active != active else { return }
        self.active = active
        surface.isHidden = !active
        surface.isPaused = !active
        onActive(active)
    }

    private func fail(_ error: Error) {
        setActive(false)
        desired = nil
        if stream != nil { scheduleReconcile() }
        Self.log.error("桌宠折射停止：\(error.localizedDescription, privacy: .public)")
        onError(error)
    }

    private enum LensError: LocalizedError {
        case graphicsUnavailable, windowUnavailable, textureUnavailable(CVReturn)

        var errorDescription: String? {
            switch self {
            case .graphicsUnavailable: return "无法初始化桌宠的 Metal 渲染"
            case .windowUnavailable: return "屏幕采集找不到桌宠窗口或所在的显示器"
            case .textureUnavailable(let status): return "屏幕帧无法转为 Metal 纹理（\(status)）"
            }
        }
    }
}
