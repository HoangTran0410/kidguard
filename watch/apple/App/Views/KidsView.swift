import SwiftUI
import KidGuardCore

struct KidsView: View {
    @ObservedObject var store: KidStore
    var body: some View {
        List {
            ForEach(store.kids) { k in
                NavigationLink(k.name) { KidHomeView(kid: k) }
            }
            .onDelete { idx in idx.map { store.kids[$0] }.forEach(store.remove) }
            NavigationLink("kids.add") { ConnectView(store: store) }
        }
        .navigationTitle("KidGuard")
    }
}
