import { BasePage } from "@zeppos/zml/base-page";
import { Col, C, t, ago, hhmm, done, toast, ask, rcSave } from "../utils/ui";

// Read-only pages: kind "loc" (last location + ask for a fresh fix) or "events" (zone / battery / new app …).

Page(
  BasePage({
    onInit(params) {
      try {
        this.o = JSON.parse(params || "{}");
      } catch (e) {
        this.o = {};
      }
    },
    build() {
      this.col = new Col();
      this.col.text(t("loading"), { color: C.mute });
      this.load();
    },
    load() {
      ask(this, { method: "STATE", params: { kid: this.o.kid } })
        .then((r) => (r.error ? this.err(r.error) : this.render(r.state)))
        .catch(() => this.err(t("noPhone")));
    },
    err(e) {
      this.col.clear();
      this.col.text(e, { color: C.red });
    },
    render(s) {
      const col = this.col;
      col.clear();
      if (this.o.kind === "events") {
        col.text(t("events"), { size: 28, color: C.mute });
        if (!s.events.length) col.text(t("noEvents"), { color: C.mute });
        s.events.forEach((e) => col.text(`${hhmm(e.t)}  ${e.text}`, { size: 24, center: false }));
        col.end();
        return;
      }
      col.text(t("where"), { size: 28, color: C.mute });
      if (s.zone) col.text(`${s.zone.text}\n${ago(s.zone.t)}`, { size: 28 });
      if (s.loc) {
        col.text(t("fixLine", ago(s.loc.t), s.loc.acc), { size: 24, color: 0xdddddd });
        col.text(`${s.loc.lat.toFixed(5)}, ${s.loc.lng.toFixed(5)}`, { size: 22, color: C.mute });
      } else {
        col.text(t("noLoc"), { color: C.mute });
      }
      col.gap(8);
      col.btn(t("locate"), () => {
        toast(t("sending"));
        ask(this, { method: "CMD", params: { kid: this.o.kid, c: { type: "locate" } } })
          .then((r) => {
            if (r && r.id) rcSave(this.o.kid, r.id, t("where"));
            if (done(r, t("locateSent"))) setTimeout(() => this.load(), 30000);
          })
          .catch(() => toast(t("noPhone")));
      }, { color: C.blue, press: C.blueP });
      col.btn(t("refresh"), () => this.load(), { h: 56, size: 24 });
      col.end();
    },
  })
);
