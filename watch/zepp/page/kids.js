import { back, push } from "@zos/router";
import { LocalStorage } from "@zos/storage";
import { BasePage } from "@zeppos/zml/base-page";
import { Col, C, t, ask } from "../utils/ui";

// All connected kids in one list: tap one to show it on the home page; "+ Thêm con" connects another.

const ls = new LocalStorage();

Page(
  BasePage({
    build() {
      this.col = new Col();
      this.col.text(t("loading"), { color: C.mute });
      ask(this, { method: "KIDS" })
        .then(({ kids = [] }) => this.draw(kids))
        .catch(() => this.draw(null));
    },
    draw(kids) {
      const col = this.col;
      col.clear();
      col.text(t("kidsH"), { size: 28, color: C.mute });
      col.gap(8);
      if (!kids) col.text(t("noPhone"), { color: C.red });
      const cur = +ls.getItem("kid", 0) || 0;
      (kids || []).forEach((name, i) => {
        const on = i === cur;
        col.btn((on ? "✓ " : "") + name, () => {
          try {
            ls.setItem("kid", i);
          } catch (e) {}
          back();
        }, on ? { color: C.green, press: C.greenP } : {});
      });
      col.gap(6);
      col.btn("+ " + t("addKid"), () => push({ url: "page/pair", params: JSON.stringify({ add: true }) }), { h: 56, size: 24 });
      col.end();
    },
  })
);
