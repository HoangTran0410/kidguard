// Settings page inside the Zepp app (parent's phone). Cards: how to connect, kids (remove), quick messages of this watch
// (settingsStorage "qmsgs": shown first under "Câu có sẵn", before the family's saved messages = rules.msgs from the
// page), advanced (paste the relay link the page shows under Settings > connection:
// https://<relay>/go#c=<id>&k=<token>&n=<name>; relay address override). Text is vi + en (the settings page has no
// locale API we rely on), Vietnamese first.

const C = { bg: "#f2f3f7", card: "#fff", text: "#1c1c1e", mute: "#8a8a8e", line: "#ececf0", blue: "#2f6bff", red: "#e5484d", soft: "#eef3ff" };
const MAX_Q = 12;

function parse(link) {
  try {
    const s = String(link || "").trim();
    const m = /^(https:\/\/[^/#?]+)\/go#(.+)$/.exec(s);
    if (!m) return null;
    const h = {};
    m[2].split("&").forEach((kv) => {
      const i = kv.indexOf("=");
      if (i > 0) h[kv.slice(0, i)] = decodeURIComponent(kv.slice(i + 1));
    });
    if (!/^[0-9a-f]{16}$/.test(h.c || "") || !h.k) return null;
    // plan B: the link opens the website (no page on the relay); its relay = r=, else the author's relay
    const url = /^https:\/\/[^/#?]+$/.test(h.r || "") ? h.r : (m[1] === "https://kid-guard.pages.dev" || m[1] === "https://kidguard.asia") ? "https://kg-relay.99-hoangtran.workers.dev" : m[1];
    return { url, id: h.c, token: h.k, name: (h.n || "").slice(0, 20) || "Con" };
  } catch (e) {
    return null;
  }
}

const json = (st, key) => {
  try {
    const v = JSON.parse(st.getItem(key) || "[]");
    return Array.isArray(v) ? v : [];
  } catch (e) {
    return [];
  }
};

const card = (title, sub, kids) =>
  View({ style: { background: C.card, borderRadius: "14px", padding: "14px 16px", marginBottom: "14px" } }, [
    Text({ bold: true, style: { fontSize: "17px", color: C.text } }, title),
    sub ? Text({ style: { fontSize: "13px", color: C.mute, marginTop: "4px", lineHeight: "18px" } }, sub) : null,
    View({ style: { marginTop: "8px" } }, kids),
  ]);

const row = (main, sub, btn) =>
  View({ style: { display: "flex", alignItems: "center", justifyContent: "space-between", padding: "10px 0", borderTop: `1px solid ${C.line}` } }, [
    View({ style: { flex: 1, minWidth: 0, paddingRight: "10px" } }, [
      Text({ style: { fontSize: "15px", color: C.text, wordBreak: "break-word" } }, main),
      sub ? Text({ style: { fontSize: "12px", color: C.mute, marginTop: "2px" } }, sub) : null,
    ]),
    btn,
  ]);

const rmBtn = (onClick) =>
  Button({ label: "Xoá", onClick, style: { fontSize: "13px", padding: "4px 14px", borderRadius: "14px", background: "#fdecec", color: C.red, border: "none" } });

const step = (n, text) =>
  View({ style: { display: "flex", alignItems: "flex-start", marginTop: "8px" } }, [
    Text({ bold: true, style: { width: "22px", height: "22px", lineHeight: "22px", borderRadius: "11px", background: C.soft, color: C.blue, fontSize: "12px", textAlign: "center", marginRight: "10px", flexShrink: 0 } }, String(n)),
    Text({ style: { fontSize: "14px", color: C.text, lineHeight: "20px", flex: 1 } }, text),
  ]);

AppSettingsPage({
  build(props) {
    const st = props.settingsStorage;
    const kids = json(st, "kids");
    const qm = json(st, "qmsgs").map((m) => String(m)).filter(Boolean);
    const saveKids = (k) => st.setItem("kids", JSON.stringify(k));
    const saveQ = (q) => st.setItem("qmsgs", JSON.stringify(q));
    const host = (u) => String(u || "").replace(/^https:\/\//, "");

    return View({ style: { background: C.bg, minHeight: "100vh", padding: "16px 14px 28px" } }, [
      View({ style: { padding: "4px 4px 14px" } }, [
        Text({ bold: true, style: { fontSize: "24px", color: C.text } }, "KidGuard"),
        Text({ style: { fontSize: "13px", color: C.mute, marginTop: "2px" } }, "Điện thoại của con trên cổ tay · Your child's phone on your wrist"),
      ]),

      card("Kết nối đồng hồ", "Connect the watch", [
        step(1, "Mở KidGuard trên đồng hồ, bấm \"Kết nối\": đồng hồ hiện mã 6 số."),
        step(2, "Trên trang KidGuard (máy bố mẹ): chọn bé → Cài đặt → Đồng hồ, nhập mã."),
        step(3, "Xong. Muốn gỡ: bấm \"Ngắt kết nối\" ở đó, hoặc Xoá bên dưới."),
      ]),

      card(`Các con (${kids.length})`, kids.length ? "Kids on this watch" : "Chưa có bé nào. No kid yet.",
        kids.map((k, i) => row(k.name || "Con", host(k.url), rmBtn(() => saveKids(kids.filter((_, j) => j !== i)))))),

      card(`Câu nhắn sẵn (${qm.length}/${MAX_Q})`,
        "Hiện đầu tiên ở \"Câu có sẵn\" trên đồng hồ, trước các câu mẫu của gia đình (lưu ở trang KidGuard). Quick messages of this watch.",
        qm.map((m, i) => row(m, "", rmBtn(() => saveQ(qm.filter((_, j) => j !== i))))).concat(
          qm.length < MAX_Q
            ? [TextInput({
                label: "+ Thêm câu / Add a message",
                placeholder: "Về nhà ăn cơm nhé",
                subStyle: { color: C.mute, fontSize: "12px" },
                labelStyle: { color: C.blue, fontSize: "15px", padding: "10px 0", borderTop: qm.length ? `1px solid ${C.line}` : "none" },
                onChange: (v) => {
                  const m = String(v || "").trim().slice(0, 120);
                  if (m && !qm.includes(m)) saveQ(qm.concat(m));
                },
              })]
            : [])),

      card("Nâng cao", "Advanced", [
        TextInput({
          label: "Dán link relay (thay cho mã 6 số)",
          placeholder: "https://…/go#c=…&k=…&n=…",
          subStyle: { color: C.mute, fontSize: "12px" },
          labelStyle: { fontSize: "15px", color: C.text, padding: "10px 0" },
          onChange: (v) => {
            const k = parse(v);
            if (!k) return st.setItem("err", "Link không đúng / Bad link");
            st.setItem("err", "");
            saveKids(kids.filter((x) => x.id !== k.id).concat(k));
          },
        }),
        TextInput({
          label: "Địa chỉ relay",
          placeholder: "https://kg-relay.99-hoangtran.workers.dev",
          value: st.getItem("relay") || "",
          subStyle: { color: C.mute, fontSize: "12px" },
          labelStyle: { fontSize: "15px", color: C.text, padding: "10px 0", borderTop: `1px solid ${C.line}` },
          onChange: (v) => {
            const r = String(v || "").trim().replace(/\/+$/, "");
            if (!r || /^https:\/\/[^/#?\s]+$/.test(r)) st.setItem("relay", r);
          },
        }),
        st.getItem("err") ? Text({ style: { color: C.red, fontSize: "13px", marginTop: "6px" } }, st.getItem("err")) : null,
      ]),
    ]);
  },
});
