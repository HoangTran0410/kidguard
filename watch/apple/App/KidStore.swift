import Foundation
import Security
import KidGuardCore

/// Kids on this watch: token in the Keychain (account = kid id), url / id / name in UserDefaults.
/// The token is the same one the parent's /go link holds: never print it, never put it in UserDefaults.
@MainActor
final class KidStore: ObservableObject {
    @Published private(set) var kids: [Kid] = []
    private let key = "kids.meta"
    private let service = "dev.kidguard.watch"

    init() { load() }

    var relay: String { Pairing.cleanRelay(UserDefaults.standard.string(forKey: "relay") ?? defaultRelay) }

    func load() {
        let metas = (try? JSONDecoder().decode([KidMeta].self, from: UserDefaults.standard.data(forKey: key) ?? Data())) ?? []
        // a meta without its token (Keychain wiped) is dropped: the parent has to connect again
        kids = metas.compactMap { m in token(m.id).map { Kid(url: m.url, id: m.id, token: $0, name: m.name) } }
    }

    func add(_ k: Kid) {
        setToken(k.token, for: k.id)
        kids = kids.filter { $0.id != k.id } + [k]
        save()
    }

    func remove(_ k: Kid) {
        SecItemDelete(query(k.id) as CFDictionary)
        kids.removeAll { $0.id == k.id }
        save()
    }

    private func save() {
        UserDefaults.standard.set(try? JSONEncoder().encode(kids.map(\.meta)), forKey: key)
    }

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    private func setToken(_ t: String, for id: String) {
        SecItemDelete(query(id) as CFDictionary)
        var q = query(id)
        q[kSecValueData as String] = Data(t.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(q as CFDictionary, nil)
    }

    private func token(_ id: String) -> String? {
        var q = query(id)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }
}
