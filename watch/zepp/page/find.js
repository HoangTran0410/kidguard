import * as hmUI from "@zos/ui";
import { Geolocation, Compass } from "@zos/sensor";
import { setPageBrightTime } from "@zos/display";
import { BasePage } from "@zeppos/zml/base-page";
import { Col, C, PAD, TOP, t, ago, rcSave, done, toast, ask } from "../utils/ui";
import { DEVICE_WIDTH as W } from "../utils/config/device";

// "Find" mode: the WATCH's own GPS + compass point an arrow at the kid's last location (for parents who
// do not need the map). Arrow rotation = bearing(me -> kid) - compass heading.

const R = 6371000;
const rad = (d) => (d * Math.PI) / 180;
const A = 240; // arrow image size (assets/*/arrow.png, points up, pivot = centre)
const REFRESH = 30000;
const LW = 36; // N/E/S/W label box
const NEAR = 20; // below this (plus the kid's GPS error) the arrow is not trustworthy

function bearing(la1, lo1, la2, lo2) {
  const p1 = rad(la1), p2 = rad(la2), dl = rad(lo2 - lo1);
  const y = Math.sin(dl) * Math.cos(p2);
  const x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

function dist(la1, lo1, la2, lo2) {
  const dp = rad(la2 - la1), dl = rad(lo2 - lo1);
  const a = Math.sin(dp / 2) ** 2 + Math.cos(rad(la1)) * Math.cos(rad(la2)) * Math.sin(dl / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

function fmtDist(m) {
  if (m < 100) return `${Math.round(m)} m`;
  if (m < 1000) return `${Math.round(m / 10) * 10} m`;
  const km = m < 10000 ? (m / 1000).toFixed(1) : String(Math.round(m / 1000));
  return `${km.replace(".", t("decSep"))} km`;
}

Page(
  BasePage({
    onInit(params) {
      try {
        this.o = JSON.parse(params || "{}");
      } catch (e) {
        this.o = {};
      }
    },
    onDestroy() {
      // sensors off = battery; brightness time follows the page destruction by itself
      this.dead = true;
      try { this.geo && this.geo.offChange(this.onGeo); this.geo && this.geo.stop(); } catch (e) {}
      try { this.cmp && this.cmp.offChange(this.onCmp); this.cmp && this.cmp.stop(); } catch (e) {}
      clearInterval(this.timer);
    },
    build() {
      try {
        this.build1();
      } catch (e) {
        // never a blank screen: show what broke (reported back by the owner from the watch)
        hmUI.createWidget(hmUI.widget.TEXT, { x: PAD, y: TOP, w: W - 2 * PAD, h: 300, text: "Error: " + e, text_size: 24, color: C.red, text_style: hmUI.text_style.WRAP });
      }
    },
    build1() {
      this.kid = null; // {lat,lng,acc,t}
      this.me = null; // {lat,lng}
      this.head = null; // degrees or null
      this.shown = -999;
      this.lastDraw = 0;
      this.col = new Col();
      const mk = (y, h, size, color) =>
        hmUI.createWidget(hmUI.widget.TEXT, {
          x: PAD, y, w: W - 2 * PAD, h, text: "", text_size: size, color,
          align_h: hmUI.align.CENTER_H, align_v: hmUI.align.CENTER_V, text_style: hmUI.text_style.WRAP,
        });
      this.wDist = mk(TOP, 64, 52, C.text);
      this.arrow = hmUI.createWidget(hmUI.widget.IMG, this.arrowProps(0));
      // N/E/S/W on a ring around the arrow, turned with the compass (N in red): which way the arrow points on the ground
      this.card = ["dirN", "dirE", "dirS", "dirW"].map((key, i) =>
        hmUI.createWidget(hmUI.widget.TEXT, { x: 0, y: 0, w: LW, h: LW, text: t(key), text_size: 26, color: i ? 0xbbbbbb : 0xff5252, align_h: hmUI.align.CENTER_H, align_v: hmUI.align.CENTER_V }));
      this.headShown = -999;
      this.placeCard(0);
      this.wAge = mk(TOP + 64 + A + 4, 34, 24, 0xdddddd);
      this.wGps = mk(TOP + 64 + A + 38, 34, 22, C.mute);
      this.wHint = mk(TOP + 64 + A + 72, 92, 22, C.amber);
      let y = TOP + 64 + A + 170;
      this.col.y = y;
      this.col.btn(t("askFresh"), () => this.fresh(), { color: C.blue, press: C.blueP });
      this.col.end();

      try { setPageBrightTime({ brightTime: 600000 }); } catch (e) {} // keep screen on while open (reset on destroy)

      this.onGeo = () => this.readGeo();
      this.onCmp = () => this.readCmp();
      try {
        this.geo = new Geolocation();
        this.geo.onChange(this.onGeo);
        this.geo.start();
      } catch (e) { this.geo = null; }
      try {
        this.cmp = new Compass();
        this.cmp.onChange(this.onCmp);
        this.cmp.start();
      } catch (e) { this.cmp = null; }
      this.readGeo();
      this.readCmp();
      this.load();
      this.timer = setInterval(() => this.load(), REFRESH);
      this.draw(true);
    },
    // heading h (deg, 0 = the watch faces north): north sits at -h on the screen
    placeCard(h) {
      const cx = W / 2, cy = TOP + 64 + A / 2, r = A / 2 - LW / 2 + 6;
      this.card.forEach((w, i) => {
        const a = rad(i * 90 - h);
        w.setProperty(hmUI.prop.MORE, { x: Math.round(cx + r * Math.sin(a) - LW / 2), y: Math.round(cy - r * Math.cos(a) - LW / 2), w: LW, h: LW });
      });
    },
    arrowProps(angle) {
      return {
        x: 0, y: TOP + 64, w: W, h: A,
        pos_x: (W - A) / 2, pos_y: 0, center_x: W / 2, center_y: A / 2,
        src: "arrow.png", angle: Math.round(angle),
      };
    },
    load() {
      ask(this, { method: "STATE", params: { kid: this.o.kid } })
        .then((r) => {
          if (this.dead) return;
          this.kid = r && r.state && r.state.loc ? r.state.loc : this.kid;
          this.kidErr = !!(r && r.error);
          this.draw(true);
        })
        .catch(() => { this.kidErr = true; if (!this.dead) this.draw(true); });
    },
    fresh() {
      toast(t("sending"));
      ask(this, { method: "CMD", params: { kid: this.o.kid, c: { type: "locate" } } })
        .then((r) => {
          if (r && r.id) rcSave(this.o.kid, r.id, t("where"));
          if (done(r, t("locateSent"))) setTimeout(() => this.load(), 30000);
        })
        .catch(() => toast(t("noPhone")));
    },
    readGeo() {
      try {
        const ok = this.geo && this.geo.getStatus() === "A";
        const la = this.geo ? this.geo.getLatitude() : 0;
        const lo = this.geo ? this.geo.getLongitude() : 0;
        this.me = ok && typeof la === "number" && typeof lo === "number" && (la || lo) ? { lat: la, lng: lo } : null;
      } catch (e) { this.me = null; }
      this.draw(false);
    },
    readCmp() {
      try {
        const a = this.cmp ? this.cmp.getDirectionAngle() : "INVALID";
        this.calib = this.cmp ? !!this.cmp.getStatus() : false;
        this.head = typeof a === "number" ? a : null;
      } catch (e) { this.head = null; }
      this.draw(false);
    },
    draw(force) {
      if (this.dead) return;
      const now = Date.now();
      if (!force && now - this.lastDraw < 120) return;
      this.lastDraw = now;
      const k = this.kid, me = this.me;
      const set = (w, s) => w.setProperty(hmUI.prop.TEXT, s);
      const acc = k && k.acc > 0 ? Math.round(k.acc) : 0;
      set(this.wAge, k ? t("kidAge", ago(k.t)) + (acc ? "  ±" + acc + " m" : "") : this.kidErr ? t("noPhone") : t("noLoc"));
      if (this.head != null) {
        let dh = Math.abs(this.head - this.headShown);
        dh = Math.min(dh, 360 - dh);
        if (force || dh >= 2) { this.headShown = this.head; this.placeCard(this.head); }
      }
      if (k && me) {
        const d = dist(me.lat, me.lng, k.lat, k.lng), b = bearing(me.lat, me.lng, k.lat, k.lng);
        set(this.wDist, (d < NEAR + acc ? "≈ " : "") + fmtDist(d));
        set(this.wGps, t("gpsOk") + " · " + t("toward", t("d8_" + (Math.round(b / 45) % 8))));
        // inside the error circle the two GPS fixes can point anywhere: the arrow still turns, the hint says not to trust it
        const near = d < NEAR + acc;
        if (this.head != null) {
          const ang = (bearing(me.lat, me.lng, k.lat, k.lng) - this.head + 360) % 360;
          let d = Math.abs(ang - this.shown);
          d = Math.min(d, 360 - d);
          if (force || d >= 2) {
            this.shown = ang;
            this.arrow.setProperty(hmUI.prop.MORE, this.arrowProps(ang));
          }
          set(this.wHint, near ? t("nearHint", Math.max(NEAR, acc)) : "");
        } else {
          set(this.wHint, near ? t("nearHint", Math.max(NEAR, acc)) : this.calib === false ? t("compassCal") : t("compassWait"));
        }
      } else {
        set(this.wGps, me ? t("gpsOk") : t("gpsWait"));
        set(this.wDist, "–");
        set(this.wHint, "");
      }
    },
  })
);
