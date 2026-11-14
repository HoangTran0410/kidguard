import * as hmUI from "@zos/ui";
import { back, push } from "@zos/router";
import { BasePage } from "@zeppos/zml/base-page";
import { Col, C, t, done, toast, ask, rcSave } from "../utils/ui";

// Generic option list. params: {kid, title, items: [{label, m, p, ok?, rl?} | {label, page, pp}]}
// m = side-service method (CMD / RULES / ANSWER), p = its params; a tap runs it and goes back.

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
      const o = this.o;
      const col = new Col();
      col.text(o.title || "", { size: 28, color: C.mute });
      col.gap(8);
      // stay: true (ring start / stop) keeps the list open after a tap, so the opposite action is one tap away
      const stat = o.stay ? col.text("", { size: 22, color: C.mute }) : null;
      let busy = false;
      (o.items || []).forEach((it) => {
        col.btn(it.label, () => {
          if (it.page) return push({ url: it.page, params: JSON.stringify(it.pp || {}) });
          if (busy) return;
          busy = true;
          toast(t("sending"));
          ask(this, { method: it.m, params: { kid: o.kid, ...(it.p || {}) } })
            .then((r) => {
              busy = false;
              if (done(r, it.ok)) {
                if (r.id) rcSave(o.kid, r.id, it.rl || it.label);
                if (!o.stay) back();
                else if (stat) stat.setProperty(hmUI.prop.TEXT, t("sentX", it.rl || it.label));
              }
            })
            .catch(() => {
              busy = false;
              toast(t("noPhone"));
            });
        });
      });
      col.end();
    },
  })
);
