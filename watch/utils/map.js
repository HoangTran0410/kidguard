import { LocalStorage } from "@zos/storage";
import { statSync, rmSync } from "@zos/fs";

// Map helpers (Web Mercator, OSM slippy tiles 256 px). The side service downloads + converts + sends the tiles;
// the watch keeps them by z/x/y in data://download (path comes from the TransferFile FileObject) and shows them with IMG.

export const TILE = 256;
export const ZMIN = 13;
export const ZMAX = 18;
export const KEEP = 20;

// World pixel (z) -> tile float coordinates of a lat/lng
export function tileF(lat, lng, z) {
  const n = Math.pow(2, z);
  const la = (Math.max(-85.0511, Math.min(85.0511, lat)) * Math.PI) / 180;
  return {
    x: ((lng + 180) / 360) * n,
    y: ((1 - Math.log(Math.tan(la) + 1 / Math.cos(la)) / Math.PI) / 2) * n,
  };
}

// 2x2 mosaic with the point inside its centre half: tiles [{z,x,y,i,j}] and the point in mosaic pixels (0..512)
export function mosaic(lat, lng, z) {
  const n = Math.pow(2, z);
  const f = tileF(lat, lng, z);
  const x0 = Math.floor(f.x - 0.5);
  const y0 = Math.floor(f.y - 0.5);
  const tiles = [];
  for (let j = 0; j < 2; j++)
    for (let i = 0; i < 2; i++) {
      const y = y0 + j;
      if (y < 0 || y >= n) continue;
      tiles.push({ z, x: (((x0 + i) % n) + n) % n, y, i, j });
    }
  return { tiles, px: (f.x - x0) * TILE, py: (f.y - y0) * TILE };
}

// metres per mosaic pixel at this latitude / zoom
export function mpp(lat, z) {
  return (156543.03392 * Math.cos((lat * Math.PI) / 180)) / Math.pow(2, z);
}

// ---- cache index in local storage: {"z/x/y": {p: path, t: last used}} ----
const ls = new LocalStorage();
export const tkey = (z, x, y) => `${z}/${x}/${y}`;

function load() {
  try {
    return JSON.parse(ls.getItem("tiles", "{}")) || {};
  } catch (e) {
    return {};
  }
}
function save(o) {
  try {
    ls.setItem("tiles", JSON.stringify(o));
  } catch (e) {}
}
function exists(p) {
  try {
    return !!statSync({ path: p });
  } catch (e) {
    return false;
  }
}

// path of a cached tile (and mark it used) or ""
export function cached(key) {
  const o = load();
  const e = o[key];
  if (!e) return "";
  if (!exists(e.p)) {
    delete o[key];
    save(o);
    return "";
  }
  e.t = Date.now();
  save(o);
  return e.p;
}

// a tile arrived: index it, then delete the oldest beyond KEEP
export function remember(key, path) {
  const o = load();
  o[key] = { p: path, t: Date.now() };
  const ks = Object.keys(o).sort((a, b) => o[b].t - o[a].t);
  ks.slice(KEEP).forEach((k) => {
    try {
      rmSync({ path: o[k].p });
    } catch (e) {}
    delete o[k];
  });
  save(o);
}
