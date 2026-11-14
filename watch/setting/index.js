// Settings page inside the Zepp app (parent's phone): list / remove kids. Kids are added with a code from the
// watch (Kết nối screen -> KidGuard page, Settings > Watch); fallback: paste the relay link the parent page shows
// under Settings > connection: https://<relay>/go#c=<id>&k=<token>&n=<name>. Optional: relay address override.

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
    return { url: m[1], id: h.c, token: h.k, name: (h.n || "").slice(0, 20) || "Con" };
  } catch (e) {
    return null;
  }
}

AppSettingsPage({
  state: { err: "" },
  build(props) {
    const st = props.settingsStorage;
    let kids = [];
    try {
      kids = JSON.parse(st.getItem("kids") || "[]");
    } catch (e) {}
    const save = (k) => st.setItem("kids", JSON.stringify(k));

    const list = kids.map((k, i) =>
      View({ style: { display: "flex", alignItems: "center", justifyContent: "space-between", padding: "10px 0", borderBottom: "1px solid #eee" } }, [
        Text({ style: { fontSize: "16px" } }, `${k.name}  ·  ${k.id.slice(0, 6)}…`),
        Button({
          label: "Xoá / Remove",
          style: { fontSize: "13px", borderRadius: "16px", background: "#f44336", color: "#fff" },
          onClick: () => save(kids.filter((_, j) => j !== i)),
        }),
      ])
    );

    return View({ style: { padding: "12px 16px" } }, [
      Text({ bold: true, style: { fontSize: "20px", marginBottom: "6px" } }, "KidGuard"),
      Text(
        { style: { fontSize: "14px", color: "#666", marginBottom: "12px" } },
        "Cách nhanh: mở KidGuard trên đồng hồ → \"Kết nối\" để lấy mã 6 số, rồi nhập mã ở trang KidGuard → Cài đặt → Đồng hồ.\n" +
          "Quick way: open KidGuard on the watch → Connect to get a 6-digit code, then enter it on the KidGuard page → Settings → Watch."
      ),
      Section({ title: kids.length ? "Các con / Kids" : "Chưa có / None yet" }, list),
      Text({ style: { fontSize: "13px", color: "#888", margin: "10px 0 4px" } }, "Hoặc dán link relay / Or paste the relay link (Settings → connection):"),
      TextInput({
        label: "Dán link relay / Paste relay link",
        placeholder: "https://…/go#c=…&k=…&n=…",
        onChange: (v) => {
          const k = parse(v);
          if (!k) {
            st.setItem("err", "Link không đúng / Bad link");
            return;
          }
          st.setItem("err", "");
          save(kids.filter((x) => x.id !== k.id).concat(k));
        },
      }),
      TextInput({
        label: "Relay (tuỳ chọn / optional)",
        placeholder: "https://kidguard.99-hoangtran.workers.dev",
        defaultValue: st.getItem("relay") || "",
        onChange: (v) => {
          const r = String(v || "").trim().replace(/\/+$/, "");
          if (!r || /^https:\/\/[^/#?\s]+$/.test(r)) st.setItem("relay", r);
        },
      }),
      st.getItem("err") ? Text({ style: { color: "#f44336", fontSize: "14px" } }, st.getItem("err")) : null,
    ]);
  },
});
