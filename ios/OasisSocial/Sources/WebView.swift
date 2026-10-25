import SwiftUI
import WebKit

/// Holds web-view state for SwiftUI and exposes simple actions.
@MainActor
final class BrowserModel: ObservableObject {
    @Published var isLoading = false
    @Published var progress: Double = 0
    @Published var showOffline = false

    weak var webView: WKWebView?

    func reload() {
        showOffline = false
        if let webView, webView.url != nil {
            webView.reload()
        } else {
            webView?.load(URLRequest(url: AppConfig.homeURL))
        }
    }
}

struct WebView: UIViewRepresentable {
    @ObservedObject var model: BrowserModel

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()          // keeps users logged in between launches
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.applicationNameForUserAgent = AppConfig.userAgentSuffix

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true   // swipe back/forward
        webView.allowsLinkPreview = true
        webView.scrollView.contentInsetAdjustmentBehavior = .automatic
        #if DEBUG
        if #available(iOS 16.4, *) { webView.isInspectable = true }  // Safari Web Inspector
        #endif

        // Pull to refresh
        let refresh = UIRefreshControl()
        refresh.addTarget(context.coordinator, action: #selector(Coordinator.pulledToRefresh(_:)), for: .valueChanged)
        webView.scrollView.refreshControl = refresh

        context.coordinator.observe(webView)
        model.webView = webView
        webView.load(URLRequest(url: AppConfig.homeURL))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    // MARK: - Coordinator

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        private let model: BrowserModel
        private var observers: [NSKeyValueObservation] = []

        init(model: BrowserModel) { self.model = model }

        func observe(_ webView: WKWebView) {
            observers = [
                webView.observe(\.estimatedProgress) { [weak self] wv, _ in
                    Task { @MainActor in self?.model.progress = wv.estimatedProgress }
                },
                webView.observe(\.isLoading) { [weak self] wv, _ in
                    Task { @MainActor in self?.model.isLoading = wv.isLoading }
                }
            ]
        }

        @objc func pulledToRefresh(_ sender: UIRefreshControl) {
            Task { @MainActor in
                model.reload()
                sender.endRefreshing()
            }
        }

        private func isInternal(_ url: URL) -> Bool {
            guard let host = url.host?.lowercased() else { return true }
            return AppConfig.internalHosts.contains { host == $0 || host.hasSuffix("." + $0) }
        }

        // Keep the site in-app; send other links, mail, tel, etc. to the system.
        func webView(_ webView: WKWebView,
                     decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url else { return decisionHandler(.allow) }
            let scheme = url.scheme?.lowercased() ?? ""

            if ["http", "https", "about", "blob", "data"].contains(scheme) {
                if scheme.hasPrefix("http"), !isInternal(url), action.targetFrame?.isMainFrame != false,
                   action.navigationType == .linkActivated {
                    UIApplication.shared.open(url)
                    return decisionHandler(.cancel)
                }
                return decisionHandler(.allow)
            }
            UIApplication.shared.open(url)   // mailto:, tel:, sms:, maps, etc.
            decisionHandler(.cancel)
        }

        // target="_blank" / window.open
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = action.request.url {
                if isInternal(url) { webView.load(action.request) } else { UIApplication.shared.open(url) }
            }
            return nil
        }

        // Camera / mic for web capture — the site is ours, so grant (iOS still shows its own prompt).
        func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                     initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType,
                     decisionHandler: @escaping (WKPermissionDecision) -> Void) {
            decisionHandler(AppConfig.internalHosts.contains(origin.host) ? .grant : .prompt)
        }

        // JavaScript alert / confirm / prompt as native dialogs
        func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                     initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
            present(alert(message, actions: [.init(title: "OK", style: .default) { _ in completionHandler() }]),
                    from: webView, fallback: completionHandler)
        }

        func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                     initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
            present(alert(message, actions: [
                .init(title: "Cancel", style: .cancel) { _ in completionHandler(false) },
                .init(title: "OK", style: .default) { _ in completionHandler(true) }
            ]), from: webView, fallback: { completionHandler(false) })
        }

        func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String,
                     defaultText: String?, initiatedByFrame frame: WKFrameInfo,
                     completionHandler: @escaping (String?) -> Void) {
            let a = alert(prompt, actions: [])
            a.addTextField { $0.text = defaultText }
            a.addAction(.init(title: "Cancel", style: .cancel) { _ in completionHandler(nil) })
            a.addAction(.init(title: "OK", style: .default) { _ in completionHandler(a.textFields?.first?.text) })
            present(a, from: webView, fallback: { completionHandler(nil) })
        }

        private func alert(_ message: String, actions: [UIAlertAction]) -> UIAlertController {
            let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
            actions.forEach(a.addAction)
            return a
        }

        private func present(_ vc: UIViewController, from view: UIView, fallback: () -> Void) {
            guard let root = view.window?.rootViewController else { return fallback() }
            var top = root
            while let next = top.presentedViewController { top = next }
            top.present(vc, animated: true)
        }

        // Loading / offline
        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            Task { @MainActor in model.showOffline = false }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            handle(error)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            handle(error)
        }

        private func handle(_ error: Error) {
            let ns = error as NSError
            if ns.domain == NSURLErrorDomain, ns.code == NSURLErrorCancelled { return }
            if ns.domain == NSURLErrorDomain {
                Task { @MainActor in model.showOffline = true }
            }
        }

        // If WebKit's process dies (memory pressure), reload instead of showing a blank screen.
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            webView.reload()
        }
    }
}
