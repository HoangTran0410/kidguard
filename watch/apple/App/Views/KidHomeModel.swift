import Foundation
import KidGuardCore

/// State of one kid screen: refresh every 15 s while visible, send commands, follow their receipt.
@MainActor
final class KidHomeModel: ObservableObject {
    enum Status: Equatable { case none, sent(String), done(String), failed(String, String), expired(String), waiting(String) }
    @Published var s: Summary?
    @Published var error: String?
    @Published var status: Status = .none
    @Published var busy = false
    let api: KidClient
    private var loop: Task<Void, Never>?
    init(kid: Kid) { api = KidClient(kid) }

    func start() {
        loop?.cancel()
        loop = Task { while !Task.isCancelled { await refresh(); try? await Task.sleep(nanoseconds: 15_000_000_000) } }
    }
    func stop() { loop?.cancel() }

    func refresh() async {
        do { s = try await api.state(); error = nil } catch { self.error = error.localizedDescription }
    }

    /// `label` = the words the screen shows ("lock", "ring" ...), kept in the status so the result names the action.
    func send(_ label: String, _ run: @escaping () async throws -> String) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let id = try await run()
                status = .sent(label)                                   // only queued on the relay
                await follow(id, label)
            } catch { status = .failed(label, error.localizedDescription) }
        }
    }

    /// Poll the receipt (state.done / expired) a few times; the phone may be asleep, then it stays "waiting for the phone".
    private func follow(_ id: String, _ label: String) async {
        for _ in 0..<6 {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard let r = try? await api.receipt(id) else { continue }
            switch r {
            case .ok: status = .done(label); await refresh(); return
            case .fail(let why): status = .failed(label, why); return
            case .expired: status = .expired(label); return
            case .wait: break
            }
        }
        status = .waiting(label)
    }
}
