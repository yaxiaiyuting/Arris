/* App adapter. No microphone or network access: Android owns permission/audio. */
(function () {
  'use strict';
  if (!new URLSearchParams(location.search).has('app')) return;
  let b, waiting = false, blown = false, disposed = false, configured = false;
  const WAIT = 26.2;
  function emit(value) {
    if (!disposed && window.ArrisHost) window.ArrisHost.event(value);
    window.dispatchEvent(new CustomEvent('arris-state', { detail: value }));
  }
  let layoutW = 0, layoutH = 0, layoutTimer = 0;
  function layout(force) {
    if (!b) return;
    // v3.6.3：尺寸没变就直接返回。Android 上 Dialog / IME / 系统栏抖动会连发 resize，
    // 每次重排都跑一遍「重建画布 + 重新锚定」，看起来就是一闪。
    if (!force && innerWidth === layoutW && innerHeight === layoutH) return;
    layoutW = innerWidth; layoutH = innerHeight;
    const portrait = innerHeight > innerWidth;
    document.body.classList.toggle('portrait', portrait);
    const height = portrait ? 1600 * innerHeight / innerWidth : 900;
    window.ARRIS_LAYOUT = { height: height, portrait: portrait };
    b.units.h = height;
    b.cue.shells.forEach(function (s) {
      if (s._originalY === undefined) s._originalY = s.y;
      s.y = portrait ? s._originalY / 900 * height * .75 : s._originalY;
    });
    // 只重绘、**不 seek**：seek(0)+seek(t) 会 resetSim/清空 flashes，
    // 重放时给每一朵烟花再补一次 Lighting.flash（实测 extFlashes 0→2，整屏变亮）。
    if (window.VFX) {
      window.VFX.resize();
      if (window.VFX.repaint) window.VFX.repaint(b.time());
    }
    fitBlessing();
  }
  function scheduleLayout() {
    clearTimeout(layoutTimer);
    layoutTimer = setTimeout(function () { layout(false); }, 120);
  }
  function fitBlessing() {
    const box = document.getElementById('blessing');
    box.style.setProperty('--wish-scale', '1');
    if (innerHeight <= innerWidth) return;
    let scale = 1;
    for (let i = 0; i < 7 && box.getBoundingClientRect().bottom > innerHeight - 120; i++) {
      scale *= .94;
      box.style.setProperty('--wish-scale', scale.toFixed(3));
    }
  }
  function stop() {
    if (!b) return;
    b.pause();
    if (b.idle) { b.idle.kill(); b.idle = null; }
    if (disposed) b.gsap.ticker.sleep();
  }
  function boot() {
    b = window.BDAY;
    if (!b || !b.ready) return;
    document.body.classList.add('arris');
    const update = b.master.eventCallback('onUpdate');
    b.master.eventCallback('onUpdate', function () {
      if (update) update();
      if (!blown && !waiting && b.time() >= WAIT) {
        waiting = true;
        stop(); b.seek(WAIT); emit('WAITING');
      }
    });
    b.master.eventCallback('onComplete', function () { stop(); emit('DONE'); });
    // A touch on the artwork must never bypass the native puff gate.
    document.getElementById('stage').addEventListener('click', function (e) {
      e.stopImmediatePropagation();
    }, true);
    document.addEventListener('keydown', function (e) {
      if ([' ', 'r', 'R', 'ArrowLeft', 'ArrowRight'].indexOf(e.key) >= 0) {
        e.preventDefault(); e.stopImmediatePropagation();
      }
    }, true);
    layout(true);
    window.addEventListener('resize', scheduleLayout);
    emit('READY');
  }
  window.ArrisBirthday = {
    configure: function (config) {
      if (configured || disposed || !b || !b.ready) return;
      configured = true;
      const lines = document.querySelectorAll('#blessing .line span');
      [config.title].concat(config.wishes || []).forEach(function (text, i) {
        if (lines[i] && text) lines[i].textContent = text;
      });
      if (config.captions) b.cue.captions.forEach(function (caption, i) {
        caption.text = config.captions[[0,0,2,3,4,6,7][i]] || caption.text;
      });
      fitBlessing();
      b.seek(0); b.play(); emit('PLAYING');
    },
    blow: function () {
      if (!waiting || blown || disposed) return false;
      blown = true; waiting = false; b.play(); emit('PLAYING'); return true;
    },
    replay: function () {
      if (disposed) return;
      blown = false; waiting = false; stop(); b.seek(0); b.play(); emit('PLAYING');
    },
    stop: function () { disposed = true; stop(); },
    // Read-only state for deterministic integration QA.
    state: function () { return { waiting: waiting, blown: blown, disposed: disposed, time: b.time() }; }
  };
  window.addEventListener('error', function () { emit('ERROR'); });
  window.addEventListener('unhandledrejection', function () { emit('ERROR'); });
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) stop();
    else if (configured && !waiting && !disposed && b.time() < b.dur) b.play();
  });
  if (window.BDAY && window.BDAY.ready) boot();
  else window.addEventListener('bday-ready', boot, { once: true });
})();
