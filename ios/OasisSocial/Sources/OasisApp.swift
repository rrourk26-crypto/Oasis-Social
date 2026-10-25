import SwiftUI

enum AppConfig {
    /// The site the app wraps (same URL the Android build uses).
    static let homeURL = URL(string: "https://oasis.myguyinthechair.com")!

    /// Hosts that stay inside the app. Anything else opens in Safari.
    static let internalHosts: Set<String> = ["oasis.myguyinthechair.com", "myguyinthechair.com"]

    /// Appended to the web view's user agent so the site can detect the iOS app.
    static let userAgentSuffix = "OasisSocial-iOS/1.2.0"
}

@main
struct OasisApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
