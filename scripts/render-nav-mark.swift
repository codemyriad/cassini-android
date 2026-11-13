import AppKit
import WebKit

let input = CommandLine.arguments[1]
let output = CommandLine.arguments[2]
let pixelWidth = Int(CommandLine.arguments[3])!
let svg = try String(contentsOfFile: input, encoding: .utf8)
let app = NSApplication.shared
app.setActivationPolicy(.prohibited)
let frame = NSRect(x: -10000, y: -10000, width: 184, height: 120)
let window = NSWindow(contentRect: frame, styleMask: .borderless, backing: .buffered, defer: false)
window.isOpaque = false
window.backgroundColor = .clear
let view = WKWebView(frame: NSRect(x: 0, y: 0, width: 184, height: 120))
view.setValue(false, forKey: "drawsBackground")
window.contentView = view
final class Exporter: NSObject, WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let settings = WKSnapshotConfiguration()
        settings.rect = CGRect(x: 0, y: 0, width: 184, height: 120)
        settings.snapshotWidth = NSNumber(value: pixelWidth)
        webView.takeSnapshot(with: settings) { image, error in
            guard let tiff = image?.tiffRepresentation,
                  let bitmap = NSBitmapImageRep(data: tiff),
                  let png = bitmap.representation(using: .png, properties: [:]) else {
                fputs("SVG export failed: \(String(describing: error))\n", stderr)
                exit(1)
            }
            do {
                try png.write(to: URL(fileURLWithPath: output))
                print("Canonical SVG rendered: \(bitmap.pixelsWide)x\(bitmap.pixelsHigh)")
                window.close()
                exit(0)
            } catch { fputs("\(error)\n", stderr); exit(1) }
        }
    }
}
let exporter = Exporter()
view.navigationDelegate = exporter
window.orderFront(nil)
view.loadHTMLString("<html><head><style>html,body{margin:0;padding:0;background:transparent;width:184px;height:120px;overflow:hidden}svg{display:block;width:184px;height:120px}</style></head><body>" + svg + "</body></html>", baseURL: nil)
DispatchQueue.main.asyncAfter(deadline: .now() + 25) { fputs("SVG export timed out\n", stderr); exit(1) }
app.run()
