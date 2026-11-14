import * as hmUI from "@zos/ui";
import { push } from "@zos/router";
import { LocalStorage } from "@zos/storage";
import { BasePage } from "@zeppos/zml/base-page";
import { Col, C, PAD, t, ago, hhmm, done, toast, ask, rcSave } from "../utils/ui";
import { DEVICE_WIDTH as W } from "../utils/config/device";
import { TILE, ZMIN, ZMAX, mosaic, mpp, tkey, cached, remember } from "../utils/map";

const ls = new LocalStorage();

// command type -> words the parent knows (unknown types show as sent)
const CMD = { ring: "ring", ringStop: "ringStop", lock: "lock", locate: "locate", message: "msg", answer: "answer" };
function cmdName(type) {
  return CMD[type] ? t(CMD[type]) : type;
}

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
    onDestroy() {
      this.dead = true;
    },
    build() {
      this.col = new Col();
      this.z = Math.max(ZMIN, Math.min(ZMAX, +ls.getItem("mz", "16") || 16));
      this.mapSt = "";
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
      if (this.o.kind === "queue") {
        col.text(t("queueH"), { size: 28, color: C.mute });
        const q = s.queue || [];
        if (!q.length) col.text(t("queueNone"), { color: C.mute });
        q.forEach((c) => col.text(`${hhmm(c.at)}  ${cmdName(c.t)}`, { size: 24, center: false }));
        col.gap(6);
        col.text(t("queueWhy"), { size: 20, color: C.mute });
        col.btn(t("refresh"), () => this.load(), { h: 56, size: 24 });
        col.end();
        return;
      }
      this.s = s;
      col.text(t("where"), { size: 28, color: C.mute });
      if (s.loc) this.map(s);
      if (s.zone) col.text(`${s.zone.text}\n${ago(s.zone.t)}`, { size: 28 });
      if (s.loc) {
        col.text(t("fixLine", ago(s.loc.t), s.loc.acc), { size: 24, color: 0xdddddd });
        col.text(`${s.loc.lat.toFixed(5)}, ${s.loc.lng.toFixed(5)}`, { size: 22, color: C.mute });
      } else {
        col.text(t("noLoc"), { color: C.mute });
      }
      col.gap(8);
      if (s.loc) col.btn(t("find"), () => push({ url: "page/find", params: JSON.stringify({ kid: this.o.kid }) }), { color: C.green, press: C.greenP });
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
    // ---- map: 2x2 OSM tiles (256 px each) with the kid inside the centre half, drawn at scale S/512 ----
    zoom(d) {
      const z = Math.max(ZMIN, Math.min(ZMAX, this.z + d));
      if (z === this.z) return;
      this.z = z;
      this.mapSt = "";
      try { ls.setItem("mz", String(z)); } catch (e) {}
      this.render(this.s);
    },
    // tiles come over Bluetooth. zml's BasePage owns the inbox (it listens to "newfile" and takes the file), so a
    // page-level inbox.on("NEWFILE") never fired: the map stayed empty. Its hook hands us each file instead.
    onReceivedFile(fo) {
      this.rx = (this.rx || 0) + 1;
      if (!fo) return;
      const take = () => {
        const nm = (fo.params && fo.params.name) || "";
        if (nm && fo.filePath) remember(nm, fo.filePath);
        if (!this.dead && this.s && this.o.kind === "loc") this.render(this.s);
      };
      if (fo.readyState === "transferred") return take();
      try { fo.on("change", (ev) => { if (ev && ev.data && ev.data.readyState === "transferred") take(); }); } catch (e) {}
    },
    map(s) {
      const col = this.col;
      // tiles at their own size (no auto_scale: converted tiles drew black when scaled, Bip Max 0.7.4), cropped to an
      // S x S window around the kid. Each IMG shows the part of its tile inside the window (pos_x/pos_y shift it).
      const S = Math.min(W - 2 * PAD, 2 * TILE);
      const k = 1;
      const x0 = PAD;
      const y0 = col.y;
      const m = mosaic(s.loc.lat, s.loc.lng, this.z);
      const wx = Math.round(Math.max(0, Math.min(2 * TILE - S, m.px - S / 2)));
      const wy = Math.round(Math.max(0, Math.min(2 * TILE - S, m.py - S / 2)));
      let have = 0;
      const miss = [];
      col.add(hmUI.widget.FILL_RECT, { x: x0, y: y0, w: S, h: S, color: 0x2a2a2a });
      m.tiles.forEach((tl) => {
        const p = cached(tkey(tl.z, tl.x, tl.y));
        const tx = tl.i * TILE, ty = tl.j * TILE;
        const ax = Math.max(tx, wx), bx = Math.min(tx + TILE, wx + S);
        const ay = Math.max(ty, wy), by = Math.min(ty + TILE, wy + S);
        if (p) have++;
        else miss.push({ z: tl.z, x: tl.x, y: tl.y });
        if (!p || bx <= ax || by <= ay) return;
        // src = the data:// path as received (a "../data/..." relative path draws nothing, Bip Max 0.7.7)
        col.add(hmUI.widget.IMG, { x: x0 + ax - wx, y: y0 + ay - wy, w: bx - ax, h: by - ay, pos_x: tx - ax, pos_y: ty - ay, src: p });
      });
      const pt = (a, b) => ({ x: Math.round(x0 + a - wx), y: Math.round(y0 + b - wy) });
      const inside = (c) => c.x > x0 && c.x < x0 + S && c.y > y0 && c.y < y0 + S;
      const mp = mpp(s.loc.lat, this.z) / k; // metres per screen pixel
      // zones (green rings), only when the centre is on the map and the circle fits
      (s.zones || []).forEach((z) => {
        // offset from the kid in metres (Web Mercator metres per pixel is mpp at the kid's latitude)
        const dx = ((z.lng - s.loc.lng) * Math.PI / 180) * 6378137 * Math.cos((s.loc.lat * Math.PI) / 180);
        const dy = ((z.lat - s.loc.lat) * Math.PI / 180) * 6378137;
        const c = pt(m.px, m.py);
        const zc = { x: Math.round(c.x + dx / mp), y: Math.round(c.y - dy / mp) };
        const r = Math.round(z.r / mp);
        if (inside(zc) && r >= 4 && r <= S / 2) col.add(hmUI.widget.CIRCLE, { center_x: zc.x, center_y: zc.y, radius: r, color: 0x2e7d32, alpha: 90 });
      });
      const c = pt(m.px, m.py);
      const ar = Math.max(8, Math.min(S / 2, Math.round((s.loc.acc || 0) / mp)));
      if (inside(c)) {
        col.add(hmUI.widget.CIRCLE, { center_x: c.x, center_y: c.y, radius: ar, color: 0x1565c0, alpha: 80 });
        col.add(hmUI.widget.CIRCLE, { center_x: c.x, center_y: c.y, radius: 10, color: 0xffffff, alpha: 255 });
        col.add(hmUI.widget.CIRCLE, { center_x: c.x, center_y: c.y, radius: 7, color: 0xd32f2f, alpha: 255 });
      }
      col.add(hmUI.widget.FILL_RECT, { x: x0, y: y0 + S - 22, w: S, h: 22, color: 0xffffff, alpha: 170 });
      col.add(hmUI.widget.TEXT, { x: x0, y: y0 + S - 22, w: S - 6, h: 22, text: "\u00a9 OpenStreetMap", text_size: 16, color: 0x222222, align_h: hmUI.align.RIGHT });
      col.gap(S + 6);
      // zoom right under the map (owner: no scrolling down for every zoom), plain - / + (owner: no numbers)
      col.row(
        { label: "\u2212", fn: () => this.zoom(-1) },
        { label: "+", fn: () => this.zoom(1) }
      );
      // ask for what is missing once per zoom/fix; wait for it over Bluetooth, give up after 90 s -> text only
      if (miss.length && !this.mapSt) {
        this.mapSt = "load";
        const key = this.z + "|" + s.loc.lat + "|" + s.loc.lng;
        ask(this, { method: "TILES", params: { list: miss, kid: this.o.kid } }, 40000)
          .then((r) => { this.mapDbg = r ? JSON.stringify(r).slice(0, 160) : "no reply"; if (!this.dead && this.s) this.render(this.s); if (r && r.error) throw new Error(r.error); if (r && r.fail && r.fail >= r.n) throw new Error("fail"); })
          .catch((e) => { this.mapDbg = this.mapDbg || "ask: " + ((e && e.message) || e); this.mapFail(key); });
        setTimeout(() => this.mapFail(key), 90000);
      }
      if (miss.length && this.mapSt === "load") col.text(t("mapLoading", have, m.tiles.length), { size: 20, color: C.mute });
      if (miss.length && this.mapSt === "fail") col.text(t("mapFail"), { size: 20, color: C.amber });
      // temporary (0.7.3): what the phone answered + what reached this page, to find where the tiles get lost
    },
    mapFail(key) {
      if (this.dead || this.mapSt !== "load" || key !== this.z + "|" + this.s.loc.lat + "|" + this.s.loc.lng) return;
      this.mapSt = "fail";
      this.render(this.s);
    },
  })
);
