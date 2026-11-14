import Testing
import Foundation
@testable import KidGuardCore

final class FakeTransport: Transport {
    var calls: [URLRequest] = []
    var replies: [(String) -> (Data, Int)] = []   // matched by "METHOD path"
    var table: [String: (Int, String)] = [:]
    func send(_ req: URLRequest) async throws -> (Data, Int) {
        calls.append(req)
        let key = "\(req.httpMethod ?? "GET") \(req.url!.path)"
        let (s, b) = table[key] ?? (404, "{}")
        return (Data(b.utf8), s)
    }
}

let sampleState = """
{"seen":1700000000000,"pending":1,"queue":[],"expired":[{"id":"gone"}],
 "rules":{"rev":5,"dailyMin":120,"msgs":["Home now","Dinner"],"blocked":["a.b","c.d"],"approved":[],"pinHash":"x","future":{"k":[1,2]},"passUntil":0,"bonus":{"day":"2026-11-14","min":10}},
 "state":{"day":"2026-11-14","leftMs":2700000,"usedMs":4500000,"limitMin":120,"battery":62,"charging":false,"screenOn":true,
  "current":"a.b","apps":[{"pkg":"a.b","label":"YouTube Kids","ms":1}],
  "location":{"lat":21.0285,"lng":105.8542,"acc":18.4,"t":1700000100000},
  "events":[{"kind":"zoneIn","t":5,"text":"Arrived Home"},{"kind":"x","t":9,"text":"n"}],
  "now":{"why":"","until":0,"name":""},
  "requests":[{"id":"r1","kind":"time","min":30,"at":111,"note":"homework done"},{"id":"r2","kind":"app","pkg":"c.d","app":"Roblox"}],
  "done":[{"id":"ok1","res":"ok"},{"id":"bad1","res":"no permission"}]}}
"""

@Suite struct CoreTests {
    func j(_ s: String) -> JSON { try! JSON.parse(Data(s.utf8)) }

    @Test func testSummaryDecode() {
        let s = Summary(j(sampleState))
        #expect((s.leftMs) == (2_700_000))
        #expect((Fmt.duration(ms: s.leftMs!)) == ("45m"))
        #expect((s.battery) == (62))
        #expect((s.current) == ("YouTube Kids"))
        #expect((s.msgs) == (["Home now", "Dinner"]))
        #expect((s.loc?.acc) == (18))
        #expect((s.zone?.text) == ("Arrived Home"))
        #expect((s.requests.count) == (2))
        #expect((s.requests[0].kind) == (.time)); #expect((s.requests[0].min) == (30)); #expect((s.requests[0].t) == (111))
        #expect((s.requests[1].kind) == (.app)); #expect((s.requests[1].pkg) == ("c.d"))
        #expect((s.seen) == (1_700_000_000_000))
    }

    @Test func testReceipt() {
        let r = j(sampleState)
        #expect((Summary.receipt(for: "ok1", in: r)) == (.ok))
        #expect((Summary.receipt(for: "bad1", in: r)) == (.fail("no permission")))
        #expect((Summary.receipt(for: "gone", in: r)) == (.expired))
        #expect((Summary.receipt(for: "none", in: r)) == (.wait))
    }

    @Test func testNewestRulesPrefersHigherRev() {
        let r = j(#"{"rules":{"rev":1,"a":1},"state":{"rules":{"rev":2,"a":2}}}"#)
        #expect((Rules.newest(r)["a"]?.int) == (2))
    }

    @Test func testGrantDailyAndPass() {
        var r = Rules.newest(j(sampleState))
        let st = j(sampleState)["state"]
        Rules.apply(.grant(min: 15), to: &r, state: st, day: "2026-11-14", nowMs: 1000)
        #expect((r["bonus"]?["min"].int) == (25))               // 10 + 15
        Rules.apply(.grant(min: 600), to: &r, state: st, day: "2026-11-14", nowMs: 1000)
        #expect((r["bonus"]?["min"].int) == (600))              // capped
        Rules.apply(.grant(min: 30), to: &r, state: j(#"{"now":{"why":"bedtime"}}"#), day: "d", nowMs: 1000)
        let pu: Int64 = r["passUntil"]?.int64 ?? -1
        #expect(pu == 1801000)
    }

    @Test func testAppOkAndUnknownFieldsSurvive() throws {
        var r = Rules.newest(j(sampleState))
        Rules.apply(.appOk(pkg: "c.d"), to: &r, state: .null, day: "", nowMs: 0)
        #expect((r["approved"]) == (.arr([.str("c.d")])))
        #expect((r["blocked"]) == (.arr([.str("a.b")])))
        Rules.stripSecrets(&r)
        #expect((r["pinHash"]) == nil)
        // unknown field + integer encoding survive the round trip
        let out = String(data: try JSON.obj(r).data(), encoding: .utf8)!
        #expect(out.contains("\"future\""))
        #expect(out.contains("\"rev\":5"))
        #expect(!(out.contains("5.0")))
    }

    @Test func testPairingRequests() throws {
        #expect((Pairing.sha256Hex("abc")) == ("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        let secret = Pairing.makeSecret()
        #expect((secret.count) == (64))
        #expect((secret) != (Pairing.makeSecret()))
        let n = try Pairing.newRequest(relay: defaultRelay, secret: secret)
        #expect((n.httpMethod) == ("POST")); #expect((n.url?.path) == ("/w/new"))
        let body = try JSON.parse(n.httpBody!)
        #expect((body["sh"].string) == (Pairing.sha256Hex(secret)))
        #expect(!(String(data: n.httpBody!, encoding: .utf8)!.contains(secret)))   // the secret itself never goes out
        let p = try Pairing.pollRequest(relay: defaultRelay, code: "123456", secret: secret)
        #expect((p.url?.path) == ("/w/123456")); #expect((p.value(forHTTPHeaderField: "X-KG-Secret")) == (secret))
        #expect(throws: KGError.self) { try Pairing.newRequest(relay: "http://x.y", secret: secret) }
        #expect((Pairing.cleanRelay("http://evil")) == (defaultRelay))
        #expect((Pairing.cleanRelay(" https://a.b/ ")) == ("https://a.b"))
    }

    @Test func testPairFlow() async throws {
        let t = FakeTransport()
        t.table["POST /w/new"] = (200, #"{"code":"123456","exp":99}"#)
        let c = try await Pairing.requestCode(transport: t)
        #expect((c.code) == ("123456")); #expect((Fmt.code(c.code)) == ("123 456"))
        t.table["GET /w/123456"] = (200, #"{"waiting":true,"exp":99}"#)
        var p = try await Pairing.poll(c, transport: t); #expect((p) == (.waiting))
        t.table["GET /w/123456"] = (404, "{}")
        p = try await Pairing.poll(c, transport: t); #expect((p) == (.expired))
        let id = String(repeating: "a", count: 16), tok = String(repeating: "b", count: 48)
        t.table["GET /w/123456"] = (200, #"{"url":"https://r.example","id":"\#(id)","token":"\#(tok)","name":"Bi"}"#)
        p = try await Pairing.poll(c, transport: t)
        #expect((p) == (.done(Kid(url: "https://r.example", id: id, token: tok, name: "Bi"))))
        t.table["GET /w/123456"] = (200, #"{"url":"http://r.example","id":"x","token":"y","name":"Bi"}"#)
        do { _ = try await Pairing.poll(c, transport: t); Issue.record("should throw") } catch { #expect((error as? KGError) == (.badReply)) }
        t.table["GET /w/123456"] = (403, #"{"error":"forbidden"}"#)
        do { _ = try await Pairing.poll(c, transport: t); Issue.record("should throw") } catch { #expect((error as? KGError) == (.http(403, "forbidden"))) }
    }

    @Test func testKidClientAnswerOrderAndHeaders() async throws {
        let t = FakeTransport()
        t.table["GET /c/\(String(repeating: "a", count: 16))/state"] = (200, sampleState)
        t.table["PUT /c/\(String(repeating: "a", count: 16))/rules"] = (200, "{}")
        t.table["POST /c/\(String(repeating: "a", count: 16))/cmd"] = (200, #"{"id":"x","result":"queued"}"#)
        let kid = Kid(url: "https://r.example", id: String(repeating: "a", count: 16), token: "tok", name: "Bi")
        let api = KidClient(kid, transport: t)
        let s = try await api.state()
        let id = try await api.answer(s.requests[0], ok: true, min: 15)
        #expect((t.calls.map { $0.httpMethod! }) == (["GET", "GET", "PUT", "POST"]))   // state, (state again in changeRules), rules, cmd
        #expect((t.calls[0].value(forHTTPHeaderField: "X-KG-Token")) == ("tok"))
        #expect((t.calls[0].value(forHTTPHeaderField: "X-KG-Name")) == ("Apple Watch"))
        let put = try JSON.parse(t.calls[2].httpBody!)
        #expect((put["bonus"]["min"].int) == (25)); #expect((put["pinHash"].string) == nil)
        let cmd = try JSON.parse(t.calls[3].httpBody!)
        #expect((cmd["type"].string) == ("answer")); #expect((cmd["min"].int) == (15)); #expect((cmd["id"].string) == (id))
        #expect((cmd["req"].string) == ("r1"))
    }

    @Test func testFormat() {
        #expect((Fmt.duration(ms: 3_900_000)) == ("1h 05m"))
        #expect((Fmt.duration(ms: -5)) == ("0m"))
        #expect((Fmt.countdown(seconds: 605)) == ("10:05"))
    }
}
