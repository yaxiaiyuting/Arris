/* ─────────────────────────────────────────────────────────────
   js/lighting.js · 灯光与合成（Lighting / Compositing TD）
   ------------------------------------------------------------------
   契约：docs/CONTRACT.md §3.3 / §5。接口
     init(bday) / update(t) / ignite(n) / flash(o) / ambient(v)
     + state(t)  ← QA 读取用的纯查询接口（额外暴露，不影响契约）

   设计原则（非常重要）：
     update(t) 是「纯时间函数」。内部先 evaluate(t) 算出全部光照参数，
     再 apply() 写 DOM。evaluate 里没有任何累加器、没有上一帧缓存、
     没有 performance.now()。同一条时间轴上 seek 到同一 t，
     任意次数、任意方向，结果完全一致（QA 可逐帧复现）。

   表现手段：
     · 所有光 = SVG 径向/线性渐变 + 单层 mix-blend-mode:screen
       （整个叠加 SVG 只做一次 screen 混合，避免多重隔离层）
     · 只有「小面积」的自发光点用 feGaussianBlur（余烬），
       大面积光池/闪光/Velvet 全部靠渐变落点，不用 filter:blur()。
     · 叠加层挂在 #stage 内，z-index 7（在 .grade 之后、.bloom/.grain 之前）。
   ───────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  const SVGNS = 'http://www.w3.org/2000/svg';
  const C = window.CUES || { dur: 48, act: {}, ignite: [], extinguish: [], shells: [] };
  const ACT = C.act || {};

  /* ══════════════════════════════════════════════════════════════
     0. 数值常量 —— docs/LIGHTING.md 的光比表就是从这里来的
     ══════════════════════════════════════════════════════════════ */

  const PAL = {
    warmLow: '#FFB066',    // 一支蜡烛 / 低位暖
    warmMid: '#FFE8C7',
    warmHigh: '#FFF3C4',   // 三支蜡烛 / 火核
    cool: '#7CAF96',       // 月绿环境
    coolHi: '#BFE3D0',
    ember: '#FF7A3C',
    bullet: '#FFC46B'
  };

  // 三支蜡烛的闪烁参数。三个 f1 / f2 / f3 互不可公约（接近无理比），
  // 相位差 0 / 2.1 / 4.3 rad，保证「永远不同步呼吸」。
  const FLAME = [
    { amp: 0.090, f1: 0.61, f2: 1.37, f3: 2.70, p1: 0.00, p2: 1.15, seed: 0.17 },
    { amp: 0.110, f1: 0.83, f2: 1.71, f3: 3.10, p1: 2.10, p2: 4.05, seed: 0.53 },
    { amp: 0.075, f1: 1.13, f2: 2.09, f3: 2.30, p1: 4.30, p2: 0.62, seed: 0.91 }
  ];

  // 火焰相对蛋糕层的兜底坐标（资产缺失时用；有 #wick-i 就按真实 bbox）
  const FLAME_FALLBACK = [
    { x: 772, y: 536 }, { x: 800, y: 536 }, { x: 828, y: 536 }
  ];

  // 点亮数 → 主光整体增益。不是线性 1/3：第一支要有明显跳变。
  const STEP_GAIN = [0, 1.30, 1.13, 1.00];

  const KEY = {
    poolAlpha: 0.44,    // 地面暖光池 screen alpha（× keyNorm^gamma）
    poolGamma: 0.58,    // <1：第一支蜡烛也有可见的池，避免「点了像没点」
    coreAlpha: 0.80,    // 火核 alpha
    poolR: [300, 420, 500, 570], // 0..3 支点亮时的池半径（世界单位）
    poolRatio: 0.40,    // 椭圆扁率 ry = rx * 0.40
    rimDelay: 0.075,    // 边缘光相对火焰的延迟（契约 60–90ms）
    sliver: 0.50        // 资产带 #tux-rimlight 时，自绘掠光补一半（加强边缘）
  };

  const FILL = {
    // 第二轮：整体抬到 0.15–0.24（审片：8–14 档 33–42% 太厚，14–30 主光区只剩 15–18%）
    base: 0.225,        // 开幕
    lit: 0.170,         // 三支点亮后「相对变暗」（仍高于旧值）
    after: 0.205,       // 吹灭后回升
    boom: 0.180,
    bless: 0.158,
    midK: 0.90,         // 中景/桌面顶光的渲染 alpha 系数
    alphaK: 0.21,       // intensity → 顶部光幕渲染 alpha 的换算（压低远景，让天空落回 L*≤8）
    rimK: 0.62,
    mistK: 0.16,
    elevation: 30       // 光源仰角 30°
  };

  const EVENT = {
    // 与 js/vfx.js 的 RISE=0.70 / riseOf(i)=0.70+0.04*i 严格对齐：
    // 爆点 = CUES.shells[i].at + 0.70 + 0.04*i，光必须和真实爆点同时到。
    rise: 0.70,
    riseStep: 0.04,
    dur: 1.15,          // 峰值平台 0.20s + power1.6 衰减 0.95s
    hold: 0.20,
    pow: 1.6,
    rim: 0.50, rimMax: 0.60,   // 角色彩色边缘光（lead 指定 0.45–0.60 的元素不透明度）
    delay: 0.06,               // 相对爆点延迟 60ms（契约 40–90ms）
    body: 0.30,         // 角色整体被染色（小面积 screen）
    ground: 0.30,       // 桌面彩色反射（screen）
    mist: 0.04,         // 背景薄雾
    deltaBudget: 0.08   // 全屏亮度增量 ≤8%
  };

  const DARK = {
    // 跟随 CUES.act.dark / boom：31.35 落黑，33.45 起烟花；全黑最多 0.45s 就回气
    from: (ACT.dark != null ? ACT.dark : 31) - 0.40,
    fully: (ACT.dark != null ? ACT.dark : 31) + 0.60,
    hold: (ACT.dark != null ? ACT.dark : 31) + 1.05,
    out: (ACT.boom != null ? ACT.boom : 33) + 0.50,
    peak: 0.970,        // 压暗峰值（comp 抬了影调地板，黑幕要再压 1.4/255）
    presence: 0.022,    // 黑幕之上的一层「他们还在」的微光
    emberAlpha: 0.55    // 余烬亮度基准（再乘 0.35–0.55 的噪波）
  };

  const BLESS = {
    at: ACT.bless != null ? ACT.bless : 41.5,
    period: 7.30,       // 呼吸周期：与烟花（≈1.2s 节奏 + 8s 段落）错开
    halo: 0.095, haloVar: 0.045,
    wash: 0.030,        // 整体偏暖 +200K 的低幅暖罩
    warmShiftK: 200
  };

  /* ══════════════════════════════════════════════════════════════
     1. 数学 / 噪波（全部纯函数，无状态）
     ══════════════════════════════════════════════════════════════ */

  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
  const lerp = (a, b, k) => a + (b - a) * k;
  function smoothstep(a, b, x) {
    if (a === b) return x < a ? 0 : 1;
    const u = clamp((x - a) / (b - a), 0, 1);
    return u * u * (3 - 2 * u);
  }
  function hash1(n) {
    const s = Math.sin(n * 127.1 + 311.7) * 43758.5453123;
    return s - Math.floor(s);
  }
  // 平滑一维 value noise（确定性：同 n 必同值）
  function noise1(x) {
    const i = Math.floor(x), f = x - i;
    const u = f * f * (3 - 2 * f);
    return hash1(i) * (1 - u) + hash1(i + 1) * u;
  }
  function hexToRgb(h) {
    h = String(h || '#ffffff').replace('#', '');
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    const n = parseInt(h, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function mixHex(a, b, k) {
    const A = hexToRgb(a), B = hexToRgb(b);
    k = clamp(k, 0, 1);
    const r = Math.round(line(A[0], B[0])), g = Math.round(line(A[1], B[1])), bl = Math.round(line(A[2], B[2]));
    function line(x, y) { return x + (y - x) * k; }
    return '#' + ((1 << 24) + (r << 16) + (g << 8) + bl).toString(16).slice(1);
  }

  /* 单支蜡烛闪烁：两个不可公约正弦 + 一层平滑噪波，权重和为 1
     → 峰值幅度恰好 = FLAME[i].amp（0.075 / 0.09 / 0.11，落在 6–11% 内） */
  function flicker(i, t) {
    const f = FLAME[i];
    const s1 = Math.sin(2 * Math.PI * f.f1 * t + f.p1);
    const s2 = Math.sin(2 * Math.PI * f.f2 * t + f.p2);
    const nz = noise1(t * f.f3 + f.seed) * 2 - 1;
    const amp = f.amp * (reducedMode ? 0.35 : 1);
    return 1 + amp * (0.52 * s1 + 0.30 * s2 + 0.18 * nz);
  }

  /* ══════════════════════════════════════════════════════════════
     2. 时间轴推导（全部从 CUES 来，不硬编码第二套时间）
     ══════════════════════════════════════════════════════════════ */

  const IGNITE = (C.ignite || []).slice(0, 3);
  const EXT = (C.extinguish || []).slice(0, 3);
  const SHELLS = C.shells || [];
  const DUR = C.dur || 48;

  function litCountAt(t) {
    let n = 0;
    for (let i = 0; i < 3; i++) {
      const tin = IGNITE[i], tout = EXT[i];
      if (tin != null && t >= tin && (tout == null || t < tout)) n++;
    }
    return n;
  }

  /* 与 main.js 的 E.arrive 同式：快出慢入，带一点过冲的收尾 */
  function arriveEase(x) {
    x = clamp(x, 0, 1);
    return 1 - Math.pow(1 - x, 3.4);
  }

  /* 单支蜡烛的「可见度」——0..1，只由 CUES 决定。
     16.4 之前恒为 0：VFX 挂了也不会漏火（可见性归 Lighting）。
     点燃 0.28s 起亮；吹灭 0.16s 收掉（火先没，光池后收）。 */
  function flameVis(i, t) {
    const tin = IGNITE[i], tout = EXT[i];
    if (tin == null || t < tin) return 0;
    const inW = arriveEase((t - tin) / 0.28);
    if (tout == null || t < tout) return inW;
    const outW = 1 - arriveEase((t - tout) / 0.16);
    return clamp(inW * outW, 0, 1);
  }

  /* 光池的连续当量 0..1：点燃 +1/3（0.5s 涨），吹灭后 0.1s 开始收（0.75s 缩）
     —— 收缩而不是淡出，观众能感到「光源在消失」 */
  function poolLevelAt(t) {
    let s = 0;
    for (let i = 0; i < 3; i++) {
      const tin = IGNITE[i], tout = EXT[i];
      if (tin == null || t < tin) continue;
      let v = arriveEase((t - tin) / 0.50);
      if (tout != null && t >= tout) {
        v = 1 - arriveEase((t - tout - 0.10) / 0.75);
      }
      s += clamp(v, 0, 1);
    }
    return clamp(s / 3, 0, 1);
  }

  /* 单支蜡烛的「光权重」：点燃有爆亮过冲 → 稳态；吹灭后有极短余辉尾 */
  function flameWeight(i, t) {
    const tin = IGNITE[i], tout = EXT[i];
    if (tin == null) return 0;
    if (tout != null && t >= tout) {
      return 0.30 * Math.exp(-(t - tout) / 0.42);
    }
    if (t < tin) return 0;
    const age = t - tin;
    const ramp = smoothstep(0, 0.22, age);
    const spike = 0.90 * Math.exp(-age / 0.11);
    return ramp * (1 + spike);
  }

  /* 主光强度：三支火焰的和，按点亮数做 step 增益 */
  function keyAt(t) {
    const lit = litCountAt(t);
    let sum = 0;
    for (let i = 0; i < 3; i++) sum += flameVis(i, t) * flameWeight(i, t) * flicker(i, t);
    const norm = (sum / 3) * STEP_GAIN[lit];
    return { sum: sum, norm: norm, lit: lit };
  }

  /* 事件光：CUES.shells → 爆点闪光包络。
     爆点时刻与 vfx.js 的 riseOf(i) 一致，衰减用 exp(-4*age) 的 0.9s 窗口。 */
  function eventCurve(age, dur, hold, pow) {
    const D = dur || EVENT.dur;
    if (age < 0 || age > D) return 0;
    const H = hold == null ? EVENT.hold : hold;
    if (age <= H) return 1;                       // 平台：爆点后 0.2s 是峰值区
    const u = (age - H) / Math.max(0.01, D - H);
    const p = pow == null ? EVENT.pow : pow;
    // power 曲线：0.8s 窗口内从 1 掉到 0，末端已经自然到 0，不需要额外收尾
    return Math.max(0, Math.pow(1 - u, p));
  }

  const KIND_AMP = {
    peony: 0.86, willow: 0.82, ring: 0.84, chrys: 0.90,
    crossette: 0.88, double: 0.92, finale: 1.00
  };

  /* 爆点相对 CUES.shells[i].at 的延迟：vfx.js 里 RISE=0.70、每发 +0.04。 */
  function riseOf(i) {
    const v = window.VFX && window.VFX.lightRise;
    return Number.isFinite(v) ? Math.max(0, v) : (EVENT.rise + EVENT.riseStep * i);
  }

  function shellsAt(t) {
    const out = [];
    for (let i = 0; i < SHELLS.length; i++) {
      const s = SHELLS[i];
      const burst = s.at + riseOf(i);
      const age = t - burst - EVENT.delay;
      const finale = s.kind === 'finale';
      const e = finale ? eventCurve(age, 0.80, 0.10, 1.6) : eventCurve(age);
      if (e <= 0.001) continue;
      const k = clamp((s.r || 160) / 232, 0, 1);        // finale 最大
      let amp = e * (KIND_AMP[s.kind] || 0.86) * (0.86 + 0.14 * k);
      if (finale) amp *= 0.62;                          // 终章环已经够亮，别把画面推爆
      out.push({
        i: i, kind: s.kind, x: s.x, y: s.y, r: s.r || 160,
        c: s.c || ['#FFE9B0', '#FFC46B'],
        burstAt: burst, age: age, dur: EVENT.dur,
        amp: amp, k: k, intensity: amp
      });
    }
    // 多发展开时重叠：按总量缩放，保证全屏亮度增量 ≤8%
    let total = 0;
    for (const e of out) total += e.amp;
    const cap = 1.30;
    const scale = total > cap ? cap / total : 1;
    for (const e of out) e.amp *= scale;
    return { list: out, total: total * scale };
  }

  /* ══════════════════════════════════════════════════════════════
     3. evaluate(t) —— 只吃 t，吐出全部光照参数（纯函数）
     ══════════════════════════════════════════════════════════════ */

  function fillIntensity(t) {
    let v = FILL.base;
    v = lerp(v, 0.212, smoothstep(0.0, 4.2, t));
    v = lerp(v, 0.198, smoothstep(7.0, 12.0, t));
    v = lerp(v, 0.188, smoothstep(14.5, 16.0, t));
    v = lerp(v, FILL.lit, smoothstep((IGNITE[0] != null ? IGNITE[0] : 16.4) - 0.15, (IGNITE[2] != null ? IGNITE[2] : 19.4) + 0.25, t)); // 点亮 → 相对变暗
    v = lerp(v, FILL.after, smoothstep((EXT[2] != null ? EXT[2] : 28.2) - 0.2, 30.4, t));// 吹灭 → 相对回升
    v = lerp(v, FILL.boom, smoothstep(ACT.boom != null ? ACT.boom : 33, (ACT.boom != null ? ACT.boom : 33) + 2.0, t));
    v = lerp(v, FILL.bless, smoothstep(BLESS.at, BLESS.at + 2.0, t));
    // 月光穿过薄雾的慢漂移（周期 11.3s，与任何其他节奏都不成整数比）
    const drift = 1 + 0.035 * Math.sin(2 * Math.PI * t / 11.3 + 0.9);
    return v * drift;
  }

  function blackoutAt(t) {
    // 叠层压暗：31 起 smoothstep（不是硬切）。全黑只维持 ~0.5s，
    // 之后立刻「回气」——观众要看到人物还在，而不是渲染失败。
    let veil = 0;
    if (t >= DARK.from) {
      veil = DARK.peak * smoothstep(DARK.from, DARK.fully, t);
      if (t >= DARK.hold) veil *= 1 - smoothstep(DARK.hold, DARK.out, t);
    }
    const ember = smoothstep(31.10, 31.95, t) * (1 - smoothstep(32.55, 33.35, t));
    const slash = smoothstep(31.35, 32.00, t) * (1 - smoothstep(32.85, 33.55, t));
    const sparkT = (t - 32.58) / 1.15;          // 32.58 起一根火星往上飘
    return {
      veil: veil,
      ember: clamp(ember, 0, 1),
      slash: clamp(slash, 0, 1),
      spark: sparkT >= 0 && sparkT <= 1 ? sparkT : -1
    };
  }

  function evaluate(tRaw) {
    const t = clamp(tRaw, 0, DUR);
    const key = keyAt(t);
    const keyDelay = keyAt(t - KEY.rimDelay);   // 边缘光延迟 75ms
    const lit = key.lit;
    const keyNorm = clamp(key.norm, 0, 1.6);
    const event = shellsAt(t);
    const dark = blackoutAt(t);
    const blessW = smoothstep(BLESS.at, BLESS.at + 1.25, tRaw);
    const breath = 0.5 + 0.5 * Math.sin((2 * Math.PI * tRaw) / BLESS.period - 1.05);
    const poolLevel = poolLevelAt(t);
    // 祝福段没有蜡烛，但有人造光晕；用它给人物一个「被照亮」的量，末帧不能是暗肚子
    const warmLevel = Math.max(poolLevel, blessW * 0.62);

    // 单支火焰：可见度（资产节点 opacity）+ 光权重 + 闪烁
    const flames = [];
    for (let i = 0; i < 3; i++) {
      const vis = flameVis(i, t);
      const w = flameWeight(i, t) * vis;
      flames.push({
        i: i, vis: vis, weight: w, flicker: flicker(i, t),
        on: IGNITE[i] != null && t >= IGNITE[i] && (EXT[i] == null || t < EXT[i]),
        glow: clamp(vis * (0.45 + 0.55 * (flicker(i, t) - 1)), 0, 0.58)
      });
    }

    // ── 主光 ─────────────────────────────────────────────
    const warmColor = mixHex(PAL.warmLow, PAL.warmHigh, clamp(0.18 + 0.82 * keyNorm, 0, 1));
    // 光池半径：120 → 180 → 240 → 300（世界单位），随 poolLevel 连续变化
    const cakeRx = (120 + 180 * poolLevel) * (1 + 0.045 * Math.sin(2 * Math.PI * t / 3.7 + 0.4));
    const poolAlpha = (0.06 + 0.84 * poolLevel) * (0.94 + 0.10 * Math.sin(2 * Math.PI * t / 2.9 + 1.3));
    const keyOut = {
      intensity: keyNorm,
      color: warmColor,
      poolLevel: poolLevel,
      cakePoolRx: cakeRx,                    // 资产 #cake-pool 的目标半径
      cakePoolK: cakeRx / 330,               // 330 = 资产自带 rx，缩放系数
      cakePoolAlpha: clamp(poolAlpha, 0, 0.92),
      radius: cakeRx * 1.85,                 // 自绘外溢光池半径
      poolAlpha: Math.pow(clamp(warmLevel, 0, 1), KEY.poolGamma) * KEY.poolAlpha,
      coreAlpha: clamp(key.sum / 3, 0, 1.3) * KEY.coreAlpha,
      stepGain: STEP_GAIN[lit]
    };

    // 角色的被照程度（P0-2）：没点灯时肚子不能比火焰亮
    const shade = {
      belly: clamp(0.56 + 0.22 * warmLevel + 0.02 * (flames[1].flicker - 1) * 10, 0.3, 1),
      icing: clamp(0.38 + 0.22 * warmLevel, 0.3, 1),
      body: clamp(0.46 + 0.24 * warmLevel, 0.3, 1),
      plate: clamp(0.50 + 0.24 * warmLevel, 0.3, 1),
      candle: clamp(0.72 + 0.10 * warmLevel + 0.02 * (flames[0].flicker - 1) * 10, 0.5, 0.84)
    };
    const bellyLight = {
      cool: clamp(0.10 * (1 - warmLevel) + 0.02, 0, 0.14),  // 月光落在肚子上的冷面
      warm: clamp(0.16 * warmLevel + 0.05 * blessW, 0, 0.2), // 烛光落在一侧的暖面
      arc: clamp(0.62 * warmLevel + 0.10 * blessW, 0, 0.72),  // 弧形受光带（主形状）
      angle: -20                                            // 暖侧来自蜡烛方向（约 -20°）
    };

    // ── 辅光（冷月绿，上方 30°）───────────────────────────
    const fillInt = fillIntensity(t) * ambGain;
    const fill = {
      intensity: fillInt,
      alphaTop: fillInt * FILL.alphaK,
      alphaMist: fillInt * FILL.mistK,
      color: PAL.cool,
      elevation: FILL.elevation,
      azimuth: -118,
      relative: clamp(1 - lit / 3.4, 0, 1)    // 描边「相对变暗」的读数
    };

    // ── 角色边缘光 ───────────────────────────────────────
    const rimWarm = clamp(keyDelay.norm, 0, 1.25);
    const rimCool = clamp(fillInt / 0.18, 0, 1.2);
    const rim = {
      delay: KEY.rimDelay,
      warm: { intensity: rimWarm, alpha: clamp(rimWarm, 0, 1) * 0.86, color: mixHex(PAL.warmMid, PAL.warmHigh, clamp(rimWarm, 0, 1)),
        from: 'cake' },
      cool: { intensity: rimCool, alpha: clamp(rimCool, 0, 1) * 0.42, color: PAL.coolHi, elevation: FILL.elevation }
    };

    // ── 事件光 ───────────────────────────────────────────
    const list = event.list.slice().sort((a, b) => b.amp - a.amp);
    const slots = [];
    for (let n = 0; n < 2; n++) {
      const e = list[n];
      if (!e) { slots.push(null); continue; }
      const a = e.amp;
      const inner = mixHex(e.c[0], e.c[1] || e.c[0], 0.62);
      slots.push({
        kind: e.kind,
        x: e.x, y: e.y,
        color: e.c[0], colorInner: inner,
        amp: a,
        mist: EVENT.mist * a,                                    // 背景薄雾 4%
        ground: EVENT.ground * a,                                // 桌面彩色反射（screen 0.30 峰值）
        // 元素不透明度走绝对区间：平台期 0.45–0.60，衰减后仍停在 0.45 附近再慢慢退
        body: EVENT.body * (0.55 + 0.45 * a),                    // 角色整体染色 0.22–0.30
        rim: (EVENT.rim + (EVENT.rimMax - EVENT.rim) * e.k) * (0.55 + 0.45 * a),
        r: e.r || 160,
        age: e.age, dur: e.dur
      });
    }
    const evTotal = event.total;
    const ev = {
      total: evTotal,
      scale: 1,
      slots: slots,
      // 全屏均值的增量估算（只做上限声明，真实值以 convert %[fx:mean] 实测为准）
      screenDelta: Math.min(EVENT.deltaBudget, evTotal * (EVENT.mist * 0.42 + EVENT.ground * 0.045 + 0.012))
    };

    // ── 烟花段「持续氛围光」──────────────────────────────
    // 两发之间不能死黑：火药烟滞留在空气里，仍把天光/冷紫边缘留在角色身上。
    // 这与事件光分开，是慢变量（不是闪），所以两个爆点之间画面仍然「活着」。
    const boomW = smoothstep(33.10, 34.30, t) * (1 - smoothstep(41.00, 42.20, t));
    const boom = {
      weight: boomW,
      mist: boomW * 0.034,
      rim: boomW * 0.13,
      color: '#9DB4FF'
    };

    // ── 体积光锥：只在蜡烛燃烧期间（16.45–28.28）───────
    const coneIn = IGNITE[0] != null ? IGNITE[0] : 16.45;
    const coneOut = EXT[2] != null ? EXT[2] : 28.28;
    const coneW = smoothstep(coneIn - 0.1, coneIn + 0.5, t) *
      (1 - smoothstep(coneOut - 0.3, coneOut + 0.5, t)) * clamp(poolLevelAt(t) * 1.2, 0, 1);

    // ── 月核高光：空场/夜景的「事件点」────────────────────
    const moonGlint = clamp(1.0 * (1 - smoothstep(0.02, 0.28, keyNorm)) * (1 - dark.veil / 0.6), 0, 1);

    // ── 蛋糕顶镜面点：只在有烛光时存在（≤800px²）────────
    const specW = clamp(poolLevelAt(t), 0, 1) * 0.95 * (1 - dark.veil * 0.9);

    // ── 黑场 ─────────────────────────────────────────────
    const darkness = {
      veil: clamp(dark.veil, 0, 0.97),
      ember: dark.ember,
      emberAlpha: dark.ember,
      slash: dark.slash,
      slashAlpha: dark.slash * 0.105,     // 左侧极淡的月光斜切（有效 4–5%）
      spark: dark.spark,
      phase: 'dark'
    };

    // ── 祝福段 ───────────────────────────────────────────
    const halo = blessW * (BLESS.halo + BLESS.haloVar * breath);
    const bless = {
      weight: blessW,
      breath: breath,
      period: BLESS.period,
      halo: halo,
      radius: 620 + 90 * breath,
      wash: blessW * (BLESS.wash * (0.75 + 0.5 * breath)),
      rimWarm: blessW * (0.30 + 0.10 * breath),  // 祝福光从上方偏后打，给角色一层暖边
      pool: blessW * (0.10 + 0.03 * breath),     // 桌面承接一点点暖底
      warmShiftK: BLESS.warmShiftK,
      color: PAL.bullet
    };

    let beat = 'awake';
    if (t >= (ACT.bless != null ? ACT.bless : 41.5)) beat = 'bless';
    else if (t >= (ACT.boom || 33)) beat = 'boom';
    else if (t >= (ACT.dark || 31)) beat = 'dark';
    else if (t >= (ACT.blow || 27)) beat = 'blow';
    else if (t >= (ACT.wish || 21)) beat = 'wish';
    else if (t >= (ACT.light || 15.6)) beat = 'light';
    else if (t >= (ACT.walk || 7)) beat = 'walk';

    return {
      schema: 'lighting/1',
      t: tRaw, tc: t, beat: beat,
      lit: lit,
      flames: flames,
      key: keyOut,
      shade: shade,
      belly: bellyLight,
      cone: { weight: coneW },
      moonGlint: moonGlint,
      spec: { weight: specW },
      fill: fill,
      rim: rim,
      event: ev,
      boom: boom,
      dark: darkness,
      bless: bless,
      ambientGain: ambGain,
      reduced: !!reducedMode
    };
  }

  /* ══════════════════════════════════════════════════════════════
     4. 世界坐标投影（跟随相机 / 图层变换）
     ══════════════════════════════════════════════════════════════ */

  let bdayRef = null, svgRef = null, rootSvg = null;
  let A = {};                 // anchors：世界锚点（root 用户单位）
  let nodes = null;           // DOM 引用
  let ready = false;
  let ambGain = 1;
  let reducedMode = false;

  function svgPoint(x, y) {
    // 用一个共享的 SVGPoint 做矩阵变换
    if (!rootSvg || !rootSvg.createSVGPoint) return { x: x, y: y };
    const p = rootSvg.createSVGPoint();
    p.x = x; p.y = y;
    return p;
  }

  function project(layerEl, x, y) {
    if (!layerEl || !layerEl.getCTM) return { x: x, y: y, s: 1 };
    let m = null;
    try { m = layerEl.getCTM(); } catch (e) { m = null; }
    if (!m) return { x: x, y: y, s: 1 };
    const p = svgPoint(x, y).matrixTransform ? svgPoint(x, y).matrixTransform(m) : { x: x, y: y };
    const s = Math.sqrt(Math.abs(m.a * m.d - m.b * m.c)) || 1;
    return { x: p.x, y: p.y, s: s };
  }

  function layer(name) {
    return (bdayRef && bdayRef.layers && bdayRef.layers[name] && bdayRef.layers[name].el) || null;
  }

  /* getBBox 会强制布局，绝不能每帧调。所有本地点坐标只在 init/资产变更时缓存一次。 */
  const BB = {};
  function cacheBoxes() {
    const ids = ['tux', 'tux-body', 'tux-head', 'tux-belly',
      'wick-1', 'wick-2', 'wick-3', 'flame-1', 'flame-2', 'flame-3',
      'flame-unit-1', 'flame-unit-2', 'flame-unit-3'];
    for (let i = 0; i < ids.length; i++) {
      const el = document.getElementById(ids[i]);
      if (!el || !el.getBBox) continue;
      try { BB[ids[i]] = el.getBBox(); } catch (e) { /* 忽略 */ }
    }
  }
  function bboxOf(sel) {
    const id = sel.charAt(0) === '#' ? sel.slice(1) : sel;
    return BB[id] || null;
  }

  function anchorTux() {
    const L = layer('tux');
    // 用 #tux-body 定轮廓宽度：整个 #tux 的 bbox 会被 #tux-rimlight 的柔光撑大，
    // 拿来放边缘光会变成画在空气里的一道竖光柱。
    const bodyBB = bboxOf('#tux-body');
    const fullBB = bboxOf('#tux');
    const bb = bodyBB || fullBB;
    const cx0 = bb ? bb.x + bb.width / 2 : 600;
    const topY = fullBB ? fullBB.y : (bb ? bb.y - 140 : 330);
    const botY = fullBB ? fullBB.y + fullBB.height : (bb ? bb.y + bb.height : 680);
    const w0 = bb ? bb.width : 172;
    const h0 = botY - topY;
    const cy0 = (topY + botY) / 2;
    const c = project(L, cx0, cy0);
    const E2 = project(L, cx0 + w0, cy0);
    const B2 = project(L, cx0, botY);
    const w = Math.abs(E2.x - c.x) || w0;
    const bY = Math.abs(B2.y - c.y) || h0 / 2;
    // 躯干轮廓（用腹部定轴线）：#tux-body 的 bbox 含侧鳍/柔光，直接当轮廓会飘到角色外
    let core = { cx: c.x, cy: c.y, halfW: w / 2 };
    const bellyBB = bboxOf('#tux-belly');
    if (bellyBB && bellyBB.width > 4) {
      const bcx = bellyBB.x + bellyBB.width / 2;
      const bcy = bellyBB.y + bellyBB.height / 2;
      const bc = project(L, bcx, bcy);
      const be = project(L, bcx + bellyBB.width / 2, bcy);
      const hw = Math.abs(be.x - bc.x) * 1.22;   // 身体比肚子宽约 22%
      const bH = Math.abs(project(L, bcx, bcy + bellyBB.height / 2).y - bc.y);
      core = { cx: bc.x, cy: bc.y, halfW: hw, halfH: bH * 1.1,
        bellyRx: Math.abs(be.x - bc.x), bellyRy: bH };
    }
    return {
      x: c.x, y: c.y,
      w: w, h: bY * 2,
      left: c.x - w / 2, right: c.x + w / 2,
      top: c.y - bY, bottom: c.y + bY,
      core: core,
      s: c.s
    };
  }

  function anchorCake() {
    const L = layer('cake');
    const c = project(L, 800, 660);
    const e = project(layer('ground'), 800, 524);   // 桌沿（远边）的亮线位置
    return { x: c.x, y: c.y, s: c.s, edgeY: e.y, edgeS: e.s };
  }

  function anchorFlames() {
    const L = layer('cake');
    const out = [];
    for (let i = 0; i < 3; i++) {
      let x = FLAME_FALLBACK[i].x, y = FLAME_FALLBACK[i].y;
      const wick = bboxOf('#wick-' + (i + 1)) || bboxOf('#flame-' + (i + 1)) || bboxOf('#flame-unit-' + (i + 1));
      if (wick) { x = wick.x + wick.width / 2; y = wick.y + wick.height * 0.15; }
      const p = project(L, x, y);
      out.push({ x: p.x, y: p.y, s: p.s, wx: x, wy: y });
    }
    return out;
  }

  let lastCamKey = '';
  function refreshWorld(force) {
    // 相机/图层没动就不用重算（截图与暂停时能省掉全部矩阵运算）
    const lc = layer('cake'), lt = layer('tux');
    const key = (lc ? lc.getAttribute('transform') : '') + '|' + (lt ? lt.getAttribute('transform') : '');
    if (!force && key === lastCamKey) return;
    lastCamKey = key;
    A = { tux: anchorTux(), cake: anchorCake(), flames: anchorFlames() };
  }

  /* ══════════════════════════════════════════════════════════════
     5. DOM：叠加层构建
     ══════════════════════════════════════════════════════════════ */

  function el(tag, attrs, parent) {
    const n = document.createElementNS(SVGNS, tag);
    if (attrs) for (const k in attrs) if (attrs[k] != null) n.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(n);
    return n;
  }

  function radialDefs(defs, id, stops, extra) {
    const g = el('radialGradient', Object.assign({
      id: id, gradientUnits: 'objectBoundingBox', cx: '0.5', cy: '0.5', r: '0.5', fx: '0.5', fy: '0.5'
    }, extra || {}), defs);
    const refs = [];
    stops.forEach((s) => {
      refs.push(el('stop', { offset: s[0], 'stop-color': s[1], 'stop-opacity': s[2] == null ? 1 : s[2] }, g));
    });
    return { node: g, stops: refs };
  }

  function glow(parent, id, fill, extra) {
    return el('ellipse', Object.assign({ id: id, cx: 0, cy: 0, rx: 1, ry: 1, fill: fill,
      transform: 'translate(-9999 -9999) scale(0.001 0.001)' }, extra || {}), parent);
  }

  /* 一组「事件闪光」三件套：薄雾 / 桌面高光 / 角色彩色掠光 */
  function eventSlot(parent, defs, tag) {
    const gd = {
      mist: radialDefs(defs, 'lxg-' + tag + '-mist', [[0, '#FFF3C4', 0.85], [0.45, '#FFD9A3', 0.4], [1, '#FFB066', 0]]),
      ground: radialDefs(defs, 'lxg-' + tag + '-ground', [[0, '#FFF3C4', 0.9], [0.55, '#FFD9A3', 0.35], [1, '#FFB066', 0]]),
      rim: radialDefs(defs, 'lxg-' + tag + '-rim', [[0, '#FFE9B0', 0.98], [0.45, '#FFC46B', 0.6], [1, '#8A5A2A', 0]]),
      body: radialDefs(defs, 'lxg-' + tag + '-body', [[0, '#FFF3C4', 0.55], [0.55, '#FFD9A3', 0.28], [1, '#FFB066', 0]])
    };
    return {
      defs: gd,
      mist: glow(parent, 'lx-ev-' + tag + '-mist', 'url(#lxg-' + tag + '-mist)'),
      ground: glow(parent, 'lx-ev-' + tag + '-ground', 'url(#lxg-' + tag + '-ground)'),
      rimL: glow(parent, 'lx-ev-' + tag + '-rim-l', 'url(#lxg-' + tag + '-rim)'),
      rimR: glow(parent, 'lx-ev-' + tag + '-rim-r', 'url(#lxg-' + tag + '-rim)'),
      body: glow(parent, 'lx-ev-' + tag + '-body', 'url(#lxg-' + tag + '-body)')
    };
  }

  function build() {
    const stage = document.querySelector('#stage');
    // 舞台可能被拆成 #svg-env / #svg-scene 两个 SVG（共用同一 viewBox 映射），
    // 或者只有一个 #stage-svg —— 全部兼容。
    rootSvg = document.querySelector('#svg-scene') ||
      document.querySelector('#stage-svg') ||
      document.querySelector('#stage > svg');
    if (!stage || !rootSvg) return false;

    const mkSvg = (id) => {
      const s = document.createElementNS(SVGNS, 'svg');
      s.setAttribute('id', id);
      s.setAttribute('viewBox', '0 0 1600 900');
      s.setAttribute('preserveAspectRatio', 'xMidYMid slice');
      s.setAttribute('aria-hidden', 'true');
      s.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;pointer-events:none;' +
        'z-index:7;mix-blend-mode:screen;overflow:visible';
      return s;
    };

    const svg = mkSvg('lx-light');
    const defs = el('defs', null, svg);
    // 小面积自发光专用的柔化（标准 6px），大光池一律靠渐变，不用滤镜
    const f0 = el('filter', { id: 'lxf-ember', x: '-120%', y: '-120%', width: '340%', height: '340%' }, defs);
    el('feGaussianBlur', { stdDeviation: '7' }, f0);

    const G = {
      mist: radialDefs(defs, 'lxg-mist', [[0, '#A8D8C0', 0.5], [0.5, '#7CAF96', 0.22], [1, '#4E7F68', 0]]),
      moon: radialDefs(defs, 'lxg-moon', [[0, '#CBEAD8', 0.62], [0.42, '#7CAF96', 0.28], [1, '#3E6A57', 0]]),
      mid: radialDefs(defs, 'lxg-mid', [[0, '#CFE9D6', 0.56], [0.50, '#8FC4A6', 0.30], [0.80, '#54856D', 0.09], [1, '#3E6A57', 0]]),
      // 去白：池心不是奶油，是饱和琥珀（L*55–60 / S≥35%）。offset 1 处必须归零。
      pool: radialDefs(defs, 'lxg-pool', [[0, '#E8934F', 0.54], [0.58, '#CF7C41', 0.52], [0.80, '#B0622F', 0.26], [0.92, '#9C4F26', 0.09], [1, '#9C4F26', 0]]),
      core: radialDefs(defs, 'lxg-core', [[0, '#FFE3B8', 0.86], [0.34, '#F2A85F', 0.46], [0.72, '#C4713A', 0.18], [1, '#8A4A22', 0]]),
      rimW: radialDefs(defs, 'lxg-rimw', [[0, '#FFF3C4', 0.92], [0.45, '#FFB066', 0.5], [1, '#FF8A3D', 0]]),
      poolHot: radialDefs(defs, 'lxg-pool-hot', [[0, '#E9A15E', 0.82], [0.45, '#C87A3E', 0.46], [0.8, '#8A4A22', 0.14], [1, '#8A4A22', 0]]),
      rimC: radialDefs(defs, 'lxg-rimc', [[0, '#D6F0E2', 0.66], [0.5, '#7CAF96', 0.26], [1, '#4E7F68', 0]]),
      rimB: radialDefs(defs, 'lxg-rimb', [[0, '#DCE4FF', 0.72], [0.5, '#9DB4FF', 0.30], [1, '#5A6BB8', 0]]),
      halo: radialDefs(defs, 'lxg-halo', [[0, '#FFE8C7', 0.5], [0.42, '#FFC46B', 0.22], [1, '#FF9A4D', 0]]),
      wash: radialDefs(defs, 'lxg-wash', [[0, '#FFC46B', 0.075], [0.55, '#FFB066', 0.035], [1, '#FF9A4D', 0]])
    };

    // 层序：薄雾 → 冷月光幕 → 暖光池 → 火核 → 边缘光 → 事件光 → 祝福光晕 → 暖罩
    const L = {
      mist: el('g', { id: 'lx-bg' }, svg),
      moon: el('g', { id: 'lx-moon' }, svg),
      pools: el('g', { id: 'lx-pools' }, svg),
      rim: el('g', { id: 'lx-rim' }, svg),
      event: el('g', { id: 'lx-event' }, svg),
      bless: el('g', { id: 'lx-bless' }, svg)
    };

    const N = {};
    N.svg = svg;
    N.mist = glow(L.mist, 'lx-mist-a', 'url(#lxg-mist)');
    N.mist2 = glow(L.mist, 'lx-mist-b', 'url(#lxg-mist)');
    // 中景/桌面软顶光：只覆盖 x300–1300 / y380–760，不碰远景与近景剪影
    N.mid = glow(L.moon, 'lx-midwash', 'url(#lxg-mid)');
    N.moonTop = glow(L.moon, 'lx-moon-top', 'url(#lxg-moon)');
    N.moonSide = glow(L.moon, 'lx-moon-side', 'url(#lxg-moon)');
    N.pool = glow(L.pools, 'lx-pool', 'url(#lxg-pool)');
    N.pool2 = glow(L.pools, 'lx-pool-core', 'url(#lxg-pool)');
    N.poolHot = glow(L.pools, 'lx-pool-hot', 'url(#lxg-pool-hot)');
    N.cores = [glow(L.pools, 'lx-core-1', 'url(#lxg-core)'), glow(L.pools, 'lx-core-2', 'url(#lxg-core)'), glow(L.pools, 'lx-core-3', 'url(#lxg-core)')];
    N.rimWarm = glow(L.rim, 'lx-rim-warm', 'url(#lxg-rimw)');
    N.rimCool = glow(L.rim, 'lx-rim-cool', 'url(#lxg-rimc)');
    N.boomRim = glow(L.rim, 'lx-rim-boom', 'url(#lxg-rimb)');
    // 角色腹部的冷暖两面（P0-2）：冷面朝月亮，暖面朝蜡烛
    N.bellyCool = glow(L.rim, 'lx-belly-cool', 'url(#lxg-rimc)');
    N.bellyWarm = glow(L.rim, 'lx-belly-warm', 'url(#lxg-rimw)');
    // 弧形受光带（低位侧光）：梯度锚定在腹部椭圆上，用 userSpaceOnUse + gradientTransform 跟随
    N.arcGrad = radialDefs(defs, 'lxg-arc', [[0, '#FFC98A', 0], [0.50, '#FFC98A', 0],
      [0.84, '#FFD9A3', 0.92], [0.94, '#FFB066', 0.55], [1, '#FF9A4D', 0]], {
      gradientUnits: 'userSpaceOnUse', cx: '0', cy: '0', r: '1'
    });
    N.arc = el('path', { id: 'lx-belly-arc', fill: 'url(#lxg-arc)', d: '' }, L.rim);
    // 蜡烛上方的暖色光锥（体积光）
    const coneG = el('linearGradient', { id: 'lxg-cone', x1: '0', y1: '1', x2: '0', y2: '0' }, defs);
    el('stop', { offset: '0', 'stop-color': '#FFC46B', 'stop-opacity': 0.95 }, coneG);
    el('stop', { offset: '0.42', 'stop-color': '#FFB066', 'stop-opacity': 0.45 }, coneG);
    el('stop', { offset: '1', 'stop-color': '#FF9A4D', 'stop-opacity': 0 }, coneG);
    N.cone = el('path', { id: 'lx-cone', fill: 'url(#lxg-cone)', d: '' }, L.pools);
    // 蛋糕顶的镜面点（≤800px²）
    radialDefs(defs, 'lxg-spec', [[0, '#FFFFFF', 0.95], [0.55, '#FFF3C4', 0.5], [1, '#FFD9A3', 0]]);
    N.spec = glow(L.pools, 'lx-spec', 'url(#lxg-spec)');
    // 月亮（env 的月核在左上 (−)、大部分在画外）——给空场一个 L*≥85 的事件点
    radialDefs(defs, 'lxg-planet', [[0, '#FFFFFF', 1.0], [0.34, '#F7FFFB', 0.92], [0.55, '#D8F5E4', 0.28], [0.78, '#9FD8B7', 0.06], [1, '#9FD8B7', 0]]);
    N.glint = glow(L.moon, 'lx-moon-glint', 'url(#lxg-planet)');
    // 温室玻璃上的月光反射（次要光只做反射，主光仍是月）
    radialDefs(defs, 'lxg-glass', [[0, '#EAF6FF', 0.85], [0.45, '#BFD9E8', 0.35], [1, '#7CAF96', 0]]);
    N.glass = glow(L.moon, 'lx-glass', 'url(#lxg-glass)');
    N.evA = eventSlot(L.event, defs, 'a');
    N.evB = eventSlot(L.event, defs, 'b');
    N.halo = glow(L.bless, 'lx-halo', 'url(#lxg-halo)');
    N.wash = glow(L.bless, 'lx-wash', 'url(#lxg-wash)');
    N.G = G;

    // 桌沿消光带：空场时那条横贯全幅的亮边没有光源，属于漏光。跟着接触线走。
    const lip = document.createElement('div');
    lip.id = 'lx-lip';
    lip.style.cssText = 'position:absolute;left:0;right:0;pointer-events:none;z-index:9;opacity:0;' +
      'background:linear-gradient(180deg,transparent 0%,rgba(0,0,0,.34) 30%,rgba(0,0,0,.62) 52%,' +
      'rgba(0,0,0,.34) 74%,transparent 100%)';
    stage.appendChild(lip);
    N.lip = lip;

    // 压暗叠层：优先接管 index.html 里的 #blackout（z10，motion 只做兜底，
    // 谁最后写谁生效 —— main.js 的 seek 顺序保证 Lighting.update 在时间线之后）。
    // 没有这个节点时自己建一个，行为完全一样。
    let veil = document.getElementById('blackout');
    if (!veil) {
      veil = document.createElement('div');
      veil.id = 'lx-veil';
      veil.style.cssText = 'position:absolute;inset:0;pointer-events:none;z-index:10;background:#000;opacity:0';
      stage.appendChild(veil);
    } else {
      veil.style.transition = 'none';
    }
    N.veil = veil;
    stage.appendChild(svg);

    // 余烬层：黑场里唯一活着的三个点。
    // z-index 11 —— 必须高过压暗层（z10），否则余烬会被自己压掉；仍低于文字（z12）。
    const svg2 = mkSvg('lx-ember');
    svg2.style.zIndex = '11';
    const defs2 = el('defs', null, svg2);
    radialDefs(defs2, 'lxg-ember2', [[0, '#FFD9A3', 0.95], [0.4, '#FF7A3C', 0.55], [1, '#C24A1E', 0]]);
    const f2 = el('filter', { id: 'lxf-ember2', x: '-120%', y: '-120%', width: '340%', height: '340%' }, defs2);
    el('feGaussianBlur', { stdDeviation: '7' }, f2);
    const egG = el('g', { id: 'lx-embers', filter: 'url(#lxf-ember2)' }, svg2);
    N.embers = [0, 1, 2].map((i) => glow(egG, 'lx-ember-' + (i + 1), 'url(#lxg-ember2)'));
    radialDefs(defs2, 'lxg-emberhub', [[0, '#C97A3C', 0.95], [0.45, '#8A4A2A', 0.5], [1, '#3A1A0C', 0]]);
    N.emberHub = glow(el('g', { id: 'lx-ember-hub-g' }, svg2), 'lx-ember-hub', 'url(#lxg-emberhub)');
    // 黑场里左侧那一道极淡的月光斜切（3–5%），给画面一个方向
    const slashDef = radialDefs(defs2, 'lxg-slash', [[0, '#BFE3D0', 0.5], [0.55, '#7CAF96', 0.22], [1, '#3E6A57', 0]]);
    N.slash = glow(el('g', { id: 'lx-moon-slash' }, svg2), 'lx-slash', 'url(#lxg-slash)');
    // 32.58s 起的一颗火星：烟花的预告
    radialDefs(defs2, 'lxg-spark', [[0, '#FFF3C4', 1], [0.35, '#FFB066', 0.6], [1, '#C24A1E', 0]]);
    // 黑幕之上的一层极淡「在场感」：让观众知道人物还在，不是渲染失败
    radialDefs(defs2, 'lxg-presence', [[0, '#CFE9DA', 0.55], [0.45, '#8FC0A8', 0.26], [1, '#4E7F68', 0]]);
    N.presence = glow(el('g', { id: 'lx-presence-g' }, svg2), 'lx-presence', 'url(#lxg-presence)');
    // 黑场里 Tux 的左侧冷轮廓（只允许受光侧一段，不做全包围）
    radialDefs(defs2, 'lxg-contour', [[0, '#CFE9DA', 0.55], [0.55, '#8FC0A8', 0.28], [1, '#4E7F68', 0]]);
    const cg = el('g', { id: 'lx-contour-g' }, svg2);
    N.contourGrad = { node: defs2.querySelector('#lxg-contour') };
    N.contour = el('path', { id: 'lx-contour', fill: 'url(#lxg-contour)', d: '' }, cg);
    const sg = el('g', { id: 'lx-spark-g', filter: 'url(#lxf-ember2)' }, svg2);
    N.spark = glow(sg, 'lx-spark', 'url(#lxg-spark)');
    stage.appendChild(svg2);
    N.svg2 = svg2;

    nodes = N;
    return true;
  }

  /* ══════════════════════════════════════════════════════════════
     6. apply() —— 把参数写到 DOM（唯一的写入口）
     ══════════════════════════════════════════════════════════════ */

  const F = (v) => (Number.isFinite(v) ? v.toFixed(2) : '0.00');

  function place(node, x, y, rx, ry, rot) {
    if (!node) return;
    const r = rot ? ' rotate(' + F(rot) + ')' : '';
    node.setAttribute('transform',
      'translate(' + F(x) + ' ' + F(y) + ')' + r + ' scale(' + F(rx) + ' ' + F(ry) + ')');
  }
  function alpha(node, v) {
    if (!node) return;
    node.setAttribute('opacity', F(clamp(v, 0, 1)));
  }


  /* 弧形受光带：在腹部椭圆上取一段 28° 的环带，两端收尖（视觉柔化），
     径向渐变把内缘和外缘各做掉一段，避免硬边。 */
  function arcBeltPath(cx, cy, rx, ry, a0, a1, rOut, rInMid, rInEnd) {
    const SEG = 8, D = Math.PI / 180, pts = [];
    for (let i = 0; i <= SEG; i++) {
      const a = (a0 + (a1 - a0) * i / SEG) * D;
      pts.push([cx + rx * rOut * Math.cos(a), cy + ry * rOut * Math.sin(a)]);
    }
    for (let i = SEG; i >= 0; i--) {
      const u = i / SEG;
      const k = rInEnd + (rInMid - rInEnd) * Math.sin(Math.PI * u);
      const a = (a0 + (a1 - a0) * u) * D;
      pts.push([cx + rx * k * Math.cos(a), cy + ry * k * Math.sin(a)]);
    }
    let d = 'M';
    for (let i = 0; i < pts.length; i++) {
      d += (i ? 'L' : '') + pts[i][0].toFixed(1) + ' ' + pts[i][1].toFixed(1);
    }
    return d + 'Z';
  }

  /* ── 资产节点：可见性与亮度（可见性归 Lighting，VFX 挂了也不能漏火） ── */
  const FILTER_CACHE = {};
  const SHADE_ELS = {};
  function setBrightness(id, key, v, contrast) {
    let el = SHADE_ELS[key];
    if (el === undefined) el = SHADE_ELS[key] = document.getElementById(id) || null;
    if (!el) return;
    // 量化到 0.02：滤波变化会重绘整个元素，闪烁幅度只有 ±2% 时没必要每帧改
    const val = Math.round(clamp(v, 0.05, 1.4) * 50) / 50;
    if (FILTER_CACHE[key] === val) return;
    FILTER_CACHE[key] = val;
    el.style.filter = 'brightness(' + val + ')' + (contrast ? ' contrast(' + contrast + ')' : '');
  }

  /* 物体自身的（含继承的）不透明度：蛋糕还在托盘里/还没进场时，
     叠加层的光不该提前亮在桌面上。只读，不改别人的节点。 */
  function opOf(el) {
    if (!el) return 1;
    const v = parseFloat(el.style.opacity);
    return Number.isFinite(v) ? v : 1;
  }
  // 只读 2 个节点的 inline opacity（GSAP 写的就是 inline），不调用 getComputedStyle
  function effectiveOpacity(el, parentEl) {
    return clamp(opOf(el) * opOf(parentEl), 0, 1);
  }

  function driveSceneNodes(s) {
    // 火焰本体 / 烛光光晕：只看 t（CUES.ignite / extinguish）
    for (let i = 0; i < 3; i++) {
      const f = s.flames[i];
      const vis = f.vis;
      const unit = document.getElementById('flame-unit-' + (i + 1));
      if (unit) {
        unit.setAttribute('opacity', F(vis));
        // display 兜底：别的模块只要不是显式改 display 就绝对漏不出火
        if (vis > 0.004) {
          if (unit.style.display === 'none') unit.style.display = '';
          unit.style.visibility = 'visible';
        } else {
          unit.style.visibility = 'hidden';
        }
      }
      const glow = document.getElementById('glow-' + (i + 1));
      if (glow) {
        glow.setAttribute('opacity', F(f.glow));
        glow.style.visibility = vis > 0.004 ? 'visible' : 'hidden';
      }
    }
    // 桌面承影池：半径随点亮数增长、随熄灭收缩（不是淡出）
    const pool = document.getElementById('cake-pool');
    if (pool) {
      const k = s.key.cakePoolK;
      // N10：蛋糕没落桌之前桌面不该有暖斑（4.20 / 14.90 / 15.50 都被抓到过 0.05）
      let cp;
      if (s.tc < 14.85) cp = 0;
      else if (s.tc < 15.90) cp = 0.10 * smoothstep(14.85, 15.35, s.tc);
      else cp = clamp(Math.pow(clamp(s.key.intensity, 0, 1), 0.58) * 0.44, 0, 0.46);
      pool.setAttribute('opacity', F(cp));
      pool.setAttribute('transform',
        'translate(800 672) scale(' + k.toFixed(4) + ') translate(-800 -672)');
    }
    // 角色 / 蛋糕在没被照亮时不该是全场最亮的东西（P0-2）
    setBrightness('tux-belly', 'belly', s.shade.belly, 1.12);
    setBrightness('cake-icing', 'icing', s.shade.icing);
    // 蜡烛柱体（#FFF6E4）本身就能到 255：跟糖霜同一套滚降，否则「蛋糕顶」永远压不到 L*88
    for (let i = 1; i <= 3; i++) setBrightness('candle-' + i, 'candle' + i, s.shade.candle);
    setBrightness('cake-body', 'body', s.shade.body);
    setBrightness('cake-plate', 'plate', s.shade.plate);
  }

  function apply(s) {
    if (!nodes) return;
    const N = nodes;
    const tux = A.tux || { x: 600, y: 500, w: 192, h: 358, left: 504, right: 696, top: 321, bottom: 679 };
    const cake = A.cake || { x: 800, y: 660, s: 1 };
    const flames = A.flames || FLAME_FALLBACK.map((f) => ({ x: f.x, y: f.y, s: 1, wx: f.x, wy: f.y }));
    const sc = cake.s || 1;
    // 物体在不在场：蛋糕还没落桌、Tux 还没走到位时，别让光先亮
    const cakeVis = effectiveOpacity(document.getElementById('cake'), layer('cake'));
    const tuxVis = effectiveOpacity(document.getElementById('tux'), layer('tux'));

    try { driveSceneNodes(s); } catch (e) { /* 资产缺失：静默降级 */ }

    // ── 冷月辅光 ────────────────────────────────────────
    // 顶部光幕：仰角 30°，从画面左上后方打下来
    const fx = tux.x * 0.5 + cake.x * 0.5;
    // 中景顶光：y 380–760 的软椭圆，主要把桌面/中景从 L*9–12 抬到 14–30
    place(N.mid, 800, 560, 640, 235);
    alpha(N.mid, s.fill.intensity * FILL.midK * (1 - s.dark.veil * 0.9));
    place(N.moonTop, 800 - 60, 60, 1120, 470);
    alpha(N.moonTop, s.fill.alphaTop * (1 - s.dark.veil * 0.92));
    place(N.moonSide, fx - 210, 300, 700, 520);
    alpha(N.moonSide, s.fill.alphaTop * 0.55 * (1 - s.dark.veil * 0.92));

    // 背景薄雾（让烟花闪光照亮空气）
    const evMistA = s.event.slots[0] ? s.event.slots[0].mist * 0.55 : 0;
    const evMistB = s.event.slots[1] ? s.event.slots[1].mist * 0.40 : 0;
    place(N.mist, 780, 470, 880, 470);
    alpha(N.mist, 0.05 + s.fill.alphaMist * 0.9 + evMistA + evMistB + s.boom.mist);
    place(N.mist2, 1040, 300, 420, 300);
    alpha(N.mist2, 0.03 + s.fill.alphaMist * 0.7 + evMistB * 1.2 + evMistA * 0.8 + s.boom.mist * 0.7);

    // ── 主光：两段式暖光 ────────────────────────────────
    // 内层：贴着火苗的小核（r≈1.0，#FFF3C4，α .28）—— 火焰本体的亮心
    // 外层：椭圆压在桌面上（rx = 2.2r，ry = rx*0.34）—— 低位光源在桌面被拉伸
    const level = s.key.poolLevel;
    const poolA = (level * 0.82 + s.bless.pool * 0.55) * (1 - s.dark.veil) * cakeVis;
    const prx = (150 + 180 * level) * sc;      // 3 支点亮时 rx=330 / ry=132，环带宽度 ≥420px
    // 池心往桌面前沿压一点：蛋糕本身挡住了池心，露在外面的环形才是观众看到的「暖」
    place(N.pool, cake.x, cake.y + 40 * sc, prx, prx * 0.40);
    alpha(N.pool, poolA);
    // 蛋糕根部的高饱和暖斑（只贴桌面，不外扩）：把暖像素占比从 ~6% 推到 8–12%
    place(N.poolHot, cake.x, cake.y + 26 * sc, 150 * (0.35 + 0.65 * level) * sc, 54 * (0.35 + 0.65 * level) * sc);
    alpha(N.poolHot, clamp(0.30 * level * cakeVis * (1 - s.dark.veil), 0, 0.5));
    place(N.pool2, cake.x, cake.y - 96 * sc,
      s.key.cakePoolRx * 0.62 * sc, s.key.cakePoolRx * 0.62 * 0.86 * sc);
    alpha(N.pool2, clamp((0.12 * level + 0.08 * s.bless.weight) * (1 - s.dark.veil) * cakeVis, 0, 1));

    // 环境层自带的地面池只做轻微跟随（几何归环境美术）
    const groundPool = document.getElementById('e-ground-pool');
    // 环境层的地面池也只跟烛光走：蛋糕还没落桌时桌面不该先亮（4s 会露馅）
    if (groundPool) groundPool.setAttribute('opacity', F(clamp(poolA * 0.6, 0, 0.95)));

    // 火核（每支一个，位置跟真实 wick）
    for (let i = 0; i < 3; i++) {
      const w = flameWeightLike(i, s);
      const p = flames[i];
      const fl = flicker(i, s.tc);
      const flick = 1 + (fl - 1) * 1.4;            // 火核比整体更敏感
      const r = (20 + 26 * Math.min(1.15, w)) * flick * (p.s || sc);
      place(N.cores[i], p.x, p.y - 7 * (p.s || sc), r, r * 1.18);
      alpha(N.cores[i], clamp(w * flick, 0, 1.3) * KEY.coreAlpha * (1 - s.dark.veil) * cakeVis);
    }

    // ── 角色边缘光 ──────────────────────────────────────
    // 资产里已经有贴合轮廓的 #tux-rimlight（暖描边在向火侧、冷描边在背光侧），
    // 所以以它的 opacity 为主；自绘掠光只在资产缺失时补位，避免「一道竖光柱」。
    const rl = document.getElementById('tux-rimlight');
    // 资产有 #tux-rimlight（美术按真实轮廓画的）时，自绘掠光彻底关掉：
    // #tux-body 的 bbox 含侧鳍，硬算出来的右边界会落在角色外的空气里，变成竖光柱。
    const sliverK = rl ? 0 : 1;
    const bodyH = tux.h || 358;
    const midY = tux.y + bodyH * 0.04;
    const warmA = Math.max(s.rim.warm.alpha, s.bless.rimWarm);
    const tcore = tux.core || { cx: tux.x, cy: tux.y, halfW: (tux.w || 240) / 2 };
    place(N.rimWarm, tcore.cx + tcore.halfW - 4, tcore.cy, 16, bodyH * 0.42, -6);
    alpha(N.rimWarm, warmA * sliverK);
    place(N.rimCool, tcore.cx - tcore.halfW + 4, tcore.cy - 12, 15, bodyH * 0.38, 5);
    alpha(N.rimCool, (s.rim.cool.alpha + s.boom.rim * 0.35) * sliverK);
    place(N.boomRim, tux.left - 2, midY - 18, 18, bodyH * 0.50, 4);
    alpha(N.boomRim, s.boom.rim * (rl ? 0.45 : 1));

    if (rl) {
      rl.setAttribute('opacity', F(clamp(0.30 + 0.70 * warmA, 0, 1)));
      rl.style.visibility = 'visible';
    }

    // 腹部/胸口的冷暖两面（P0-2）：让「被照亮」读得出来，
    // 同时中心比边缘暗（体积感），不是整体提亮。
    const bw = tux.w || 240, bh = tux.h || 358;
    const bx = tux.x, by = tux.y + bh * 0.02;
    place(N.bellyCool, bx - bw * 0.06, by, bw * 0.24, bh * 0.34, 8);
    alpha(N.bellyCool, s.belly.cool * (1 - s.dark.veil) * tuxVis);
    // 只留一点环境反弹，主体形状交给弧带（整块 screen 会把肚子涂成一片奶油）
    place(N.bellyWarm, bx + bw * 0.09, by + bh * 0.03, bw * 0.22, bh * 0.26, -12);
    alpha(N.bellyWarm, s.belly.warm * 0.34 * (1 - s.dark.veil) * tuxVis);

    // ── 弧形受光带（低位侧光，来自蜡烛方向）──────────────
    // 28° 环带贴在腹部右下，两端收尖；渐变 0.84–0.94 给 3–5% 柔化
    const bcx = tcore.cx, bcy = tcore.cy;
    const brx = (tcore.bellyRx || bw * 0.22), bry = (tcore.bellyRy || bh * 0.34);
    N.arc.setAttribute('d', arcBeltPath(bcx, bcy, brx, bry, -2, 30, 1.04, 0.70, 0.96));
    N.arcGrad.node.setAttribute('gradientTransform',
      'translate(' + F(bcx) + ' ' + F(bcy) + ') scale(' + F(brx) + ' ' + F(bry) + ')');
    alpha(N.arc, s.belly.arc * (1 - s.dark.veil) * tuxVis);

    // 月核高光：开场（t<8.2）是事件点，之后压到 44%，不再和叙事主光抢视线
    const glintGate = (s.t < 8.2) ? 1 : 0.44;
    const glintW = s.moonGlint * glintGate;
    place(N.glint, 152, 104, 26, 22);
    alpha(N.glint, glintW);

    // 玻璃冷反射：与月同侧的斜向高光（(1180,150) 附近的一段）
    place(N.glass, 1186, 168, 150, 17, -14);
    alpha(N.glass, glintW * 0.34);

    // ── 蜡烛上方的暖色光锥（16.45–28.28，α≤0.06）──────────
    const coneK = s.cone.weight;
    const cl = flames[1] || flames[0];
    place(N.cone, cl.x, cl.y - 86 * (cl.s || sc), 78 * (cl.s || sc), 150 * (cl.s || sc));
    alpha(N.cone, coneK * 0.055);

    // ── 蛋糕顶的镜面点（≤800px²）────────────────────────
    place(N.spec, cake.x - 30 * sc, cake.y - 66 * sc, 22 * sc, 10 * sc);
    alpha(N.spec, s.spec.weight);

    // ── 事件光（两槽）───────────────────────────────────
    [N.evA, N.evB].forEach((slot, k) => {
      const d = s.event.slots[k];
      if (!slot) return;
      if (!d) {
        ['mist', 'ground', 'rimL', 'rimR', 'body'].forEach((key) => { if (slot[key]) slot[key].setAttribute('opacity', '0'); });
        return;
      }
      // 动态改色
      const st = slot.defs;
      st.mist.stops[0].setAttribute('stop-color', d.colorInner);
      st.mist.stops[1].setAttribute('stop-color', d.color);
      st.ground.stops[0].setAttribute('stop-color', d.colorInner);
      st.ground.stops[1].setAttribute('stop-color', d.color);
      st.rim.stops[0].setAttribute('stop-color', d.colorInner);
      st.rim.stops[1].setAttribute('stop-color', d.color);

      place(slot.mist, d.x, d.y * 0.62 + 150, 620, 430);
      alpha(slot.mist, d.mist);
      const gr = d.r || 160;
      place(slot.ground, d.x, cake.y, gr * 1.6, gr * 0.42);
      alpha(slot.ground, d.ground);
      place(slot.body, (tux.core ? tux.core.cx : tux.x), (tux.core ? tux.core.cy : tux.y), bw * 0.46, bh * 0.42);
      alpha(slot.body, d.body * (1 - s.dark.veil * 0.6));
      // 彩色边缘光必须贴真实轮廓（用腹部轴线推躯干边界），否则会变成空气里的一道竖光
      const core = tux.core || { cx: tux.x, cy: tux.y, halfW: (tux.w || 240) / 2 };
      const halfH = (core.halfH || bodyH * 0.34);
      place(slot.rimL, core.cx - core.halfW + 5, core.cy, 20, halfH * 1.15, 5);
      alpha(slot.rimL, d.rim * (1 - s.dark.veil * 0.5));
      place(slot.rimR, core.cx + core.halfW - 5, core.cy + 4, 21, halfH * 1.2, -6);
      alpha(slot.rimR, d.rim * (1 - s.dark.veil * 0.5));
    });

    // 桌沿消光：只在「没有任何光源」时出现（空场 / 入场），烛光亮起后退到 0
    if (N.lip) {
      const lipY = cake.y - 8;   // 桌沿前唇的连续高光
      const h = 34 * sc;
      N.lip.style.top = ((lipY - h / 2) / 900 * 100).toFixed(3) + '%';
      N.lip.style.height = (h / 900 * 100).toFixed(3) + '%';
      N.lip.style.opacity = F(clamp((1 - Math.min(1, level * 2.2)) * (1 - s.dark.veil) * 0.45, 0, 0.45));
    }

    // ── 黑场：压暗 + 余烬 + 月光斜切 + 火星预告 ─────────
    if (N.veil) N.veil.style.opacity = F(s.dark.veil);
    const emberR = [15.0, 18.0, 13.0];
    for (let i = 0; i < 3; i++) {
      const p = flames[i];
      // 呼吸：周期 2.3s，三支相位 0 / 2.09 / 4.19，亮度 0.35–0.55
      const b = 0.45 + 0.10 * Math.sin(2 * Math.PI * s.t / 2.3 + [0, 2.09, 4.19][i]);
      const r = emberR[i] * (p.s || sc) * (0.94 + 0.12 * b);
      place(N.embers[i], p.x, p.y + 3 * (p.s || sc), r, r * 1.25);
      alpha(N.embers[i], s.dark.emberAlpha * b * 1.15);
    }
    // 蛋糕底座那颗余温斑（40×20）：黑场里除了三支 wick，还要有「整体还热着」的读数
    place(N.emberHub, cake.x, cake.y - 12 * sc, 40 * sc, 20 * sc);
    alpha(N.emberHub, s.dark.ember * 0.22 * (1 - smoothstep(33.0, 33.6, s.t)));
    // 黑场：Tux 左侧轮廓（月光从左上打过来，只亮受光侧一段）
    if (s.dark.ember > 0.01 && tcore) {
      const crx = tcore.bellyRx || bw * 0.22, cry = (tcore.bellyRy || bh * 0.34) * 1.28;
      N.contour.setAttribute('d', arcBeltPath(tcore.cx, tcore.cy + bh * 0.02, crx * 1.2, cry, 152, 214, 1.06, 0.60, 0.94));
      alpha(N.contour, s.dark.ember * 0.095);
    } else alpha(N.contour, 0);

    // 在场感微光（只在黑场里出现）
    place(N.presence, (tux.x + cake.x) / 2 + 20, (tux.y + cake.y) / 2, 360 * sc, 300 * sc);
    alpha(N.presence, s.dark.ember * DARK.presence * 1.6 * Math.min(1, tuxVis));
    // 左侧那道极淡的月光斜切：给黑场一个方向
    place(N.slash, 250, 470, 120, 520, -32);
    alpha(N.slash, s.dark.slashAlpha);
    // 32.58s 起：一颗向上飘的火星，烟花的预告
    if (s.dark.spark >= 0) {
      const sp = s.dark.spark;
      const base = flames[1] || flames[0];
      const x = base.x + Math.sin(sp * Math.PI * 2.2) * 9 + sp * 6;
      const y = base.y - 10 - 150 * (1 - Math.pow(1 - sp, 2.2));
      place(N.spark, x, y, 3.4 * (base.s || sc), 3.4 * (base.s || sc));
      alpha(N.spark, 0.62 * Math.pow(1 - sp, 1.5));
    } else {
      alpha(N.spark, 0);
    }

    // ── 祝福光晕 ────────────────────────────────────────
    const hx = (tux.x + cake.x) / 2, hy = (tux.y + cake.y) / 2 - 40;
    place(N.halo, hx, hy - 30, s.bless.radius * sc, s.bless.radius * 0.72 * sc);
    alpha(N.halo, s.bless.halo);
    place(N.wash, 800, 470, 980, 620);
    alpha(N.wash, s.bless.wash);
  }

  // 单支火焰「点亮权重」——与 flameWeight 同式，但走 state.tc
  function flameWeightLike(i, s) {
    const tin = IGNITE[i], tout = EXT[i];
    const t = s.tc;
    if (tin == null) return 0;
    if (tout != null && t >= tout) return 0.30 * Math.exp(-(t - tout) / 0.42);
    if (t < tin) return 0;
    const age = t - tin;
    return smoothstep(0, 0.22, age) * (1 + 0.90 * Math.exp(-age / 0.11));
  }

  /* ══════════════════════════════════════════════════════════════
     7. 公共接口
     ══════════════════════════════════════════════════════════════ */

  const Lighting = {
    /* 一次性：抓节点、建叠加层、算初始世界锚点 */
    init(bday) {
      bdayRef = bday || null;
      reducedMode = !!(bday && bday.reduced);
      if (!build()) return;
      cacheBoxes();
      refreshWorld(true);
      ready = true;
      // 主时间线在 seek 里是「先 Lighting.update 后 applyCamera」，
      // 所以相机矩阵会晚一帧。这里在下一帧补一次世界锚点刷新，
      // 保证截图（shoot.cjs 等 2 帧）里的光池与角色严格贴合。
      const resync = () => { if (ready) { refreshWorld(true); apply(lastState || evaluate(0)); } };
      if (typeof requestAnimationFrame === 'function') requestAnimationFrame(resync);
      apply(evaluate(0));
    },

    /* 每帧 / 每次 seek：纯时间寻址，可重复调用 */
    update(t) {
      if (!ready) return;
      let s;
      try {
        s = evaluate(Number.isFinite(t) ? t : 0);
        lastState = s;
        apply(s);
      } catch (e) {
        // 资产缺失/异常时绝不让主时间线挂掉
        if (window.console && console.warn) console.warn('[Lighting] update skipped:', e && e.message);
      }
      // 相机在 update 之后才写入，补一帧刷新世界锚点
      if (typeof requestAnimationFrame === 'function') {
        if (rafPending) return;
        rafPending = true;
        requestAnimationFrame(() => {
          rafPending = false;
          if (!ready || !lastState) return;
          refreshWorld(false);
          apply(lastState);
        });
      }
    },

    /* 已点燃蜡烛数（motion 会在时间线里调；authoritative 状态仍以 t 为准） */
    ignite(count) {
      igniteHint = clamp(count | 0, 0, 3);
      if (ready) apply(evaluate(currentTime()));
    },

    /* 外部（VFX）主动打一次事件光。o = {x,y,r,color,intensity,dur} */
    flash(o) {
      if (!o) return;
      const t0 = Number.isFinite(o.at) ? o.at : currentTime();
      extFlashes.push({
        at: t0,
        x: Number.isFinite(o.x) ? o.x : 800,
        y: Number.isFinite(o.y) ? o.y : 300,
        r: Number.isFinite(o.r) ? o.r : 160,
        c: o.color || o.c || '#FFE9B0',
        i: Number.isFinite(o.intensity) ? o.intensity : 1,
        dur: Number.isFinite(o.dur) ? o.dur : 0.6,
        kind: o.kind || 'ext'
      });
      if (extFlashes.length > 24) extFlashes.splice(0, extFlashes.length - 24);
      if (ready) apply(evaluate(currentTime()));
    },

    /* 环境光总增益 0..1（1 = 设计值） */
    ambient(v) {
      ambGain = clamp(Number.isFinite(v) ? v : 1, 0, 1.5);
      if (ready) apply(evaluate(currentTime()));
    },

    /* QA：纯查询，返回该时刻的光照参数（不写 DOM） */
    state(t) {
      const s = evaluate(Number.isFinite(t) ? t : currentTime());
      return Object.assign({}, s, {
        anchors: {
          cake: A.cake || null,
          tux: A.tux || null,
          flames: A.flames || null
        },
        external: extFlashes.length
      });
    },

    /* 便于调试：当前实际写入的叠加层 */
    _nodes: () => nodes
  };

  let lastState = null;
  let rafPending = false;
  let igniteHint = 0;
  const extFlashes = [];

  function currentTime() {
    if (bdayRef && bdayRef.master) {
      const t = bdayRef.master.time();
      return Number.isFinite(t) ? t : 0;
    }
    return lastState ? lastState.t : 0;
  }

  // 外部 flash 参与合成：在 shellsAt 之外叠加
  // （包一层而不是改 shellsAt，保证 CUES 推导仍是纯函数）
  const _shellsAt = shellsAt;
  shellsAt = function (t) {
    const base = _shellsAt(t);
    if (!extFlashes.length) return base;
    for (let i = 0; i < extFlashes.length; i++) {
      const f = extFlashes[i];
      const age = t - f.at;
      if (age < 0 || age > f.dur + 0.2) continue;
      const e = eventCurve(age, f.dur, f.kind === 'ext' ? 'peony' : f.kind);
      const amp = e * clamp(f.i, 0, 1.4);
      if (amp <= 0.0005) continue;
      base.list.push({ i: 99, kind: f.kind, x: f.x, y: f.y, r: f.r, c: [f.c, f.c], burstAt: f.at,
        age: age, dur: f.dur, amp: amp, pre: 0, intensity: f.i });
      base.total += amp;
    }
    return base;
  };

  window.Lighting = Lighting;
})();
