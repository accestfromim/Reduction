import ImageIO
import UniformTypeIdentifiers
import WebKit

@MainActor
final class ArticleExport: NSObject, WKNavigationDelegate {
    private static let width = 540
    private let web: WKWebView
    private let height: Int
    private var response: CheckedContinuation<Void, Error>?
    private var deadline: Task<Void, Never>?

    init(height: Int, dataStore: WKWebsiteDataStore) {
        self.height = height
        let config = WKWebViewConfiguration()
        config.websiteDataStore = dataStore
        config.defaultWebpagePreferences.allowsContentJavaScript = false
        web = WKWebView(frame: CGRect(x: 0, y: 0, width: Self.width, height: height), configuration: config)
        super.init()
        web.navigationDelegate = self
    }

    func write(html: String, base: URL, to file: URL) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            response = continuation
            deadline = Task { [weak self] in
                do {
                    try await Task.sleep(for: .seconds(20))
                    self?.finish(.failure(ReductionError("文章资源加载超时")))
                } catch { return }
            }
            web.loadHTMLString(html, baseURL: base)
        }
        _ = try await web.callAsyncJavaScript("await document.fonts.ready; return true;", arguments: [:], in: nil,
                                                contentWorld: .defaultClient)
        let width = Self.width * 2
        guard let canvas = CGContext(data: nil, width: width, height: height * 2, bitsPerComponent: 8, bytesPerRow: 0,
                                     space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
            throw ReductionError("无法分配文章图像，请把文章分段导出")
        }
        canvas.setFillColor(CGColor(gray: 1, alpha: 1))
        canvas.fill(CGRect(x: 0, y: 0, width: width, height: height * 2))
        for offset in stride(from: 0, to: height, by: 1024) {
            try Task.checkCancellation()
            let length = min(1024, height - offset)
            let config = WKPDFConfiguration()
            config.rect = CGRect(x: 0, y: offset, width: Self.width, height: length)
            let data = try await web.pdf(configuration: config)
            guard let provider = CGDataProvider(data: data as CFData), let pdf = CGPDFDocument(provider),
                  pdf.numberOfPages == 1, let page = pdf.page(at: 1) else { throw ReductionError("文章页面无法转成图片") }
            let rect = CGRect(x: 0, y: (height - offset - length) * 2, width: width, height: length * 2)
            canvas.saveGState()
            canvas.clip(to: rect)
            canvas.concatenate(page.getDrawingTransform(.mediaBox, rect: rect, rotate: 0, preserveAspectRatio: false))
            canvas.drawPDFPage(page)
            canvas.restoreGState()
        }
        guard let image = canvas.makeImage(), let png = CGImageDestinationCreateWithURL(file as CFURL, UTType.png.identifier as CFString, 1, nil) else {
            throw ReductionError("无法创建文章长图")
        }
        CGImageDestinationAddImage(png, image, nil)
        guard CGImageDestinationFinalize(png) else { throw ReductionError("无法保存文章长图") }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { finish(.success(())) }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        finish(.failure(error))
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { finish(.failure(error)) }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { finish(.failure(ReductionError("文章渲染进程提前退出"))) }

    private func finish(_ result: Result<Void, Error>) {
        let waiting = response
        response = nil
        deadline?.cancel()
        deadline = nil
        waiting?.resume(with: result)
    }
}
