import Foundation

public let defaultRelay = "https://kidguard.99-hoangtran.workers.dev"
public let clientName = "Apple Watch"

public enum KGError: Error, LocalizedError, Equatable {
    case http(Int, String)     // status, relay / phone error text
    case badReply
    case offline(String)
    public var errorDescription: String? {
        switch self {
        case .http(let c, let m): return m.isEmpty ? "HTTP \(c)" : m
        case .badReply: return "bad reply"
        case .offline(let m): return m
        }
    }
}

/// Network seam: URLSession in the app, a fake in tests.
public protocol Transport {
    func send(_ req: URLRequest) async throws -> (Data, Int)
}

public struct URLSessionTransport: Transport {
    let session: URLSession
    public init(_ s: URLSession = .shared) { session = s }
    public func send(_ req: URLRequest) async throws -> (Data, Int) {
        do {
            let (d, r) = try await session.data(for: req)
            return (d, (r as? HTTPURLResponse)?.statusCode ?? 0)
        } catch { throw KGError.offline((error as NSError).localizedDescription) }
    }
}

/// Builds requests (pure, unit-tested) and decodes replies. Never logs a token or a secret.
public enum Wire {
    public static func request(_ url: String, _ method: String, headers: [String: String] = [:], body: JSON? = nil) throws -> URLRequest {
        guard let u = URL(string: url), u.scheme == "https" else { throw KGError.badReply }
        var r = URLRequest(url: u, timeoutInterval: 15)
        r.httpMethod = method
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
        if let body { r.httpBody = try body.data() }
        return r
    }

    public static func kidRequest(_ k: Kid, _ path: String, _ method: String = "GET", body: JSON? = nil) throws -> URLRequest {
        try request("\(k.url)/c/\(k.id)\(path)", method, headers: ["X-KG-Token": k.token, "X-KG-Name": clientName], body: body)
    }

    public static func decode(_ data: Data, _ status: Int) throws -> JSON {
        let j = (try? JSON.parse(data)) ?? .obj([:])
        if status >= 400 || status == 0 { throw KGError.http(status, j["error"].string ?? "") }
        return j
    }

    public static func newId(now: Date = Date()) -> String {
        "w" + String(Int64(now.timeIntervalSince1970 * 1000), radix: 36) + String(UInt32.random(in: 0..<UInt32.max), radix: 36).prefix(6)
    }
}

/// Talks to one kid through the relay (docs/protocol.md): state, cmd, rules.
public struct KidClient {
    public let kid: Kid
    let transport: Transport
    public init(_ kid: Kid, transport: Transport = URLSessionTransport()) { self.kid = kid; self.transport = transport }

    func call(_ path: String, _ method: String = "GET", body: JSON? = nil) async throws -> JSON {
        let (d, s) = try await transport.send(Wire.kidRequest(kid, path, method, body: body))
        return try Wire.decode(d, s)
    }

    public func stateJSON() async throws -> JSON { try await call("/state") }
    public func state() async throws -> Summary { Summary(try await stateJSON()) }

    /// POST /cmd with our own id: the phone's receipt (`state.done`) or the worker's (`expired`) is matched on it.
    /// Returns the id. The relay only queues the command ("sent"); the result comes later via `receipt`.
    @discardableResult
    public func command(_ type: String, _ extra: [String: JSON] = [:]) async throws -> String {
        let id = Wire.newId()
        var c = extra
        c["type"] = .str(type); c["id"] = .str(id)
        let j = try await call("/cmd", "POST", body: .obj(c))
        let res = j["result"].string ?? "queued"
        if res != "queued" && res != "ok" && res != "no such sos" { throw KGError.http(200, res) }
        return id
    }

    public func message(_ text: String) async throws -> String { try await command("message", ["text": .str(String(text.prefix(120)))]) }

    public func receipt(_ id: String) async throws -> Receipt { Summary.receipt(for: id, in: try await stateJSON()) }

    /// read newest rules -> change one thing -> PUT the WHOLE object.
    public func changeRules(_ op: RuleOp, nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws {
        let j = try await stateJSON()
        var r = Rules.newest(j)
        Rules.apply(op, to: &r, state: j["state"], day: j["state"]["day"].string ?? "", nowMs: nowMs)
        Rules.stripSecrets(&r)
        _ = try await call("/rules", "PUT", body: .obj(r))
    }

    /// Same order as the page: edit the rules first, then tell the child (`answer` removes the request).
    @discardableResult
    public func answer(_ q: KidRequest, ok: Bool, min: Int) async throws -> String {
        if ok { try await changeRules(q.kind == .app ? .appOk(pkg: q.pkg) : .grant(min: min)) }
        var extra: [String: JSON] = ["req": .str(q.id), "ok": .bool(ok)]
        if ok && q.kind == .app { extra["always"] = .bool(true) }
        if ok && q.kind == .time { extra["min"] = .num(Double(min)) }
        return try await command("answer", extra)
    }
}
