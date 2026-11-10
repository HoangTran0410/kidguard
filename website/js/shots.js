// Screenshot lightbox (owner 2026-11-11: the small gallery shots are too dense to read): tap a shot -> full screen,
// prev / next buttons, swipe, arrow keys, Esc / tap outside / X to close. Uses the same image file (no extra download).
// No inline styles or scripts (site CSP): classes live in css/site.css (.lb*).
(function () {
  var imgs = Array.prototype.slice.call(document.querySelectorAll('.gallery img'));
  if (!imgs.length) return;
  var vi = (document.documentElement.lang || '').indexOf('vi') === 0;
  var T = vi ? { close: 'Đóng', prev: 'Ảnh trước', next: 'Ảnh sau', open: 'Xem lớn' }
             : { close: 'Close', prev: 'Previous', next: 'Next', open: 'View larger' };
  var box, pic, cap, count, at = 0, opener = null, x0 = null;

  function el(tag, cls, parent) { var e = document.createElement(tag); if (cls) e.className = cls; if (parent) parent.appendChild(e); return e; }
  function btn(cls, label, glyph, fn) {
    var b = el('button', 'lb-b ' + cls, box); b.type = 'button'; b.setAttribute('aria-label', label); b.title = label; b.textContent = glyph;
    b.addEventListener('click', function (e) { e.stopPropagation(); fn(); });
    return b;
  }
  function build() {
    box = el('div', 'lb', document.body);
    box.setAttribute('role', 'dialog'); box.setAttribute('aria-modal', 'true'); box.hidden = true;
    var fig = el('figure', 'lb-f', box);
    pic = el('img', 'lb-i', fig); pic.decoding = 'async';
    cap = el('figcaption', 'lb-c', fig);
    count = el('div', 'lb-n', box);
    btn('lb-x', T.close, '✕', close);
    btn('lb-p', T.prev, '‹', function () { go(-1); });
    btn('lb-q', T.next, '›', function () { go(1); });
    box.addEventListener('click', function (e) { if (e.target === box || e.target === fig) close(); });
    box.addEventListener('touchstart', function (e) { x0 = e.touches.length === 1 ? e.touches[0].clientX : null; }, { passive: true });
    box.addEventListener('touchend', function (e) {
      if (x0 === null) return;
      var dx = e.changedTouches[0].clientX - x0; x0 = null;
      if (Math.abs(dx) > 50) go(dx < 0 ? 1 : -1);
    }, { passive: true });
  }
  function show(i) {
    at = (i + imgs.length) % imgs.length;
    var src = imgs[at], f = src.closest('figure'), c = f && f.querySelector('figcaption');
    pic.src = src.currentSrc || src.src; pic.alt = src.alt;
    cap.innerHTML = '';
    if (c) cap.appendChild(c.cloneNode(true)).className = '';
    count.textContent = (at + 1) + ' / ' + imgs.length;
    box.setAttribute('aria-label', src.alt);
  }
  function go(d) { show(at + d); }
  function open(i) {
    if (!box) build();
    opener = imgs[i].parentNode;
    show(i); box.hidden = false;
    document.documentElement.classList.add('lb-on');
    box.querySelector('.lb-x').focus();
  }
  function close() {
    if (!box || box.hidden) return;
    box.hidden = true; document.documentElement.classList.remove('lb-on');
    if (opener && opener.focus) opener.focus();
  }
  document.addEventListener('keydown', function (e) {
    if (!box || box.hidden) return;
    if (e.key === 'Escape') close();
    else if (e.key === 'ArrowRight') go(1);
    else if (e.key === 'ArrowLeft') go(-1);
    else if (e.key === 'Tab') { // keep focus inside the dialog
      var b = box.querySelectorAll('button'), first = b[0], last = b[b.length - 1];
      if (e.shiftKey && document.activeElement === first) { last.focus(); e.preventDefault(); }
      else if (!e.shiftKey && document.activeElement === last) { first.focus(); e.preventDefault(); }
    }
  });
  imgs.forEach(function (img, i) {
    var ph = img.parentNode; // .phone
    ph.classList.add('lb-t'); ph.setAttribute('role', 'button'); ph.tabIndex = 0; ph.setAttribute('aria-label', T.open + ': ' + img.alt);
    ph.addEventListener('click', function () { open(i); });
    ph.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(i); } });
  });
})();
