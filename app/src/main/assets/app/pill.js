/* One UI 9.5 floating pill tab bar for the older One UI Book apps.
   window.PILL_NAV = [{ box: css selector of the group, item: selector of its tabs, active: active class, vertical: bool }]
   Each group gets the pill surface and a selection indicator that slides (spring) to the active tab. */
(function () {
  'use strict';
  var cfg = window.PILL_NAV || [], last = {}, sched = 0;
  function apply() {
    cfg.forEach(function (c, ci) {
      var boxes = document.querySelectorAll(c.box);
      Array.prototype.forEach.call(boxes, function (box, bi) {
        if (!box.classList.contains('oneui-pill')) box.classList.add('oneui-pill');
        if (c.vertical && !box.classList.contains('vertical')) box.classList.add('vertical');
        if (!c.item) return;
        var ind = null;
        for (var k = 0; k < box.children.length; k++) if (box.children[k].classList.contains('pill-ind')) { ind = box.children[k]; break; }
        if (!ind) { ind = document.createElement('span'); ind.className = 'pill-ind'; ind.setAttribute('aria-hidden', 'true'); box.insertBefore(ind, box.firstChild); }
        var on = null, items = box.querySelectorAll(c.item);
        for (var i = 0; i < items.length; i++) if (items[i].classList.contains(c.active) && items[i].offsetParent) { on = items[i]; break; }
        var key = ci + ':' + bi;
        if (!on) { ind.style.opacity = '0'; last[key] = null; return; }
        var r = { l: on.offsetLeft, t: on.offsetTop, w: on.offsetWidth, h: on.offsetHeight }, p = last[key];
        if (p && p.l === r.l && p.t === r.t && p.w === r.w && p.h === r.h && ind.style.opacity === '1') return;
        ind.style.opacity = '1'; ind.style.left = r.l + 'px'; ind.style.top = r.t + 'px'; ind.style.width = r.w + 'px'; ind.style.height = r.h + 'px';
        last[key] = r;
      });
    });
  }
  function soon() { if (!sched) sched = requestAnimationFrame(function () { sched = 0; apply(); }); }
  function start() {
    apply();
    // only react to mutations that touch a pill group (big file lists / track lists change constantly)
    var sel = cfg.map(function (c) { return c.box; }).join(',');
    new MutationObserver(function (recs) {
      for (var i = 0; i < recs.length; i++) {
        var r = recs[i], t = r.target.nodeType === 1 ? r.target : r.target.parentElement;
        if (t && t.closest && t.closest(sel)) { soon(); return; }
        if (r.type === 'childList' && r.addedNodes.length) {
          if (r.addedNodes.length > 40) { soon(); return; }
          for (var k = 0; k < r.addedNodes.length; k++) { var n = r.addedNodes[k]; if (n.nodeType === 1 && (n.matches(sel) || n.querySelector(sel))) { soon(); return; } }
        }
      }
    }).observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'hidden'] });
    window.addEventListener('resize', soon);
    document.addEventListener('transitionend', function (e) { if (e.propertyName === 'width' || e.propertyName === 'padding-left' || e.propertyName === 'padding') soon(); }, true);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
  window.PillNav = { refresh: apply };
})();
