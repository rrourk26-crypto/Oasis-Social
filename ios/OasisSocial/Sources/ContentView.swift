import SwiftUI

struct ContentView: View {
    @StateObject private var browser = BrowserModel()

    var body: some View {
        ZStack {
            WebView(model: browser)
                .ignoresSafeArea(.container, edges: .bottom)

            if browser.isLoading && browser.progress < 1 {
                VStack {
                    ProgressView(value: browser.progress)
                        .progressViewStyle(.linear)
                        .tint(.accentColor)
                    Spacer()
                }
            }

            if browser.showOffline {
                OfflineView { browser.reload() }
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: browser.showOffline)
    }
}

struct OfflineView: View {
    var retry: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "wifi.slash")
                .font(.system(size: 52, weight: .light))
                .foregroundStyle(.secondary)
            Text("You're offline")
                .font(.title2.weight(.semibold))
            Text("Oasis couldn't connect. Check your connection and try again.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 40)
            Button("Try again", action: retry)
                .buttonStyle(.borderedProminent)
                .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }
}
