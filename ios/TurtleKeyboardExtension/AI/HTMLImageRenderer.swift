import UIKit
import WebKit

// MARK: - HTMLImageRenderer
//
// Renders the HTML fragment returned by /org into a 500×500 PNG using a hidden
// WKWebView that hosts the bubkoo/html-to-image library. The resulting UIImage
// is handed back so the keyboard can put it on the pasteboard.
//
// Runtime layout:
//   org_template.html  ← hosts the styles for the primitives (table, dl,
//                        .stat, .grid/.card, .checklist, .callout, .badge)
//                        and pulls html-to-image from unpkg.
//   #target            ← div whose innerHTML we replace with the model output.
//   #canvas            ← the fixed 500×500 box passed to htmlToImage.toPng.
//
// Memory note: WKWebView is heavy for a keyboard extension. We keep one
// instance alive across renders and never attach it to the view hierarchy.

final class HTMLImageRenderer: NSObject, WKNavigationDelegate {

    static let shared = HTMLImageRenderer()

    private let canvasSize = CGSize(width: 500, height: 500)
    private var webView: WKWebView!
    private var ready = false
    private var pending: [(String, (UIImage?) -> Void)] = []

    private override init() {
        super.init()
        let cfg = WKWebViewConfiguration()
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        webView = WKWebView(
            frame: CGRect(origin: .zero, size: canvasSize),
            configuration: cfg
        )
        webView.navigationDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = .white
        loadTemplate()
    }

    /// Attach the WebView off-screen inside the keyboard's view hierarchy.
    /// Required because html-to-image uses requestAnimationFrame internally,
    /// and rAF is paused on detached WKWebViews — leading to silent hangs.
    func attach(to host: UIView) {
        guard webView.superview == nil else { return }
        webView.frame = CGRect(x: -10000, y: -10000,
                               width: canvasSize.width, height: canvasSize.height)
        webView.isHidden = true
        webView.isUserInteractionEnabled = false
        host.addSubview(webView)
        log("attached to host view")
    }

    private func log(_ s: String) { NSLog("🐢[HTMLRenderer] %@", s) }

    private func loadTemplate() {
        guard let url = Bundle.main.url(forResource: "org_template", withExtension: "html") else {
            log("org_template.html not found in bundle")
            return
        }
        webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
    }

    // MARK: - Public

    func render(html: String, completion: @escaping (UIImage?) -> Void) {
        if !ready {
            pending.append((html, completion))
            return
        }
        runRender(html: html, completion: completion)
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        waitForLibrary(attempts: 25)
    }

    private func waitForLibrary(attempts: Int) {
        webView.evaluateJavaScript("typeof htmlToImage !== 'undefined'") { [weak self] result, _ in
            guard let self = self else { return }
            if let ok = result as? Bool, ok {
                self.ready = true
                self.log("html-to-image ready")
                let queued = self.pending
                self.pending = []
                for (html, cb) in queued { self.runRender(html: html, completion: cb) }
            } else if attempts > 0 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                    self.waitForLibrary(attempts: attempts - 1)
                }
            } else {
                self.log("html-to-image failed to load")
                let queued = self.pending; self.pending = []
                for (_, cb) in queued { cb(nil) }
            }
        }
    }

    // MARK: - Render

    private func runRender(html: String, completion: @escaping (UIImage?) -> Void) {
        // callAsyncJavaScript wraps the body in an async function and awaits
        // the returned Promise, so we can simply `return` the data URL.
        let js = """
        if (typeof htmlToImage === 'undefined') {
          return 'ERR:lib-missing';
        }
        try {
          document.getElementById('target').innerHTML = html;
          await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
          const node = document.getElementById('canvas');
          const url = await htmlToImage.toPng(node, {
            pixelRatio: 1,
            backgroundColor: '#ffffff',
            width: w,
            height: h,
            cacheBust: true,
            skipFonts: true
          });
          return url;
        } catch (e) {
          return 'ERR:' + (e && e.message ? e.message : String(e));
        }
        """

        let args: [String: Any] = [
            "html": html,
            "w": Int(canvasSize.width),
            "h": Int(canvasSize.height),
        ]

        var didFinish = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in
            guard !didFinish else { return }
            didFinish = true
            self?.log("render timed out after 20s")
            completion(nil)
        }

        webView.callAsyncJavaScript(js, arguments: args, in: nil, in: .page) { [weak self] result in
            guard !didFinish else { return }
            didFinish = true
            guard let self = self else { completion(nil); return }
            switch result {
            case .failure(let err):
                self.log("JS error: \(err.localizedDescription)")
                completion(nil)
            case .success(let value):
                guard let dataURL = value as? String else {
                    self.log("unexpected JS result type: \(String(describing: value))")
                    completion(nil); return
                }
                if dataURL.hasPrefix("ERR:") {
                    self.log("render failed: \(dataURL)")
                    completion(nil); return
                }
                guard let comma = dataURL.firstIndex(of: ",") else {
                    self.log("data URL missing comma")
                    completion(nil); return
                }
                let b64 = String(dataURL[dataURL.index(after: comma)...])
                guard let bytes = Data(base64Encoded: b64),
                      let image = UIImage(data: bytes) else {
                    self.log("decode failed (\(b64.count) chars)")
                    completion(nil); return
                }
                self.log("rendered \(Int(image.size.width))×\(Int(image.size.height))")
                completion(image)
            }
        }
    }
}
