// Guide pages (owner 2026-11-16, like the tv-remote guide): one top-level section per page with Previous / Next,
// search over every heading and paragraph, and on phones a sticky bar "Contents · <page>" that opens the contents as a
// drawer (search box at its top). Without JS the page stays one long read with the plain contents list.
(function () {
  var lay = document.querySelector('.glay'), toc = document.querySelector('.toc'), main = document.querySelector('.glay .gmain');
  if (!lay || !toc || !main) return;
  var vi = (document.documentElement.lang || '').indexOf('vi') === 0;
  var T = vi
    ? { toc: 'Mục lục', find: 'Tìm trong hướng dẫn', none: 'Không thấy kết quả. Thử từ khác, ví dụ "relay", "giờ ngủ", "gỡ".', prev: 'Trước', next: 'Tiếp', close: 'Đóng', n: ' kết quả', ph: 'Tìm kiếm' }
    : { toc: 'Contents', find: 'Search the guide', none: 'No results. Try another word, e.g. "relay", "bedtime", "remove".', prev: 'Previous', next: 'Next', close: 'Close', n: ' results', ph: 'Search' };
  var pages = Array.prototype.slice.call(main.children).filter(function (s) { return s.tagName === 'SECTION' && s.id; });
  if (pages.length < 2) return;
  var links = Array.prototype.slice.call(toc.querySelectorAll('a[href^="#"]'));
  var title = function (s) { var h = s.querySelector('h2'); return h ? h.childNodes[0].textContent.trim() : s.id; };
  var esc = function (s) { return s.replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); };
  // same length as an NFC input (one precomposed Vietnamese letter -> one char), so positions map back for <mark>
  var norm = function (s) { return s.normalize('NFC').split('').map(function (c) { return c.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[đĐ]/g, 'd').charAt(0) || c; }).join('').toLowerCase(); };
  var flat = function (s) { return ' ' + norm(s).replace(/[^a-z0-9]+/g, ' ') + ' '; };   // words only: a match starts a word
  var wpos = function (n, w) { var k = (' ' + n.replace(/[^a-z0-9]/g, ' ')).indexOf(' ' + w); return k; };
  var SVG = '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>';
  lay.classList.add('gpaged');

  // --- phone bar + drawer ---
  var bar = document.createElement('div');
  bar.className = 'gbar';
  bar.innerHTML = '<button type="button" class="gmenu" aria-expanded="false"><span class="gmi" aria-hidden="true">☰</span><span class="gml"></span></button>' +
    '<button type="button" class="gsb" aria-label="' + T.find + '">' + SVG + '</button>';
  main.insertBefore(bar, main.firstChild);
  var back = document.createElement('div'); back.className = 'gback'; lay.appendChild(back);
  var head = document.createElement('div');
  head.className = 'gtop';
  head.innerHTML = '<div class="gtop-h"><b>' + T.toc + '</b><button type="button" class="gclose" aria-label="' + T.close + '">✕</button></div>' +
    '<label class="gfind">' + SVG + '<input type="search" placeholder="' + T.ph + '" aria-label="' + T.find + '" autocomplete="off"><kbd>/</kbd></label>';
  var old = toc.querySelector(':scope > b'); if (old) old.remove();
  toc.insertBefore(head, toc.firstChild);
  var list = toc.querySelector('ol'), res = document.createElement('div');
  res.className = 'gres'; res.hidden = true; toc.appendChild(res);
  var input = head.querySelector('input');
  function drawer(on) {
    toc.classList.toggle('open', on); back.classList.toggle('on', on);
    bar.querySelector('.gmenu').setAttribute('aria-expanded', on ? 'true' : 'false');
    document.documentElement.classList.toggle('glock', on);
  }
  bar.querySelector('.gmenu').onclick = function () { drawer(true); };
  bar.querySelector('.gsb').onclick = function () { drawer(true); setTimeout(function () { input.focus(); }, 50); };
  back.onclick = head.querySelector('.gclose').onclick = function () { drawer(false); };
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && toc.classList.contains('open')) drawer(false);
    if (e.key === '/' && document.activeElement !== input && !/INPUT|TEXTAREA/.test((document.activeElement || {}).tagName || '')) { e.preventDefault(); if (innerWidth < 960) drawer(true); input.focus(); }
  });

  // --- pager ---
  var pager = document.createElement('div'); pager.setAttribute('role', 'navigation');   // not <nav>: site.css styles every nav as the header menu
  pager.className = 'gpager'; pager.setAttribute('aria-label', vi ? 'Trang' : 'Pages');
  main.appendChild(pager);

  var cur = null;
  function show(id, scroll) {
    var el = id && document.getElementById(id);
    var pg = el && pages.filter(function (p) { return p === el || p.contains(el); })[0];
    if (!pg) { pg = cur || pages[0]; el = null; }
    var changed = pg !== cur;
    cur = pg;
    pages.forEach(function (p) { p.hidden = p !== pg; });
    var i = pages.indexOf(pg), pv = pages[i - 1], nx = pages[i + 1];
    pager.innerHTML = (pv ? '<a class="pv" href="#' + pv.id + '"><small>← ' + T.prev + '</small><b>' + esc(title(pv)) + '</b></a>' : '<span></span>') +
      (nx ? '<a class="nx" href="#' + nx.id + '"><small>' + T.next + ' →</small><b>' + esc(title(nx)) + '</b></a>' : '');
    bar.querySelector('.gml').textContent = T.toc + ' · ' + (i + 1) + '/' + pages.length + ' · ' + title(pg);
    links.forEach(function (a) {
      var h = a.getAttribute('href').slice(1), on = h === pg.id || (el && h === id && h !== pg.id);
      a.classList.toggle('on', !!on); if (on) a.setAttribute('aria-current', 'location'); else a.removeAttribute('aria-current');
      // phones: only the open page's sub-steps are listed under it
      if (a.parentNode.classList.contains('sub')) a.parentNode.classList.toggle('here', pg.contains(document.getElementById(h)));
    });
    if (el && el !== pg) {
      var d = el.closest('details'); if (d) d.open = true;
      el.scrollIntoView();
    } else if (scroll && changed) {
      var top = lay.getBoundingClientRect().top + scrollY - 64;
      if (scrollY > top) scrollTo(0, top);
    }
  }
  function route(scroll) { show(decodeURIComponent(location.hash.slice(1)), scroll); }
  window.addEventListener('hashchange', function () { drawer(false); clearQ(); route(true); });
  toc.addEventListener('click', function (e) {
    var a = e.target.closest && e.target.closest('a[href^="#"]');
    if (a && a.getAttribute('href') === location.hash) { drawer(false); clearQ(); route(true); }   // same hash: no hashchange
  });

  // --- search: one unit per heading (its text = everything until the next heading) ---
  var units = [];
  pages.forEach(function (p) {
    var u = null;
    p.querySelectorAll('h2,h3,h4,summary,p,li,td,th,dt,dd,pre,figcaption').forEach(function (n) {
      if (/^(H2|H3|H4|SUMMARY)$/.test(n.tagName)) {
        var tgt = n.id ? n : n.closest('[id]');
        u = { page: p, head: n.textContent.trim(), id: (tgt && p.contains(tgt) ? tgt : p).id, text: '' };
        if (n.tagName === 'SUMMARY') { u.id = (n.closest('[id]') || p).id; }
        units.push(u);
      } else if (u && !(n.tagName === 'P' && n.closest('li'))) u.text += ' ' + n.textContent.replace(/\s+/g, ' ').trim();
    });
  });
  units.forEach(function (u) { u.nh = flat(u.head); u.nt = flat(u.text); u.np = flat(title(u.page)); });
  function snip(text, words) {
    text = text.normalize('NFC');
    var nt = norm(text), at = -1;
    words.forEach(function (w) { var k = wpos(nt, w); if (k >= 0 && (at < 0 || k < at)) at = k; });
    var s = Math.max(0, at - 40), out = (s > 0 ? '…' : '') + text.slice(s, s + 140) + (s + 140 < text.length ? '…' : '');
    var h = esc(out);
    words.forEach(function (w) {
      if (w.length < 2) return;
      var no = norm(out), k = wpos(no, w); if (k < 0) return;
      var raw = out.slice(k, k + w.length); h = h.replace(esc(raw), '<mark>' + esc(raw) + '</mark>');
    });
    return h;
  }
  function clearQ() { if (input.value) { input.value = ''; find(); } }
  function find() {
    var q = flat(input.value).trim(), words = q.split(' ').filter(Boolean);
    list.hidden = !!words.length; res.hidden = !words.length;
    if (!words.length) { res.innerHTML = ''; return; }
    var hits = units.map(function (u) {
      var all = u.nh + u.np + u.nt, sc = 0, has = function (s, w) { return s.indexOf(' ' + w) >= 0; };
      for (var i = 0; i < words.length; i++) {
        if (!has(all, words[i])) return null;
        sc += (has(u.nh, words[i]) ? 5 : 0) + (has(u.np, words[i]) ? 2 : 0) + (has(u.nt, words[i]) ? 1 : 0);
      }
      if (words.length > 1 && (u.nh + u.nt).indexOf(' ' + q) >= 0) sc += 10;   // the words side by side
      return { u: u, sc: sc };
    }).filter(Boolean).sort(function (a, b) { return b.sc - a.sc; }).slice(0, 20);
    res.innerHTML = hits.length ? '<p class="gn">' + hits.length + T.n + '</p>' + hits.map(function (h) {
      var u = h.u, pt = title(u.page);
      return '<a href="#' + u.id + '"><small>' + esc(pt) + (u.head !== pt ? ' › ' + esc(u.head) : '') + '</small><span>' + snip(u.text || u.head, words) + '</span></a>';
    }).join('') : '<p class="gn">' + T.none + '</p>';
  }
  input.addEventListener('input', find);
  input.addEventListener('keydown', function (e) { if (e.key === 'Enter') { var a = res.querySelector('a'); if (a) a.click(); } });

  route(false);
})();
