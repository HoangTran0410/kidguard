#!/usr/bin/env node
// Plan B (docs/decisions/remote.md 2026-11-18): the relay worker is JSON only, so the website (Pages) serves the parent
// page and the two setup pages. Copies (all gitignored, rebuilt by deploy.sh before every Pages deploy):
//   ui/index.html   -> website/app/index.html   (/app/, and /go via website/_redirects)
//   ui/assets/*     -> website/ui/*             (the page loads /ui/<file>; next to the UI pack files there)
//   ui/install.html -> website/install.html     (/install)
//   ui/qr.html      -> website/qr.html          (/qr)
// No APK here (owner 2026-11-18): /install links the latest GitHub release and the parent picks the downloaded file
// (GitHub downloads have no CORS, so the page cannot fetch it itself; an APK on Pages may draw the scan that killed the worker).
import fs from "node:fs";
import path from "node:path";

const root = path.join(path.dirname(new URL(import.meta.url).pathname), "..");
const ui = path.join(root, "ui"), site = path.join(root, "website");
const cp = (from, to) => { fs.mkdirSync(path.dirname(to), { recursive: true }); fs.copyFileSync(from, to); };

cp(path.join(ui, "index.html"), path.join(site, "app", "index.html"));
let n = 0;
for (const f of fs.readdirSync(path.join(ui, "assets"))) if (/^[a-z0-9][a-z0-9-]{0,40}\.(js|css)$/.test(f)) { cp(path.join(ui, "assets", f), path.join(site, "ui", f)); n++; }
cp(path.join(ui, "install.html"), path.join(site, "install.html"));
cp(path.join(ui, "qr.html"), path.join(site, "qr.html"));
for (const f of ["kidguard.apk", "kidguard.apk.sha256", "version.json"]) fs.rmSync(path.join(site, f), { force: true });
console.log(`website: app/index.html, ui/ (${n} files), install.html, qr.html`);
