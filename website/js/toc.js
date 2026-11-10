// Guide table of contents follows the reading position (owner 2026-11-11: the menu never showed where you are).
// The section (and sub-article) whose top passed the sticky header is marked .on and aria-current; on the sticky
// desktop TOC the marked row is kept in view. One rAF-throttled scroll handler, no layout writes.
(function () {
  var toc = document.querySelector('.toc');
  if (!toc) return;
  var links = Array.prototype.slice.call(toc.querySelectorAll('a[href^="#"]'));
  var items = links.map(function (a) { return { a: a, el: document.getElementById(a.getAttribute('href').slice(1)), sub: a.parentNode.classList.contains('sub') }; })
    .filter(function (x) { return x.el; });
  if (!items.length) return;
  var cur = null, curSub = null, queued = false;

  function pick() {
    queued = false;
    var line = 96, main = null, sub = null, mt = -1e9, st = -1e9;   // just under the sticky header
    for (var i = 0; i < items.length; i++) {   // the passed heading closest to the line (TOC order may differ from the page)
      var r = items[i].el.getBoundingClientRect();
      if (r.top - line > 0) continue;
      if (items[i].sub) { if (r.bottom > line && r.top > st) { sub = items[i]; st = r.top; } }
      else if (r.top > mt) { main = items[i]; mt = r.top; }
    }
    // at the very bottom the last short section may never reach the line
    if (window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4) {
      var lb = -1e9; items.forEach(function (x) { var t = x.el.getBoundingClientRect().top; if (!x.sub && t > lb) { lb = t; main = x; } });
    }
    if (sub && main && !main.el.contains(sub.el)) sub = null;
    if (main !== cur || sub !== curSub) {
      links.forEach(function (a) { a.classList.remove('on'); a.removeAttribute('aria-current'); });
      if (main) { main.a.classList.add('on'); main.a.setAttribute('aria-current', 'location'); }
      if (sub) { sub.a.classList.add('on'); sub.a.setAttribute('aria-current', 'location'); }
      cur = main; curSub = sub;
      var show = sub || main;
      if (show && toc.scrollHeight > toc.clientHeight) {   // sticky TOC taller than the screen: keep the row visible
        var t = show.a.offsetTop - toc.offsetTop, h = show.a.offsetHeight;
        if (t < toc.scrollTop || t + h > toc.scrollTop + toc.clientHeight) toc.scrollTop = t - toc.clientHeight / 3;
      }
    }
  }
  function q() { if (!queued) { queued = true; requestAnimationFrame(pick); } }
  window.addEventListener('scroll', q, { passive: true });
  window.addEventListener('resize', q);
  window.addEventListener('hashchange', q);
  pick();
})();
