import * as hmUI from "@zos/ui";
import { push } from "@zos/router";
import { LocalStorage } from "@zos/storage";
import { BasePage } from "@zeppos/zml/base-page";
import { Col, C, t, hhmm, done, toast, ask, rcSave, chatSeenKey } from "../utils/ui";

// Family chat with one kid (protocol "Family chat"): the thread from state.chat, newest first, and a reply by the
// system keyboard (typing or voice-to-text, Zepp OS 4.0+), or a saved quick message. A reply = the `message` command
// (the phone adds it to the thread). Voice notes / photos the child sent show as a line: the watch can't play them.

const ls = new LocalStorage();

function closeKb() {
  try { hmUI.deleteKeyboard(); } catch (e) {}
}

Page(
  BasePage({
    onInit(params) {
      try {
        this.o = JSON.parse(params || "{}");
      } catch (e) {
        this.o = {};
      }
      this.mine = []; // sent from here, not in state.chat yet
    },
    onDestroy() {
      this.dead = true;
      if (this.timer) clearInterval(this.timer);
      closeKb();
    },
    build() {
      this.col = new Col();
      this.col.text(t("loading"), { color: C.mute });
      this.load();
      this.timer = setInterval(() => this.load(), 20000);
    },
    load() {
      ask(this, { method: "STATE", params: { kid: this.o.kid } })
        .then((r) => {
          if (this.dead) return;
          if (r.error) return this.err(r.error === "unpaired" ? t("unpaired") : r.error);
          this.s = r.state;
          this.render();
        })
        .catch(() => !this.dead && this.err(t("noPhone")));
    },
    err(e) {
      this.col.clear();
      this.col.text(e, { color: C.red });
      this.col.btn(t("retry"), () => this.load());
    },
    send(text) {
      const x = String(text || "").trim().slice(0, 500);
      if (!x) return;
      toast(t("sending"));
      ask(this, { method: "CMD", params: { kid: this.o.kid, c: { type: "message", text: x } } })
        .then((r) => {
          if (!done(r, t("sent"))) return;
          if (r.id) rcSave(this.o.kid, r.id, t("msg"));
          this.mine.push({ t: Date.now(), x });
          if (this.s) this.render();
          setTimeout(() => !this.dead && this.load(), 2500);
        })
        .catch(() => toast(t("noPhone")));
    },
    kb(type) {
      try {
        hmUI.createKeyboard({
          inputType: type,
          text: "",
          onComplete: (_, res) => {
            closeKb();
            this.send(res && res.data);
          },
          onCancel: () => closeKb(),
        });
      } catch (e) {
        closeKb();
        toast(t("noKb"));
      }
    },
    render() {
      const s = this.s;
      const col = this.col;
      col.clear();
      const chat = Array.isArray(s.chat) ? s.chat : [];
      // everything the child wrote is now read (home shows the unread count)
      const newest = chat.filter((c) => c.f === "k").reduce((m, c) => Math.max(m, c.t || 0), 0);
      try { if (newest) ls.setItem(chatSeenKey(this.o.kid), String(newest)); } catch (e) {}

      col.text(t("chatH", s.name || ""), { size: 28, color: C.mute });
      const it = hmUI.inputType || {};
      const canKb = typeof hmUI.createKeyboard === "function";
      if (canKb && it.VOICE != null) col.btn(t("speak"), () => this.kb(it.VOICE), { color: C.green, press: C.greenP });
      if (canKb) col.btn(t("type"), () => this.kb(it.CHAR != null ? it.CHAR : 0), { color: C.blue, press: C.blueP });
      col.btn(t("quick"), () =>
        push({
          url: "page/pick",
          params: JSON.stringify({
            kid: this.o.kid, title: t("quick"),
            items: (s.msgs && s.msgs.length ? s.msgs : ["m1", "m2", "m3", "m4", "m5"].map((k) => t(k)))
              .map((m) => ({ label: m.length > 40 ? m.slice(0, 38) + "…" : m, m: "CMD", p: { c: { type: "message", text: m } }, rl: t("msg") })),
          }),
        })
      );
      col.gap(10);

      // own sends the phone has not echoed yet (same text from a parent within 10 min = echoed)
      const echoed = (m) => chat.some((c) => c.f === "p" && c.x === m.x.slice(0, 160) && Math.abs((c.t || 0) - m.t) < 600000);
      this.mine = this.mine.filter((m) => !echoed(m) && Date.now() - m.t < 600000);
      const rows = chat.map((c) => ({ ...c, wait: false }))
        .concat(this.mine.map((m) => ({ t: m.t, f: "p", w: "", x: m.x, a: "", d: 0, wait: true })))
        .sort((a, b) => (b.t || 0) - (a.t || 0));
      if (!rows.length) col.text(t("chatEmpty"), { size: 24, color: C.mute });
      rows.forEach((c) => {
        const kid = c.f === "k";
        const who = kid ? s.name || t("kid") : c.w || t("parent");
        col.text(`${who} · ${c.wait ? t("chatWait") : hhmm(c.t)}`, { size: 20, color: C.mute, center: false });
        const att = c.a ? t("att_" + (["audio", "image", "video"].includes(c.a) ? c.a : "file"), c.d || "") : "";
        const body = [c.x, att].filter(Boolean).join("\n");
        col.text(body || "…", { size: 26, color: kid ? 0xffcc80 : 0xbbdefb, center: false });
        col.gap(8);
      });
      col.btn(t("refresh"), () => this.load(), { h: 56, size: 24 });
      col.end();
    },
  })
);
