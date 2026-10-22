/* ─────────────────────────────────────────────────────────────
   js/finish.js · 最终合成 / 调色（comp-polish）
   只做"整片级"的后处理叠加，不碰任何具体元素的光（那是 lighting.js 的活）。

   三层叠加（红线：新层 ≤3）：
     1. .fin-tone-lift   暗部抬起 + 青绿偏色（mix-blend-mode: lighten，等于给黑位设一个地板）
     2. .fin-tone-warm   高光偏暖（soft-light + 上下渐变：上强下弱，中间调位移 <3/255）
     3. .fin-halation    火焰/蜡烛/烟花的橙红溢光（局部径向，screen，无任何 blur）

   性能纪律：
     · 不注册任何逐帧动画；DOM 只在 init 时建一次。
     · update(t) 内部 0.5s 门闩 → 每秒最多 2 次属性写入，且每次只写 1–2 个 CSS 变量。
     · 层都是纯色/渐变，不触发重栅格化（含 will-change 的只有 .grain，那是 CSS 里的事）。
   ───────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  const APP = new URLSearchParams(location.search).has('app');
  const WARM_BASE = 0.62;      // .fin-tone-warm 基础不透明度（实测 ≈ 高光 R+6 G+2 B-4）
  const last = { t: -99, warm: -1, hx: -1, hy: -1, lift: -1 };
  let root = null, elLift = null, elWarm = null, elHal = null;
  let ready = false, reduced = false, bday = null;

  function build() {
    const stage = document.getElementById('stage');
    if (!stage) return false;
    root = stage;
    const mk = (cls) => {
      let el = document.querySelector('.' + cls);
      if (!el) {
        el = document.createElement('div');
        el.className = cls;
        el.setAttribute('aria-hidden', 'true');
        // 插在 .grade 之后：同 z-index 时按 DOM 顺序叠在 grade 之上、bloom(8) 之下
        // 插在 .grade **之前**：先 lift/warm/halation，再由 .grade 的暗角收边
        const grade = stage.querySelector('.grade');
        if (grade && grade.parentNode === stage) stage.insertBefore(el, grade);
        else stage.appendChild(el);
      }
      return el;
    };
    elLift = mk('fin-tone-lift');
    elWarm = mk('fin-tone-warm');
    elHal = mk('fin-halation');
    return true;
  }

  /* 黑场保护：31–33.2s 把暗部地板收回去，黑场 mean 才守得住 0.027 */
  function liftAt(t) {
    if (t < 30.6) return 1;
    if (t < 31.4) return 1 - (t - 30.6) / 0.8 * 0.75;
    if (t < 33.2) return 0.25;
    if (t < 34.2) return 0.25 + (t - 33.2) * 0.75;
    return 1;
  }

  /* 剧情色温曲线：许愿段最暖，黑场最冷，祝福回暖。纯 t 的函数，可反向。 */
  function warmAt(t) {
    const lerp = (a, b, u) => a + (b - a) * u;
    if (t < 14.5) return lerp(0.90, 1.00, Math.min(1, t / 14.5));      // 空景偏冷 → 端蛋糕回暖
    if (t < 21) return lerp(1.00, 1.10, (t - 14.5) / 6.5);             // 点灯：暖起来
    if (t < 28.5) return lerp(1.10, 1.14, Math.min(1, (t - 21) / 7.5));// 许愿/吹灭：最暖
    if (t < 33.4) return lerp(1.14, 0.78, (t - 28.5) / 4.9);           // 黑场：收冷
    if (t < 41.0) return lerp(0.78, 1.02, (t - 33.4) / 7.6);           // 烟花：回暖
    return lerp(1.02, 1.08, Math.min(1, (t - 41.0) / 7));              // 祝福：暖收尾
  }

  /* 光晕锚点：有烟花追烟花，没烟花贴蜡烛 */
  function haloAt(t) {
    const cue = (bday && bday.cue) || window.CUES;
    let hx = 50, hy = 58;                       // 蜡烛（蛋糕中心偏上）
    if (cue && cue.shells) {
      for (let i = 0; i < cue.shells.length; i++) {
        const s = cue.shells[i];
        const span = 1.9;                        // 爆点到余烬
        if (t >= s.at + 0.55 && t < s.at + 0.55 + span) {
          hx = (s.x / 1600) * 100;
          hy = (s.y / 900) * 100;
          break;
        }
      }
    }
    return { hx, hy };
  }

  function apply(warm, hx, hy, lift) {
    if (Math.abs(lift - last.lift) > 0.01) {
      last.lift = lift;
      elLift.style.opacity = lift.toFixed(2);
    }
    if (Math.abs(warm - last.warm) > 0.004) {
      last.warm = warm;
      elWarm.style.opacity = (WARM_BASE * warm).toFixed(3);
    }
    if (Math.abs(hx - last.hx) > 0.4 || Math.abs(hy - last.hy) > 0.4) {
      last.hx = hx; last.hy = hy;
      const s = elHal.style;
      s.setProperty('--hx2', hx.toFixed(2) + '%');
      s.setProperty('--hy2', hy.toFixed(2) + '%');
    }
  }

  const Finish = {
    enabled: true,

    init(b) {
      bday = b || window.BDAY || null;
      reduced = !!(bday && bday.reduced) ||
        window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      if (!build()) return;
      ready = true;
      // reduced：整层关掉（颗粒在 CSS 里也有 @media 兜底）
      if (reduced) {
        Finish.enabled = false;
        [elLift, elWarm, elHal].forEach((e) => { e.style.display = 'none'; });
        return;
      }
      const t = (bday && typeof bday.time === 'function') ? bday.time() : 0;
      apply(warmAt(t), 50, 58, liftAt(t));
      Finish.update(t);
    },

    update(t) {
      if (!ready || !Finish.enabled) return;
      const tt = Number(t) || 0;
      // 门闩：每秒最多 2 次属性写入
      if (tt > last.t && tt - last.t < 0.49) return;
      last.t = tt;
      const w = warmAt(tt);
      const h = haloAt(tt);
      apply(w, h.hx, h.hy, liftAt(tt));
    },

    /* 给 QA 用的开关：Finish.enabled=false 时整层不生效（A/B 量色偏用） */
    setEnabled(on) {
      Finish.enabled = !!on;
      if (!ready) return;
      const d = on && !reduced ? '' : 'none';
      [elLift, elWarm, elHal].forEach((e) => { e.style.display = d; });
      last.t = -99; last.warm = -1; last.hx = -1; last.hy = -1; last.lift = -1;
    }
  };

  window.Finish = Finish;

  /* ── 接线：main.js 没有调用 Finish，这里自己挂 ──
     seek / 播放都不注册 RAF，只用事件 + 2Hz 定时器读 bday.time() */
  function boot() {
    const b = window.BDAY;
    if (!b) { window.addEventListener('bday-ready', boot, { once: true }); return; }
    // v3.6.4：应用内不建这三层全屏 mix-blend-mode 合成层 —— 它们各占一整屏图块，
    // 与 tile memory 超限直接相关。基础 .grade 暗角/雾仍在，浏览器预览保留完整调色。
    if (APP) { Finish.enabled = false; return; }
    // 包一层 seek：每帧截图/拖拽后同步一次（内部 0.5s 门闩，不会每帧写）
    const origSeek = b.seek;
    if (typeof origSeek === 'function' && !b.__finishWrapped) {
      b.__finishWrapped = true;
      b.seek = function (t) {
        const r = origSeek.apply(this, arguments);
        try { Finish.update(this.time ? this.time() : t); } catch (e) { /* noop */ }
        return r;
      };
    }
    Finish.init(b);
    // 播放态：2Hz 轮询（setInterval，不是逐帧）
    setInterval(() => {
      if (!Finish.enabled) return;
      try { if (b && typeof b.time === 'function' && b.master && b.master.isActive()) Finish.update(b.time()); } catch (e) { /* noop */ }
    }, 500);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
  window.addEventListener('bday-ready', () => { if (!ready) boot(); });
})();
