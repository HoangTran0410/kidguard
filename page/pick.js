import { back, push } from "@zos/router";
import { BasePage } from "@zeppos/zml/base-page";
import { Col, C, t, done, toast } from "../utils/ui";

// Generic option list. params: {kid, title, items: [{label, m, p, ok?} | {label, page, pp}]}
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
      let busy = false;
      (o.items || []).forEach((it) => {
        col.btn(it.label, () => {
          if (it.page) return push({ url: it.page, params: JSON.stringify(it.pp || {}) });
          if (busy) return;
          busy = true;
          toast(t("sending"));
          this.request({ method: it.m, params: { kid: o.kid, ...(it.p || {}) } })
            .then((r) => {
              busy = false;
              if (done(r, it.ok)) back();
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
