(() => {
 'use strict';
 const listeners = [];
 window.chrome = window.chrome || {};
 window.chrome.webview = {
  postMessage(msg) {
   if (window.AndroidHost && window.AndroidHost.postMessage) {
    window.AndroidHost.postMessage(JSON.stringify(msg));
   }
  },
  addEventListener(type, fn) {
   if (type === 'message' && typeof fn === 'function') listeners.push(fn);
  }
 };

 window.__onAndroidMessage = function(jsonStr) {
  let data;
  try { data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr; } catch (e) { return; }
  if (data && data.event === 'switchKind' && data.data && data.data.kind) {
   const u = new URL(location.href);
   u.searchParams.set('kind', data.data.kind);
   location.replace(u.toString());
   return;
  }
  const ev = { data };
  for (const fn of listeners) {
   try { fn(ev); } catch (e) {}
  }
 };

 window.__setAndroidInsets = function(topPx, bottomPx) {
  document.documentElement.style.setProperty('--sat', (topPx || 0) + 'px');
  document.documentElement.style.setProperty('--sab', (bottomPx || 0) + 'px');
 };

 window.__applyWorkspaceAppearance = function(pref) {
  if (!pref) return;
  const root = document.documentElement;
  if (pref.theme === 'dark' || pref.theme === 'light') root.setAttribute('data-theme', pref.theme);
  else root.removeAttribute('data-theme');
  if (typeof pref.glassT === 'number') {
   const t = Math.max(0, Math.min(1, pref.glassT));
   root.style.setProperty('--glass-t', String(t));
   const blurPx = t >= 0.5 ? Math.round(18 - (t - 0.5) * 24) : 18;
   root.style.setProperty('--lg-blur', blurPx + 'px');
  }
  root.setAttribute('data-glass-style', pref.glassStyle === 'blur' ? 'blur' : 'prism');
 };

 // Predictive Back Stack Handler (matches Prism / screen recordings — never abruptly kills app)
 window.__handleAndroidBack = function() {
  const about = document.getElementById('about-dialog');
  if (about && about.open) { about.close(); return 'handled'; }
  const setDlg = document.getElementById('settings-dialog');
  if (setDlg && setDlg.open) { setDlg.close(); return 'handled'; }
  const ss = document.getElementById('slideshow');
  if (ss && !ss.hidden) { ss.hidden = true; return 'handled'; }
  const fb = document.getElementById('find-bar');
  if (fb && !fb.hidden) { fb.hidden = true; return 'handled'; }
  if (document.body.classList.contains('focus-mode')) {
   document.body.classList.remove('focus-mode');
   const ef = document.getElementById('exit-focus');
   if (ef) ef.hidden = true;
   return 'handled';
  }
  const insp = document.getElementById('inspector');
  if (insp && !insp.hidden && window.innerWidth < 640) {
   insp.hidden = true;
   return 'handled';
  }
  const sbar = document.getElementById('start-search-bar');
  if (sbar && !sbar.hidden) {
   const closeBtn = document.getElementById('start-search-close');
   if (closeBtn) closeBtn.click();
   return 'handled';
  }
  const ed = document.getElementById('editor');
  if (ed && !ed.hidden) {
   const homeBtn = ed.querySelector('[data-action="home"]');
   if (homeBtn) { homeBtn.click(); return 'handled'; }
  }
  const recentBtn = document.querySelector('#start-nav button[data-start="recent"].selected');
  if (recentBtn) {
   const newBtn = document.querySelector('#start-nav button[data-start="new"]');
   if (newBtn) { newBtn.click(); return 'handled'; }
  }
  const kind = new URLSearchParams(location.search).get('kind') || 'workspace';
  if (kind !== 'workspace') {
   const u = new URL(location.href);
   u.searchParams.set('kind', 'workspace');
   location.replace(u.toString());
   return 'handled';
  }
  return 'exit';
 };

 function bindGlow(el) {
  if (!el || el.__kmGlow) return;
  el.__kmGlow = true;
  el.classList.add('km-lens-glow');
  const setPt = (ev) => {
   const r = el.getBoundingClientRect();
   const p = ev.touches && ev.touches[0] ? ev.touches[0] : ev;
   el.style.setProperty('--px', (p.clientX - r.left) + 'px');
   el.style.setProperty('--py', (p.clientY - r.top) + 'px');
   el.style.setProperty('--pa', '1');
  };
  const clearPt = () => el.style.setProperty('--pa', '0');
  el.addEventListener('pointerdown', setPt, { passive: true });
  el.addEventListener('pointermove', (e) => { if (e.buttons) setPt(e); }, { passive: true });
  el.addEventListener('pointerup', clearPt, { passive: true });
  el.addEventListener('pointercancel', clearPt, { passive: true });
 }

 // Interactive Kyant0 / MeiloX Finger Drag-to-Scrub Liquid Pill Lens
 function bindPillScrub(boxSel, itemSel) {
  const box = document.querySelector(boxSel);
  if (!box || box.__scrubBound) return;
  box.__scrubBound = true;
  let active = false, lastIdx = -1;
  const pickAt = (clientX, clientY) => {
   const items = Array.from(box.querySelectorAll(itemSel)).filter(n => n.offsetParent);
   for (let i = 0; i < items.length; i++) {
    const r = items[i].getBoundingClientRect();
    if (clientX >= r.left && clientX <= r.right && clientY >= r.top - 10 && clientY <= r.bottom + 10) {
     if (i !== lastIdx) {
      lastIdx = i;
      items[i].click();
      if (window.PillNav) window.PillNav.refresh();
     }
     break;
    }
   }
  };
  box.addEventListener('pointerdown', e => {
   active = true; lastIdx = -1;
   box.classList.add('scrubbing');
  }, { passive: true });
  box.addEventListener('pointermove', e => {
   if (!active) return;
   pickAt(e.clientX, e.clientY);
  }, { passive: true });
  const end = () => { active = false; box.classList.remove('scrubbing'); };
  box.addEventListener('pointerup', end, { passive: true });
  box.addEventListener('pointercancel', end, { passive: true });
 }

 window.addEventListener('DOMContentLoaded', () => {
  const kind = new URLSearchParams(location.search).get('kind') || 'workspace';
  if (kind !== 'workspace') {
   const nav = document.getElementById('start-nav');
   if (nav) {
    const b = document.createElement('button');
    b.textContent = '허브';
    b.onclick = () => {
     const u = new URL(location.href);
     u.searchParams.set('kind', 'workspace');
     location.replace(u.toString());
    };
    nav.appendChild(b);
   }
  }
  if (window.innerWidth < 880) {
   const insp = document.getElementById('inspector');
   if (insp) insp.hidden = true;
  }
  window.addEventListener('resize', () => {
   const insp = document.getElementById('inspector');
   if (insp && window.innerWidth < 640) insp.hidden = true;
   if (window.PillNav) {
    requestAnimationFrame(() => window.PillNav.refresh());
    setTimeout(() => window.PillNav && window.PillNav.refresh(), 120);
   }
  });

  document.querySelectorAll('#start-nav, .topbar, #ribbon, #inspector, #find-bar, #start-search-fab, #start-search-bar, #exit-focus, .rail').forEach(bindGlow);
  bindPillScrub('#start-nav', 'button');
  bindPillScrub('#tabs', '.tab');

  // Scroll-aware floating dock auto-collapse on Start screen (matches screen recording)
  const startEl = document.getElementById('start');
  const startMain = document.getElementById('start-content');
  const nav = document.getElementById('start-nav');
  const fab = document.getElementById('start-search-fab');
  let lastY = 0;
  const onScroll = (sc) => {
   if (window.innerWidth >= 640 || !nav) return;
   const y = sc.scrollTop;
   const dy = y - lastY;
   lastY = y;
   if (y <= 12 || dy < -8) {
    nav.classList.remove('dock-collapsed');
    if (fab) fab.classList.remove('dock-collapsed');
   } else if (dy > 10 && y > 48) {
    nav.classList.add('dock-collapsed');
    if (fab) fab.classList.add('dock-collapsed');
   }
  };
  if (startEl) startEl.addEventListener('scroll', () => onScroll(startEl), { passive: true });
  if (startMain) startMain.addEventListener('scroll', () => onScroll(startMain), { passive: true });

  // Auto-bind staggered entrance animation & glow to newly inserted cards
  const sc = document.getElementById('start-content');
  if (sc) {
   new MutationObserver(() => {
    sc.querySelectorAll('.app-card, .template, .recent-row').forEach((el, i) => {
     el.style.setProperty('--d', Math.min(i * 32, 240) + 'ms');
     el.classList.add('ws-anim-card');
     bindGlow(el);
    });
    if (window.PillNav) requestAnimationFrame(() => window.PillNav.refresh());
   }).observe(sc, { childList: true });
  }
 });
})();
