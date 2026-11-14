import Foundation

/// One connected kid. `token` lives in the Keychain on the watch, the rest in UserDefaults.
public struct Kid: Codable, Equatable, Identifiable {
    public var url: String, id: String, token: String, name: String
    public init(url: String, id: String, token: String, name: String) {
        self.url = url; self.id = id; self.token = token; self.name = name
    }
    /// Everything but the token (what UserDefaults may hold).
    public var meta: KidMeta { KidMeta(url: url, id: id, name: name) }
}

public struct KidMeta: Codable, Equatable, Identifiable {
    public var url: String, id: String, name: String
    public init(url: String, id: String, name: String) { self.url = url; self.id = id; self.name = name }
}

public struct Location: Equatable {
    public var lat: Double, lng: Double, acc: Int, t: Int64
}

public struct KidRequest: Equatable, Identifiable {
    public enum Kind: Equatable { case time, app }
    public var id: String, kind: Kind, min: Int, pkg: String, app: String, note: String, t: Int64
}

public enum Receipt: Equatable {
    case ok, fail(String), expired, wait
}

/// What the screens show; a port of `summary()` in `watch/zepp/app-side/index.js`.
public struct Summary: Equatable {
    public var seen: Int64                 // ms, last contact with the phone (relay)
    public var pending: Int                // commands still queued
    public var msgs: [String]              // parent's quick messages (rules.msgs)
    public var day: String
    public var leftMs: Int64?              // nil = no limit running / unknown
    public var usedMs: Int64, limitMin: Int
    public var battery: Int?, charging: Bool, screenOn: Bool
    public var current: String             // app in front ("" when screen is off)
    public var loc: Location?
    public var zone: (t: Int64, text: String)?
    public var nowWhy: String, nowUntil: Int64, nowName: String
    public var lost: Bool
    public var requests: [KidRequest]
    public var paused: Int64               // rules.pausedUntil

    public static func == (a: Summary, b: Summary) -> Bool {
        a.seen == b.seen && a.leftMs == b.leftMs && a.requests == b.requests && a.loc == b.loc && a.nowWhy == b.nowWhy
            && a.battery == b.battery && a.pending == b.pending && a.msgs == b.msgs && a.current == b.current
    }

    public static let fallbackMsgs = [
        "Come home for dinner", "Call us now", "Time for bed", "Put the phone away and study", "We're coming to pick you up",
    ]

    public init(_ reply: JSON) {
        let s = reply["state"], r = Rules.newest(reply)
        seen = reply["seen"].int64
        pending = reply["pending"].int ?? 0
        msgs = (r["msgs"]?.array ?? []).compactMap { $0.string }.filter { !$0.isEmpty }.prefix(12).map { String($0.prefix(120)) }
        day = s["day"].string ?? ""
        leftMs = s["leftMs"].double.map { Int64($0) }
        usedMs = s["usedMs"].int64
        limitMin = s["limitMin"].int ?? 0
        battery = s["battery"].int
        charging = s["charging"].bool ?? false
        screenOn = s["screenOn"].bool ?? true
        let cur = s["apps"].array.first { $0["pkg"].string == s["current"].string && s["current"].string != nil }
        current = screenOn ? (cur?["label"].string ?? "") : ""
        let l = s["location"]
        if let lat = l["lat"].double, let lng = l["lng"].double {
            loc = Location(lat: lat, lng: lng, acc: Int((l["acc"].double ?? 0).rounded()), t: l["t"].int64)
        } else { loc = nil }
        let ev = s["events"].array.last { $0["kind"].string == "zoneIn" || $0["kind"].string == "zoneOut" }
        zone = ev.map { (t: $0["t"].int64, text: $0["text"].string ?? "") }
        nowWhy = s["now"]["why"].string ?? ""
        nowUntil = s["now"]["until"].int64
        nowName = String((s["now"]["name"].string ?? "").prefix(24))
        lost = (s["lost"]["on"].bool ?? false) || (r["lostOn"]?.bool ?? false)
        paused = r["pausedUntil"]?.int64 ?? 0
        requests = s["requests"].array.compactMap { x in
            guard let id = x["id"].string else { return nil }
            let app = x["kind"].string == "app"
            return KidRequest(id: id, kind: app ? .app : .time, min: x["min"].int ?? 0, pkg: x["pkg"].string ?? "",
                              app: String((x["app"].string ?? x["pkg"].string ?? "").prefix(24)),
                              note: String((x["note"].string ?? "").prefix(60)),
                              t: x["at"].double.map { Int64($0) } ?? x["t"].int64)
        }
    }

    /// Result of a command we sent: `state.done[]` (phone ran it) or `expired[]` (relay dropped it), else still waiting.
    public static func receipt(for id: String, in reply: JSON) -> Receipt {
        if let d = reply["state"]["done"].array.first(where: { $0["id"].string == id }) {
            let res = d["res"].string ?? ""
            return res == "ok" ? .ok : .fail(String(res.prefix(40)))
        }
        if reply["expired"].array.contains(where: { $0["id"].string == id }) { return .expired }
        return .wait
    }
}
