import * as hmUI from "@zos/ui";
import { push } from "@zos/router";
import { LocalStorage } from "@zos/storage";
import { Vibrator } from "@zos/sensor";
import { BasePage } from "@zeppos/zml/base-page";
import { DEVICE_WIDTH as W } from "../utils/config/device";
import { Col, C, t, fmtMin, ago, hhmm, twoTap, done, toast, PAD } from "../utils/ui";

// Parent home: one kid at a time (tap the name to switch). Data comes from the side service
// on the parent's phone, which reads the KidGuard relay.

const ls = new LocalStorage();
const R = 84;
let seenAlerts = "";

function buzz() {
  try {
    new Vibrator().start();
  } catch (e) {}
}

function go(url, params) {
  push({ url, params: JSON.stringify(params) });
}

Page(
  BasePage({
    state: { kid: 0, kids: [], s: null, err: "" },
    onInit() {
      this.state.kid = +ls.getItem("kid", 0) || 0;
    },
    build() {
      this.col = new Col();
      this.col.text(t("loading"), { color: C.mute });
      this.loadKids();
      this.timer = setInterval(() => this.load(), 30000);
    },
    onResume() {
      this.load();
    },
    onDestroy() {
      if (this.timer) clearInterval(this.timer);
    },
    loadKids() {
      this.request({ method: "KIDS" })
        .then(({ kids = [] }) => {
          this.state.kids = kids;
          if (this.state.kid >= kids.length) this.state.kid = 0;
          this.load();
        })
        .catch(() => this.showErr(t("noPhone")));
    },
    load() {
      this.request({ method: "STATE", params: { kid: this.state.kid } })
        .then((r) => {
          if (r.error) return this.showErr(r.error);
          this.state.s = r.state;
          this.state.err = "";
          this.render();
        })
        .catch(() => this.showErr(t("noPhone")));
    },
    showErr(e) {
      this.state.err = e;
      this.render();
    },
    act(method, params, okText) {
      return this.request({ method, params: { kid: this.state.kid, ...params } })
        .then((r) => {
          if (done(r, okText)) setTimeout(() => this.load(), 1500);
          return r;
        })
        .catch(() => toast(t("noPhone")));
    },
    switchKid() {
      const n = this.state.kids.length;
      if (n < 2) return;
      this.state.kid = (this.state.kid + 1) % n;
      ls.setItem("kid", this.state.kid);
      this.state.s = null;
      this.load();
    },
    render() {
      const col = this.col;
      col.clear();
      const s = this.state.s;
      const kidOpt = { kid: this.state.kid };

      if (!s) {
        col.text(this.state.err || t("loading"), { color: this.state.err ? C.red : C.mute });
        col.btn(t("retry"), () => this.loadKids());
        return;
      }

      // header: name (tap = next kid) + how fresh the data is
      const online = s.seen && Date.now() - s.seen < 3 * 60000;
      col.text(s.name, { size: 32 });
      if (this.state.kids.length > 1) col.btn(t("nextKid"), () => this.switchKid(), { h: 48, size: 22 });
      col.text((online ? "● " + t("online") : "○ " + t("seen", ago(s.seen))) + (s.demo ? " · " + t("demo") : ""), {
        size: 22, color: online ? 0x66bb6a : C.amber,
      });
      if (this.state.err) col.text(this.state.err, { size: 22, color: C.red });

      // vibrate once per new SOS / request while the app is open
      const sig = s.sos.map((x) => x.id).concat(s.req.map((x) => x.id)).join(",");
      if (sig && sig !== seenAlerts) buzz();
      seenAlerts = sig;

      // SOS
      s.sos.forEach((x) => {
        col.gap(6);
        col.btn(t("sosAt", hhmm(x.t)), () =>
          go("page/pick", {
            kid: this.state.kid, title: t("sosAt", hhmm(x.t)),
            items: [
              { label: t("sosDone"), m: "CMD", p: { c: { type: "sosAck", sos: x.id } } },
              { label: t("where"), page: "page/info", pp: { kind: "loc", kid: this.state.kid } },
              { label: t("ring"), m: "CMD", p: { c: { type: "ring" } } },
            ],
          }), { color: C.red, press: C.redP });
      });

      // time requests
      s.req.forEach((x) => {
        col.gap(6);
        col.text(t("reqLine", x.min) + (x.note ? `\n“${x.note}”` : ""), { size: 24, color: 0xffcc80 });
        col.row(
          { label: t("yes"), color: C.green, press: C.greenP, fn: () => this.act("ANSWER", { id: x.id, ok: true, min: x.min }, t("granted", x.min)) },
          { label: t("no"), fn: () => this.act("ANSWER", { id: x.id, ok: false }, t("denied")) }
        );
      });

      // ring with minutes left
      col.gap(8);
      const cy = col.y + R;
      const noLimit = !s.limitMin || s.leftMs < 0;
      const frac = noLimit ? 0 : Math.min(1, s.leftMs / (s.limitMin * 60000));
      const color = frac > 0.3 ? 0x4caf50 : frac > 0.1 ? 0xff9800 : 0xf44336;
      const arc = { x: (W - 2 * R) / 2, y: cy - R, w: 2 * R, h: 2 * R, line_width: 14 };
      col.add(hmUI.widget.ARC, { ...arc, color: 0x333333, start_angle: -90, end_angle: 270 });
      if (frac > 0) col.add(hmUI.widget.ARC, { ...arc, color, start_angle: -90, end_angle: -90 + Math.round(360 * frac) });
      col.add(hmUI.widget.TEXT, { x: 0, y: cy - 40, w: W, h: 60, text: noLimit ? "∞" : fmtMin(s.leftMs), text_size: 52, color: C.text, align_h: hmUI.align.CENTER_H });
      col.add(hmUI.widget.TEXT, { x: 0, y: cy + 20, w: W, h: 30, text: t("left"), text_size: 22, color: C.mute, align_h: hmUI.align.CENTER_H });
      col.y = cy + R + 10;

      const lines = [t("used", fmtMin(s.usedMs)) + (noLimit ? "" : " / " + fmtMin(s.limitMin * 60000)) + (s.bonus ? `  (+${s.bonus}')` : "")];
      const paused = s.paused > Date.now();
      if (paused) lines.push(t("pausedTill", hhmm(s.paused)));
      if (s.bed && s.bed.on) lines.push(t("bedLine", s.bed.s, s.bed.e));
      lines.push(t("battery", s.battery >= 0 ? s.battery + "%" : "?") + (s.charging ? " ⚡" : ""));
      lines.push(s.screenOn ? (s.current ? t("now", s.current) : t("screenOn")) : t("screenOff"));
      if (s.zone) lines.push(`${s.zone.text} · ${ago(s.zone.t)}`);
      if (s.pending) lines.push(t("pending", s.pending));
      col.text(lines.join("\n"), { size: 24, color: 0xdddddd });
      col.gap(6);

      // actions
      col.row(
        { label: t("more"), color: C.green, press: C.greenP, fn: () => go("page/pick", {
          ...kidOpt, title: t("more"),
          items: [15, 30, 60, 120].map((n) => ({ label: `+${n >= 60 ? n / 60 + "h" : n + "'"}`, m: "RULES", p: { op: { k: "bonus", n } }, ok: t("granted", n) }))
            .concat(s.bonus ? [{ label: t("bonusClear"), m: "RULES", p: { op: { k: "bonusClear" } } }] : []),
        }) },
        paused
          ? { label: t("resume"), color: C.blue, press: C.blueP, fn: () => this.act("RULES", { op: { k: "resume" } }) }
          : { label: t("pause"), fn: () => go("page/pick", {
            ...kidOpt, title: t("pauseH"),
            items: [15, 30, 60, 120, 240].map((n) => ({ label: n >= 60 ? n / 60 + "h" : n + "'", m: "RULES", p: { op: { k: "pause", n } } })),
          }) }
      );
      const half = (W - 2 * PAD - 12) / 2;
      const yRow = col.y;
      twoTap(col, t("lock"), t("tapAgain"), () => this.act("CMD", { c: { type: "lock" } }), { x: PAD, w: half, size: 26, color: C.red, press: C.redP, advance: false });
      col.y = yRow;
      col.btn(t("ring"), () => go("page/pick", {
        ...kidOpt, title: t("ring"),
        items: [
          { label: t("ringStart"), m: "CMD", p: { c: { type: "ring" } } },
          { label: t("ringStop"), m: "CMD", p: { c: { type: "ringStop" } } },
        ],
      }), { x: PAD + half + 12, w: half, size: 26 });
      col.row(
        { label: t("where"), fn: () => go("page/info", { kind: "loc", kid: this.state.kid }) },
        { label: t("msg"), fn: () => go("page/pick", {
          ...kidOpt, title: t("msg"),
          items: ["m1", "m2", "m3", "m4", "m5"].map((k) => ({ label: t(k), m: "CMD", p: { c: { type: "message", text: t(k) } } })),
        }) }
      );
      col.row(
        { label: t("apps"), fn: () => go("page/apps", { kid: this.state.kid }) },
        { label: s.bed && s.bed.on ? t("bedOff") : t("bedOn"), fn: () => this.act("RULES", { op: { k: "bed", on: !(s.bed && s.bed.on) } }) }
      );
      col.row(
        { label: t("limit"), fn: () => go("page/pick", {
          ...kidOpt, title: t("limitH") + (s.byDay ? "\n" + t("limitByDay") : ""),
          items: [30, 60, 90, 120, 180, 0].map((n) => ({ label: n ? fmtMin(n * 60000) : t("noLimit"), m: "RULES", p: { op: { k: "limit", n } } })),
        }) },
        { label: t("events"), fn: () => go("page/info", { kind: "events", kid: this.state.kid }) }
      );
      col.btn(t("refresh"), () => { toast(t("loading")); this.load(); }, { h: 56, size: 24 });
      col.end();
    },
  })
);
