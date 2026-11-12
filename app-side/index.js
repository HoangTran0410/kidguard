import { BaseSideService } from "@zeppos/zml/base-side";

// Runs inside the Zepp app on the paired phone, so it can reach the internet.
// With relay settings (url, kid id, parent token) it reads the real KidGuard state
// (GET <relay>/c/<id>/state, header X-KG-Token, see kidguard docs/protocol.md);
// without them it returns demo data so the watch UI can be tried in the simulator.

const DEMO = {
  demo: true,
  leftMs: 45 * 60000,
  usedMs: 75 * 60000,
  limitMin: 120,
  battery: 62,
  charging: false,
  current: "YouTube Kids",
  model: "Oppo A5s",
};

function pick(s) {
  const cur = (s.apps || []).find((a) => a.pkg === s.current);
  return {
    demo: false,
    leftMs: s.leftMs ?? -1,
    usedMs: s.usedMs ?? 0,
    limitMin: s.limitMin ?? 0,
    battery: s.battery ?? -1,
    charging: !!s.charging,
    current: s.screenOn === false ? "" : (cur && cur.label) || "",
    model: s.model || "",
  };
}

async function getState() {
  const url = settings.settingsStorage.getItem("relay");
  const id = settings.settingsStorage.getItem("kid");
  const token = settings.settingsStorage.getItem("token");
  if (!url || !id || !token) return DEMO;
  const r = await fetch({
    url: `${url.replace(/\/$/, "")}/c/${id}/state`,
    method: "GET",
    headers: { "X-KG-Token": token },
  });
  const body = typeof r.body === "string" ? JSON.parse(r.body) : r.body;
  return pick(body.state || body);
}

AppSideService(
  BaseSideService({
    onInit() {},
    onRequest(req, res) {
      if (req.method === "GET_STATE") {
        getState()
          .then((s) => res(null, { state: s }))
          .catch((e) => res(null, { error: String(e) }));
      } else if (req.method === "ASK_MORE") {
        // Not wired yet: KidGuard has no relay route for a child time request.
        res(null, { ok: true, demo: true });
      }
    },
    onRun() {},
    onDestroy() {},
  })
);
