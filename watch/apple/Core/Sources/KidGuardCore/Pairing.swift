import Foundation
import CryptoKit

/// Connect by 6-digit code (docs/protocol.md "Watch connect"). The secret never leaves the watch: the relay only sees sha256.
public enum Pairing {
    public struct Code: Equatable { public var code: String, secret: String, expMs: Int64 }
    public enum Poll: Equatable { case waiting, expired, done(Kid) }

    public static func sha256Hex(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// 32 random bytes as 64 hex chars (system CSPRNG).
    public static func makeSecret() -> String {
        var g = SystemRandomNumberGenerator()
        return (0..<32).map { _ in String(format: "%02x", UInt8.random(in: 0...255, using: &g)) }.joined()
    }

    public static func newRequest(relay: String, secret: String) throws -> URLRequest {
        try Wire.request(relay + "/w/new", "POST", body: .obj(["sh": .str(sha256Hex(secret))]))
    }

    public static func pollRequest(relay: String, code: String, secret: String) throws -> URLRequest {
        try Wire.request(relay + "/w/" + code, "GET", headers: ["X-KG-Secret": secret, "X-KG-Name": clientName])
    }

    /// Relay base: https only, no trailing slash, else the default.
    public static func cleanRelay(_ s: String) -> String {
        let t = s.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
        return t.range(of: "^https://[^/#?\\s]+$", options: .regularExpression) != nil ? t : defaultRelay
    }

    public static func requestCode(relay: String = defaultRelay, transport: Transport = URLSessionTransport()) async throws -> Code {
        let secret = makeSecret()
        let (d, s) = try await transport.send(newRequest(relay: relay, secret: secret))
        let j = try Wire.decode(d, s)
        guard let code = j["code"].string, code.range(of: "^[0-9]{6}$", options: .regularExpression) != nil else { throw KGError.badReply }
        return Code(code: code, secret: secret, expMs: j["exp"].int64)
    }

    /// 404 = unknown / expired / already fetched -> `.expired` (ask a new code). Reply is validated like the Zepp side.
    public static func poll(_ c: Code, relay: String = defaultRelay, transport: Transport = URLSessionTransport()) async throws -> Poll {
        let (d, s) = try await transport.send(pollRequest(relay: relay, code: c.code, secret: c.secret))
        if s == 404 { return .expired }
        let j = try Wire.decode(d, s)
        if j["waiting"].bool == true { return .waiting }
        return .done(try parseKid(j))
    }

    public static func parseKid(_ j: JSON) throws -> Kid {
        func ok(_ s: String?, _ re: String) -> String? { s.flatMap { $0.range(of: re, options: .regularExpression) != nil ? $0 : nil } }
        guard let url = ok(j["url"].string, "^https://[^/#?\\s]+$"), let id = ok(j["id"].string, "^[0-9a-f]{16}$"),
              let token = ok(j["token"].string, "^[0-9a-f]{48}$") else { throw KGError.badReply }
        let name = String((j["name"].string ?? "").prefix(20))
        return Kid(url: url, id: id, token: token, name: name.isEmpty ? "Kid" : name)
    }
}
