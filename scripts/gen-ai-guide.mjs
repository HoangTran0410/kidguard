#!/usr/bin/env node
// The guide for chat AIs: website/ai/<lang>/index.html (what KidGuard is + one line per guide section) and
// website/ai/<lang>/<section>.html (one section), from website/guide.html and website/en/guide.html (the one source).
// The site's "Ask an AI" button points the AI at the index; it opens only the sections it needs.
// Why pages, not llms.txt (owner 2026-11-17): Gemini answered "can't access" llms.txt while it read the same kind of
// pages on tvremote.pages.dev (this is a port of tv-remote dev/gen/guide_md.js): chat readers open HTML, not .txt / .md.
// Markdown stays as text inside <pre>; links are real. Run after editing a guide: node scripts/gen-ai-guide.mjs
// (build.sh runs it; --check exits 1 when website/ai is stale).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const SITE = "https://kidguard.asia";
const OUT = path.join(ROOT, "website/ai");
const SRC = { vi: "website/guide.html", en: "website/en/guide.html" };
const HEAD = {
  vi: { title: "Hướng dẫn KidGuard (cho trợ lý AI)",
    how: "KidGuard: app quản lý điện thoại Android của con, miễn phí, mã nguồn mở. Máy con chạy app KidGuard (mạnh nhất khi bật Device Owner, hoặc chế độ cơ bản); bố mẹ quản lý bằng trình duyệt bất kỳ (Wi-Fi nhà, hoặc ngoài nhà qua Tailscale / relay Cloudflare) hoặc app bố mẹ trên Android. Không cần tài khoản, luật chạy trên máy con cả khi mất mạng, mọi thứ gỡ lại được. Máy con iPhone không hỗ trợ.",
    find: "Tìm mục dưới đây rồi chỉ mở trang của mục đó. Bản tiếng Anh, có thêm chi tiết kỹ thuật:" },
  en: { title: "KidGuard user guide (for AI assistants)",
    how: "KidGuard: free, open-source parental control for Android. The child's phone runs the KidGuard app (strongest as Device Owner, or basic mode); parents manage it from any browser (home Wi-Fi, or away through Tailscale / a Cloudflare relay) or the Android parent app. No account, rules run on the child's phone even offline, everything can be undone. iPhone child phones are not supported.",
    find: "Find the section below, then open only its page. More technical detail:" },
};

const ENT = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", rsaquo: "›", larr: "←", rarr: "→", middot: "·", hellip: "…", ndash: "–", mdash: "—", times: "×", copy: "©" };
const unent = s => s.replace(/&(#x?[0-9a-f]+|\w+);/gi, (m, e) => e[0] === "#" ? String.fromCodePoint(e[1] === "x" || e[1] === "X" ? parseInt(e.slice(2), 16) : +e.slice(1)) : ENT[e] ?? m);
const attr = (tag, name) => { const m = (tag || "").match(new RegExp("\\s" + name + '="([^"]*)"')); return m ? unent(m[1]) : null; };

function tree(html) {
  const root = { tag: "root", kids: [] }, stack = [root];
  const VOID = new Set(["img", "br", "hr", "input", "source", "wbr", "use", "path", "circle", "rect", "meta", "link"]);
  for (const m of html.matchAll(/<!--[\s\S]*?-->|<(\/?)([a-zA-Z0-9]+)([^>]*?)(\/?)>|([^<]+)/g)) {
    if (m[5] !== undefined) { stack[stack.length - 1].kids.push(unent(m[5])); continue; }
    if (!m[2]) continue;
    const tag = m[2].toLowerCase();
    if (m[1]) { const i = stack.map(n => n.tag).lastIndexOf(tag); if (i > 0) stack.length = i; continue; }
    const node = { tag, attrs: m[3], kids: [] };
    stack[stack.length - 1].kids.push(node);
    if (!VOID.has(tag) && !m[4]) stack.push(node);
  }
  return root;
}
const cls = n => (attr(n.attrs, "class") || "").split(/\s+/);
const skip = n => ["img", "button", "svg", "script", "style", "picture", "video"].includes(n.tag) || cls(n).includes("tag");   // "Dễ nhất" badges

let ids = new Set(), lang = "vi";
function link(href) {
  if (!href) return "";
  const m = href.match(/^(?:(?:\/?(?:en\/)?guide(?:\.html)?)?)#([\w-]+)$/);
  if (m) return ids.has(m[1]) ? m[1] + ".md" : SITE + (lang === "en" ? "/en" : "") + "/guide#" + m[1];
  if (/^[a-z]+:/.test(href)) return href;
  return SITE + "/" + href.replace(/^\.?\//, "");
}
function inline(n) {
  if (typeof n === "string") return n.replace(/\s+/g, " ");
  if (skip(n)) return "";
  const t = n.kids.map(inline).join("");
  if (n.tag === "br") return " ";
  if (n.tag === "b" || n.tag === "strong") return t.trim() ? "**" + t.trim() + "**" : "";
  if (n.tag === "code" || n.tag === "kbd") return t.trim() ? "`" + t.trim() + "`" : "";
  if (n.tag === "a") { const u = link(attr(n.attrs, "href")); return u && t.trim() ? "[" + t.trim() + "](" + u + ")" : t; }
  return t;
}
const clean = s => s.replace(/[ \t]+/g, " ").replace(/ ([,.;:!?)])/g, "$1").trim();
function blocks(n, out) {
  for (const k of n.kids) {
    if (typeof k === "string") { if (k.trim()) out.push(clean(k)); continue; }
    if (skip(k)) continue;
    const c = cls(k);
    if (k.tag === "h2") continue;   // the page's title
    if (/^h[3-6]$/.test(k.tag)) out.push("## " + clean(inline(k)));
    else if (k.tag === "p") { const t = clean(inline(k)); if (t) out.push((c.includes("tip") || c.includes("warnote") ? "> " : "") + t); }
    else if (k.tag === "ol" || k.tag === "ul") {
      let i = 0;
      const items = k.kids.filter(x => typeof x !== "string" && x.tag === "li").map(li => {
        const sub = []; blocks(li, sub);
        return (k.tag === "ol" ? ++i + ". " : "- ") + sub.join(" ").replace(/\n+/g, " ");
      }).filter(s => s.replace(/^(\d+\.|-) /, "").trim());
      if (items.length) out.push(items.join("\n"));
    } else if (k.tag === "table") {
      const rows = [];
      const walk = x => { if (typeof x === "string" || skip(x)) return; if (x.tag === "tr") rows.push(x.kids.filter(y => typeof y !== "string" && (y.tag === "td" || y.tag === "th")).map(y => clean(inline(y)).replace(/\|/g, "\\|"))); else x.kids.forEach(walk); };
      walk(k);
      if (rows.length) out.push(["| " + rows[0].join(" | ") + " |", "|" + rows[0].map(() => " --- |").join(""), ...rows.slice(1).map(r => "| " + r.join(" | ") + " |")].join("\n"));
    } else if (k.tag === "pre") out.push("```\n" + k.kids.map(x => typeof x === "string" ? x : inline(x).replace(/`/g, "")).join("").trim() + "\n```");
    else if (["div", "figure", "figcaption", "section", "li", "details", "summary", "span", "tbody", "thead", "dl", "dd", "dt"].includes(k.tag)) {
      const sub = []; blocks(k, sub);
      if (sub.length && k.kids.every(x => typeof x === "string" || ["span", "b", "a", "code", "i", "em", "u", "small", "br", "kbd", "strong"].includes(x.tag))) out.push(clean(inline(k)));
      else out.push(...sub);
    } else { const t = clean(inline(k)); if (t) out.push(t); }
  }
  return out;
}
function sections(html) {
  const out = [];
  for (const m of html.matchAll(/<section id="([\w-]+)"[^>]*>([\s\S]*?)<\/section>/g)) {
    const t = tree(m[2]);
    const h2 = t.kids.find(k => typeof k !== "string" && k.tag === "h2");
    if (h2) out.push({ id: m[1], h2, node: t });
  }
  return out;
}
const esc = x => x.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
function page(md, lg) {
  const title = esc(md.match(/^# (.*)/)[1]);
  const body = esc(md).replace(/\[([^\]]+)\]\(([\w-]+)\.md\)/g, (m, t, id) => '[<a href="' + (id === "index" ? "./" : id) + '">' + t + "</a>]")
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, (m, t, u) => '[' + t + '](<a href="' + u + '">' + u + "</a>)");
  return '<!doctype html><html lang="' + lg + '"><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
    + "<title>" + title + '</title><pre style="white-space:pre-wrap;font:15px/1.5 sans-serif;max-width:760px;margin:24px auto;padding:0 16px">' + body + "</pre></html>\n";
}
export function build() {
  const files = {};
  for (const lg of ["vi", "en"]) {
    lang = lg;
    const html = fs.readFileSync(path.join(ROOT, SRC[lg]), "utf8"), S = sections(html);
    ids = new Set(S.map(s => s.id));
    const index = ["# " + HEAD[lg].title, "", HEAD[lg].how, "", HEAD[lg].find + " " + SITE + "/llms.txt", ""];
    for (const s of S) {
      const title = clean(inline(s.h2)), body = blocks(s.node, []).join("\n\n");
      files[lg + "/" + s.id + ".md"] = "# " + title + "\n\n" + body + "\n";
      const subs = body.split("\n").filter(l => l.startsWith("## ")).map(l => l.slice(3).replace(/\*\*|`/g, ""));
      const lines = body.split("\n"), first = (lines.find(l => /^[^#>|`\-\d]/.test(l)) || (lines.find(l => /^(\d+\.|-) /.test(l)) || "").replace(/^(\d+\.|-) /, ""))
        .replace(/\*\*|`/g, "").replace(/\[([^\]]+)\]\([^)]+\)/g, "$1");
      const sent = (first.match(/^.{20,170}[.!?](?=\s|$)/) || [""])[0];   // whole sentences up to ~170 chars, else cut at a word
      const short = sent || (first.length > 160 ? first.slice(0, first.lastIndexOf(" ", 157)) + "…" : first);
      index.push("- [" + title + "](" + s.id + ".md): " + short + (subs.length ? " (" + subs.join("; ") + ")" : ""));
    }
    files[lg + "/index.md"] = index.join("\n") + "\n";
  }
  const out = {};
  for (const [f, md] of Object.entries(files)) out[f.replace(/\.md$/, ".html")] = page(md, f.slice(0, 2));
  return out;
}
const CHECK = process.argv.includes("--check");
const files = build();
if (CHECK) {
  const have = fs.existsSync(OUT) ? fs.readdirSync(OUT, { recursive: true }).filter(f => f.endsWith(".html")).map(f => f.split(path.sep).join("/")).sort() : [];
  const bad = Object.keys(files).filter(f => !fs.existsSync(path.join(OUT, f)) || fs.readFileSync(path.join(OUT, f), "utf8") !== files[f]);
  const extra = have.filter(f => !files[f]);
  if (bad.length || extra.length) { console.error("website/ai is stale (run node scripts/gen-ai-guide.mjs): " + [...bad, ...extra].join(", ")); process.exit(1); }
  console.log("AI guide ok: " + Object.keys(files).length + " pages");
} else {
  fs.rmSync(OUT, { recursive: true, force: true });
  for (const [f, s] of Object.entries(files)) { fs.mkdirSync(path.dirname(path.join(OUT, f)), { recursive: true }); fs.writeFileSync(path.join(OUT, f), s); }
  console.log(Object.keys(files).length + " pages in website/ai/");
}
