import * as hmUI from "@zos/ui";
import { back } from "@zos/router";
import { LocalStorage } from "@zos/storage";
import { BasePage } from "@zeppos/zml/base-page";
import { Col, C, t, ask } from "../utils/ui";

// Connect a kid, TV-pairing style: a 6-digit code is shown here, the parent types it on the KidGuard page
// (Settings > Watch). The side service (app-side/index.js) gets the code from the relay and polls until the
// relay hands the kid over. A new code when it expires. Polling only runs while this screen is open.

const ls = new LocalStorage();

Page(
  BasePage({
    state: { code: "", exp: 0, busy: false, done: false },
    build() {
      this.col = new Col();
      this.start();
      this.tick = setInterval(() => this.onTick(), 1000);
    },
    onDestroy() {
      if (this.tick) clearInterval(this.tick);
      if (this.leave) clearTimeout(this.leave);
    },
    start() {
      const st = this.state;
      st.busy = true;
      st.code = "";
      this.n = 0;
      this.draw(t("loading"), C.mute);
      ask(this, { method: "PAIR_NEW" })
        .then((r) => {
          st.busy = false;
          if (r.error) return this.fail(r.error);
          st.code = r.code;
          st.exp = r.exp;
          this.draw();
        })
        .catch(() => {
          st.busy = false;
          this.fail(t("noPhone"));
        });
    },
    fail(msg) {
      this.state.code = "";
      this.draw(msg, C.red);
    },
    // wording: code screen, or a single message + retry / demo
    draw(msg, color) {
      const col = this.col, st = this.state;
      col.clear();
      this.left = null;
      col.text(t("connect"), { size: 30, color: C.mute });
      if (!st.code) {
        col.gap(8);
        col.text(msg || "", { color: color || C.mute });
        if (!st.busy) col.btn(t("retry"), () => this.start());
      } else {
        col.gap(6);
        col.text(st.code.slice(0, 3) + " " + st.code.slice(3), { size: 76 });
        col.gap(4);
        col.text(t("pairHow"), { size: 22, color: 0xdddddd });
        this.left = col.text(this.leftText(), { size: 22, color: C.mute });
      }
      col.gap(10);
      col.btn(t("tryDemo"), () => {
        try {
          ls.setItem("demo", "1");
        } catch (e) {}
        back();
      }, { h: 52, size: 22 });
      col.end();
    },
    leftText() {
      const s = Math.max(0, Math.round((this.state.exp - Date.now()) / 1000));
      return t("timeLeft", Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"));
    },
    onTick() {
      const st = this.state;
      if (st.done || st.busy || !st.code) return;
      if (Date.now() >= st.exp) return this.start();   // expired: a new code
      if (this.left) this.left.setProperty(hmUI.prop.TEXT, this.leftText());
      if (++this.n % 3) return;   // poll about every 3 s
      st.busy = true;
      ask(this, { method: "PAIR_POLL", params: { code: st.code } }, 8000)
        .then((r) => {
          st.busy = false;
          if (r.expired) return this.start();
          if (r.done) return this.ok(r);
        })
        .catch(() => {
          st.busy = false;   // a missed poll: try again on the next round
        });
    },
    ok(r) {
      this.state.done = true;
      try {
        ls.setItem("kid", r.index);
        ls.removeItem("demo");
      } catch (e) {}
      const col = this.col;
      col.clear();
      col.gap(60);
      col.text(r.name, { size: 40 });
      col.text("✓ " + t("connected"), { size: 30, color: 0x66bb6a });
      this.leave = setTimeout(() => back(), 1800);
    },
  })
);
