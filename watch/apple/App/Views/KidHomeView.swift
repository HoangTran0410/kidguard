import SwiftUI
import KidGuardCore

struct KidHomeView: View {
    let kid: Kid
    @StateObject private var m: KidHomeModel
    @State private var confirmLock = false
    init(kid: Kid) { self.kid = kid; _m = StateObject(wrappedValue: KidHomeModel(kid: kid)) }

    var body: some View {
        List {
            if let s = m.s {
                header(s)
                ForEach(s.requests) { requestRow($0) }
                actions(s)
            } else if let e = m.error {
                Text(e).font(.footnote).foregroundStyle(.red)
                Button("retry") { Task { await m.refresh() } }
            } else {
                ProgressView()
            }
            statusRow
        }
        .navigationTitle(kid.name)
        .onAppear { m.start() }
        .onDisappear { m.stop() }
    }

    // time left / why / battery / last seen
    @ViewBuilder private func header(_ s: Summary) -> some View {
        Section {
            if s.lost { Text("home.lost").foregroundStyle(.red) }
            if let line = whyLine(s) {
                Text(line).font(.headline)
            } else if let left = s.leftMs {
                VStack(alignment: .leading) {
                    Text(Fmt.duration(ms: left)).font(.title2.bold()).monospacedDigit()
                    Text("home.left").font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                Text("home.nolimit")
            }
            if !s.current.isEmpty { Label(s.current, systemImage: "app") .font(.footnote) }
            HStack {
                if let b = s.battery { Label("\(b)%", systemImage: s.charging ? "battery.100.bolt" : "battery.50") }
                Spacer()
                Text(ago(ms: s.seen)).foregroundStyle(.secondary)
            }.font(.footnote)
        }
    }

    // same wording as the Zepp watch `nowLine()`: empty when the phone is simply in use
    private func whyLine(_ s: Summary) -> String? {
        let until = s.nowUntil > 0 ? "\n" + String(format: String(localized: "why.until"), clock(ms: s.nowUntil)) : ""
        switch s.nowWhy {
        case "": return nil
        case "daily": return String(localized: "why.daily") + until
        case "paused": return String(localized: "why.paused") + until
        case "schedule": return String(localized: "why.schedule") + (s.nowName.isEmpty ? "" : ": " + s.nowName) + until
        case "bedtime": return String(localized: "why.bed") + until
        case "pass": return String(localized: "why.pass") + until
        case "code": return String(localized: "why.code") + until
        default: return s.nowWhy + until
        }
    }

    @ViewBuilder private func requestRow(_ q: KidRequest) -> some View {
        Section {
            if q.kind == .app {
                Text(String(format: String(localized: "req.app"), q.app)).font(.headline)
            } else {
                Text(String(format: String(localized: "req.time"), q.min)).font(.headline)
            }
            if !q.note.isEmpty { Text(q.note).font(.footnote).foregroundStyle(.secondary) }
            if q.kind == .app {
                Button("req.allow") { m.send(String(localized: "act.answer")) { try await m.api.answer(q, ok: true, min: 0) } }
            } else {
                Button("+15") { m.send(String(localized: "act.answer")) { try await m.api.answer(q, ok: true, min: 15) } }
                Button("+30") { m.send(String(localized: "act.answer")) { try await m.api.answer(q, ok: true, min: 30) } }
            }
            Button("req.deny", role: .destructive) { m.send(String(localized: "act.answer")) { try await m.api.answer(q, ok: false, min: 0) } }
        } header: { Text("req.title") }
    }

    @ViewBuilder private func actions(_ s: Summary) -> some View {
        Section {
            Button { confirmLock = true } label: { Label("act.lock", systemImage: "lock") }
                .confirmationDialog("act.lock.ask", isPresented: $confirmLock) {
                    Button("act.lock") { m.send(String(localized: "act.lock")) { try await m.api.command("lock") } }
                }
            Button { m.send(String(localized: "act.ring")) { try await m.api.command("ring") } } label: { Label("act.ring", systemImage: "bell") }
            Button { m.send(String(localized: "act.ringStop")) { try await m.api.command("ringStop") } } label: { Label("act.ringStop", systemImage: "bell.slash") }
            Button { m.send(String(localized: "act.locate")) { try await m.api.command("locate") } } label: { Label("act.locate", systemImage: "location") }
            NavigationLink { MessagesView(model: m, msgs: s.msgs) } label: { Label("act.message", systemImage: "text.bubble") }
            NavigationLink { WhereView(kid: kid, model: m) } label: { Label("act.where", systemImage: "map") }
        }
        .disabled(m.busy)
    }

    // "sent" first (only queued), then the phone's receipt
    @ViewBuilder private var statusRow: some View {
        switch m.status {
        case .none: EmptyView()
        case .sent(let l): Text(String(format: String(localized: "st.sent"), l)).font(.footnote)
        case .done(let l): Text(String(format: String(localized: "st.done"), l)).font(.footnote).foregroundStyle(.green)
        case .failed(let l, let why): Text(String(format: String(localized: "st.failed"), l, why)).font(.footnote).foregroundStyle(.red)
        case .expired(let l): Text(String(format: String(localized: "st.expired"), l)).font(.footnote).foregroundStyle(.orange)
        case .waiting(let l): Text(String(format: String(localized: "st.waiting"), l)).font(.footnote).foregroundStyle(.secondary)
        }
    }
}

struct MessagesView: View {
    @ObservedObject var model: KidHomeModel
    let msgs: [String]
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        // presets from rules.msgs, else the built-in list (localized)
        let list = msgs.isEmpty ? ["m1", "m2", "m3", "m4", "m5"].map { String(localized: String.LocalizationValue($0)) } : msgs
        List(list, id: \.self) { text in
            Button(text) {
                model.send(String(localized: "act.message")) { try await model.api.message(text) }
                dismiss()
            }
        }
        .navigationTitle("act.message")
    }
}
