import AppKit
import ImageIO
import UniformTypeIdentifiers
import WebKit

final class MacArticle: NSObject, WKNavigationDelegate {
    private let view: WKWebView
    private let page: URL
    private let image: URL
    private let preview: URL
    private let width = 540
    private var height = 0
    private var full: CGContext?
    private var top: CGContext?

    static func render(_ paths: ArraySlice<String>) -> Never {
        guard paths.count == 3 else { Reduction.fail("文章导出需要页面、长图和预览图路径") }
        let files = paths.map { URL(fileURLWithPath: $0) }
        let app = NSApplication.shared
        app.setActivationPolicy(.prohibited)
        let renderer = MacArticle(page: files[0], image: files[1], preview: files[2])
        renderer.view.loadFileURL(files[0], allowingReadAccessTo: files[0])
        Timer.scheduledTimer(withTimeInterval: 75, repeats: false) { _ in
            Reduction.fail("WebKit 导出文章超时")
        }
        withExtendedLifetime(renderer) { app.run() }
        Reduction.fail("WebKit 退出时文章尚未导出")
    }

    private init(page: URL, image: URL, preview: URL) {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        view = WKWebView(frame: CGRect(x: 0, y: 0, width: 540, height: 800), configuration: configuration)
        self.page = page
        self.image = image
        self.preview = preview
        super.init()
        view.navigationDelegate = self
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        decisionHandler(action.request.url == page ? .allow : .cancel)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        measure()
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        Reduction.fail(error.localizedDescription)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        Reduction.fail("WebKit 文章进程提前退出")
    }

    private func measure() {
        view.evaluateJavaScript("Number(document.documentElement.dataset.articleHeight || 0)") { [weak self] value, error in
            guard let self else { return }
            if let error { Reduction.fail(error.localizedDescription) }
            guard let number = value as? NSNumber else { Reduction.fail("WebKit 没有返回文章高度") }
            let measured = number.intValue
            guard measured >= 0 && measured <= 60_000 else { Reduction.fail("文章高度超出导出范围") }
            if measured == 0 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in self?.measure() }
                return
            }
            self.height = measured
            self.full = self.canvas(width: self.width * 2, height: measured * 2)
            self.top = self.canvas(width: self.width * 3 / 2, height: min(measured, 1800) * 3 / 2)
            self.view.setFrameSize(NSSize(width: self.width, height: measured))
            DispatchQueue.main.async { self.capture(from: 0) }
        }
    }

    private func canvas(width: Int, height: Int) -> CGContext {
        guard let canvas = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
            Reduction.fail("无法分配文章图像内存")
        }
        canvas.setFillColor(CGColor(gray: 1, alpha: 1))
        canvas.fill(CGRect(x: 0, y: 0, width: width, height: height))
        return canvas
    }

    private func capture(from start: Int) {
        let length = min(2048, height - start)
        let region = WKPDFConfiguration()
        region.rect = CGRect(x: 0, y: start, width: width, height: length)
        view.createPDF(configuration: region) { [weak self] result in
            guard let self, let full = self.full, let top = self.top else { return }
            switch result {
            case .failure(let error): Reduction.fail(error.localizedDescription)
            case .success(let data):
                guard let provider = CGDataProvider(data: data as CFData), let document = CGPDFDocument(provider),
                      document.numberOfPages == 1, let frame = document.page(at: 1) else { Reduction.fail("无法读取 WebKit 的文章页面") }
                self.paint(frame, on: full, from: start, length: length, scale: 2)
                if start < 1800 { self.paint(frame, on: top, from: start, length: length, scale: 1.5) }
                if start + length < self.height {
                    self.capture(from: start + length)
                } else {
                    self.save(full, to: self.image, format: .png)
                    self.save(top, to: self.preview, format: .jpeg)
                    print(self.height)
                    exit(EXIT_SUCCESS)
                }
            }
        }
    }

    private func paint(_ page: CGPDFPage, on canvas: CGContext, from start: Int, length: Int, scale: CGFloat) {
        let region = CGRect(x: 0, y: CGFloat(canvas.height) - CGFloat(start + length) * scale,
                            width: CGFloat(canvas.width), height: CGFloat(length) * scale)
        canvas.saveGState()
        canvas.clip(to: region)
        canvas.concatenate(page.getDrawingTransform(.mediaBox, rect: region, rotate: 0, preserveAspectRatio: false))
        canvas.drawPDFPage(page)
        canvas.restoreGState()
    }

    private func save(_ canvas: CGContext, to url: URL, format: UTType) {
        guard let picture = canvas.makeImage(), let writer = CGImageDestinationCreateWithURL(url as CFURL, format.identifier as CFString, 1, nil) else {
            Reduction.fail("无法创建文章图像文件")
        }
        let options = [kCGImageDestinationLossyCompressionQuality: 0.82] as CFDictionary
        CGImageDestinationAddImage(writer, picture, format == .jpeg ? options : nil)
        guard CGImageDestinationFinalize(writer) else { Reduction.fail("无法写入文章图像文件") }
    }
}
