import { BasePage } from "@zeppos/zml/base-page";
import { Col, C, t, fmtMin, done, toast, ask } from "../utils/ui";

// Today's apps by use; tap = block / unblock (rules.blocked, whole rules object re-sent by the side service).

Page(
  BasePage({
    onInit(params) {
      try {
        this.kid = JSON.parse(params || "{}").kid || 0;
      } catch (e) {
        this.kid = 0;
      }
    },
    build() {
      this.col = new Col();
      this.col.text(t("loading"), { color: C.mute });
      this.load();
    },
    load() {
      ask(this, { method: "STATE", params: { kid: this.kid } })
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
      col.text(t("appsH"), { size: 28, color: C.mute });
      col.text(t("appsHint"), { size: 20, color: C.mute });
      col.gap(6);
      if (!s.apps.length) col.text(t("noApps"), { color: C.mute });
      let busy = false;
      s.apps.forEach((a) => {
        col.btn(`${a.l} · ${fmtMin(a.ms)}${a.b ? "  ⛔" : ""}`, () => {
          if (busy) return;
          busy = true;
          toast(t("sending"));
          ask(this, { method: "RULES", params: { kid: this.kid, op: { k: "block", p: a.p, on: !a.b } } })
            .then((r) => {
              busy = false;
              if (done(r, a.b ? t("unblocked", a.l) : t("blocked", a.l))) this.load();
            })
            .catch(() => {
              busy = false;
              toast(t("noPhone"));
            });
        }, { size: 24, h: 60, color: a.b ? C.red : C.btn, press: a.b ? C.redP : C.btnP });
      });
      col.end();
    },
  })
);
