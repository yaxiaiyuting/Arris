/* ─────────────────────────────────────────────────────────────────────────
   js/assets.env.js — 场景 / 背景美术（environment-artist）
   夜森林里的玻璃温室 · 七层视差

   约定（见 docs/CONTRACT.md §2 §3.2）：
     window.Assets.env = { sky, aurora, far, mid, ground, near, fg }
     每个值是「不含外层 <svg>」的 SVG 片段字符串，注入 #layer-sky … #layer-fg。
     所有 defs id 以 e- 前缀，避免与 tux(t-) / cake(k-) 冲突。
     无位图；不用 feTurbulence —— 雾与柔边一律用渐变 + 小范围高斯模糊，
     因为每层每帧都被 applyCamera 重写 transform，滤镜只能压在静态小元素上。

   坐标：viewBox 0 0 1600 900；桌面接触线 y = 660；视觉中心 x = 800。
   构图纪律：主体区 x∈[420,980] y∈[300,700] 低对比低密度；
             文字区 x∈[900,1440] y∈[150,620] 不做高对比细节。

   光向设定（与 CONTRACT §5 一致）：
     主光 = 桌上三支蜡烛（暖 #FFB066，低位，x≈800 y≈660）
     环境 = 左上方冷月（#7CAF96 / #9FD8B7，高位，圆心在画外）
   → 所有轮廓光只出现在「左上缘」，右侧一律衰减。
   ───────────────────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  window.Assets = window.Assets || {};
  window.Assets.env = window.Assets.env || {};

  /* ── 工具：确定性伪随机 + 几何小工具 ─────────────────────────────── */
  function mulberry32(a) {
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      var t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  const R = mulberry32(0x7A5EED11);
  const rnd = () => R();
  const rg = (a, b) => a + rnd() * (b - a);
  const f1 = (n) => Math.round(n * 10) / 10;
  const f2 = (n) => Math.round(n * 100) / 100;

  /* 不规则闭合团块：苔石 / 苔斑 / 树冠都用它，避免"圆球感" */
  function blob(cx, cy, rx, ry, n, jitter) {
    const p = [];
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2 + rnd() * 0.22;
      const k = 1 + (rnd() - 0.5) * jitter;
      p.push([cx + Math.cos(a) * rx * k, cy + Math.sin(a) * ry * k]);
    }
    const mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
    let s = mid(p[n - 1], p[0]);
    let d = 'M' + f1(s[0]) + ' ' + f1(s[1]);
    for (let i = 0; i < n; i++) {
      const a = p[i], b = p[(i + 1) % n];
      s = mid(a, b);
      d += 'Q' + f1(a[0]) + ' ' + f1(a[1]) + ' ' + f1(s[0]) + ' ' + f1(s[1]);
    }
    return d + 'Z';
  }

  /* 双段三次贝塞尔弧：p = [p0,c1,c2,p3,c4,c5,p6] */
  function arcPoint(p, t) {
    const seg = t < 0.5 ? 0 : 1;
    const u = seg ? (t - 0.5) * 2 : t * 2, mu = 1 - u;
    const i = seg * 3;
    const x = mu * mu * mu * p[i][0] + 3 * mu * mu * u * p[i + 1][0] + 3 * mu * u * u * p[i + 2][0] + u * u * u * p[i + 3][0];
    const y = mu * mu * mu * p[i][1] + 3 * mu * mu * u * p[i + 1][1] + 3 * mu * u * u * p[i + 2][1] + u * u * u * p[i + 3][1];
    return [x, y];
  }
  function arcPath(p, dy) {
    const o = dy || 0;
    return 'M' + f1(p[0][0]) + ' ' + f1(p[0][1] + o) +
      'C' + f1(p[1][0]) + ' ' + f1(p[1][1] + o) + ' ' + f1(p[2][0]) + ' ' + f1(p[2][1] + o) + ' ' + f1(p[3][0]) + ' ' + f1(p[3][1] + o) +
      'C' + f1(p[4][0]) + ' ' + f1(p[4][1] + o) + ' ' + f1(p[5][0]) + ' ' + f1(p[5][1] + o) + ' ' + f1(p[6][0]) + ' ' + f1(p[6][1] + o);
  }
  /* 有厚度的拱肋：外弧正向 → 内弧反向，闭合成一条真正的带子 */
  function ribBand(p, th) {
    const N = 46, out = [], inn = [];
    for (let i = 0; i <= N; i++) {
      const a = arcPoint(p, i / N);
      out.push(a);
      inn.push([a[0], a[1] + th]);
    }
    let d = 'M' + f1(out[0][0]) + ' ' + f1(out[0][1]);
    for (let i = 1; i <= N; i++) d += 'L' + f1(out[i][0]) + ' ' + f1(out[i][1]);
    for (let i = N; i >= 0; i--) d += 'L' + f1(inn[i][0]) + ' ' + f1(inn[i][1]);
    return d + 'Z';
  }

  /* ═══════════════════════════════════════════════════════════════════════
     1. SKY · 星空 / 薄云 / 月晕        depth .02
     月晕圆心在画外左上（96,52），画面里只留散射 —— 没有居中大月亮。
     星与月用 screen 混合，这样低 alpha 也能"发光"而不是糊成灰。
     ═══════════════════════════════════════════════════════════════════════ */
  const SKY = (function () {
    const pal = ['#FFF7E7', '#FFF7E7', '#FFEFD2', '#E8FBFF', '#D6E6FF', '#FFE2B8', '#B9F2C8', '#FFFFFF'];

    let stars = '';
    let made = 0, guard = 0;
    while (made < 84 && guard < 1400) {
      guard++;
      const band = rnd();
      const x = -24 + rnd() * 1648;
      /* 上密下疏：0–250 一档，250–430 一档 */
      const y = band < 0.68 ? rg(6, 252) : rg(252, 430);
      if (x > 404 && x < 1006 && y > 286) continue;          // 主体区留白
      const inText = x > 872 && y > 138;                      // 文字区：只留极少数暗星
      if (inText && rnd() > 0.20) continue;
      const big = Math.pow(rnd(), 2.1);
      const r = 0.75 + big * 1.7;
      let op = 0.30 + rnd() * 0.66;
      if (inText) op *= 0.26;
      if (y > 320) op *= 0.62;
      const c = pal[(rnd() * pal.length) | 0];
      stars += '<circle cx="' + f1(x) + '" cy="' + f1(y) + '" r="' + f2(r) + '" fill="' + c + '" opacity="' + f2(op) + '"/>';
      if (r > 1.75 && op > 0.55) {
        const s = f2(r * 3.6);
        stars += '<path d="M' + f1(x - s) + ' ' + f1(y) + 'H' + f1(x + s) +
          'M' + f1(x) + ' ' + f1(y - s * 0.68) + 'V' + f1(y + s * 0.68) +
          '" stroke="' + c + '" stroke-width=".5" stroke-linecap="round" opacity=".22"/>';
      }
      made++;
    }
    /* 定位星：把视线从左上带到中心，不压主体（y<290） */
    stars +=
      '<circle cx="296" cy="88" r="2.2" fill="#FFFDF4" opacity=".9"/>' +
      '<circle cx="286" cy="82" r="4.6" fill="#D8F3E6" opacity=".16"/>' +
      '<circle cx="492" cy="176" r="1.6" fill="#E8FBFF" opacity=".66"/>' +
      '<circle cx="150" cy="238" r="1.2" fill="#FFEFD2" opacity=".52"/>' +
      '<circle cx="690" cy="128" r="1.4" fill="#FFF7E7" opacity=".55"/>' +
      '<circle cx="1024" cy="104" r="1.3" fill="#D6E6FF" opacity=".38"/>';

    return '' +
      '<defs>' +
        '<radialGradient id="e-grad-moon" cx="50%" cy="50%" r="50%">' +
          '<stop offset="0" stop-color="#EAFFF4" stop-opacity=".40"/>' +
          '<stop offset=".17" stop-color="#C6F2DC" stop-opacity=".34"/>' +
          '<stop offset=".40" stop-color="#9FD8B7" stop-opacity=".13"/>' +
          '<stop offset=".70" stop-color="#7CAF96" stop-opacity=".045"/>' +
          '<stop offset="1" stop-color="#7CAF96" stop-opacity="0"/>' +
        '</radialGradient>' +
        '<radialGradient id="e-grad-moon-core" cx="50%" cy="50%" r="50%">' +
          '<stop offset="0" stop-color="#FFFFFF" stop-opacity=".22"/>' +
          '<stop offset=".34" stop-color="#E3FFF1" stop-opacity=".10"/>' +
          '<stop offset=".62" stop-color="#D8F7E6" stop-opacity=".05"/>' +
          '<stop offset="1" stop-color="#CFF0DD" stop-opacity="0"/>' +
        '</radialGradient>' +
        '<linearGradient id="e-grad-cloud" x1="0" y1="0" x2="1" y2="0">' +
          '<stop offset="0" stop-color="#9FD0B8" stop-opacity=".16"/>' +
          '<stop offset=".38" stop-color="#B7DCC8" stop-opacity=".09"/>' +
          '<stop offset=".72" stop-color="#8FBFA8" stop-opacity=".04"/>' +
          '<stop offset="1" stop-color="#6EAF7E" stop-opacity=".015"/>' +
        '</linearGradient>' +
        '<filter id="e-blur-cloud" x="-15%" y="-170%" width="130%" height="440%">' +
          '<feGaussianBlur stdDeviation="14"/>' +
        '</filter>' +
        /* 天顶压暗：只到 y≈470，且是渐变不是实色 —— 不做满屏不透明底 */
        '<linearGradient id="e-grad-veil" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#010503" stop-opacity=".90"/>' +
          '<stop offset=".20" stop-color="#020806" stop-opacity=".84"/>' +
          '<stop offset=".44" stop-color="#030A06" stop-opacity=".72"/>' +
          '<stop offset=".66" stop-color="#05110A" stop-opacity=".56"/>' +
          '<stop offset=".86" stop-color="#07150E" stop-opacity=".30"/>' +
          '<stop offset="1" stop-color="#08160F" stop-opacity="0"/>' +
        '</linearGradient>' +
      '</defs>' +
      '<rect id="e-sky-veil" x="-80" y="-60" width="1760" height="700" fill="url(#e-grad-veil)"/>' +
      '<g id="e-sky" style="mix-blend-mode:screen">' +
        '<g id="e-sky-moon">' +
          '<ellipse cx="96" cy="52" rx="360" ry="322" fill="url(#e-grad-moon)"/>' +
          '<ellipse cx="76" cy="38" rx="30" ry="27" fill="url(#e-grad-moon-core)"/>' +
        '</g>' +
        '<g id="e-sky-cloud" filter="url(#e-blur-cloud)">' +
          '<path d="M-80 214 C170 172 336 192 528 218 C712 242 828 232 982 210 C1128 190 1268 198 1418 224 ' +
            'L1418 258 C1258 236 1120 232 972 250 C812 270 662 280 500 256 C332 230 182 236 -80 268 Z" ' +
            'fill="url(#e-grad-cloud)" opacity=".7"/>' +
          '<path d="M-80 322 C120 304 286 326 470 342 C640 356 800 348 986 324 C1160 302 1330 310 1440 330 ' +
            'L1440 348 C1330 330 1160 322 986 342 C800 366 640 374 470 360 C286 346 120 326 -80 344 Z" ' +
            'fill="url(#e-grad-cloud)" opacity=".3"/>' +
          '<path d="M-80 132 C90 112 240 128 360 144 C440 154 486 166 540 180 L520 196 C450 176 360 160 236 150 ' +
            'C110 140 20 150 -80 164 Z" fill="url(#e-grad-cloud)" opacity=".24"/>' +
        '</g>' +
        '<g id="e-sky-stars">' + stars + '</g>' +
      '</g>';
  })();

  /* ═══════════════════════════════════════════════════════════════════════
     2. AURORA · 三条可形变极光带     depth .06
     #e-aurora-1/2/3 是三个 <path>，JS 直接改 d 即可。
     不用滤镜（形状会逐帧变）：柔边靠竖向 alpha 渐变 + 横向遮罩，
     外加 3 条静态"光帘"细丝做帘幕质感。
     ═══════════════════════════════════════════════════════════════════════ */
  const AURORA = '' +
    '<defs>' +
      '<linearGradient id="e-grad-aur-a" x1="0" y1="0" x2="0" y2="1">' +
        '<stop offset="0" stop-color="#7FF0D0" stop-opacity="0"/>' +
        '<stop offset=".30" stop-color="#7FF0D0" stop-opacity="0.045"/>' +
        '<stop offset=".58" stop-color="#9BF6DA" stop-opacity="0.093"/>' +
        '<stop offset=".74" stop-color="#D8FFF3" stop-opacity="0.126"/>' +
        '<stop offset=".86" stop-color="#E8FFF8" stop-opacity="0.054"/>' +
        '<stop offset=".94" stop-color="#E8FFF8" stop-opacity="0.018"/>' +
        '<stop offset="1" stop-color="#E8FFF8" stop-opacity="0"/>' +
      '</linearGradient>' +
      '<linearGradient id="e-grad-aur-b" x1="0" y1="0" x2="0" y2="1">' +
        '<stop offset="0" stop-color="#B9F2C8" stop-opacity="0"/>' +
        '<stop offset=".36" stop-color="#B9F2C8" stop-opacity="0.033"/>' +
        '<stop offset=".64" stop-color="#7FF0D0" stop-opacity="0.063"/>' +
        '<stop offset=".80" stop-color="#CBFFF0" stop-opacity="0.09"/>' +
        '<stop offset=".91" stop-color="#CBFFF0" stop-opacity="0.036"/>' +
        '<stop offset="1" stop-color="#CBFFF0" stop-opacity="0"/>' +
      '</linearGradient>' +
      '<linearGradient id="e-grad-aur-h" gradientUnits="userSpaceOnUse" x1="-200" y1="0" x2="1500" y2="0">' +
        '<stop offset="0" stop-color="#fff" stop-opacity=".45"/>' +
        '<stop offset=".26" stop-color="#fff" stop-opacity="1"/>' +
        '<stop offset=".55" stop-color="#fff" stop-opacity=".6"/>' +
        '<stop offset=".80" stop-color="#fff" stop-opacity=".15"/>' +
        '<stop offset="1" stop-color="#fff" stop-opacity="0"/>' +
      '</linearGradient>' +
      '<filter id="e-blur-aur" x="-12%" y="-120%" width="124%" height="340%">' +
        '<feGaussianBlur stdDeviation="26"/>' +
      '</filter>' +
    '</defs>' +
    /* 无 mask：三条带整体压在 y<200，天然避开主体剪裁矩形 x[440,780] ∧ y[200,520] */
    '<g id="e-aurora" filter="url(#e-blur-aur)" style="mix-blend-mode:screen">' +
      /* 主带（path id 保留，JS 可直接改 d） */
      '<path id="e-aurora-1" fill="url(#e-grad-aur-a)" ' +
        'd="M-160 206 C60 178 192 132 342 126 C520 118 612 158 762 166 C902 172 1012 142 1162 116 ' +
        'C1302 92 1422 98 1520 110 L1532 142 C1422 130 1302 124 1162 148 C1012 174 902 204 762 198 ' +
        'C612 190 520 152 342 158 C192 162 60 206 -140 236 Z"/>' +
      /* 高带 */
      '<path id="e-aurora-2" fill="url(#e-grad-aur-b)" ' +
        'd="M-120 112 C122 86 282 54 472 58 C642 62 702 94 862 98 C1002 102 1122 80 1282 62 ' +
        'C1382 48 1472 54 1560 64 L1564 86 C1472 76 1382 70 1282 84 C1122 102 1002 126 862 122 ' +
        'C702 118 642 86 472 82 C282 78 122 108 -110 134 Z"/>' +
      /* 第三条：保留 id 与可形变能力，但压到几乎读不出来（lead 要求"只留 2 条"） */
      '<path id="e-aurora-3" fill="url(#e-grad-aur-b)" opacity=".22" ' +
        'd="M-180 252 C40 234 222 214 402 218 C602 222 682 246 862 248 C1042 250 1142 230 1302 216 ' +
        'C1422 206 1502 210 1602 220 L1602 240 C1502 232 1422 228 1302 236 C1142 250 1042 270 862 268 ' +
        'C682 266 602 242 402 238 C222 234 40 252 -170 272 Z"/>' +
    '</g>';

  /* ═══════════════════════════════════════════════════════════════════════
     3. FAR · 远山 / 远林                depth .14
     单色系（#0E2A1D 家族）三档明度：后林最亮最雾、前林最暗最实。
     ═══════════════════════════════════════════════════════════════════════ */
  const FAR = (function () {
    /* 锯齿冷杉：4 层枝丫，尖端收细 */
    function fir(x, base, h, w, fill, op, skew) {
      const half = w / 2;
      const tier = [];
      tier.push([x, base - h]);
      for (let i = 0; i < 4; i++) {
        const t = 0.30 + (i / 4) * 0.62;
        const yy = base - h * (1 - t);
        const ww = half * Math.pow(t, 0.78);
        tier.push([x + ww, yy + h * 0.03]);
        tier.push([x + ww * 0.46, yy - h * 0.055]);
      }
      tier.push([x + half * 0.30, base - h * 0.02]);
      tier.push([x + half * 0.09, base]);
      const right = tier.slice();
      const left = [[x - half * 0.09, base], [x - half * 0.30, base - h * 0.02]];
      for (let i = 3; i >= 0; i--) {
        const t = 0.30 + (i / 4) * 0.62;
        const yy = base - h * (1 - t);
        const ww = half * Math.pow(t, 0.78);
        left.push([x - ww * 0.46, yy - h * 0.055]);
        left.push([x - ww, yy + h * 0.03]);
      }
      let d = 'M' + f1(right[0][0]) + ' ' + f1(right[0][1]);
      for (let i = 1; i < right.length; i++) d += 'L' + f1(right[i][0]) + ' ' + f1(right[i][1]);
      for (let i = 0; i < left.length; i++) d += 'L' + f1(left[i][0]) + ' ' + f1(left[i][1]);
      /* 绕自身竖轴做轻微倾斜（每 3 棵 1 棵） */
      const tf = skew ? ' transform="translate(' + f1(x) + ' 0) skewX(' + skew + ') translate(' + f1(-x) + ' 0)"' : '';
      return '<path d="' + d + 'Z" fill="' + fill + '" opacity="' + op + '"' + tf + '/>';
    }

    /* 三档高度 + 随机间距（40–190），不再等高等距 */
    const HEIGHTS = [60, 110, 170];
    function forest(yBase, fill, op, x0, x1, seedShift) {
      let out = '', x = x0, i = seedShift || 0;
      while (x < x1) {
        const h = HEIGHTS[(i + (i % 3 === 0 ? 1 : 0)) % 3] * rg(0.88, 1.14);
        const skew = (i % 3 === 0) ? (rnd() < 0.5 ? -7 : 7) : 0;
        out += fir(x, yBase + rg(-10, 10), h, h * rg(0.40, 0.54), fill, op, skew);
        x += rg(40, 190);
        i++;
      }
      return out;
    }

    /* 后层（α.35，更远更淡）+ 前层（α.50，稍实） */
    const back = forest(498, '#123527', 0.35, -70, 1700, 0);
    const front = forest(516, '#07180F', 0.50, -110, 1720, 1);

    return '' +
      '<defs>' +
        '<linearGradient id="e-grad-far-haze" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#2A6B4E" stop-opacity="0"/>' +
          '<stop offset=".30" stop-color="#2A6B4E" stop-opacity=".04"/>' +
          '<stop offset=".58" stop-color="#20563D" stop-opacity=".05"/>' +
          '<stop offset="1" stop-color="#16382A" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-far-deep" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#020906" stop-opacity="0"/>' +
          '<stop offset=".22" stop-color="#020906" stop-opacity=".26"/>' +
          '<stop offset=".48" stop-color="#020906" stop-opacity=".52"/>' +
          '<stop offset=".74" stop-color="#020906" stop-opacity=".74"/>' +
          '<stop offset=".9" stop-color="#010503" stop-opacity=".88"/>' +
          '<stop offset="1" stop-color="#010503" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-far-cut" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#0E2A1D" stop-opacity="0"/>' +
          '<stop offset=".3" stop-color="#0B2016" stop-opacity=".34"/>' +
          '<stop offset=".62" stop-color="#0B2016" stop-opacity=".62"/>' +
          '<stop offset=".86" stop-color="#08180F" stop-opacity=".82"/>' +
          '<stop offset="1" stop-color="#08180F" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-far-floor" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#04100A" stop-opacity="0"/>' +
          '<stop offset=".45" stop-color="#04100A" stop-opacity=".42"/>' +
          '<stop offset="1" stop-color="#030D07" stop-opacity=".86"/>' +
        '</linearGradient>' +
        '<filter id="e-blur-far" x="-6%" y="-30%" width="112%" height="180%">' +
          '<feGaussianBlur stdDeviation="1.5"/>' +
        '</filter>' +
        '<filter id="e-blur-far-ridge" x="-10%" y="-80%" width="120%" height="300%">' +
          '<feGaussianBlur stdDeviation="6"/>' +
        '</filter>' +
      '</defs>' +
      '<g id="e-far">' +
        '<rect x="-80" y="330" width="1760" height="190" fill="url(#e-grad-far-haze)"/>' +
        /* 远山：两条极缓山脊 + 山脚雾 */
        '<g filter="url(#e-blur-far-ridge)">' +
          '<path d="M-80 452 C120 402 236 388 356 400 C470 412 536 440 640 434 C742 428 806 394 906 388 ' +
            'C1008 382 1072 412 1168 426 C1272 440 1360 414 1450 400 C1528 388 1608 396 1700 410 ' +
            'L1700 528 L-80 528 Z" fill="#0B2216" opacity=".55"/>' +
          '<path d="M-80 492 C96 464 200 456 320 466 C430 476 520 494 640 490 C760 486 860 460 980 458 ' +
            'C1104 456 1200 484 1320 486 C1420 488 1520 470 1700 464 L1700 552 L-80 552 Z" fill="#071A11" opacity=".6"/>' +
        '</g>' +
        '<g filter="url(#e-blur-far)">' + back + front + '</g>' +
        '<rect x="-80" y="430" width="1760" height="230" fill="url(#e-grad-far-cut)" opacity=".96"/>' +
        '<rect x="-80" y="470" width="1760" height="230" fill="url(#e-grad-far-floor)"/>' +
        '<rect x="-80" y="386" width="1760" height="300" fill="url(#e-grad-far-deep)"/>' +
      '</g>';
  })();

  /* ═══════════════════════════════════════════════════════════════════════
     4. MID · 中景森林 + 温室玻璃骨架   depth .28
     空间顺序（后 → 前）：
       玻璃外的树 → 玻璃面/空气 → 后墙竖棂 → 拱肋与放射隔条 → 玻璃反光
       → 玻璃内侧垂藤/蕨 → 顶部树冠暗块 → 左缘大树干（前景遮挡）
     ═══════════════════════════════════════════════════════════════════════ */
  const MID = (function () {
    /* 三道拱：从"贴墙的那道"到"从头顶掠过去的那道"，越近越大 → 透视 */
    const A1 = [[130, 470], [250, 300], [480, 205], [800, 200], [1120, 205], [1350, 300], [1470, 470]];
    const A2 = [[16, 432], [166, 214], [458, 96], [800, 90], [1142, 96], [1434, 214], [1584, 432]];
    const A3 = [[-96, 392], [56, 112], [432, -22], [800, -28], [1168, -22], [1544, 112], [1696, 392]];

    /* 放射状玻璃隔条：同一 t 上连接两道拱 */
    function bars(pA, pB, n, baseOp) {
      let s = '';
      for (let i = 1; i < n; i++) {
        const t = i / n;
        const a = arcPoint(pA, t), b = arcPoint(pB, t);
        const x = (a[0] + b[0]) / 2;
        /* 中间（主体区）弱、两侧强 */
        const edge = Math.min(1, Math.min(x, 1600 - x) / 380);
        /* 文字区（x>900）不许有结构细节：越往右越弱 */
        const txt = x > 880 ? Math.max(0, 1 - (x - 880) / 360) : 1;
        const op = baseOp * (0.22 + 0.78 * edge) * txt;
        s += '<path d="M' + f1(a[0]) + ' ' + f1(a[1]) + 'L' + f1(b[0]) + ' ' + f1(b[1]) +
          '" stroke="url(#e-grad-rib)" stroke-width="1.3" opacity="' + f2(op) + '"/>';
      }
      return s;
    }

    /* 玻璃外的树：细长、低对比，只在两侧成林 */
    function trunk(x, w, top, base, fill) {
      const h = w / 2, ext = (base - top);
      return '<path d="M' + f1(x - h) + ' ' + f1(base) +
        'C' + f1(x - h * 1.25) + ' ' + f1(base - ext * 0.30) + ' ' + f1(x - h * 0.86) + ' ' + f1(base - ext * 0.62) +
        ' ' + f1(x - h * 0.62) + ' ' + f1(top) +
        'L' + f1(x + h * 0.56) + ' ' + f1(top) +
        'C' + f1(x + h * 0.80) + ' ' + f1(base - ext * 0.60) + ' ' + f1(x + h * 1.16) + ' ' + f1(base - ext * 0.32) +
        ' ' + f1(x + h * 1.30) + ' ' + f1(base) + 'Z" fill="' + fill + '"/>';
    }
    let outTrees = '';
    [[58, 34, 150], [122, 22, 96], [186, 30, 214], [262, 18, 132], [330, 26, 176], [404, 16, 108]]
      .forEach(function (t) { outTrees += trunk(t[0], t[1], t[2], 672, '#123527'); });
    [[1058, 18, 208], [1116, 14, 132], [1512, 28, 176], [1576, 20, 118]]
      .forEach(function (t) { outTrees += trunk(t[0], t[1], t[2], 672, '#0F2E20'); });

    /* 玻璃内侧垂藤（挂在顶部树冠下缘） */
    function vine(x, y, len, bend, w) {
      const y1 = y + len;
      let s = '<path d="M' + f1(x) + ' ' + f1(y) +
        'C' + f1(x + bend) + ' ' + f1(y + len * 0.36) + ' ' + f1(x - bend * 0.55) + ' ' + f1(y + len * 0.70) +
        ' ' + f1(x + bend * 0.4) + ' ' + f1(y1) + '" fill="none" stroke="#2A5340" stroke-width="' + w +
        '" stroke-linecap="round" opacity=".55"/>';
      for (let k = 1; k <= 4; k++) {
        const ly = y + len * (0.20 + k * 0.17);
        const lx = x + bend * 0.2;
        s += '<path d="M' + f1(lx) + ' ' + f1(ly) + 'q' + (k % 2 ? 13 : -13) + ' 7 ' + (k % 2 ? 20 : -20) + ' 19" ' +
          'fill="none" stroke="#2C5844" stroke-width="1.4" opacity=".26"/>';
      }
      return s;
    }
    let vines = '';
    vines += vine(336, 128, 158, -12, 2.2);
    vines += vine(412, 104, 132, 9, 1.8);
    vines += vine(706, 96, 176, -14, 2.0);
    vines += vine(1016, 112, 120, 10, 1.6);

    /* 玻璃内侧底部蕨（中景，比近景虚一点、淡一点） */
    function fern(x, y, len, ang, w, fill, op, bend) {
      const rad = ang * Math.PI / 180;
      const ex = x + Math.cos(rad) * len, ey = y + Math.sin(rad) * len;
      const cx = x + Math.cos(rad) * len * 0.52 - Math.sin(rad) * bend;
      const cy = y + Math.sin(rad) * len * 0.52 + Math.cos(rad) * bend;
      const n = 10, L = [], Rt = [];
      for (let i = 0; i <= n; i++) {
        const t = i / n, mt = 1 - t;
        const px = mt * mt * x + 2 * mt * t * cx + t * t * ex;
        const py = mt * mt * y + 2 * mt * t * cy + t * t * ey;
        const tx = 2 * mt * (cx - x) + 2 * t * (ex - cx);
        const ty = 2 * mt * (cy - y) + 2 * t * (ey - cy);
        const tl = Math.hypot(tx, ty) || 1;
        const nx = -ty / tl, ny = tx / tl;
        const hh = w * (1 - t * 0.88) * (i === 0 ? 0.18 : (i % 2 ? 1 : 0.5));
        L.push([px + nx * hh, py + ny * hh]);
        Rt.push([px - nx * hh, py - ny * hh]);
      }
      let d = 'M' + f1(L[0][0]) + ' ' + f1(L[0][1]);
      for (let i = 1; i < L.length; i++) d += 'L' + f1(L[i][0]) + ' ' + f1(L[i][1]);
      for (let i = Rt.length - 1; i >= 0; i--) d += 'L' + f1(Rt[i][0]) + ' ' + f1(Rt[i][1]);
      return '<path d="' + d + 'Z" fill="' + fill + '" opacity="' + op + '"/>';
    }
    let ferns = '';
    [[96, 668, 132, -68, -16], [168, 676, 158, -56, -12], [244, 684, 116, -74, -8],
     [318, 676, 142, -62, -12], [386, 668, 104, -80, -6]]
      .forEach(function (a) { ferns += fern(a[0], a[1], a[2], a[3], a[2] * 0.15, '#0A1D14', 0.9, a[4]); });
    [[1188, 664, 128, -112, 12], [1262, 672, 152, -104, 10], [1344, 680, 112, -120, 8],
     [1432, 670, 138, -98, 10], [1520, 662, 106, -126, 6]]
      .forEach(function (a) { ferns += fern(a[0], a[1], a[2], a[3], a[2] * 0.15, '#08180F', 0.9, a[4]); });

    /* ── 横檩：2 道，按柱子断开，粗细/明度不同 ── */
    let purlins = '';
    (function () {
      /* 只画在两侧开间：中央留空（主体区干净），也保证横檩不横贯全幅 */
      const rows = [
        { y: 246, w: 3.2, segs: [[86, 246], [300, 408], [1244, 1368], [1420, 1524]] },
        { y: 352, w: 2.4, segs: [[110, 320], [1230, 1330], [1440, 1512]] },
        { y: 168, w: 2.0, segs: [[92, 250], [1450, 1520]] }
      ];
      for (let r = 0; r < rows.length; r++) {
        const row = rows[r];
        for (let i = 0; i < row.segs.length; i++) {
          const A = row.segs[i][0], B = row.segs[i][1];
          purlins += '<path d="M' + A + ' ' + row.y + 'L' + B + ' ' + (row.y + 3) +
            '" stroke="#020806" stroke-width="' + row.w + '" opacity=".95" fill="none" stroke-linecap="round"/>';
          purlins += '<path d="M' + A + ' ' + (row.y - 1.2) + 'L' + B + ' ' + (row.y + 1.8) +
            '" stroke="#8FD9B4" stroke-width=".9" opacity=".16" fill="none"/>';
        }
      }
    })();

    /* ── 斜撑：竖棂与檐口之间的 X 撑 ── */
    let braces = '';
    (function () {
      const sets = [[92, 394, 470, 560], [246, 470, 470, 560],
                    [1022, 1178, 470, 560], [1490, 1332, 470, 560]];
      for (let i = 0; i < sets.length; i++) {
        const a = sets[i];
        braces += '<path d="M' + a[0] + ' ' + a[1] + 'L' + a[1] + ' ' + a[3] + '" stroke="#020806" ' +
          'stroke-width="2.4" opacity=".8"/>';
        braces += '<path d="M' + a[1] + ' ' + a[3] + 'L' + a[0] + ' ' + a[2] + '" stroke="#020806" ' +
          'stroke-width="1.8" opacity=".6"/>';
      }
      braces += '<path d="M520 470L520 512M980 470L980 512" stroke="#06140D" stroke-width="2" opacity=".5"/>';
    })();

    /* ── 右侧针叶剪影：4 棵，只落在文字矩形之外 ── */
    let rightConifers = '';
    (function () {
      function fir(x, base, h, w, skew) {
        const half = w / 2, tier = [[x, base - h]];
        for (let i = 0; i < 4; i++) {
          const t = 0.30 + (i / 4) * 0.62;
          const yy = base - h * (1 - t), ww = half * Math.pow(t, 0.78);
          tier.push([x + ww, yy + h * 0.03], [x + ww * 0.46, yy - h * 0.055]);
        }
        tier.push([x + half * 0.3, base - h * 0.02], [x + half * 0.09, base]);
        const left = [[x - half * 0.09, base], [x - half * 0.3, base - h * 0.02]];
        for (let i = 3; i >= 0; i--) {
          const t = 0.30 + (i / 4) * 0.62;
          const yy = base - h * (1 - t), ww = half * Math.pow(t, 0.78);
          left.push([x - ww * 0.46, yy - h * 0.055], [x - ww, yy + h * 0.03]);
        }
        let d = 'M' + f1(tier[0][0]) + ' ' + f1(tier[0][1]);
        for (let i = 1; i < tier.length; i++) d += 'L' + f1(tier[i][0]) + ' ' + f1(tier[i][1]);
        for (let i = 0; i < left.length; i++) d += 'L' + f1(left[i][0]) + ' ' + f1(left[i][1]);
        const tf = skew ? ' transform="translate(' + f1(x) + ' 0) skewX(' + skew + ') translate(' + f1(-x) + ' 0)"' : '';
        return '<path d="' + d + 'Z" fill="#07180F" opacity="' + f2(0.72 + rnd() * 0.2) + '"' + tf + '/>';
      }
      /* 中心 x∈[1140,1420] ∧ y∈[225,470] 是文字区：树只落在 1140 左、1420 右 */
      rightConifers += fir(1094, 520, 168, 80, 0);
      rightConifers += fir(1078, 516, 112, 52, -7);
      rightConifers += fir(1478, 522, 196, 92, 7);
      rightConifers += fir(1508, 518, 132, 60, 0);
      rightConifers += fir(1532, 566, 88, 40, 0);
    })();

    /* ── 远桌面 / 石阶：一整条台面，中间靠横向渐变淡出（不成"两块亮条"） ── */
    let farStep = '';
    (function () {
      farStep += '<path d="M-60 512 C220 502 480 498 760 498 C1040 498 1300 502 1660 514 ' +
        'L1660 606 C1300 594 1040 590 760 590 C480 590 220 594 -60 606 Z" fill="url(#e-grad-step-top)"/>';
      farStep += '<path d="M-60 606 C220 594 480 590 760 590 C1040 590 1300 594 1660 606 ' +
        'L1660 624 C1300 612 1040 608 760 608 C480 608 220 612 -60 624 Z" fill="#04100A" opacity=".7"/>';
      for (let i = 0; i < 10; i++) {
        farStep += '<path d="' + blob(rg(-40, 1640), 528 + rg(-10, 10), rg(48, 96), rg(6, 12), 9, 0.5) +
          '" fill="#0E2A1C" opacity=".45"/>';
      }
      for (let i = 0; i < 9; i++) {
        farStep += '<path d="' + blob(rg(20, 1580), 566 + rg(-10, 10), rg(7, 15), rg(4, 8), 6, 0.45) +
          '" fill="#0A2015" opacity=".8"/>';
      }
    })();

    /* 右侧 8 只萤火：只落在 y>480（避开文字安全区 y 225–470） */
    let fireflies = '';
    const ffSeed = [[1064, 512], [1102, 566], [1156, 604], [1218, 556],
                    [1290, 612], [1352, 548], [1430, 596], [1478, 520]];
    for (let i = 0; i < ffSeed.length; i++) {
      const f = ffSeed[i], rr = 1.4 + rnd() * 1.5;
      fireflies += '<circle cx="' + f[0] + '" cy="' + f[1] + '" r="' + f2(rr) + '" fill="#E8FFD0" opacity=".62"/>';
      fireflies += '<circle cx="' + f[0] + '" cy="' + f[1] + '" r="' + f2(rr * 4.5) + '" fill="#B9F2C8" opacity=".09"/>';
    }

    return '' +
      '<defs>' +
        /* 玻璃只做"薄光"，不做奶白膜：整体透明度按 lead 要求再降 40%+ */
        '<linearGradient id="e-grad-glass" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#040C07" stop-opacity="0"/>' +
          '<stop offset=".10" stop-color="#06110B" stop-opacity=".16"/>' +
          '<stop offset=".26" stop-color="#07140D" stop-opacity=".12"/>' +
          '<stop offset=".44" stop-color="#081710" stop-opacity=".10"/>' +
          '<stop offset=".58" stop-color="#081710" stop-opacity=".10"/>' +
          '<stop offset=".74" stop-color="#06120B" stop-opacity=".16"/>' +
          '<stop offset=".88" stop-color="#04100A" stop-opacity=".30"/>' +
          '<stop offset="1" stop-color="#04100A" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<radialGradient id="e-grad-quiet" cx="50%" cy="50%" r="50%">' +
          '<stop offset="0" stop-color="#2B6247" stop-opacity=".03"/>' +
          '<stop offset=".55" stop-color="#1F4E38" stop-opacity=".015"/>' +
          '<stop offset="1" stop-color="#16382A" stop-opacity="0"/>' +
        '</radialGradient>' +
        '<linearGradient id="e-grad-mullion-l" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#0B1D13" stop-opacity="0"/>' +
          '<stop offset=".16" stop-color="#040D08" stop-opacity=".9"/>' +
          '<stop offset=".55" stop-color="#030B07" stop-opacity=".88"/>' +
          '<stop offset=".88" stop-color="#020806" stop-opacity=".6"/>' +
          '<stop offset="1" stop-color="#06120B" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-mullion-r" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#16382A" stop-opacity="0"/>' +
          '<stop offset=".18" stop-color="#1D3D2C" stop-opacity=".30"/>' +
          '<stop offset=".58" stop-color="#1A3727" stop-opacity=".22"/>' +
          '<stop offset=".9" stop-color="#142E20" stop-opacity=".16"/>' +
          '<stop offset="1" stop-color="#122C1E" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-rib" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="1520" y2="0">' +
          '<stop offset="0" stop-color="#8FD9B4" stop-opacity=".50"/>' +
          '<stop offset=".18" stop-color="#6FBE97" stop-opacity=".34"/>' +
          '<stop offset=".40" stop-color="#43805F" stop-opacity=".24"/>' +
          '<stop offset=".62" stop-color="#356B51" stop-opacity=".17"/>' +
          '<stop offset=".84" stop-color="#2A5641" stop-opacity=".10"/>' +
          '<stop offset="1" stop-color="#224632" stop-opacity=".06"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-member" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="1520" y2="0">' +
          '<stop offset="0" stop-color="#061309" stop-opacity=".88"/>' +
          '<stop offset=".30" stop-color="#050F0A" stop-opacity=".84"/>' +
          '<stop offset=".62" stop-color="#040D08" stop-opacity=".80"/>' +
          '<stop offset="1" stop-color="#030B07" stop-opacity=".74"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-beam" gradientUnits="userSpaceOnUse" x1="60" y1="0" x2="1540" y2="0">' +
          '<stop offset="0" stop-color="#79C6A4" stop-opacity=".26"/>' +
          '<stop offset=".20" stop-color="#4E8F70" stop-opacity=".16"/>' +
          '<stop offset=".42" stop-color="#356351" stop-opacity=".055"/>' +
          '<stop offset=".66" stop-color="#2E5546" stop-opacity=".05"/>' +
          '<stop offset=".86" stop-color="#3A6B55" stop-opacity=".085"/>' +
          '<stop offset="1" stop-color="#478068" stop-opacity=".13"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-sheen" x1="0" y1="0" x2="1" y2="1">' +
          '<stop offset="0" stop-color="#BFEBD8" stop-opacity="0"/>' +
          '<stop offset=".5" stop-color="#DCFFF0" stop-opacity=".075"/>' +
          '<stop offset="1" stop-color="#BFEBD8" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-shaft" x1="0" y1="0" x2="1" y2="1">' +
          '<stop offset="0" stop-color="#BFF0D6" stop-opacity=".10"/>' +
          '<stop offset=".55" stop-color="#9FD8B7" stop-opacity=".045"/>' +
          '<stop offset="1" stop-color="#7CAF96" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-canopy" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#030D07" stop-opacity=".98"/>' +
          '<stop offset=".48" stop-color="#05130B" stop-opacity=".95"/>' +
          '<stop offset=".82" stop-color="#071A10" stop-opacity=".80"/>' +
          '<stop offset="1" stop-color="#081C12" stop-opacity=".42"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-bottom" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#030C07" stop-opacity="0"/>' +
          '<stop offset=".20" stop-color="#030C07" stop-opacity=".22"/>' +
          '<stop offset=".44" stop-color="#020A06" stop-opacity=".55"/>' +
          '<stop offset=".66" stop-color="#020A06" stop-opacity=".76"/>' +
          '<stop offset=".86" stop-color="#010503" stop-opacity=".9"/>' +
          '<stop offset="1" stop-color="#010503" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-rimlight" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="640">' +
          '<stop offset="0" stop-color="#A8E6C6" stop-opacity=".40"/>' +
          '<stop offset=".45" stop-color="#7FD0A8" stop-opacity=".24"/>' +
          '<stop offset="1" stop-color="#7FD0A8" stop-opacity=".03"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-depth-r" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#2A5B42" stop-opacity=".34"/>' +
          '<stop offset=".5" stop-color="#24503A" stop-opacity=".26"/>' +
          '<stop offset="1" stop-color="#173A28" stop-opacity=".10"/>' +
        '</linearGradient>' +
        '<radialGradient id="e-grad-moon-r" cx="50%" cy="50%" r="50%">' +
          '<stop offset="0" stop-color="#7CAF96" stop-opacity=".065"/>' +
          '<stop offset=".45" stop-color="#5E9A78" stop-opacity=".035"/>' +
          '<stop offset="1" stop-color="#7CAF96" stop-opacity="0"/>' +
        '</radialGradient>' +
        '<linearGradient id="e-grad-step-top" gradientUnits="userSpaceOnUse" x1="0" y1="506" x2="0" y2="612">' +
          '<stop offset="0" stop-color="#24503A" stop-opacity="0"/>' +
          '<stop offset=".24" stop-color="#224A35" stop-opacity=".5"/>' +
          '<stop offset=".54" stop-color="#1E4433" stop-opacity=".62"/>' +
          '<stop offset=".80" stop-color="#173A28" stop-opacity=".48"/>' +
          '<stop offset="1" stop-color="#0E2418" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-scenefog" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#2A5B44" stop-opacity="0"/>' +
          '<stop offset=".34" stop-color="#2A5B44" stop-opacity=".10"/>' +
          '<stop offset=".62" stop-color="#204E3A" stop-opacity=".13"/>' +
          '<stop offset="1" stop-color="#173C2B" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-fog-r" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#1E442F" stop-opacity="0"/>' +
          '<stop offset=".5" stop-color="#1E442F" stop-opacity=".16"/>' +
          '<stop offset="1" stop-color="#16382A" stop-opacity="0"/>' +
        '</linearGradient>' +
        '<filter id="e-blur-mid" x="-14%" y="-18%" width="128%" height="136%">' +
          '<feGaussianBlur stdDeviation="6"/>' +
        '</filter>' +
        '<filter id="e-blur-mid-soft" x="-10%" y="-12%" width="120%" height="124%">' +
          '<feGaussianBlur stdDeviation="2.2"/>' +
        '</filter>' +
        '<filter id="e-blur-hair" x="-4%" y="-40%" width="108%" height="180%">' +
          '<feGaussianBlur stdDeviation="0.8"/>' +
        '</filter>' +
        '<filter id="e-blur-canopy" x="-8%" y="-30%" width="116%" height="160%">' +
          '<feGaussianBlur stdDeviation="10"/>' +
        '</filter>' +
        '<filter id="e-blur-depth" x="-14%" y="-26%" width="128%" height="152%">' +
          '<feGaussianBlur stdDeviation="30"/>' +
        '</filter>' +
        '<filter id="e-blur-leaf" x="-20%" y="-24%" width="140%" height="148%">' +
          '<feGaussianBlur stdDeviation="7"/>' +
        '</filter>' +
      '</defs>' +
      '<g id="e-mid">' +

        /* ① 玻璃外的树（在玻璃之后，低对比） */
        '<g id="e-mid-trees-out" filter="url(#e-blur-mid-soft)" opacity=".88">' + outTrees + '</g>' +

        /* ①b 右侧纵深：月晕渐层 + 针叶剪影（避开 x1140–1420 ∧ y225–470） */
        '<g id="e-mid-rightdepth">' +
          '<ellipse cx="1408" cy="352" rx="290" ry="230" fill="url(#e-grad-moon-r)"/>' +
          '<g filter="url(#e-blur-mid-soft)" opacity=".92">' + rightConifers + '</g>' +
        '</g>' +

        /* ② 玻璃面 + 中央空气感 */
        '<rect id="e-mid-glass" x="-80" y="-80" width="1760" height="770" fill="url(#e-grad-glass)"/>' +
        '<ellipse cx="772" cy="452" rx="470" ry="286" fill="url(#e-grad-quiet)"/>' +

        /* ③ 后墙竖棂：从地面到 springline（y≈470），左亮右暗 */
        '<g id="e-mid-mullions-l">' +
          '<rect x="90" y="456" width="10" height="216" fill="url(#e-grad-mullion-l)"/>' +
          '<rect x="88" y="470" width="2.2" height="196" fill="#9FE8C2" opacity=".26"/>' +
          '<rect x="246" y="456" width="9" height="216" fill="url(#e-grad-mullion-l)"/>' +
          '<rect x="244" y="470" width="1.8" height="196" fill="#9FE8C2" opacity=".15"/>' +
          '<rect x="394" y="456" width="8" height="216" fill="url(#e-grad-mullion-l)" opacity=".9"/>' +
          '<rect x="392" y="470" width="1.5" height="196" fill="#9FE8C2" opacity=".10"/>' +
        '</g>' +
        '<g id="e-mid-mullions-r">' +
          '<rect x="1024" y="456" width="9" height="216" fill="url(#e-grad-mullion-r)"/>' +
          '<rect x="1178" y="456" width="8" height="216" fill="url(#e-grad-mullion-r)"/>' +
          '<rect x="1332" y="456" width="9" height="216" fill="url(#e-grad-mullion-r)"/>' +
          '<rect x="1486" y="456" width="10" height="216" fill="url(#e-grad-mullion-r)" opacity=".8"/>' +
        '</g>' +

        /* ④ 玻璃屋面：三道拱肋（带厚度）+ 放射隔条 + 檐口横梁 */
        '<g id="e-mid-arch">' +
          /* 檐口横梁（墙/顶交界），中间淡：不抢主体 */
          '<path d="M120 468 C420 462 600 459 800 459 C1000 459 1180 462 1480 468" fill="none" ' +
            'stroke="url(#e-grad-beam)" stroke-width="2.6" stroke-linecap="round" opacity=".42"/>' +
          /* 结构肋只留两条：4u / #06140D / α.82 */
          '<path d="' + ribBand(A2, 4) + '" fill="#06140D" opacity=".82"/>' +
          '<path d="' + ribBand(A1, 4) + '" fill="#06140D" opacity=".82"/>' +
          /* 玻璃分格：短向细条，中间与右侧自动衰减 */
          bars(A1, A2, 16, 0.34) +
          /* 横檩 + 斜撑：整体 0.8px 柔化，避免边缘出现"硬台阶" */
          '<g filter="url(#e-blur-hair)">' + purlins + braces + '</g>' +
          /* 顶弧：拱顶主梁（比结构肋再实一档） */
          '<path d="' + ribBand(A2, 7) + '" fill="#040E09" opacity=".55"/>' +
        '</g>' +

        /* ⑤ 左上斜射光柱：给"空气"一个方向 */
        '<path filter="url(#e-blur-mid)" d="M60 -40 L330 -40 L188 700 L-40 700 Z" fill="url(#e-grad-shaft)" opacity=".62"/>' +

        /* ⑥ 玻璃反光条 */
        '<g id="e-mid-sheen" filter="url(#e-blur-mid)" opacity=".9">' +
          '<path d="M232 470 L342 60 L392 60 L282 470 Z" fill="url(#e-grad-sheen)"/>' +
          '<path d="M1266 458 L1360 108 L1376 108 L1284 458 Z" fill="url(#e-grad-sheen)" opacity=".28"/>' +
        '</g>' +

        /* ⑦ 玻璃内侧：垂藤 + 中景蕨 */
        '<g id="e-mid-vines" filter="url(#e-blur-mid-soft)">' + vines + '</g>' +
        '<g id="e-mid-ferns" filter="url(#e-blur-mid-soft)">' + ferns + '</g>' +

        /* ⑧ 顶部树冠：左侧垂下的枝叶 + 右侧压住文字区的大暗块 */
        '<g id="e-mid-canopy" filter="url(#e-blur-canopy)">' +
          /* 左上：从拱顶垂下的枝叶，边缘参差 */
          '<path d="M-100 -90 L980 -90 C968 6 920 62 852 84 C788 104 736 138 668 138 ' +
            'C602 138 556 102 494 118 C430 136 372 168 306 186 C238 204 168 156 104 196 ' +
            'C44 226 4 178 -100 226 Z" fill="url(#e-grad-canopy)"/>' +
          /* 右上：大块暗冠，底缘停在 y≈200（标题从 y≈234 起） */
          '<path d="M900 -90 L1700 -90 L1700 214 C1648 208 1612 176 1552 168 ' +
            'C1490 168 1444 146 1384 172 C1326 196 1296 96 1236 104 C1168 114 1118 92 1052 116 ' +
            'C992 138 946 60 920 12 Z" fill="url(#e-grad-canopy)" opacity=".97"/>' +
        '</g>' +

        /* ⑧b 右侧纵深：软冠层 + 低雾 + 萤火（x>1000 不补实体） */
        '<g id="e-mid-depth-r">' +
          /* 底层只留明度差（无轮廓） */
          '<g filter="url(#e-blur-depth)" opacity=".7">' +
            '<path d="M1030 296 C1160 264 1320 276 1460 300 C1552 316 1616 348 1650 392 ' +
              'C1600 470 1470 522 1330 528 C1190 534 1080 508 1010 462 Z" fill="url(#e-grad-depth-r)"/>' +
          '</g>' +
          /* 上层：3 片失焦叶形，给出"冠层"的轮廓线索 */
          '<g filter="url(#e-blur-leaf)" opacity=".5">' +
            '<path d="M1082 300 C1156 288 1230 306 1284 344 C1224 380 1146 386 1082 356 Z" ' +
              'fill="url(#e-grad-depth-r)"/>' +
            '<path d="M1240 346 C1300 330 1364 344 1408 378 C1358 410 1296 414 1244 392 Z" ' +
              'fill="url(#e-grad-depth-r)"/>' +
            '<path d="M1382 292 C1428 282 1478 296 1508 324 C1468 348 1420 350 1384 332 Z" ' +
              'fill="url(#e-grad-depth-r)" opacity=".85"/>' +
          '</g>' +
          '<rect x="960" y="472" width="680" height="76" fill="url(#e-grad-fog-r)" opacity=".9"/>' +
          '<g id="e-fireflies" style="mix-blend-mode:screen">' + fireflies + '</g>' +
        '</g>' +

        /* ⑨ 底部暗带：桌面后方压暗，让蛋糕/Tux 浮起来 */
        '<rect x="-80" y="418" width="1760" height="330" fill="url(#e-grad-bottom)"/>' +

        /* ⑨a 远桌面 / 石阶：mid 与 ground 之间的过渡层 */
        '<g id="e-mid-farstep">' + farStep + '</g>' +
        /* ⑨b 雾带 72px：纯渐变（两端 transparent），禁 mask / 禁滤镜 */
        '<rect x="-90" y="548" width="1780" height="72" fill="url(#e-grad-scenefog)"/>' +


        /* ⑩ 最前：左缘大树干（不规则轮廓 + 只在上半段给轮廓光） */
        '<g id="e-mid-trunk-front">' +
          '<path d="M-100 716 L-100 -60 L54 -60 C66 60 76 168 74 268 C72 372 62 500 44 620 ' +
            'C36 672 26 700 14 716 Z" fill="#040F09"/>' +
          '<path d="M54 -60 C66 60 76 168 74 268 C72 372 62 500 44 620" fill="none" ' +
            'stroke="url(#e-grad-rimlight)" stroke-width="3" opacity=".55"/>' +
          '<path d="M318 716 C322 600 330 520 322 436 C316 372 308 330 312 268 ' +
            'C316 196 306 118 296 -60 L378 -60 C388 60 392 132 388 200 ' +
            'C384 262 396 330 392 404 C388 486 398 600 418 716 Z" fill="#050F0A"/>' +
          /* 树皮的一两处断裂，避免直筒 */
          '<path d="M330 430 C344 424 356 420 368 422 L366 436 C354 434 342 438 330 444 Z" ' +
            'fill="#0A1C12" opacity=".7"/>' +
          '<path d="M334 210 C346 204 358 202 370 206 L368 218 C356 214 344 216 334 222 Z" ' +
            'fill="#0A1C12" opacity=".55"/>' +
          '<path d="M318 716 C324 560 328 420 320 268 C316 180 310 90 304 -60" fill="none" ' +
            'stroke="url(#e-grad-rimlight)" stroke-width="2.4" opacity=".38"/>' +
          /* 一根从树干伸出的横枝，打破竖直感 */
          '<path d="M74 214 C140 196 190 172 226 140 C236 132 246 130 254 134 ' +
            'C238 160 196 192 140 214 C118 223 96 228 74 228 Z" fill="#05110A" opacity=".9"/>' +
        '</g>' +
      '</g>';
  })();

  /* ═══════════════════════════════════════════════════════════════════════
     5. GROUND · 苔石桌面 / 桌沿 / 暖光池   depth .55
     接触线 y = 660；近沿是弧（中低旁高）而不是一条水平线 → 有透视。
     ═══════════════════════════════════════════════════════════════════════ */
  const GROUND = (function () {
    /* ── 台面苔斑：3 组主斑（不规则）+ 2 组辅斑，全部压在池外 ── */
    let mossL = '';
    (function () {
      const groups = [
        [214, 648, 128, 40, 1.0], [92, 690, 108, 32, 0.72],
        [566, 742, 92, 26, 0.8], [1300, 660, 140, 42, 0.95],
        [1520, 706, 104, 30, 0.62], [1080, 742, 84, 24, 0.5]
      ];
      for (let i = 0; i < groups.length; i++) {
        const m = groups[i];
        mossL += '<path d="' + blob(m[0], m[1], m[2], m[3], 11, 0.52) +
          '" fill="url(#e-grad-moss)" opacity="' + m[4] + '"/>';
      }
    })();

    /* ── 前缘碎石：沿石台边缘半埋的小石块 ── */
    let gravel = '';
    (function () {
      const g = [[128, 704, 22], [262, 716, 17], [438, 728, 14], [612, 736, 12],
                 [986, 736, 13], [1158, 730, 16], [1328, 720, 20], [1476, 708, 24]];
      for (let i = 0; i < g.length; i++) {
        const x = g[i][0], y = g[i][1], r = g[i][2];
        gravel += '<path d="' + blob(x, y, r, r * 0.55, 7, 0.4) + '" fill="#0B2216"/>';
        gravel += '<path d="' + blob(x - r * 0.2, y - r * 0.22, r * 0.55, r * 0.26, 6, 0.4) +
          '" fill="#2A5C44" opacity=".35"/>';
      }
    })();

    /* ── 板岩面：4 块低对比色面 + 收敛暗缝 ── */
    let slabs = '';
    (function () {
      const facets = [
        [[-90, 600], [430, 616], [400, 710], [-90, 706]],
        [[430, 616], [800, 614], [800, 726], [400, 710]],
        [[800, 614], [1170, 616], [1200, 712], [800, 726]],
        [[1170, 616], [1690, 600], [1690, 706], [1200, 712]]
      ];
      for (let i = 0; i < facets.length; i++) {
        const f = facets[i];
        slabs += '<path d="M' + f[0][0] + ' ' + f[0][1] + 'L' + f[1][0] + ' ' + f[1][1] +
          'L' + f[2][0] + ' ' + f[2][1] + 'L' + f[3][0] + ' ' + f[3][1] +
          'Z" fill="#2A5F45" opacity="' + (i % 2 ? '.055' : '.08') + '"/>';
      }
      const seams = [
        'M470 622 C430 660 396 692 372 726', 'M1180 622 C1222 660 1252 692 1274 726',
        'M676 616 C666 660 654 700 640 738', 'M924 616 C938 660 950 700 962 738',
        'M180 640 C140 674 104 700 74 726', 'M1436 640 C1478 674 1516 700 1546 726'
      ];
      for (let i = 0; i < seams.length; i++) {
        slabs += '<path d="' + seams[i] + '" fill="none" stroke="#04100A" stroke-width="' +
          (i < 2 ? 2.8 : 2.0) + '" opacity="' + (i < 2 ? '.55' : '.4') + '"/>';
        slabs += '<path d="' + seams[i] + '" fill="none" stroke="#5FA98A" stroke-width="1.2" ' +
          'opacity=".10" transform="translate(2.4 -1.4)"/>';
      }
    })();

    /* ── 前缘崩口 ── */
    let chips = '';
    [[166, 716, 26, 8], [604, 740, 22, 7], [1012, 740, 28, 8], [1368, 722, 24, 7]]
      .forEach(function (c) {
        chips += '<path d="' + blob(c[0], c[1], c[2], c[3], 6, 0.5) + '" fill="#030D08" opacity=".85"/>';
      });

    return '' +
      '<defs>' +
        /* 台面：后沿溶进暗里，前缘收在 y≈726 */
        '<linearGradient id="e-grad-surface" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#16402B" stop-opacity="0"/>' +
          '<stop offset=".12" stop-color="#17432C" stop-opacity=".16"/>' +
          '<stop offset=".26" stop-color="#17432C" stop-opacity=".36"/>' +
          '<stop offset=".40" stop-color="#16402B" stop-opacity=".58"/>' +
          '<stop offset=".54" stop-color="#16402B" stop-opacity=".8"/>' +
          '<stop offset=".66" stop-color="#15392A" stop-opacity=".94"/>' +
          '<stop offset="1" stop-color="#103020" stop-opacity="1"/>' +
        '</linearGradient>' +
        '<radialGradient id="e-grad-surface-lit" cx="50%" cy="50%" r="50%">' +
          '<stop offset="0" stop-color="#20452F" stop-opacity=".2"/>' +
          '<stop offset=".55" stop-color="#16341F" stop-opacity=".08"/>' +
          '<stop offset="1" stop-color="#1B4230" stop-opacity="0"/>' +
        '</radialGradient>' +
        /* 暖池：靠彩度层次，不靠提亮 */
        '<radialGradient id="e-grad-pool" gradientUnits="userSpaceOnUse" cx="800" cy="660" r="320">' +
          '<stop offset="0" stop-color="#E9A15E" stop-opacity=".5"/>' +
          '<stop offset=".26" stop-color="#D98C4E" stop-opacity=".34"/>' +
          '<stop offset=".56" stop-color="#A65C2C" stop-opacity=".17"/>' +
          '<stop offset=".82" stop-color="#6E3A1C" stop-opacity=".05"/>' +
          '<stop offset="1" stop-color="#4A2512" stop-opacity="0"/>' +
        '</radialGradient>' +
        /* 前缘倒角：中段暖、两侧冷 */
        '<linearGradient id="e-grad-rim" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="1600" y2="0">' +
          '<stop offset="0" stop-color="#4E8A6C" stop-opacity=".14"/>' +
          '<stop offset=".18" stop-color="#5E9A78" stop-opacity=".26"/>' +
          '<stop offset=".36" stop-color="#C09068" stop-opacity=".22"/>' +
          '<stop offset=".50" stop-color="#E0B183" stop-opacity=".32"/>' +
          '<stop offset=".64" stop-color="#C09068" stop-opacity=".22"/>' +
          '<stop offset=".82" stop-color="#5E9A78" stop-opacity=".22"/>' +
          '<stop offset="1" stop-color="#4E8A6C" stop-opacity=".12"/>' +
        '</linearGradient>' +
        /* 石台正面：上缘有一点点回光，往下沉到黑 */
        '<linearGradient id="e-grad-thick" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#123021" stop-opacity="1"/>' +
          '<stop offset=".22" stop-color="#0C2418" stop-opacity="1"/>' +
          '<stop offset=".62" stop-color="#06140C" stop-opacity="1"/>' +
          '<stop offset="1" stop-color="#020906" stop-opacity="1"/>' +
        '</linearGradient>' +
        '<linearGradient id="e-grad-thick-rim" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="1600" y2="0">' +
          '<stop offset="0" stop-color="#3E7A5C" stop-opacity=".10"/>' +
          '<stop offset=".30" stop-color="#B87A44" stop-opacity=".10"/>' +
          '<stop offset=".50" stop-color="#D9A874" stop-opacity=".16"/>' +
          '<stop offset=".70" stop-color="#B87A44" stop-opacity=".09"/>' +
          '<stop offset="1" stop-color="#3E7A5C" stop-opacity=".07"/>' +
        '</linearGradient>' +
        '<radialGradient id="e-grad-moss" cx="50%" cy="50%" r="50%">' +
          '<stop offset="0" stop-color="#0E2A1C" stop-opacity=".42"/>' +
          '<stop offset=".55" stop-color="#0A2015" stop-opacity=".19"/>' +
          '<stop offset="1" stop-color="#081A11" stop-opacity="0"/>' +
        '</radialGradient>' +
        '<linearGradient id="e-grad-floor" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#020806" stop-opacity="0"/>' +
          '<stop offset=".45" stop-color="#020806" stop-opacity=".16"/>' +
          '<stop offset="1" stop-color="#010503" stop-opacity=".34"/>' +
        '</linearGradient>' +
        '<radialGradient id="e-grad-corner" cx="50%" cy="50%" r="50%">' +
          '<stop offset="0" stop-color="#020806" stop-opacity=".58"/>' +
          '<stop offset=".62" stop-color="#020806" stop-opacity=".26"/>' +
          '<stop offset="1" stop-color="#020806" stop-opacity="0"/>' +
        '</radialGradient>' +
        '<filter id="e-blur-g" x="-14%" y="-50%" width="128%" height="200%">' +
          '<feGaussianBlur stdDeviation="3"/>' +
        '</filter>' +
        '<filter id="e-blur-g2" x="-20%" y="-80%" width="140%" height="260%">' +
          '<feGaussianBlur stdDeviation="9"/>' +
        '</filter>' +
      '</defs>' +
      '<g id="e-ground">' +

        /* ① 台后软影 */
        '<ellipse cx="800" cy="596" rx="740" ry="58" fill="#030C07" opacity=".7" filter="url(#e-blur-g2)"/>' +

        /* ② 台面（后沿起伏、前缘弧形收口） */
        '<path id="e-ground-surface" fill="url(#e-grad-surface)" ' +
          'd="M-90 600 C96 578 208 612 344 600 C470 590 566 616 700 608 ' +
          'C828 600 906 620 1030 610 C1160 600 1252 618 1372 606 C1478 596 1584 606 1690 592 ' +
          'L1690 700 C1400 722 1100 734 800 736 C500 734 200 722 -90 700 Z"/>' +

        /* ③ 板岩面 + 阴影场 + 苔斑 */
        '<g filter="url(#e-blur-g)">' + slabs + '</g>' +
        '<ellipse cx="800" cy="656" rx="400" ry="98" fill="url(#e-grad-surface-lit)" opacity=".22"/>' +
        '<ellipse cx="1706" cy="740" rx="270" ry="140" fill="url(#e-grad-corner)"/>' +
        '<ellipse cx="-106" cy="740" rx="270" ry="140" fill="url(#e-grad-corner)"/>' +
        '<g filter="url(#e-blur-g2)" opacity=".9">' + mossL + '</g>' +

        /* ④ 暖光池（契约 id） */
        '<ellipse id="e-ground-pool" cx="800" cy="660" rx="300" ry="112" fill="url(#e-grad-pool)" ' +
          'style="mix-blend-mode:screen"/>' +

        /* ⑤ 前缘：12u 亮边 → 5u 暗缝 → 石台正面 */
        '<path d="M-90 700 C200 722 500 734 800 736 C1100 734 1400 722 1690 700 ' +
          'L1690 713 C1400 735 1100 747 800 749 C500 747 200 735 -90 713 Z" ' +
          'fill="url(#e-grad-rim)"/>' +
        '<path d="M-90 704 C200 726 500 738 800 740 C1100 738 1400 726 1690 704" fill="none" ' +
          'stroke="url(#e-grad-rim)" stroke-width="1.6"/>' +
        '<path d="M-90 713 C200 735 500 747 800 749 C1100 747 1400 735 1690 713 ' +
          'L1690 719 C1400 741 1100 753 800 755 C500 753 200 741 -90 719 Z" fill="#030C07" opacity=".8"/>' +
        '<path id="e-ground-thickness" fill="url(#e-grad-thick)" ' +
          'd="M-90 713 C200 735 500 747 800 749 C1100 747 1400 735 1690 713 ' +
          'L1690 900 L-90 900 Z"/>' +
        '<path d="M-90 719 C200 741 500 753 800 755 C1100 753 1400 741 1690 719" fill="none" ' +
          'stroke="url(#e-grad-thick-rim)" stroke-width="3.2" opacity=".85"/>' +
        /* 正面的竖向断裂 */
        '<g opacity=".9">' +
          '<path d="M236 748 L220 900" stroke="#061510" stroke-width="3" opacity=".62"/>' +
          '<path d="M240 748 L226 900" stroke="#2E6349" stroke-width="1" opacity=".13"/>' +
          '<path d="M614 756 L604 900" stroke="#061510" stroke-width="2.6" opacity=".55"/>' +
          '<path d="M992 756 L1004 900" stroke="#061510" stroke-width="2.8" opacity=".55"/>' +
          '<path d="M988 756 L998 900" stroke="#2E6349" stroke-width="1" opacity=".11"/>' +
          '<path d="M1372 746 L1392 900" stroke="#061510" stroke-width="2.6" opacity=".5"/>' +
        '</g>' +
        '<g filter="url(#e-blur-g)">' + chips + '</g>' +

        /* ⑥ 前缘碎石（半埋） */
        '<g filter="url(#e-blur-g)" opacity=".95">' + gravel + '</g>' +

        /* ⑦ 底缘柔化 */
        '<rect x="-90" y="790" width="1780" height="120" fill="url(#e-grad-floor)"/>' +
      '</g>';
  })();

  /* ═══════════════════════════════════════════════════════════════════════
     6. NEAR · 近景草叶 / 蕨 / 苔石    depth 1.35
     只压两个下角，内侧留出 Tux 通道（左 ≤450，右 ≥1130）。
     ═══════════════════════════════════════════════════════════════════════ */
  const NEAR = (function () {
    function blade(x, base, h, bend, w, fill, op) {
      const tx = x + bend, ty = base - h;
      return '<path d="M' + f1(x - w) + ' ' + f1(base) +
        'Q' + f1(x + bend * 0.30) + ' ' + f1(base - h * 0.52) + ' ' + f1(tx) + ' ' + f1(ty) +
        'Q' + f1(x + bend * 0.36 + w * 0.8) + ' ' + f1(base - h * 0.50) + ' ' + f1(x + w) + ' ' + f1(base) +
        'Z" fill="' + fill + '" opacity="' + op + '"/>';
    }
    function fern(x, y, len, ang, w, fill, op, bend) {
      const rad = ang * Math.PI / 180;
      const ex = x + Math.cos(rad) * len, ey = y + Math.sin(rad) * len;
      const cx = x + Math.cos(rad) * len * 0.52 - Math.sin(rad) * bend;
      const cy = y + Math.sin(rad) * len * 0.52 + Math.cos(rad) * bend;
      const n = 12, L = [], Rt = [];
      for (let i = 0; i <= n; i++) {
        const t = i / n, mt = 1 - t;
        const px = mt * mt * x + 2 * mt * t * cx + t * t * ex;
        const py = mt * mt * y + 2 * mt * t * cy + t * t * ey;
        const tx = 2 * mt * (cx - x) + 2 * t * (ex - cx);
        const ty = 2 * mt * (cy - y) + 2 * t * (ey - cy);
        const tl = Math.hypot(tx, ty) || 1;
        const nx = -ty / tl, ny = tx / tl;
        const hh = w * (1 - t * 0.92) * (i === 0 ? 0.12 : (i % 2 ? 1 : 0.44));
        L.push([px + nx * hh, py + ny * hh]);
        Rt.push([px - nx * hh, py - ny * hh]);
      }
      let d = 'M' + f1(L[0][0]) + ' ' + f1(L[0][1]);
      for (let i = 1; i < L.length; i++) d += 'L' + f1(L[i][0]) + ' ' + f1(L[i][1]);
      for (let i = Rt.length - 1; i >= 0; i--) d += 'L' + f1(Rt[i][0]) + ' ' + f1(Rt[i][1]);
      return '<path d="' + d + 'Z" fill="' + fill + '" opacity="' + op + '"/>';
    }

    /* ── 左侧近景铸铁柱：柱身 + 柱头 + 两道箍 + 柱础 ── */
    let column = '';
    (function () {
      const x0 = 58, x1 = 176;
      column += '<path d="M62 -40 C76 140 84 330 80 500 C77 640 70 780 62 940 ' +
        'L172 940 C166 780 160 640 158 500 C156 330 154 140 160 -40 Z" fill="#03080 6"/>'
        .replace('#03080 6', '#03080A');
      /* 柱头 */
      column += '<path d="M40 -40 L196 -40 L184 46 L52 46 Z" fill="#040A08"/>';
      column += '<path d="M44 42 L192 42 L186 62 L50 62 Z" fill="#020706"/>';
      column += '<path d="M40 -40 L196 -40" stroke="#4E8A6C" stroke-width="2" opacity=".12"/>';
      /* 箍 */
      column += '<path d="M54 452 L188 452 L186 470 L56 470 Z" fill="#020706"/>';
      column += '<path d="M56 690 L186 690 L184 706 L58 706 Z" fill="#020706"/>';
      /* 柱础 */
      column += '<path d="M34 852 L200 852 L212 940 L22 940 Z" fill="#030908"/>';
      /* 受光侧一小段冷边（右缘） */
      column += '<path d="M160 -40 C160 140 158 330 160 500 C162 640 168 780 174 940" ' +
        'fill="none" stroke="#7FD0A8" stroke-width="3" opacity=".10"/>';
    })();

    /* ── 左下：不规则苔石 + 蕨丛 + 草 ── */
    let left = '';
    left += '<path d="' + blob(120, 846, 250, 150, 11, 0.30) + '" fill="#06120C"/>';
    left += '<path d="' + blob(46, 872, 190, 118, 9, 0.34) + '" fill="#050F0A"/>';
    /* 石面苔与受光边（用小块渐变而不是描边，避免"铁丝感"） */
    left += '<path d="' + blob(168, 742, 118, 40, 8, 0.45) + '" fill="#0A2015" opacity=".9"/>';
    left += '<path d="M-40 790 C24 762 96 748 168 748 C214 748 252 754 282 764 L276 776 ' +
      'C244 766 208 762 166 762 C98 762 30 776 -30 802 Z" fill="#12321F" opacity=".55"/>';
    [[-6, 792, 196, -54, 40, -34], [58, 828, 226, -48, 44, -26], [152, 796, 180, -62, 34, -28],
     [232, 818, 150, -70, 27, -20], [104, 878, 208, -42, 36, -36]]
      .forEach(function (a) { left += fern(a[0], a[1], a[2], a[3], a[4], '#050F0A', 0.96, a[5]); });
    for (let i = 0; i < 22; i++) {
      /* 平方分布：绝大多数挤在画外与角上，靠内侧只有少数 */
      const x = -80 + Math.pow(rnd(), 1.9) * 520;
      const h = rg(96, 262) * (1 - Math.min(0.35, Math.max(0, (x - 180) / 900)));
      left += blade(x, 908, h, rg(-64, 44), rg(4.6, 10.6), i % 3 ? '#050F0A' : '#07160E', rg(0.62, 0.96));
    }

    /* ── 右下：更高的蕨丛 + 角石 ── */
    let right = '';
    right += '<path d="' + blob(1520, 842, 250, 160, 11, 0.30) + '" fill="#050F0A"/>';
    right += '<path d="' + blob(1640, 880, 200, 130, 9, 0.34) + '" fill="#040C08"/>';
    right += '<path d="' + blob(1462, 744, 116, 38, 8, 0.45) + '" fill="#092016" opacity=".85"/>';
    right += '<path d="M1690 796 C1626 766 1554 750 1482 750 C1434 750 1396 756 1366 766 L1372 778 ' +
      'C1404 768 1440 764 1482 764 C1552 764 1620 778 1680 804 Z" fill="#0C2A1B" opacity=".5"/>';
    [[1700, 800, 214, -128, 44, 30], [1618, 832, 248, -120, 48, 24], [1508, 800, 200, -110, 38, 20],
     [1424, 828, 168, -102, 30, 16], [1340, 862, 214, -94, 34, 12], [1250, 884, 176, -86, 28, 8]]
      .forEach(function (a) { right += fern(a[0], a[1], a[2], a[3], a[4], '#040C08', 0.96, a[5]); });
    for (let i = 0; i < 24; i++) {
      const x = 1700 - Math.pow(rnd(), 1.9) * 520;
      const h = rg(112, 282) * (1 - Math.min(0.3, Math.max(0, (1500 - x) / 900)));
      right += blade(x, 912, h, rg(-46, 58), rg(4.8, 10.8), i % 3 ? '#040C08' : '#061309', rg(0.64, 0.97));
    }

    /* ── 底部草影：把下边框化开 ── */
    let fringe = '';
    for (let i = 0; i < 24; i++) {
      fringe += blade(rg(-40, 1650), 938, rg(30, 80), rg(-26, 26), rg(6, 13), '#030D08', rg(0.4, 0.72));
    }

    return '' +
      '<defs>' +
        '<filter id="e-blur-near" x="-10%" y="-14%" width="120%" height="128%">' +
          '<feGaussianBlur stdDeviation="1.4"/>' +
        '</filter>' +
        '<filter id="e-blur-near-soft" x="-14%" y="-20%" width="128%" height="140%">' +
          '<feGaussianBlur stdDeviation="3.2"/>' +
        '</filter>' +
      '</defs>' +
      '<g id="e-near">' +
        /* 左侧近景铸铁柱：虚焦、L*≤8、约占画面 5.6% */
        '<g id="e-near-column" filter="url(#e-blur-near-soft)" opacity=".97">' +
          column +
        '</g>' +
        '<g id="e-near-l" filter="url(#e-blur-near)">' + left + '</g>' +
        '<g id="e-near-r" filter="url(#e-blur-near)">' + right + '</g>' +
        '<g id="e-near-fringe" filter="url(#e-blur-near-soft)" opacity=".8">' + fringe + '</g>' +
      '</g>';
  })();

  /* ═══════════════════════════════════════════════════════════════════════
     7. FG · 极近景枝叶   depth 2.2
     只占左上 / 右缘 / 左下三块；形状要"认得出是叶子"，只是失焦。
     左上避让 caption（x 67–300 / y 40–62）：大团叶压在 x<200，沿上边缘的枝走 y<70。
     ═══════════════════════════════════════════════════════════════════════ */
  const FG = (function () {
    /* 单片叶子：两段弧 + 尖端，方向 deg */
    function leaf(x, y, len, ang, w) {
      const rad = ang * Math.PI / 180;
      const ex = x + Math.cos(rad) * len, ey = y + Math.sin(rad) * len;
      const nx = -Math.sin(rad), ny = Math.cos(rad);
      const c1x = x + Math.cos(rad) * len * 0.42 + nx * w, c1y = y + Math.sin(rad) * len * 0.42 + ny * w;
      const c2x = x + Math.cos(rad) * len * 0.42 - nx * w, c2y = y + Math.sin(rad) * len * 0.42 - ny * w;
      return 'M' + f1(x) + ' ' + f1(y) + 'Q' + f1(c1x) + ' ' + f1(c1y) + ' ' + f1(ex) + ' ' + f1(ey) +
        'Q' + f1(c2x) + ' ' + f1(c2y) + ' ' + f1(x) + ' ' + f1(y) + 'Z';
    }

    let leftTop = '';
    /* 主干：从画外伸进来的粗枝 */
    leftTop += '<path d="M-120 -40 C-20 6 84 22 182 40 C248 52 300 66 344 86" fill="none" ' +
      'stroke="#020806" stroke-width="16" stroke-linecap="round"/>';
    leftTop += '<path d="M-120 26 C-30 46 60 60 148 78 C206 90 250 106 286 128" fill="none" ' +
      'stroke="#020806" stroke-width="10" stroke-linecap="round" opacity=".9"/>';
    /* 叶簇：几片大叶 + 更深的一团 */
    leftTop += '<path d="' + blob(-18, 46, 238, 146, 10, 0.34) + '" fill="#020806" opacity=".97"/>';
    leftTop += '<path d="' + leaf(120, 60, 150, 34, 44) + '" fill="#020806" opacity=".9"/>';
    leftTop += '<path d="' + leaf(232, 76, 132, 46, 38) + '" fill="#020806" opacity=".82"/>';
    leftTop += '<path d="' + leaf(314, 92, 104, 62, 30) + '" fill="#020806" opacity=".7"/>';
    leftTop += '<path d="' + leaf(258, 40, 118, 102, 34) + '" fill="#030B07" opacity=".75"/>';
    /* 叶缘的冷光（失焦光边，让前景和背景分开） */
    leftTop += '<path d="M-70 96 C10 128 96 146 176 168" fill="none" stroke="#8FD9B4" stroke-width="7" opacity=".10"/>';

    let rightEdge = '';
    rightEdge += '<path d="M1660 40 C1606 96 1580 176 1578 262 C1576 344 1598 420 1640 470" fill="none" ' +
      'stroke="#020806" stroke-width="14" stroke-linecap="round"/>';
    rightEdge += '<path d="' + leaf(1596, 150, 128, 148, 40) + '" fill="#020806" opacity=".9"/>';
    rightEdge += '<path d="' + leaf(1588, 286, 116, 158, 36) + '" fill="#020806" opacity=".82"/>';
    rightEdge += '<path d="' + leaf(1610, 398, 96, 168, 30) + '" fill="#020806" opacity=".72"/>';
    rightEdge += '<path d="M1690 -20 C1640 20 1610 74 1604 128 L1640 132 C1648 80 1672 34 1710 6 Z" ' +
      'fill="#020806" opacity=".85"/>';

    /* 失焦光边：只沿叶子的上/外侧描一条很软的薄荷光，模拟 bokeh 边缘 */
    const rimTop =
      '<g filter="url(#e-blur-fg-rim)" opacity=".9">' +
        '<path d="M-40 40 C60 76 150 92 236 122" fill="none" stroke="#A8E6C6" stroke-width="9" opacity=".13"/>' +
        '<path d="M118 76 C158 50 206 34 248 30" fill="none" stroke="#A8E6C6" stroke-width="7" opacity=".10"/>' +
        '<path d="M-20 128 C46 156 104 176 152 204" fill="none" stroke="#A8E6C6" stroke-width="11" opacity=".075"/>' +
      '</g>';

    let bottomLeft = '';
    bottomLeft += '<path d="' + leaf(-20, 820, 250, 18, 86) + '" fill="#020806" opacity=".92"/>';
    bottomLeft += '<path d="' + leaf(60, 900, 220, -18, 74) + '" fill="#030B07" opacity=".85"/>';
    bottomLeft += '<path d="M-140 700 C-40 726 44 774 104 842 L40 890 C-10 838 -70 796 -140 774 Z" ' +
      'fill="#020806" opacity=".8"/>';

    return '' +
      '<defs>' +
        '<filter id="e-blur-fg" x="-30%" y="-30%" width="160%" height="160%">' +
          '<feGaussianBlur stdDeviation="3.2"/>' +
        '</filter>' +
        '<filter id="e-blur-fg-soft" x="-40%" y="-60%" width="180%" height="240%">' +
          '<feGaussianBlur stdDeviation="9"/>' +
        '</filter>' +
        '<filter id="e-blur-fg-rim" x="-30%" y="-80%" width="160%" height="260%">' +
          '<feGaussianBlur stdDeviation="7"/>' +
        '</filter>' +
      '</defs>' +
      '<g id="e-fg">' +
        '<g filter="url(#e-blur-fg)">' +
          leftTop + rightEdge + bottomLeft +
        '</g>' +
        rimTop +
        '<g filter="url(#e-blur-fg-soft)" opacity=".7">' +
          '<path d="' + blob(60, 30, 190, 110, 8, 0.3) + '" fill="#020806"/>' +
          '<path d="' + blob(1610, 300, 90, 220, 8, 0.3) + '" fill="#020806"/>' +
        '</g>' +
      '</g>';
  })();

  /* ═══════════════════════════════════════════════════════════════════════ */
  window.Assets.env = {
    sky: SKY,
    aurora: AURORA,
    far: FAR,
    mid: MID,
    ground: GROUND,
    near: NEAR,
    fg: FG
  };

  if (typeof console !== 'undefined' && console.debug) {
    console.debug('[env] layers', Object.keys(window.Assets.env).map(function (k) {
      return k + ':' + window.Assets.env[k].length;
    }).join('  '));
  }
})();
