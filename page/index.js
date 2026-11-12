import * as hmUI from "@zos/ui";
import { getText } from "@zos/i18n";
import { BasePage } from "@zeppos/zml/base-page";
import { L } from "zosLoader:./index.[pf].layout.js";

const ACCENT = 0x4caf50;
const WARN = 0xff9800;
const BAD = 0xf44336;

function fmt(ms) {
  const m = Math.max(0, Math.round(ms / 60000));
  return m >= 60 ? `${Math.floor(m / 60)}h${String(m % 60).padStart(2, "0")}` : `${m}'`;
}

Page(
  BasePage({
    build() {
      this.bg = hmUI.createWidget(hmUI.widget.ARC, { ...L.arc, color: 0x333333, start_angle: -90, end_angle: 270 });
      this.arc = hmUI.createWidget(hmUI.widget.ARC, { ...L.arc, color: ACCENT, start_angle: -90, end_angle: -90 });
      this.big = hmUI.createWidget(hmUI.widget.TEXT, { ...L.big, text: "…" });
      this.sub = hmUI.createWidget(hmUI.widget.TEXT, { ...L.sub, text: getText("left") });
      this.info = hmUI.createWidget(hmUI.widget.TEXT, { ...L.info, text: "" });
      this.btn = hmUI.createWidget(hmUI.widget.BUTTON, {
        ...L.btn,
        text: getText("askMore"),
        click_func: () => this.askMore(),
      });
      this.load();
    },
    load() {
      this.request({ method: "GET_STATE" })
        .then(({ state, error }) => {
          if (error || !state) return this.info.setProperty(hmUI.prop.TEXT, getText("noConn"));
          this.show(state);
        })
        .catch(() => this.info.setProperty(hmUI.prop.TEXT, getText("noConn")));
    },
    show(s) {
      const limitMs = s.limitMin * 60000;
      const noLimit = !s.limitMin || s.leftMs < 0;
      const frac = noLimit ? 0 : Math.min(1, s.leftMs / limitMs);
      const color = frac > 0.3 ? ACCENT : frac > 0.1 ? WARN : BAD;
      this.arc.setProperty(hmUI.prop.MORE, { ...L.arc, color, start_angle: -90, end_angle: -90 + Math.round(360 * frac) });
      this.big.setProperty(hmUI.prop.TEXT, noLimit ? "∞" : fmt(s.leftMs));
      const lines = [
        `${getText("used")} ${fmt(s.usedMs)}${noLimit ? "" : " / " + fmt(limitMs)}`,
        `${getText("phone")} ${s.battery >= 0 ? s.battery + "%" : "?"}${s.charging ? " ⚡" : ""}`,
      ];
      if (s.current) lines.push(`${getText("now")} ${s.current}`);
      this.info.setProperty(hmUI.prop.TEXT, lines.join("\n"));
      if (s.demo) hmUI.showToast({ text: getText("demo") });
    },
    askMore() {
      this.request({ method: "ASK_MORE", params: { min: 15 } })
        .then(() => hmUI.showToast({ text: getText("sent") }))
        .catch(() => hmUI.showToast({ text: getText("noConn") }));
    },
  })
);
