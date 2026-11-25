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
 // Add "← 워크스페이스" hub switcher when inside Typing / Graphs / Present start screen
 window.addEventListener('DOMContentLoaded', () => {
  const kind = new URLSearchParams(location.search).get('kind') || 'workspace';
  if (kind !== 'workspace') {
   const nav = document.getElementById('start-nav');
   if (nav) {
    const b = document.createElement('button');
    b.textContent = '← 워크스페이스 허브';
    b.onclick = () => {
     const u = new URL(location.href);
     u.searchParams.set('kind', 'workspace');
     location.replace(u.toString());
    };
    nav.appendChild(b);
   }
  }
  // On phones (<640px), keep inspector collapsed by default so editor opens cleanly
  if (window.innerWidth < 640) {
   const insp = document.getElementById('inspector');
   if (insp) insp.hidden = true;
  }
  // Kyant0 + MeiloX interactive optical glass touch bloom on pills & glass surfaces
  const bindGlow = (el) => {
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
  };
  document.querySelectorAll('#start-nav, .topbar, #ribbon, #inspector, #find-bar').forEach(bindGlow);
 });
})();
