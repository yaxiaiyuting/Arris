/* ─────────────────────────────────────────────────────────────
   main.js · 集成层（Lead 维护）
   职责：注入资产、相机 / 视差驱动、时间寻址、UI、减少动效分支
   ───────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  const gsap = window.gsap;
  const CUES = window.CUES;
  const q = (s) => document.querySelector(s);
  const ERRORS = [];
  /* 任何同事模块抛错都不能让整页停下：记录并继续 */
  const guard = (label, fn, fallback) => {
    try { return fn(); } catch (e) {
      const msg = '[' + label + '] ' + (e && e.message ? e.message : e);
      if (ERRORS.indexOf(msg) < 0) { ERRORS.push(msg); console.error('BDAY', msg, e); }
      return fallback;
    }
  };
  const param = new URLSearchParams(location.search);
  const STILL = param.has('still') || param.has('t');
  const CLEAN = param.has('clean');
  const FORCE_MOTION = param.has('motion');
  // 调试开关：定位性能/图层问题（不影响正常打开）
  const NO_VFX = param.has('novfx');
  const NO_LIGHT = param.has('nolight');
  const REDUCED = !FORCE_MOTION &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  gsap.registerPlugin(window.CustomEase, window.MotionPathPlugin);
  gsap.config({ nullTargetWarn: false, force3D: true });

  /* ── 缓动库：所有运动只用这里定义的 ease，禁止散落 linear ── */
  if (window.CustomEase) {
    const mk = (name, path) => { try { window.CustomEase.create(name, path); } catch (e) { /* ignore */ } };
    mk('arrive', 'M0,0 C0.16,0 0.2,0.92 0.42,1.02 0.62,1.08 0.82,1.01 1,1');
    mk('settle', 'M0,0 C0.18,0 0.24,1.2 0.5,1.07 0.72,0.975 0.87,1 1,1');
    mk('weight', 'M0,0 C0.26,0 0.34,0.64 0.48,0.9 0.64,1.06 0.82,1 1,1');
    mk('drift',  'M0,0 C0.36,0 0.44,0.06 0.5,0.5 0.56,0.94 0.64,1 1,1');
  }
  const E = {
    linear: (t) => t,
    sineInOut: (t) => -(Math.cos(Math.PI * t) - 1) / 2,
    power2Out: (t) => 1 - (1 - t) * (1 - t),
    power2In: (t) => t * t,
    power3Out: (t) => 1 - Math.pow(1 - t, 3),
    power3In: (t) => t * t * t,
    power3InOut: (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
    backOut: (t) => 1 + 2.4 * Math.pow(t - 1, 3) + 1.4 * Math.pow(t - 1, 2),
    arrive: (t) => 1 - Math.pow(1 - t, 3.4),
    drift: (t) => t * t * (3 - 2 * t)
  };
  // 阻尼回弹：到位、微过冲、收住
  E.settle = (t) => {
    const raw = 1 - Math.exp(-5.2 * t) * Math.cos(7.8 * t);
    const end = 1 - Math.exp(-5.2) * Math.cos(7.8);
    return raw / end;
  };

  /* ── 元素 ── */
  const stage = q('#stage');
  const svg = q('#svg-scene');
  const canvasBack = q('#fx-back');
  const canvasFront = q('#fx-front');
  const blackout = q('#blackout');
  const bloomEl = q('#bloom');
  const captionEl = q('#caption');
  const blessingEl = q('#blessing');
  const ui = q('#ui');
  const layers = {};
  /* 两个 SVG 共用一套 data-depth 契约：背层在 #svg-env，主体在 #svg-scene */
  document.querySelectorAll('#stage g[data-depth]').forEach((g) => {
    layers[g.id.replace('layer-', '')] = { el: g, depth: parseFloat(g.dataset.depth) || 0 };
    g.style.willChange = 'transform';
  });

  /* ── 相机 ── */
  const cam = { x: 0, y: 0, z: 0, w: 0 };
  const rig = {
    cam,
    keys: [],
    ease(name) { return E[name] || E.sineInOut; },
    /* key(at, {x,y,z, dur, ease}) —— at 是这一段的开始时间 */
    key(at, opt) {
      rig.keys.push(Object.assign({ at: at, dur: 1.6, ease: 'sineInOut' }, opt));
      rig.keys.sort((a, b) => a.at - b.at);
      return rig;
    },
    /* 纯函数：给定时间返回机位，保证任意 seek 都可复现 */
    at(t) {
      const keys = rig.keys;
      const start = { x: 0, y: 0, z: 0 };
      if (!keys.length) return start;
      let prev = start;
      for (let i = 0; i < keys.length; i++) {
        const k = keys[i];
        if (t < k.at) return prev;
        const target = { x: k.x, y: k.y, z: k.z };
        const p = Math.min(1, (t - k.at) / Math.max(0.001, k.dur));
        const fn = E[k.ease] || E.sineInOut;
        const e = fn(p);
        const s = {
          x: prev.x + (target.x - prev.x) * e,
          y: prev.y + (target.y - prev.y) * e,
          z: prev.z + (target.z - prev.z) * e
        };
        if (p < 1) return s;
        prev = target;
      }
      return prev;
    }
  };

  /* 每层视差变换 + 不可公约频率的漂移（避免整体同步平移） */
  const driftCfg = [
    { a: 3.2, f1: 0.043, f2: 0.071 },
    { a: 4.4, f1: 0.061, f2: 0.038 },
    { a: 5.6, f1: 0.031, f2: 0.067 },
    { a: 7.4, f1: 0.047, f2: 0.029 },
    { a: 1.6, f1: 0.023, f2: 0.041 },
    { a: 2.2, f1: 0.019, f2: 0.053 },
    { a: 2.6, f1: 0.017, f2: 0.037 },
    { a: 9.5, f1: 0.055, f2: 0.026 },
    { a: 14.0, f1: 0.039, f2: 0.022 }
  ];
  const layerList = Object.keys(layers).map((k, i) => Object.assign({ name: k }, layers[k], driftCfg[i % driftCfg.length]));

  function applyCamera(t) {
    const c = rig.at(t);
    cam.x = c.x; cam.y = c.y; cam.z = c.z;
    const handheldX = Math.sin(t * 0.29) * 1.5 + Math.sin(t * 0.163 + 1.7) * 1.1;
    const handheldY = Math.cos(t * 0.241 + 0.6) * 1.3 + Math.sin(t * 0.107 + 2.4) * 0.9;

    for (let i = 0; i < layerList.length; i++) {
      const L = layerList[i];
      const d = L.depth;
      const ph = i * 1.37;
      const dx = Math.sin(t * L.f1 + ph) * L.a + Math.cos(t * L.f2 * 1.618 + ph * 2.1) * L.a * 0.45;
      const dy = Math.cos(t * L.f2 + ph * 0.7) * L.a * 0.55 + Math.sin(t * L.f1 * 1.414 + ph) * L.a * 0.3;
      const sc = (1 + c.z) * (1 + c.z * d * 0.34);
      /* 用 CSS transform 而不是 SVG transform 属性：
         属性写法会让浏览器每帧重新栅格化整块 SVG（软件光栅下 60fps → 4fps）；
         CSS transform 可以把每一层提到独立合成层，只做位移/缩放合成。 */
      /* 量化 + 变化才写：视差每帧只动几十分之一像素时就跳过，
         避免为了不可见的位移去失效整块图层（这是低帧率的主要来源）。 */
      const tx = Math.round((-c.x * d + dx + handheldX * 0.6) * 4) / 4;
      const ty = Math.round((-c.y * d + dy + handheldY * 0.6) * 4) / 4;
      const qs = Math.round(sc * 3000) / 3000;
      if (L._tx !== tx || L._ty !== ty || L._sc !== qs) {
        L._tx = tx; L._ty = ty; L._sc = qs;
        L.el.style.transformOrigin = '800px 450px';
        L.el.style.transform = `translate3d(${tx}px, ${ty}px, 0) scale(${qs})`;
      }
    }
  }

  /* ── 资产注入 ── */
  function inject(id, html) {
    const host = q('#' + id);
    if (!host || !html) return;
    host.innerHTML = html;
  }
  function bootAssets() {
    const A = window.Assets || {};
    const env = A.env || {};
    inject('layer-sky', env.sky);
    inject('layer-aurora', env.aurora);
    inject('layer-far', env.far);
    inject('layer-mid', env.mid);
    inject('layer-ground', env.ground);
    inject('layer-near', env.near);
    inject('layer-fg', env.fg);
    inject('layer-tux', A.tux && A.tux.svg);
    inject('layer-cake', A.cake && A.cake.svg);
    /* 帽子与耳机必须跟着头走：它们是 #tux-head 的兄弟节点，
       头一扭头/一鼓腮（scaleX 1.045）就会和帽子、耳罩脱开 —— 表现为"头好像不见了"。
       在 boot 时把它们挂进 #tux-head（此时头还没有任何 transform，坐标不变），
       motion 对 hat / headphones 的独立小抖动会变成"局部延迟"，正好是要的二次动作。 */
    const headEl = document.getElementById('tux-head');
    ['tux-hat', 'tux-headphones'].forEach((id) => {
      const el = document.getElementById(id);
      if (headEl && el && el.parentNode !== headEl) headEl.appendChild(el);
    });

    // 缺资产时给一个中性占位，保证时间线与构图仍可调试
    if (!(A.tux && A.tux.svg)) inject('layer-tux', FALLBACK.tux);
    if (!(A.cake && A.cake.svg)) inject('layer-cake', FALLBACK.cake);
  }
  const FALLBACK = {
    tux: `<g id="tux" opacity=".92"><ellipse id="tux-shadow" cx="600" cy="662" rx="96" ry="18" fill="#000" opacity=".45"/>
      <g id="tux-body"><ellipse cx="600" cy="560" rx="86" ry="104" fill="#13251c" stroke="#3a5142" stroke-width="2"/>
      <ellipse id="tux-belly" cx="600" cy="578" rx="58" ry="70" fill="#fff6dd" opacity=".9"/>
      <g id="tux-head"><circle cx="600" cy="438" r="62" fill="#13251c" stroke="#3a5142" stroke-width="2"/>
      <g id="tux-eye-l"><circle cx="580" cy="430" r="6" fill="#fff6dd"/></g>
      <g id="tux-eye-r"><circle cx="622" cy="430" r="6" fill="#fff6dd"/></g>
      <g id="tux-beak"><path d="M588 452 Q600 470 612 452 Z" fill="#e9b64d"/></g></g>
      <g id="tux-hat"><path d="M560 386 L600 322 L640 386 Z" fill="#6eaf7e"/></g>
      <g id="tux-headphones"><path d="M538 430 Q600 350 662 430" fill="none" stroke="#6eaf7e" stroke-width="10"/></g></g></g>`,
    cake: `<g id="cake"><ellipse id="cake-shadow" cx="800" cy="664" rx="120" ry="22" fill="#000" opacity=".4"/>
      <g id="cake-plate"><ellipse cx="800" cy="656" rx="120" ry="26" fill="#e8dfc9"/></g>
      <g id="cake-body"><rect x="720" y="590" width="160" height="66" rx="10" fill="#f6e3c0"/></g>
      <g id="cake-icing"><path d="M720 596 Q800 576 880 596 L880 606 Q800 588 720 606 Z" fill="#fff0d3"/></g>
      <g id="candle-1"><rect x="768" y="540" width="8" height="52" fill="#f0a99b"/><g id="flame-unit-1"><path id="flame-1" d="M772 528 q6 10 0 16 -6-6 0-16z" fill="#ffb066"/></g></g>
      <g id="candle-2"><rect x="796" y="540" width="8" height="52" fill="#ffe8c7"/><g id="flame-unit-2"><path id="flame-2" d="M800 528 q6 10 0 16 -6-6 0-16z" fill="#ffb066"/></g></g>
      <g id="candle-3"><rect x="824" y="540" width="8" height="52" fill="#f0a99b"/><g id="flame-unit-3"><path id="flame-3" d="M828 528 q6 10 0 16 -6-6 0-16z" fill="#ffb066"/></g></g></g>`
  };

  /* ── 状态 ── */
  const BDAY = {
    cue: CUES,
    root: stage,
    svg,
    svgEnv: q('#svg-env'),
    camera: cam,
    layers,
    rig,
    E,
    gsap,
    fx: { back: canvasBack, front: canvasFront },
    units: { w: 1600, h: 900 },
    /* 兼容接口：字幕的唯一权威是 captionAt(t)，见 paintUi。
       队友仍可调用，但会被同一帧的 paintUi 覆盖（避免 seek 时不求解）。 */
    setCaption(text) { if (captionEl) captionEl.textContent = text || ''; },
    captionAt(t) {
      const list = CUES.captions || [];
      for (let i = 0; i < list.length; i++) {
        if (t >= list[i].at && t < list[i].out) return list[i];
      }
      return null;
    },
    time() { return BDAY.master ? BDAY.master.time() : 0; },
    dur: CUES.dur,
    reduced: REDUCED,
    errors: ERRORS,
    ready: false
  };
  window.BDAY = BDAY;

  /* ── 播放 / 寻址 ── */
  function status(playing) {
    stage.classList.toggle('playing', !!playing);
    if (playing) stage.classList.add('ui-live');
  }

  BDAY.seek = function (t) {
    t = Math.max(0, Math.min(CUES.dur + 8, t));
    if (BDAY.idle) { BDAY.idle.pause(); BDAY.idle = null; }
    if (BDAY.master) {
      BDAY.master.pause();
      BDAY.master.time(0, false);
      BDAY.master.time(t, true);
    }
    if (window.VFX && window.VFX.seek) guard('vfx.seek', () => window.VFX.seek(Math.min(t, CUES.dur)));
    if (window.Lighting && window.Lighting.update) guard('lighting.update', () => window.Lighting.update(t));
    applyCamera(t);
    BDAY.paintUi(t);
    status(false);
  };

  /* 低配兜底：真机上如果播放帧率掉到 20 以下，自动切"轻量档"——
     关掉三层合成叠加与前景粒子画布，只留主体。宁可少两层调色，也不要卡成幻灯片。
     （页面在无头/软件光栅下测得的帧率不代表真机，所以只在播放态、连续 2s 低于阈值时触发。） */
  let liteChecked = false;
  function watchFps() {
    if (liteChecked || param.has('app')) return;
    liteChecked = true;
    let frames = 0; const t0 = performance.now();
    const tick = () => {
      frames++;
      const dt = performance.now() - t0;
      if (dt < 2000) return requestAnimationFrame(tick);
      const fps = frames / (dt / 1000);
      if (fps < 20 && !REDUCED) { stage.classList.add('lite'); console.warn('BDAY lite mode', fps.toFixed(1) + 'fps'); }
    };
    requestAnimationFrame(tick);
  }

  BDAY.play = function () {
    if (!BDAY.master) return;
    const t = BDAY.master.time();
    if (t >= CUES.dur - 0.05) return BDAY.seek(0) || BDAY.play();
    if (BDAY.idle) { BDAY.idle.pause(); BDAY.idle = null; }
    BDAY.master.play();
    if (window.VFX && window.VFX.play) guard('vfx.play', () => window.VFX.play());
    setTimeout(watchFps, 400);
    status(true);
  };
  BDAY.pause = function () {
    if (BDAY.master) BDAY.master.pause();
    if (window.VFX && window.VFX.pause) guard('vfx.pause', () => window.VFX.pause());
    status(false);
  };
  BDAY.toggle = function () { stage.classList.contains('playing') ? BDAY.pause() : BDAY.play(); };

  /* ── UI ── */
  const uiFill = q('#ui-fill'), uiKnob = q('#ui-knob'), uiTime = q('#ui-time'),
    uiBeat = q('#ui-beat'), uiTrack = q('#ui-track'), uiMarks = q('#ui-marks');

  /* 幕名直接从 CUES.act 生成，避免时间轴改了这里没跟上 */
  const ACT_NAME = {
    awake: '夜森苏醒', walk: '端来蛋糕', place: '放下蛋糕', light: '点亮愿望',
    wish: '许愿', blow: '吹灭蜡烛', dark: '黑场', boom: '升空烟花', bless: '生日快乐', end: '余韵'
  };
  const ACT_LABEL = Object.keys(CUES.act).map((k) => [CUES.act[k], ACT_NAME[k] || k]);
  BDAY.paintUi = function (t) {
    const p = Math.max(0, Math.min(1, t / CUES.dur));
    if (uiFill) uiFill.style.width = (p * 100).toFixed(2) + '%';
    if (uiKnob) uiKnob.style.left = (p * 100).toFixed(2) + '%';
    if (uiTime) uiTime.textContent = t.toFixed(1).padStart(4, '0') + ' / ' + CUES.dur.toFixed(1);
    if (uiBeat) {
      let label = ACT_LABEL[0][1];
      for (let i = 0; i < ACT_LABEL.length; i++) if (t >= ACT_LABEL[i][0]) label = ACT_LABEL[i][1];
      uiBeat.textContent = label;
    }
    // 字幕：由 t 直接求解，不依赖 timeline 的 call 回调（seek 可逆、可复现）
    if (captionEl) {
      const cap = BDAY.captionAt(t);
      const txt = cap ? cap.text : '';
      if (captionEl.textContent !== txt) captionEl.textContent = txt;
      let o = 0;
      if (cap) {
        const fin = Math.min(1, (t - cap.at) / 0.55);
        const fout = Math.min(1, (cap.out - t) / 0.55);
        o = Math.max(0, Math.min(fin, fout));
      }
      captionEl.style.opacity = o.toFixed(3);
    }
    // 祝福文字的暗色底衬只在祝福段存在（否则会一直压暗右半幅画面）
    if (blessingEl) {
      const on = t >= CUES.act.bless - 0.15;
      if (on !== BDAY._blessOn) { BDAY._blessOn = on; blessingEl.classList.toggle('on', on); }
    }
    stage.dataset.time = t.toFixed(2);
    let beat = 'awake';
    const a = CUES.act;
    if (t >= a.bless) beat = 'bless'; else if (t >= a.boom) beat = 'boom';
    else if (t >= a.dark) beat = 'dark'; else if (t >= a.blow) beat = 'blow';
    else if (t >= a.wish) beat = 'wish'; else if (t >= a.light) beat = 'light';
    else if (t >= a.walk) beat = 'walk';
    stage.dataset.beat = beat;
  };

  if (uiMarks) {
    Object.keys(CUES.act).forEach((k) => {
      if (k === 'awake') return;
      const i = document.createElement('i');
      i.style.left = (CUES.act[k] / CUES.dur * 100).toFixed(2) + '%';
      i.title = k;
      uiMarks.appendChild(i);
    });
  }
  const seekFromEvent = (e) => {
    const r = uiTrack.getBoundingClientRect();
    const p = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    BDAY.seek(p * CUES.dur);
  };
  if (uiTrack) {
    uiTrack.addEventListener('pointerdown', (e) => {
      seekFromEvent(e); uiTrack.setPointerCapture(e.pointerId);
      const move = (ev) => seekFromEvent(ev);
      const up = () => { uiTrack.removeEventListener('pointermove', move); uiTrack.removeEventListener('pointerup', up); };
      uiTrack.addEventListener('pointermove', move); uiTrack.addEventListener('pointerup', up);
    });
  }
  q('#ui-play').addEventListener('click', (e) => { e.stopPropagation(); BDAY.toggle(); });
  q('#ui-replay').addEventListener('click', (e) => { e.stopPropagation(); BDAY.seek(0); BDAY.play(); });
  q('#ui-skip').addEventListener('click', (e) => { e.stopPropagation(); BDAY.seek(CUES.act.boom - 0.4); BDAY.play(); });

  document.addEventListener('keydown', (e) => {
    if (e.key === ' ') { e.preventDefault(); BDAY.toggle(); }
    else if (e.key === 'r' || e.key === 'R') { BDAY.seek(0); BDAY.play(); }
    else if (e.key === 'ArrowRight') BDAY.seek(BDAY.time() + 1);
    else if (e.key === 'ArrowLeft') BDAY.seek(BDAY.time() - 1);
  });

  // UI 自动隐藏
  let uiTimer = 0;
  const showUi = () => {
    stage.classList.add('ui-live');
    clearTimeout(uiTimer);
    if (!STILL) uiTimer = setTimeout(() => stage.classList.remove('ui-live'), 2600);
  };
  stage.addEventListener('pointermove', showUi);
  stage.addEventListener('pointerdown', showUi);
  stage.addEventListener('click', (e) => {
    if (e.target.closest('.ui')) return;
    BDAY.toggle();
  });

  /* ── Canvas DPR ── */
  function fitCanvas() {
    /* 特效用 1× 背板（与成片一致）。降分辨率对帧率没有实测收益，只会让烟火变糊。 */
    const ratio = param.has('app') ? Math.max(.25, Math.min(1.25, innerWidth * (devicePixelRatio || 1) / 1600)) : Math.min(2, Math.max(1, window.devicePixelRatio || 1));
    [canvasBack, canvasFront].forEach((c) => {
      if (!c) return;
      const w = Math.round(1600 * ratio), h = Math.round(900 * ratio);
      if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
      const ctx = c.getContext('2d');
      if (ctx) ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    });
    BDAY.pixelRatio = ratio;
    if (window.VFX && window.VFX.resize) window.VFX.resize();
  }

  /* ── 启动 ── */
  function boot() {
    bootAssets();
    fitCanvas();

    if (NO_LIGHT) { window.Lighting = { init(){}, update(){}, ignite(){}, flash(){}, ambient(){} }; }
    if (NO_VFX) document.querySelectorAll('.fx').forEach((c) => { c.style.display = 'none'; });
    if (window.Lighting && window.Lighting.init) guard('lighting.init', () => window.Lighting.init(BDAY));
    if (window.VFX && window.VFX.init) guard('vfx.init', () => window.VFX.init(BDAY));

    // 相机关键帧
    CUES.cam.forEach((k) => rig.key(k.at, { x: k.x, y: k.y, z: k.z, dur: k.dur, ease: k.ease }));

    // 主时间线：motion.js 负责，缺失时用空时间线保证可调
    let master = null;
    if (window.Motion && window.Motion.build) master = guard('motion.build', () => window.Motion.build(BDAY), null);
    if (!master) master = gsap.timeline({ paused: true, defaults: { ease: 'none' } });
    master.pause();
    if (master.duration() < CUES.dur) master.to({ _pad: 0 }, { _pad: 1, duration: CUES.dur - master.duration() }, master.duration());
    BDAY.master = master;

    master.eventCallback('onUpdate', () => {
      const t = master.time();
      if (window.Lighting && window.Lighting.update) guard('lighting.update', () => window.Lighting.update(t));
      applyCamera(t);
      BDAY.paintUi(t);
    });
    master.eventCallback('onComplete', () => {
      startIdle();
    });

    if (CLEAN) stage.classList.add('clean');
    if (param.has('grain')) stage.classList.add('with-grain');
    if (REDUCED) {
      BDAY.seek(CUES.dur - 0.35);
      status(false);
    } else {
      BDAY.seek(0);
      // 直接打开就自动播；静帧/减少动效模式不自动播
      if (!STILL) setTimeout(() => { if (!BDAY.master.time()) BDAY.play(); }, 620);
    }
    showUi();

    // 静态截图/直链：?t=12.5
    if (param.has('t')) {
      const t = parseFloat(param.get('t')) || 0;
      BDAY.seek(t);
    }
    if (CLEAN) { stage.classList.remove('ui-live'); ui.style.display = 'none'; }

    BDAY.ready = true;
    document.documentElement.classList.add('bday-ready');
    window.dispatchEvent(new CustomEvent('bday-ready'));
  }

  /* 收尾后无限循环的低频呼吸（不是整段平移动画，只做微差） */
  function startIdle() {
    if (REDUCED) return;
    if (BDAY.idle) BDAY.idle.kill();
    const t0 = CUES.dur;
    const halo = { v: 0 };
    BDAY.idle = gsap.timeline({ repeat: -1, yoyo: true, defaults: { ease: 'sine.inOut' } })
      .to(halo, { v: 1, duration: 5.4, onUpdate: () => {
        applyCamera(t0 + halo.v * 1.6);
        if (window.Lighting && window.Lighting.update) guard('lighting.update', () => window.Lighting.update(t0 + halo.v * 1.2));
      } }, 0)
      .to({}, { duration: 0.001 });
  }

  window.addEventListener('resize', fitCanvas);

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
