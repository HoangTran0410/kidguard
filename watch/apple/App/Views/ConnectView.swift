import SwiftUI
import KidGuardCore

/// Connect by code (docs/protocol.md "Watch connect"): show 6 digits, parent types them on the page (Settings > Watch).
/// Poll every 3 s while this screen is open; ask a new code when it expires. The secret lives only in this model.
@MainActor
final class ConnectModel: ObservableObject {
    @Published var code: Pairing.Code?
    @Published var left = 0
    @Published var error: String?
    @Published var connected: String?
    private var task: Task<Void, Never>?
    private let store: KidStore
    init(store: KidStore) { self.store = store }

    func start() {
        task?.cancel()
        task = Task { await run() }
    }
    func stop() { task?.cancel() }

    private func run() async {
        let relay = store.relay
        while !Task.isCancelled {
            do {
                let c = try await Pairing.requestCode(relay: relay)
                code = c; error = nil
                var tick = 0
                var expired = false
                while !Task.isCancelled && !expired {
                    let remain = Int((c.expMs - Int64(Date().timeIntervalSince1970 * 1000)) / 1000)
                    left = remain
                    if remain <= 0 { break }                       // expired: next loop asks a new code
                    if tick % 3 == 0 {                             // about every 3 s
                        switch (try? await Pairing.poll(c, relay: relay)) ?? .waiting {
                        case .done(let kid):
                            store.add(kid); connected = kid.name; stop(); return
                        case .expired: expired = true
                        case .waiting: break
                        }
                    }
                    tick += 1
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            } catch {
                code = nil
                self.error = error.localizedDescription
                try? await Task.sleep(nanoseconds: 5_000_000_000)   // retry
            }
        }
    }
}

struct ConnectView: View {
    @StateObject private var m: ConnectModel
    init(store: KidStore) { _m = StateObject(wrappedValue: ConnectModel(store: store)) }

    var body: some View {
        ScrollView {
            VStack(spacing: 6) {
                Text("connect.title").font(.headline).foregroundStyle(.secondary)
                if let name = m.connected {
                    Text(name).font(.title2)
                    Text("connect.done").foregroundStyle(.green)
                } else if let c = m.code {
                    Text(Fmt.code(c.code)).font(.system(size: 38, weight: .bold, design: .rounded)).monospacedDigit()
                        .minimumScaleFactor(0.5).lineLimit(1)
                    Text("connect.how").font(.footnote).multilineTextAlignment(.center)
                    Text(String(format: String(localized: "connect.left"), Fmt.countdown(seconds: m.left)))
                        .font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                } else if let e = m.error {
                    Text(e).font(.footnote).foregroundStyle(.red)
                } else {
                    ProgressView()
                }
            }
        }
        .onAppear { m.start() }
        .onDisappear { m.stop() }
    }
}
