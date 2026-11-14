import Foundation

/// Rules edits, a port of `watch/zepp/app-side/index.js` `editRules()` + `grantApply()` + `newestRules()`.
/// Same rule as the parent page (`kid.js` grantApply) and the phone's `Grant.apply`.
public enum RuleOp: Equatable {
    case grant(min: Int)          // more time today (or a timed pass when the phone is locked for another reason)
    case passEnd
    case appOk(pkg: String)       // approve an app request: allow-list it, unblock it
    case pause(min: Int)
    case resume
    case bedtime(on: Bool)
}

public enum Rules {
    static let minute: Int64 = 60_000

    /// The state carries a copy of the rules too; the higher `rev` wins.
    public static func newest(_ reply: JSON) -> [String: JSON] {
        let a = reply["rules"].object, b = reply["state"]["rules"].object
        if let a, let b { return (a["rev"]?.int64 ?? 0) >= (b["rev"]?.int64 ?? 0) ? a : b }
        return a ?? b ?? [:]
    }

    /// The phone never accepts these from a parent edit; strip them like the other watch does.
    public static func stripSecrets(_ r: inout [String: JSON]) {
        r.removeValue(forKey: "pinHash"); r.removeValue(forKey: "pinSet"); r.removeValue(forKey: "pin")
    }

    @discardableResult
    public static func grant(_ r: inout [String: JSON], state: JSON, min: Int, day: String, nowMs: Int64) -> String {
        let why = state["now"]["why"].string ?? ""
        if why == "" || why == "daily" {
            let b = r["bonus"] ?? .null
            let c0 = (b["day"].string == day) ? b["min"].int64 : 0
            r["bonus"] = .obj(["day": .str(day), "min": .num(Double(Swift.min(600, c0 + Int64(min))))])
            return "daily"
        }
        let base = Swift.max(nowMs, r["passUntil"]?.int64 ?? 0)
        r["passUntil"] = .num(Double(base + Int64(min) * minute))
        return "pass"
    }

    public static func apply(_ op: RuleOp, to r: inout [String: JSON], state: JSON, day: String, nowMs: Int64) {
        switch op {
        case .grant(let m): grant(&r, state: state, min: m, day: day, nowMs: nowMs)
        case .passEnd: r["passUntil"] = .num(0)
        case .appOk(let p):
            var ok = r["approved"]?.array ?? []
            if !ok.contains(.str(p)) { ok.append(.str(p)) }
            r["approved"] = .arr(ok)
            r["blocked"] = .arr((r["blocked"]?.array ?? []).filter { $0 != .str(p) })
        case .pause(let m): r["pausedUntil"] = .num(Double(nowMs + Int64(m) * minute))
        case .resume: r["pausedUntil"] = .num(0)
        case .bedtime(let on):
            var b = r["bedtime"]?.object ?? ["start": .str("21:30"), "end": .str("06:30")]
            b["on"] = .bool(on)
            r["bedtime"] = .obj(b)
        }
    }
}
