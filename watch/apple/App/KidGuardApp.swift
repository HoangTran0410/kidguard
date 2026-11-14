import SwiftUI

@main
struct KidGuardApp: App {
    @StateObject private var store = KidStore()
    var body: some Scene {
        WindowGroup {
            NavigationStack {
                if store.kids.isEmpty { ConnectView(store: store) } else { KidsView(store: store) }
            }
        }
    }
}
