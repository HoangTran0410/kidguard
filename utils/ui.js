import * as hmUI from "@zos/ui";
import { getText } from "@zos/i18n";
import { DEVICE_WIDTH as W } from "./config/device";

// Tiny column layout for scrolling pages: widgets are stacked top to bottom below the system
// title bar; the page scrolls by itself when the column is taller than the screen.

export const TOP = 80;
export const PAD = 24;
export const C = {
  text: 0xffffff,
  mute: 0x9e9e9e,
  green: 0x2e7d32,
  greenP: 0x1b5e20,
  btn: 0x2a2a2a,
  btnP: 0x444444,
  red: 0xc62828,
  redP: 0x8e0000,
  amber: 0xef6c00,
  amberP: 0xb53d00,
  blue: 0x1565c0,
  blueP: 0x003c8f,
};

export const t = (k, ...a) => {
  let s = getText(k);
  a.forEach((v, i) => (s = s.replace("%" + (i + 1), v)));
  return s;
};

export function fmtMin(ms) {
  const m = Math.max(0, Math.round(ms / 60000));
  return m >= 60 ? `${Math.floor(m / 60)}h${String(m % 60).padStart(2, "0")}` : `${m}'`;
}

export function ago(ms) {
  if (!ms) return "?";
  const m = Math.round((Date.now() - ms) / 60000);
  if (m < 1) return t("justNow");
  if (m < 60) return t("minAgo", m);
  if (m < 48 * 60) return t("hAgo", Math.round(m / 60));
  return t("dAgo", Math.round(m / 1440));
}

export function hhmm(ms) {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

export class Col {
  constructor() {
    this.y = TOP;
    this.ws = [];
  }
  add(type, opts) {
    const w = hmUI.createWidget(type, opts);
    this.ws.push(w);
    return w;
  }
  clear() {
    this.ws.forEach((w) => hmUI.deleteWidget(w));
    this.ws = [];
    this.y = TOP;
  }
  gap(n) {
    this.y += n;
  }
  text(str, { size = 26, color = C.text, center = true, x = PAD, w = W - 2 * PAD } = {}) {
    const lay = hmUI.getTextLayout(str, { text_size: size, text_width: w, wrapped: 1 });
    const h = Math.max(size + 8, (lay && lay.height) || size + 8) + 4;
    const wd = this.add(hmUI.widget.TEXT, {
      x, y: this.y, w, h, text: str, text_size: size, color,
      align_h: center ? hmUI.align.CENTER_H : hmUI.align.LEFT,
      text_style: hmUI.text_style.WRAP,
    });
    this.y += h;
    return wd;
  }
  btn(label, fn, { color = C.btn, press = C.btnP, x = PAD, w = W - 2 * PAD, h = 68, size = 28, advance = true } = {}) {
    const b = this.add(hmUI.widget.BUTTON, {
      x, y: this.y, w, h, radius: h / 2, text: label, text_size: size,
      normal_color: color, press_color: press, click_func: fn,
    });
    if (advance) this.y += h + 12;
    return b;
  }
  // two buttons side by side: [{label, fn, color?, press?}, {…}]
  row(a, b) {
    const w = (W - 2 * PAD - 12) / 2;
    const one = (o, x) => (o ? this.btn(o.label, o.fn, { x, w, size: 26, color: o.color || C.btn, press: o.press || C.btnP, advance: false }) : null);
    const r = [one(a, PAD), one(b, PAD + w + 12)];
    this.y += 68 + 12;
    return r;
  }
  end() {
    // bottom spacer so the last button clears the rounded corners when scrolled to the end
    this.add(hmUI.widget.FILL_RECT, { x: 0, y: this.y, w: W, h: 60, color: 0x000000 });
    this.y += 60;
  }
}

// A button that needs a second tap within 3 s (for actions the child notices: lock, ring).
export function twoTap(col, label, confirmLabel, fn, opts) {
  let armed = 0;
  let b;
  const click = () => {
    if (Date.now() - armed < 3000) {
      armed = 0;
      b.setProperty(hmUI.prop.TEXT, label);
      fn();
    } else {
      armed = Date.now();
      b.setProperty(hmUI.prop.TEXT, confirmLabel);
      setTimeout(() => {
        if (armed && Date.now() - armed >= 2900) {
          armed = 0;
          b.setProperty(hmUI.prop.TEXT, label);
        }
      }, 3000);
    }
  };
  b = col.btn(label, click, opts);
  return b;
}

export function toast(s) {
  hmUI.showToast({ text: s });
}

// Turn a side-service reply into a toast; returns true on success.
export function done(res, okText) {
  if (!res || res.error) {
    toast(t("fail") + (res && res.error ? ": " + res.error : ""));
    return false;
  }
  toast(res.result === "demo" ? t("demoSent") : okText || t("sent"));
  return true;
}
