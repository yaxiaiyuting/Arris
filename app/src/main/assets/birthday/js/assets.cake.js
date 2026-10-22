/* ─────────────────────────────────────────────────────────────
   assets.cake.js · 生日蛋糕 + 蜡烛（character-artist / task-3）

   契约：docs/CONTRACT.md §4。字符串由 main.js innerHTML 注入 #layer-cake。
   坐标：盘心 (800,660)，盘半径 128，瓷盘厚 8；
   蛋糕体 **220 × 96**（x 690–910，y 576–672；#cake-body 的 getBBox 就是 220×96）；
   蜡烛 46 / 56 / 50，wick 顶 / 火焰锚点 529 / 519 / 525。
   根 <g id="cake"> 无 transform，所有 id 都是场景绝对坐标。

   #wick-1..3 是烛芯（VFX 定位火焰锚点用，位于 #candle-N 内、#flame-unit-N 之前）。
   #flame-unit-N = 火苗根部锚点；内部 #flame-N 是外焰，.flame-core 是内焰。
   #glow-N / #cake-pool 用 screen 混合，强度交给 lighting。
   #smoke-N 默认 opacity=0，熄灭时由 motion/vfx 淡入。

   圆弧约定（实测）：左→右的前沿弧 = sweep 0；右→左的下沿弧 = sweep 1。
   ───────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  var svg = [
    '<defs>',
    '  <radialGradient id="kg-shadow" cx="50%" cy="50%" r="50%">',
    '    <stop offset="0" stop-color="#02100A" stop-opacity=".85"/>',
    '    <stop offset=".42" stop-color="#02100A" stop-opacity=".55"/>',
    '    <stop offset=".78" stop-color="#02100A" stop-opacity=".18"/>',
    '    <stop offset="1" stop-color="#02100A" stop-opacity="0"/>',
    '  </radialGradient>',
    '  <linearGradient id="kg-plate-top" x1=".18" y1="0" x2=".82" y2="1">',
    '    <stop offset="0" stop-color="#FFFDF4"/>',
    '    <stop offset=".4" stop-color="#F2E7CE"/>',
    '    <stop offset=".78" stop-color="#DDCBAB"/>',
    '    <stop offset="1" stop-color="#C3B08D"/>',
    '  </linearGradient>',
    '  <linearGradient id="kg-plate-side" x1="0" y1="0" x2="0" y2="1">',
    '    <stop offset="0" stop-color="#CDBD99"/>',
    '    <stop offset=".5" stop-color="#AA9A7C"/>',
    '    <stop offset="1" stop-color="#7A6C54"/>',
    '  </linearGradient>',
    '  <linearGradient id="kg-sponge" x1=".1" y1="0" x2=".9" y2="1">',
    '    <stop offset="0" stop-color="#FBEDCB"/>',
    '    <stop offset=".42" stop-color="#F2DAA9"/>',
    '    <stop offset=".8" stop-color="#DDB97E"/>',
    '    <stop offset="1" stop-color="#BE8F58"/>',
    '  </linearGradient>',
    '  <linearGradient id="kg-cream-band" x1="0" y1="0" x2="0" y2="1">',
    '    <stop offset="0" stop-color="#FDF4E2"/>',
    '    <stop offset=".55" stop-color="#F4E1B8"/>',
    '    <stop offset="1" stop-color="#DFC692"/>',
    '  </linearGradient>',
    /* 夹层定版：#CC6A63（资产端 L*55 / C*46 / h≈22）。
       暖光 screen 之后落回 L*66–72 / C*38–44 / h28–34 → 看得见的珊瑚红带。
       注意是"资产端要更暗更饱和"，不要按最终的奶油粉去配。 */
    '  <linearGradient id="kg-jam" x1="0" y1="0" x2="0" y2="1">',
    '    <stop offset="0" stop-color="#CC6A63"/>',
    '    <stop offset="1" stop-color="#C8625C"/>',
    '  </linearGradient>',
    '  <linearGradient id="kg-icing" x1=".12" y1="0" x2=".88" y2="1">',
    '    <stop offset="0" stop-color="#FBF0DC"/>',
    '    <stop offset=".38" stop-color="#F6E6C8"/>',
    '    <stop offset=".76" stop-color="#E9D2AC"/>',
    '    <stop offset="1" stop-color="#D8BC8E"/>',
    '  </linearGradient>',
    '  <linearGradient id="kg-icing-drip" x1="0" y1="0" x2="0" y2="1">',
    '    <stop offset="0" stop-color="#FFF8E7"/>',
    '    <stop offset=".62" stop-color="#FBEBCB"/>',
    '    <stop offset="1" stop-color="#E6CCA2"/>',
    '  </linearGradient>',
    '  <radialGradient id="kg-cherry" cx="36%" cy="30%" r="76%">',
    '    <stop offset="0" stop-color="#FF8A94"/>',
    '    <stop offset=".38" stop-color="#E23A50"/>',
    '    <stop offset=".78" stop-color="#A81F35"/>',
    '    <stop offset="1" stop-color="#6E1222"/>',
    '  </radialGradient>',
    '  <linearGradient id="kg-candle-1" x1="0" y1="0" x2="1" y2="0">',
    '    <stop offset="0" stop-color="#F8C8BC"/>',
    '    <stop offset=".42" stop-color="#F0A99B"/>',
    '    <stop offset="1" stop-color="#C97B6E"/>',
    '  </linearGradient>',
    '  <linearGradient id="kg-candle-2" x1="0" y1="0" x2="1" y2="0">',
    '    <stop offset="0" stop-color="#FFF8E8"/>',
    '    <stop offset=".42" stop-color="#FFE8C7"/>',
    '    <stop offset="1" stop-color="#D8B98C"/>',
    '  </linearGradient>',
    '  <linearGradient id="kg-candle-3" x1="0" y1="0" x2="1" y2="0">',
    '    <stop offset="0" stop-color="#D2EFDB"/>',
    '    <stop offset=".42" stop-color="#9FD3AC"/>',
    '    <stop offset="1" stop-color="#6A9E79"/>',
    '  </linearGradient>',
    '  <linearGradient id="kg-candle-top" x1="0" y1="0" x2="0" y2="1">',
    '    <stop offset="0" stop-color="#FFF8EA" stop-opacity=".55"/>',
    '    <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>',
    '  </linearGradient>',
    '  <linearGradient id="kg-flame" x1="0" y1="1" x2="0" y2="0">',
    '    <stop offset="0" stop-color="#FFE9B0"/>',
    '    <stop offset=".3" stop-color="#FFC46B"/>',
    '    <stop offset=".68" stop-color="#FF9A4C"/>',
    '    <stop offset="1" stop-color="#FF7A3C" stop-opacity="0"/>',
    '  </linearGradient>',
    '  <linearGradient id="kg-flame-core" x1="0" y1="1" x2="0" y2="0">',
    '    <stop offset="0" stop-color="#FFFFFF"/>',
    '    <stop offset=".45" stop-color="#FFF6D8"/>',
    '    <stop offset=".85" stop-color="#FFE29A"/>',
    '    <stop offset="1" stop-color="#FFD98A" stop-opacity="0"/>',
    '  </linearGradient>',
    '  <radialGradient id="kg-glow" cx="50%" cy="50%" r="50%">',
    '    <stop offset="0" stop-color="#FFE8C7" stop-opacity=".62"/>',
    '    <stop offset=".28" stop-color="#FFC46B" stop-opacity=".34"/>',
    '    <stop offset=".62" stop-color="#FF9A4C" stop-opacity=".12"/>',
    '    <stop offset="1" stop-color="#FF8A3C" stop-opacity="0"/>',
    '  </radialGradient>',
    '  <radialGradient id="kg-pool" cx="50%" cy="50%" r="50%">',
    '    <stop offset="0" stop-color="#FFC46B" stop-opacity=".34"/>',
    '    <stop offset=".42" stop-color="#FFA65C" stop-opacity=".16"/>',
    '    <stop offset=".74" stop-color="#FF8A4C" stop-opacity=".05"/>',
    '    <stop offset="1" stop-color="#FF8A4C" stop-opacity="0"/>',
    '  </radialGradient>',
    '  <linearGradient id="kg-spec" x1="0" y1="0" x2="1" y2="0">',
    '    <stop offset="0" stop-color="#FFF6E4" stop-opacity="0"/>',
    '    <stop offset=".42" stop-color="#FFF6E4" stop-opacity=".66"/>',
    '    <stop offset="1" stop-color="#FFF6E4" stop-opacity="0"/>',
    '  </linearGradient>',
    '  <linearGradient id="kg-wax" x1="0" y1="0" x2="0" y2="1">',
    '    <stop offset="0" stop-color="#FFFFFF" stop-opacity=".05"/>',
    '    <stop offset=".25" stop-color="#FFFFFF" stop-opacity=".34"/>',
    '    <stop offset=".8" stop-color="#FFFFFF" stop-opacity=".12"/>',
    '    <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>',
    '  </linearGradient>',
    '  <radialGradient id="kg-warm-face" cx="50%" cy="50%" r="50%">',
    '    <stop offset="0" stop-color="#FFB066" stop-opacity=".2"/>',
    '    <stop offset="1" stop-color="#FFB066" stop-opacity="0"/>',
    '  </radialGradient>',
    '  <clipPath id="kg-cc-1"><rect x="746" y="536" width="12" height="46" rx="5"/></clipPath>',
    '  <clipPath id="kg-cc-2"><rect x="794" y="526" width="12" height="56" rx="5"/></clipPath>',
    '  <clipPath id="kg-cc-3"><rect x="842" y="532" width="12" height="50" rx="5"/></clipPath>',
    '</defs>',

    '<g id="cake">',

    /* ── 桌面暖光池 ───────────────────────────────────────── */
    '<g id="cake-pool" data-pivot="800 674" style="mix-blend-mode:screen" opacity=".9">',
    '  <ellipse cx="800" cy="674" rx="200" ry="54" fill="url(#kg-pool)"/>',
    '  <ellipse cx="800" cy="670" rx="126" ry="34" fill="url(#kg-pool)" opacity=".7"/>',
    '</g>',

    /* ── 接触阴影 ─────────────────────────────────────────── */
    /* 接触阴影：撑到 rx 132 / 中心 .55，让蛋糕"压"在桌面上 */
    '<ellipse id="cake-shadow" data-pivot="800 676" cx="800" cy="674" rx="132" ry="21" fill="url(#kg-shadow)"/>',

    /* ── 盘子：半径 128，侧边厚 8 + 底部反射光 ────────────── */
    /* 白盘：rx 101 = 蛋糕体半径 110 × 0.92。
       盘比体小 → 两侧不再露出同心白环，只剩前沿一道薄唇（672→680）。 */
    '<g id="cake-plate" data-pivot="800 652">',
    '  <ellipse cx="800" cy="660" rx="99" ry="26" fill="url(#kg-plate-side)"/>',
    '  <ellipse cx="800" cy="652" rx="101" ry="27" fill="url(#kg-plate-top)"/>',
    '  <ellipse cx="800" cy="653" rx="86" ry="20" fill="#B8A581" opacity=".18"/>',
    '  <ellipse cx="800" cy="653" rx="78" ry="17" fill="#FFFBF0" opacity=".3"/>',
    '  <path d="M704 660A101 27 0 0 0 896 660" fill="none" stroke="#FFFDF6" stroke-width="2.4" stroke-linecap="round" opacity=".8"/>',
    '  <ellipse cx="800" cy="670" rx="84" ry="14" fill="#8A7A5C" opacity=".18"/>',
    /* 盘沿反射：只做左前一小段镜面（不整圈） */
    '  <path d="M716 668A101 27 0 0 0 780 678" fill="none" stroke="#FFFFFF" stroke-width="2.6" stroke-linecap="round" opacity=".45"/>',
    '  <path d="M724 664A101 27 0 0 0 772 674" fill="none" stroke="#FFF8E8" stroke-width="1.2" stroke-linecap="round" opacity=".3"/>',
    /* 底部反射光（桌面暖光打回瓷盘底沿） */
    '  <path d="M706 680A99 26 0 0 0 894 680" fill="none" stroke="#FFE8C7" stroke-width="2" stroke-linecap="round" opacity=".28"/>',
    '  <path d="M712 686A97 25 0 0 0 888 686" fill="none" stroke="#6E6047" stroke-width="1.4" stroke-linecap="round" opacity=".36"/>',
    '</g>',

    /* ── 蛋糕体：getBBox = 220 × 96（x 690–910 / y 576–672） ──
         奶油 / 18 窄果酱 / 奶油 / 海绵，全部按 rx110 ry24 的弧走 ── */
    '<g id="cake-body" data-pivot="800 612">',
    '  <path d="M690 576A110 24 0 0 0 910 576L910 648A110 24 0 0 1 690 648Z" fill="url(#kg-sponge)"/>',
    /* 上奶油层：紧贴糖霜下沿 */
    '  <path d="M690 576A110 24 0 0 0 910 576L910 596A110 24 0 0 1 690 596Z" fill="url(#kg-cream-band)"/>',
    /* 果酱：18 高窄带，coral → cream */
    '  <path d="M690 596A110 24 0 0 0 910 596L910 614A110 24 0 0 1 690 614Z" fill="url(#kg-jam)"/>',
    '  <path d="M690 596A110 24 0 0 0 910 596" fill="none" stroke="#D98D84" stroke-width="1.6" opacity=".6"/>',
    /* 1u 高光线：果酱表面的湿润反光 */
    '  <path d="M694 598.2A110 24 0 0 0 906 598.2" fill="none" stroke="#F7C4B8" stroke-width="1" opacity=".55"/>',
    '  <path d="M690 614A110 24 0 0 0 910 614" fill="none" stroke="#B25C56" stroke-width="1.4" opacity=".35"/>',
    /* 果酱下面再留一层奶油，再接海绵 */
    '  <path d="M690 614A110 24 0 0 0 910 614L910 620A110 24 0 0 1 690 620Z" fill="url(#kg-cream-band)"/>',
    '  <path d="M690 620A110 24 0 0 0 910 620" fill="none" stroke="#D9BE93" stroke-width="1.2" opacity=".32"/>',
    /* 右侧暗面 / 左侧受光 */
    '  <path d="M856 577C890 584 910 596 910 618C910 638 886 650 846 653C874 638 878 600 856 577Z" fill="#8C5F33" opacity=".2"/>',
    '  <path d="M690 592C690 620 708 640 742 649C710 647 690 632 690 610Z" fill="#FFF3D6" opacity=".2"/>',
    '  <g opacity=".9">',
    '    <rect x="726" y="634" width="4.8" height="1.8" rx=".9" fill="#F0A99B" transform="rotate(-18 728 635)"/>',
    '    <rect x="758" y="644" width="4.8" height="1.8" rx=".9" fill="#9FD3AC" transform="rotate(12 760 645)"/>',
    '    <rect x="862" y="638" width="4.8" height="1.8" rx=".9" fill="#FFD98A" transform="rotate(-8 864 639)"/>',
    '    <circle cx="828" cy="646" r="1.7" fill="#FFF7E7" opacity=".75"/>',
    '    <circle cx="794" cy="642" r="1.4" fill="#FFE8C7" opacity=".65"/>',
    '  </g>',
    '  <path d="M690 576A110 24 0 0 0 910 576" fill="none" stroke="#C79B62" stroke-width="1.6" opacity=".55"/>',
    '</g>',

    /* ── 糖霜：顶面 + 短奶油流（不盖住 18 高果酱带） ───────── */
    '<g id="cake-icing" data-pivot="800 575">',
    '  <ellipse cx="800" cy="575" rx="115" ry="25" fill="url(#kg-icing)"/>',
    '  <path d="M728 584C724 598 726 616 736 617C746 618 748 600 747 584Z" fill="url(#kg-icing-drip)"/>',
    '  <path d="M782 586C780 594 782 600 789 601C796 602 798 594 796 585Z" fill="url(#kg-icing-drip)"/>',
    '  <path d="M838 586C836 596 838 604 846 605C854 606 856 596 854 585Z" fill="url(#kg-icing-drip)"/>',
    '  <path d="M884 584C880 598 882 614 892 615C902 616 904 598 902 583Z" fill="url(#kg-icing-drip)"/>',
    '  <path d="M729 616q7 3 14 0" fill="none" stroke="#C9A97C" stroke-width="1.5" stroke-linecap="round" opacity=".38"/>',
    '  <path d="M782 600q7 3 14 0" fill="none" stroke="#C9A97C" stroke-width="1.3" stroke-linecap="round" opacity=".3"/>',
    '  <path d="M838 604q7 3 14 0" fill="none" stroke="#C9A97C" stroke-width="1.3" stroke-linecap="round" opacity=".3"/>',
    '  <path d="M885 614q7 3 14 0" fill="none" stroke="#C9A97C" stroke-width="1.5" stroke-linecap="round" opacity=".38"/>',
    /* 镜面高光带：宽 84（≤ 体宽 220 的 40%），两端淡出 */
    '  <ellipse cx="772" cy="566" rx="42" ry="7.5" fill="url(#kg-spec)" opacity=".85"/>',
    '  <path d="M738 562C754 556 786 554 802 558" fill="none" stroke="#FFFFFF" stroke-width="1.2" stroke-linecap="round" opacity=".28"/>',
    '  <path d="M687 587A115 25 0 0 0 913 587" fill="none" stroke="#B08F62" stroke-width="3" stroke-linecap="round" opacity=".26"/>',
    '  <path d="M689 583A115 25 0 0 0 911 583" fill="none" stroke="#FFFDF6" stroke-width="1.4" stroke-linecap="round" opacity=".45"/>',
    '  <path d="M737 606C736 611 738 614 742 614" fill="none" stroke="#FFF9EC" stroke-width="2" stroke-linecap="round" opacity=".6"/>',
    '  <path d="M892 606C892 611 894 614 897 614" fill="none" stroke="#FFF9EC" stroke-width="2" stroke-linecap="round" opacity=".6"/>',
    '</g>',

    /* ── 樱桃（右侧，避开三支蜡烛） ───────────────────────── */
    '<g id="cake-cherry" data-pivot="878 582">',
    '  <path d="M878 572C880 565 886 560 891 561" fill="none" stroke="#4F7A3C" stroke-width="1.9" stroke-linecap="round"/>',
    '  <path d="M886 562C889 557 896 555 900 558C896 562 890 564 886 562Z" fill="#6FA35A"/>',
    '  <circle cx="878" cy="582" r="10.5" fill="url(#kg-cherry)"/>',
    '  <ellipse cx="874" cy="578" rx="3.2" ry="2.2" fill="#FFCDD2" opacity=".85" transform="rotate(-28 874 578)"/>',
    '  <ellipse cx="878" cy="587" rx="6" ry="3" fill="#5E0E1C" opacity=".35"/>',
    '</g>',

    /* ── 三支蜡烛：46 / 56 / 50，锚点 529 / 519 / 525 ─────── */
    '<g id="candle-1" data-pivot="752 582">',
    '  <ellipse cx="752" cy="582" rx="8" ry="2.6" fill="#8C5A2E" opacity=".25"/>',
    '  <rect x="746" y="536" width="12" height="46" rx="5" fill="url(#kg-candle-1)"/>',
    '  <g clip-path="url(#kg-cc-1)">',
    '    <path d="M744 549l18-8M744 564l18-8M744 579l18-8" stroke="#FFF7E7" stroke-width="3.2" stroke-linecap="round" opacity=".78"/>',
    '    <rect x="746" y="536" width="12" height="9" fill="url(#kg-candle-top)"/>',
    '    <rect x="747.3" y="545" width="2.6" height="34" fill="url(#kg-wax)"/>',
    '  </g>',
    '  <ellipse cx="752" cy="538" rx="5.4" ry="1.9" fill="#8E4E3E" opacity=".5"/>',
    '  <path d="M747.6 537.6q4.4-1.6 8.8 0" fill="none" stroke="#FFE9DC" stroke-width="1" opacity=".6"/>',
    '  <path d="M748 539q4 2 8 0" fill="none" stroke="#FFF0DC" stroke-width="1" opacity=".5"/>',
    '  <rect x="746" y="536" width="12" height="46" rx="5" fill="none" stroke="#B4634F" stroke-width="1.1" opacity=".45"/>',
    '  <path id="wick-1" d="M752 536q1.8-4 0-7" fill="none" stroke="#4A3524" stroke-width="2.2" stroke-linecap="round"/>',
    '  <g id="flame-unit-1" data-pivot="752 529" transform="translate(752 529)">',
    '    <ellipse cx="0" cy="-3" rx="4.8" ry="3.3" fill="#8FC8FF" opacity=".45" style="mix-blend-mode:screen"/>',
    '    <path id="flame-1" d="M0 0C-6.9-6.9-8.3-14.6-3.1-20.9C-1.6-22.5-2.5-25.4.2-26.4C2.1-27 3.6-24.5 3.1-21.8C6.9-15.1 7.3-6.7 0 0Z" fill="url(#kg-flame)"/>',
    '    <path class="flame-core" d="M0-2.2C-3.5-6.9-4-12.3-.6-16.4C2.7-12.7 3.7-8.1 2.5-4.6C1.9-3 1-1.8 0-2.2Z" fill="url(#kg-flame-core)"/>',
    '  </g>',
    '</g>',

    '<g id="candle-2" data-pivot="800 582">',
    '  <ellipse cx="800" cy="582" rx="8" ry="2.6" fill="#8C5A2E" opacity=".25"/>',
    '  <rect x="794" y="526" width="12" height="56" rx="5" fill="url(#kg-candle-2)"/>',
    '  <g clip-path="url(#kg-cc-2)">',
    '    <path d="M792 541l18-8M792 557l18-8M792 573l18-8" stroke="#F0A99B" stroke-width="3.2" stroke-linecap="round" opacity=".8"/>',
    '    <rect x="794" y="526" width="12" height="9" fill="url(#kg-candle-top)"/>',
    '    <rect x="795.3" y="535" width="2.6" height="42" fill="url(#kg-wax)"/>',
    '  </g>',
    '  <ellipse cx="800" cy="528" rx="5.4" ry="1.9" fill="#A88354" opacity=".5"/>',
    '  <path d="M795.6 527.6q4.4-1.6 8.8 0" fill="none" stroke="#FFF6E6" stroke-width="1" opacity=".55"/>',
    '  <path d="M796 529q4 2 8 0" fill="none" stroke="#FFFDF6" stroke-width="1" opacity=".6"/>',
    '  <rect x="794" y="526" width="12" height="56" rx="5" fill="none" stroke="#C3A169" stroke-width="1.1" opacity=".45"/>',
    '  <path id="wick-2" d="M800 526q-1.8-4 0-7" fill="none" stroke="#4A3524" stroke-width="2.2" stroke-linecap="round"/>',
    '  <g id="flame-unit-2" data-pivot="800 519" transform="translate(800 519)">',
    '    <ellipse cx="0" cy="-3" rx="4.8" ry="3.3" fill="#8FC8FF" opacity=".45" style="mix-blend-mode:screen"/>',
    '    <path id="flame-2" d="M0 0C-7.2-7.2-8.6-15.4-3.1-22C-1.5-23.6-2.5-26.9.4-27.9C2.5-28.5 4-25.7 3.3-22.8C7.2-15.8 7.6-7 0 0Z" fill="url(#kg-flame)"/>',
    '    <path class="flame-core" d="M0-2.2C-3.7-7.2-4.3-12.9-.6-17.4C2.9-13.3 3.9-8.4 2.7-4.7C2-3.1 1-1.8 0-2.2Z" fill="url(#kg-flame-core)"/>',
    '  </g>',
    '</g>',

    '<g id="candle-3" data-pivot="848 582">',
    '  <ellipse cx="848" cy="582" rx="8" ry="2.6" fill="#8C5A2E" opacity=".25"/>',
    '  <rect x="842" y="532" width="12" height="50" rx="5" fill="url(#kg-candle-3)"/>',
    '  <g clip-path="url(#kg-cc-3)">',
    '    <path d="M840 545l18-8M840 560l18-8M840 575l18-8" stroke="#FFF9EC" stroke-width="3.2" stroke-linecap="round" opacity=".78"/>',
    '    <rect x="842" y="532" width="12" height="9" fill="url(#kg-candle-top)"/>',
    '    <rect x="843.3" y="541" width="2.6" height="37" fill="url(#kg-wax)"/>',
    '  </g>',
    '  <ellipse cx="848" cy="534" rx="5.4" ry="1.9" fill="#4F7A5E" opacity=".5"/>',
    '  <path d="M843.6 533.6q4.4-1.6 8.8 0" fill="none" stroke="#E7F7EC" stroke-width="1" opacity=".5"/>',
    '  <path d="M844 535q4 2 8 0" fill="none" stroke="#EAF9EF" stroke-width="1" opacity=".5"/>',
    '  <rect x="842" y="532" width="12" height="50" rx="5" fill="none" stroke="#5C8C6B" stroke-width="1.1" opacity=".45"/>',
    '  <path id="wick-3" d="M848 532q2-4 0-7" fill="none" stroke="#4A3524" stroke-width="2.2" stroke-linecap="round"/>',
    '  <g id="flame-unit-3" data-pivot="848 525" transform="translate(848 525)">',
    '    <ellipse cx="0" cy="-3" rx="4.8" ry="3.3" fill="#8FC8FF" opacity=".45" style="mix-blend-mode:screen"/>',
    '    <path id="flame-3" d="M0 0C-7-7-8.4-14.8-3.1-21.4C-1.5-23-2.5-26.1.3-27.1C2.3-27.7 3.8-25 3.2-22.1C7-15.2 7.4-6.8 0 0Z" fill="url(#kg-flame)"/>',
    '    <path class="flame-core" d="M0-2.2C-3.5-7-4.1-12.5-.6-16.8C2.8-12.9 3.8-8.2 2.6-4.6C1.9-3 1-1.8 0-2.2Z" fill="url(#kg-flame-core)"/>',
    '  </g>',
    '</g>',

    /* ── 烛光光晕（screen，半径 72–94） ───────────────────── */
    '<g id="glow-1" data-pivot="752 516" style="mix-blend-mode:screen" opacity=".36">',
    '  <circle cx="752" cy="516" r="62" fill="url(#kg-glow)"/>',
    '</g>',
    '<g id="glow-2" data-pivot="800 506" style="mix-blend-mode:screen" opacity=".36">',
    '  <circle cx="800" cy="506" r="74" fill="url(#kg-glow)"/>',
    '</g>',
    '<g id="glow-3" data-pivot="848 512" style="mix-blend-mode:screen" opacity=".36">',
    '  <circle cx="848" cy="512" r="66" fill="url(#kg-glow)"/>',
    '</g>',

    /* 烛光在糖霜上的暖反光 */
    '<ellipse cx="800" cy="572" rx="96" ry="22" fill="url(#kg-warm-face)" style="mix-blend-mode:screen"/>',

    /* ── 熄灭轻烟：中性描边、默认透明，留给 motion 淡入 ───── */
    '<path id="smoke-1" data-pivot="752 529" d="M752 529c-5-9 5-14 0-23c-5-9 5-14 0-23c-4-7 4-11 1-17" fill="none" stroke="#B9CBBC" stroke-width="2.2" stroke-linecap="round" opacity="0"/>',
    '<path id="smoke-2" data-pivot="800 519" d="M800 519c-5-9 5-14 0-23c-5-9 5-14 0-23c-4-7 4-11 1-17" fill="none" stroke="#B9CBBC" stroke-width="2.2" stroke-linecap="round" opacity="0"/>',
    '<path id="smoke-3" data-pivot="848 525" d="M848 525c-5-9 5-14 0-23c-5-9 5-14 0-23c-4-7 4-11 1-17" fill="none" stroke="#B9CBBC" stroke-width="2.2" stroke-linecap="round" opacity="0"/>',

    /* 盘面反光点 */
    '<ellipse cx="726" cy="642" rx="20" ry="4" fill="#FFF7E7" opacity=".22" style="mix-blend-mode:screen"/>',

    '</g>'
  ].join('');

  window.Assets = window.Assets || {};
  window.Assets.cake = {
    svg: svg,
    /* 旋转/缩放原点（SVG 用户坐标，配 GSAP svgOrigin）；火焰锚点 = wick 顶 */
    parts: {
      'cake': [800, 600],
      'cake-shadow': [800, 676],
      'cake-plate': [800, 652],
      'cake-body': [800, 612],
      'cake-icing': [800, 575],
      'cake-cherry': [878, 582],
      'candle-1': [752, 582],
      'candle-2': [800, 582],
      'candle-3': [848, 582],
      'wick-1': [752, 529],
      'wick-2': [800, 519],
      'wick-3': [848, 525],
      'flame-unit-1': [752, 529],
      'flame-unit-2': [800, 519],
      'flame-unit-3': [848, 525],
      'flame-1': [752, 529],
      'flame-2': [800, 519],
      'flame-3': [848, 525],
      'glow-1': [752, 516],
      'glow-2': [800, 506],
      'glow-3': [848, 512],
      'smoke-1': [752, 529],
      'smoke-2': [800, 519],
      'smoke-3': [848, 525],
      'cake-pool': [800, 674]
    },
    /* lighting / vfx 用：盘心、火焰锚点（≈529/519/525）、最高点 */
    anchors: {
      plate: [800, 648],
      flame: [[752, 529], [800, 519], [848, 525]],
      top: 480,
      bodyBox: { x: 690, y: 576, w: 220, h: 96 }
    }
  };
})();
