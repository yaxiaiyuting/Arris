/* ─────────────────────────────────────────────────────────────
   assets.tux.js · Tux 企鹅（character-artist / task-3）

   契约：docs/CONTRACT.md §4。字符串由 main.js innerHTML 注入 #layer-tux。
   坐标：1600×900 用户单位；脚底 y=660，身体中心 x=600，头顶 y=331。
   根 <g id="tux"> 不带 transform，所有零件都画在场景绝对坐标里。

   旋转原点见 window.Assets.tux.parts（SVG 用户坐标，配 GSAP svgOrigin）：
     gsap.to('#tux-flipper-l', { rotation: 18, svgOrigin: '498 486' });

   托盘：盘面中心 (600,526)，直径 184，厚 7；静止姿势是双手端在胸前。
   蛋糕要挂上托盘时，盘面 y=526，蛋糕盘底 y=648 → 位移 dy = -122（motion 负责）。

   眼睛：每组内 .tux-lid 默认 translate(0 -56) 收在眼皮外，眨眼把 y 动到 0；
   也可以用 #tux-eye-l 的 scaleY（svgOrigin 在眼心）做快速眨眼。
   不要新增/删除 §4 的 id。
   ───────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  /* 主轮廓复用：描边、裁剪、边缘光同一组数，改比例只改这里 */
  var BODY = 'M600 448C666 448 704 504 704 566C704 624 664 651 600 651C536 651 496 624 496 566C496 504 534 448 600 448Z';
  var HEAD = 'M600 331C652 331 684 366 684 410C684 456 650 489 600 489C550 489 516 456 516 410C516 366 548 331 600 331Z';
  var BELLY = 'M600 494C640 494 668 530 668 570C668 614 642 644 600 644C558 644 532 614 532 570C532 530 560 494 600 494Z';
  var FEET_L = 'M562 630C540 631 520 639 515 649C512 656 519 660 531 660C549 659 570 655 576 646C580 638 573 631 562 630Z';
  var FEET_R = 'M638 630C660 631 680 639 685 649C688 656 681 660 669 660C651 659 630 655 624 646C620 638 627 631 638 630Z';
  /* 端盘子的姿势：肩 498/702,486，鳍尖压在盘沿 524/676,536 */
  var FLIP_L = 'M498 486C478 504 480 530 498 544C506 552 522 548 524 536C528 514 518 496 512 490Z';
  var FLIP_R = 'M702 486C722 504 720 530 702 544C694 552 678 548 676 536C672 514 682 496 688 490Z';
  var HAT = 'M556 344C578 357 622 357 648 338C644 308 638 274 632 252C630 244 622 243 620 250C606 280 580 316 556 344Z';

  var svg = [
    /* ── defs：全部 tg- 前缀，避免和 env / cake 撞名 ───────────── */
    '<defs>',
    '  <radialGradient id="tg-body" cx="34%" cy="20%" r="88%">',
    '    <stop offset="0" stop-color="#2C4837"/>',
    '    <stop offset=".34" stop-color="#1A3426"/>',
    '    <stop offset=".7" stop-color="#112418"/>',
    '    <stop offset="1" stop-color="#0A1711"/>',
    '  </radialGradient>',
    '  <radialGradient id="tg-flipper" cx="30%" cy="26%" r="86%">',
    '    <stop offset="0" stop-color="#35543F"/>',
    '    <stop offset=".4" stop-color="#1F3A2A"/>',
    '    <stop offset=".78" stop-color="#14291C"/>',
    '    <stop offset="1" stop-color="#0C1A13"/>',
    '  </radialGradient>',
    '  <radialGradient id="tg-body-shade" cx="50%" cy="50%" r="56%">',
    '    <stop offset="0" stop-color="#04100A" stop-opacity="0"/>',
    '    <stop offset=".52" stop-color="#04100A" stop-opacity=".18"/>',
    '    <stop offset="1" stop-color="#04100A" stop-opacity=".7"/>',
    '  </radialGradient>',
    '  <radialGradient id="tg-head" cx="37%" cy="20%" r="86%">',
    '    <stop offset="0" stop-color="#365742"/>',
    '    <stop offset=".35" stop-color="#1E3929"/>',
    '    <stop offset=".72" stop-color="#14291D"/>',
    '    <stop offset="1" stop-color="#0B1A12"/>',
    '  </radialGradient>',
    '  <radialGradient id="tg-belly" cx="42%" cy="28%" r="86%">',
    '    <stop offset="0" stop-color="#F8EED8"/>',
    '    <stop offset=".42" stop-color="#F2E4C6"/>',
    '    <stop offset=".78" stop-color="#DFCBA8"/>',
    '    <stop offset="1" stop-color="#CFB891"/>',
    '  </radialGradient>',
    /* 三段体积：暗侧(左) → 中间调 → 亮侧(右)，body / head 各裁一份 */
    '  <linearGradient id="tg-body-side" x1="0" y1="0" x2="1" y2="0">',
    '    <stop offset="0" stop-color="#02100A" stop-opacity=".58"/>',
    '    <stop offset=".3" stop-color="#02100A" stop-opacity=".24"/>',
    '    <stop offset=".5" stop-color="#FFB066" stop-opacity="0"/>',
    '    <stop offset=".78" stop-color="#FFB066" stop-opacity=".15"/>',
    '    <stop offset="1" stop-color="#FFD9A3" stop-opacity=".36"/>',
    '  </linearGradient>',
    '  <linearGradient id="tg-head-side" x1="0" y1="0" x2="1" y2="0">',
    '    <stop offset="0" stop-color="#02100A" stop-opacity=".5"/>',
    '    <stop offset=".28" stop-color="#02100A" stop-opacity=".2"/>',
    '    <stop offset=".52" stop-color="#FFB066" stop-opacity="0"/>',
    '    <stop offset=".8" stop-color="#FFB066" stop-opacity=".13"/>',
    '    <stop offset="1" stop-color="#FFD9A3" stop-opacity=".3"/>',
    '  </linearGradient>',
    '  <radialGradient id="tg-iris" cx="46%" cy="38%" r="62%">',
    '    <stop offset="0" stop-color="#3F6249"/>',
    '    <stop offset=".62" stop-color="#1D3526"/>',
    '    <stop offset="1" stop-color="#0B1710"/>',
    '  </radialGradient>',
    '  <linearGradient id="tg-belly-shade" x1="0" y1="0" x2="1" y2="1">',
    '    <stop offset="0" stop-color="#FFFFFF" stop-opacity=".32"/>',
    '    <stop offset=".46" stop-color="#FFFFFF" stop-opacity="0"/>',
    '    <stop offset=".8" stop-color="#C7A470" stop-opacity=".18"/>',
    '    <stop offset="1" stop-color="#A98454" stop-opacity=".36"/>',
    '  </linearGradient>',
    '  <radialGradient id="tg-belly-ao" cx="50%" cy="0%" r="72%">',
    '    <stop offset="0" stop-color="#B79A6C" stop-opacity=".42"/>',
    '    <stop offset=".55" stop-color="#B79A6C" stop-opacity=".15"/>',
    '    <stop offset="1" stop-color="#B79A6C" stop-opacity="0"/>',
    '  </radialGradient>',
    '  <linearGradient id="tg-beak" x1="0" y1="0" x2="0" y2="1">',
    '    <stop offset="0" stop-color="#FFE1A4"/>',
    '    <stop offset=".5" stop-color="#F3B159"/>',
    '    <stop offset="1" stop-color="#BE7128"/>',
    '  </linearGradient>',
    '  <linearGradient id="tg-foot" x1=".2" y1="0" x2=".8" y2="1">',
    '    <stop offset="0" stop-color="#F5CB7E"/>',
    '    <stop offset=".52" stop-color="#E8B45A"/>',
    '    <stop offset="1" stop-color="#B8792F"/>',
    '  </linearGradient>',
    '  <linearGradient id="tg-sage" x1=".15" y1="0" x2=".85" y2="1">',
    '    <stop offset="0" stop-color="#52896B"/>',
    '    <stop offset=".4" stop-color="#3B6E56"/>',
    '    <stop offset=".76" stop-color="#2A5240"/>',
    '    <stop offset="1" stop-color="#1B3B2F"/>',
    '  </linearGradient>',
    '  <radialGradient id="tg-cup" cx="30%" cy="20%" r="90%">',
    '    <stop offset="0" stop-color="#3D6B54"/>',
    '    <stop offset=".42" stop-color="#2E5D4A"/>',
    '    <stop offset=".82" stop-color="#234838"/>',
    '    <stop offset="1" stop-color="#17301F"/>',
    '  </radialGradient>',
    '  <radialGradient id="tg-eye" cx="38%" cy="26%" r="82%">',
    '    <stop offset="0" stop-color="#FFFEFB"/>',
    '    <stop offset=".58" stop-color="#FBF1DA"/>',
    '    <stop offset="1" stop-color="#DFC9A1"/>',
    '  </radialGradient>',
    '  <radialGradient id="tg-pupil" cx="40%" cy="32%" r="78%">',
    '    <stop offset="0" stop-color="#26402F"/>',
    '    <stop offset=".52" stop-color="#0C1811"/>',
    '    <stop offset="1" stop-color="#040A07"/>',
    '  </radialGradient>',
    '  <linearGradient id="tg-lid" x1="0" y1="0" x2="0" y2="1">',
    '    <stop offset="0" stop-color="#24402F"/>',
    '    <stop offset=".5" stop-color="#172C20"/>',
    '    <stop offset="1" stop-color="#102018"/>',
    '  </linearGradient>',
    '  <radialGradient id="tg-blush" cx="50%" cy="50%" r="50%">',
    '    <stop offset="0" stop-color="#F0A99B" stop-opacity=".6"/>',
    '    <stop offset=".58" stop-color="#F0A99B" stop-opacity=".2"/>',
    '    <stop offset="1" stop-color="#F0A99B" stop-opacity="0"/>',
    '  </radialGradient>',
    '  <radialGradient id="tg-shadow" cx="50%" cy="50%" r="50%">',
    '    <stop offset="0" stop-color="#02100A" stop-opacity=".64"/>',
    '    <stop offset=".52" stop-color="#02100A" stop-opacity=".34"/>',
    '    <stop offset="1" stop-color="#02100A" stop-opacity="0"/>',
    '  </radialGradient>',
    '  <radialGradient id="tg-contact" cx="50%" cy="50%" r="50%">',
    '    <stop offset="0" stop-color="#01100A" stop-opacity=".55"/>',
    '    <stop offset=".6" stop-color="#01100A" stop-opacity=".28"/>',
    '    <stop offset="1" stop-color="#01100A" stop-opacity="0"/>',
    '  </radialGradient>',
    '  <linearGradient id="tg-hat" x1=".9" y1=".1" x2=".15" y2="1">',
    '    <stop offset="0" stop-color="#C3EDCD"/>',
    '    <stop offset=".38" stop-color="#8BCC9D"/>',
    '    <stop offset=".74" stop-color="#5D9B72"/>',
    '    <stop offset="1" stop-color="#39684E"/>',
    '  </linearGradient>',
    '  <linearGradient id="tg-hat-cream" x1="0" y1="0" x2="0" y2="1">',
    '    <stop offset="0" stop-color="#E9F2E4"/>',
    '    <stop offset=".5" stop-color="#DCE8D6"/>',
    '    <stop offset="1" stop-color="#B9CCBC"/>',
    '  </linearGradient>',
    '  <radialGradient id="tg-pom" cx="34%" cy="28%" r="78%">',
    '    <stop offset="0" stop-color="#F6F0DE"/>',
    '    <stop offset=".55" stop-color="#E6D9BC"/>',
    '    <stop offset="1" stop-color="#C4AC84"/>',
    '  </radialGradient>',
    '  <linearGradient id="tg-tray-top" x1=".15" y1="0" x2=".85" y2="1">',
    '    <stop offset="0" stop-color="#EBC795"/>',
    '    <stop offset=".42" stop-color="#C99A66"/>',
    '    <stop offset=".8" stop-color="#9C6F45"/>',
    '    <stop offset="1" stop-color="#7C5636"/>',
    '  </linearGradient>',
    '  <linearGradient id="tg-rim-warm" x1="0" y1="1" x2="0" y2="0">',
    '    <stop offset="0" stop-color="#FFB066" stop-opacity="0"/>',
    '    <stop offset=".4" stop-color="#FFD0A0" stop-opacity=".8"/>',
    '    <stop offset="1" stop-color="#FFF4DC" stop-opacity="0"/>',
    '  </linearGradient>',
    '  <linearGradient id="tg-rim-cool" x1="0" y1="1" x2="1" y2="0">',
    '    <stop offset="0" stop-color="#8FD3AE" stop-opacity="0"/>',
    '    <stop offset=".5" stop-color="#C3EBD6" stop-opacity=".55"/>',
    '    <stop offset="1" stop-color="#D6F5E6" stop-opacity="0"/>',
    '  </linearGradient>',
    '  <radialGradient id="tg-warmwash" cx="50%" cy="50%" r="50%">',
    '    <stop offset="0" stop-color="#FFB066" stop-opacity=".42"/>',
    '    <stop offset=".55" stop-color="#FF9A4C" stop-opacity=".16"/>',
    '    <stop offset="1" stop-color="#FF9A4C" stop-opacity="0"/>',
    '  </radialGradient>',
    '  <radialGradient id="tg-coolwash" cx="50%" cy="50%" r="50%">',
    '    <stop offset="0" stop-color="#5E8C74" stop-opacity=".26"/>',
    '    <stop offset=".6" stop-color="#5E8C74" stop-opacity=".1"/>',
    '    <stop offset="1" stop-color="#5E8C74" stop-opacity="0"/>',
    '  </radialGradient>',
    '  <clipPath id="tg-bodyclip"><path d="' + BODY + '"/></clipPath>',
    '  <clipPath id="tg-headclip"><path d="' + HEAD + '"/></clipPath>',
    '  <clipPath id="tg-bellyclip"><path d="' + BELLY + '"/></clipPath>',
    '  <clipPath id="tg-hatclip"><path d="' + HAT + '"/></clipPath>',
    '  <clipPath id="tg-eyeclip-l"><ellipse cx="570" cy="412" rx="14.6" ry="16"/></clipPath>',
    '  <clipPath id="tg-eyeclip-r"><ellipse cx="630" cy="412" rx="14.6" ry="16"/></clipPath>',
    '</defs>',

    /* data-ink = 不含接触阴影的实际墨迹盒（x y w h）；
       #tux 的 getBBox 会多出阴影（470–730 / 636–682）与帽顶绒球（229.5）。 */
    '<g id="tux" data-ink="483.7 229.5 233.1 434.5">',

    /* ── 接触阴影 ─────────────────────────────────────────── */
    /* 接触阴影：大软影 + 脚下一圈更实的芯，防止人物"飘" */
    '<ellipse id="tux-shadow" data-pivot="600 657" cx="600" cy="659" rx="130" ry="23" fill="url(#tg-shadow)"/>',
    '<ellipse cx="600" cy="659" rx="86" ry="13" fill="url(#tg-contact)"/>',

    /* ── 身体：宽蛋形；顶部冷光 + 底部压暗都裁在轮廓里 ────── */
    '<g id="tux-body" data-pivot="600 552">',
    '  <path d="' + BODY + '" fill="url(#tg-body)"/>',
    '  <g clip-path="url(#tg-bodyclip)">',
    /* 暗侧 → 中间调 → 亮侧（竖向分区，不是只有顶部高光） */
    '    <rect x="496" y="448" width="208" height="203" fill="url(#tg-body-side)"/>',
    '    <ellipse cx="566" cy="486" rx="66" ry="40" fill="url(#tg-coolwash)" opacity=".55"/>',
    '    <ellipse cx="600" cy="612" rx="94" ry="38" fill="url(#tg-body-shade)"/>',
    '  </g>',
    /* 羽毛走向：短弧，近看有方向，远看融进体积 */
    '  <g fill="none" stroke="#2C503E" stroke-width="1.6" stroke-linecap="round" opacity=".45">',
    '    <path d="M512 528q14-14 30-7"/>',
    '    <path d="M505 566q16-15 34-7"/>',
    '    <path d="M508 604q15-13 31-6"/>',
    '    <path d="M688 528q-14-14-30-7"/>',
    '    <path d="M695 566q-16-15-34-7"/>',
    '    <path d="M692 604q-15-13-31-6"/>',
    '  </g>',
    /* 轮廓：暗色收边（不是同色亮描边），受光/背光交给 #tux-rimlight 两段 */
    '  <path d="' + BODY + '" fill="none" stroke="#08150F" stroke-width="2.4" stroke-linejoin="round" opacity=".55"/>',
    '</g>',

    /* ── 边缘光：只做躯干受光侧一段 + 背光侧一小段（不是闭环 → 不像气泡）。
           路径贴着 body 的实际轮廓（body 是梨形，按正圆/椭圆算会跑到脚下），
           画在 body 之后、肚子/脚/鳍之前：下缘会被脚自然遮住，不会横切脚背。
           整体 opacity 由 lighting 控制。 ─────────────────────── */
    '<g id="tux-rimlight" data-pivot="600 500">',
    /* 受光侧（烛光在右下）：3.2u / α .55 */
    '  <path d="M675 483C692 506 701 540 701 566C699 598 675 624 652 636" fill="none" stroke="#FFD9A3" stroke-width="3.2" stroke-linecap="round" opacity=".55"/>',
    /* 背光侧（左上冷光）：3u / α .45，与上段之间留断口 */
    '  <path d="M499 560C499 588 510 614 532 632" fill="none" stroke="#9FD8B7" stroke-width="3" stroke-linecap="round" opacity=".45"/>',
    '</g>',


    /* ── 肚子：径向渐变 + 边缘 AO 圈 + 下巴投下的暗部 ─────── */
    '<g id="tux-belly" data-pivot="600 566">',
    '  <path d="' + BELLY + '" fill="url(#tg-belly)"/>',
    '  <g clip-path="url(#tg-bellyclip)">',
    '    <path d="' + BELLY + '" fill="url(#tg-belly-shade)"/>',
    '    <ellipse cx="600" cy="508" rx="58" ry="18" fill="url(#tg-belly-ao)"/>',
    /* 极低对比的绒羽纹理：破掉平涂，远看融进体积 */
    '    <g fill="none" stroke="#C9B48C" stroke-width="1.1" stroke-linecap="round" opacity=".1">',
    '      <path d="M572 540q12-7 24-1"/>',
    '      <path d="M564 574q14-8 28-2"/>',
    '      <path d="M576 608q11-6 22-1"/>',
    '    </g>',
    /* 右上受烛光的暖反弹 + 左上冷压边：同一块白也要有冷暖 */
    '    <ellipse cx="628" cy="566" rx="42" ry="62" fill="#FFD9A3" opacity=".2"/>',
    '    <ellipse cx="560" cy="540" rx="38" ry="58" fill="#7C8C7A" opacity=".12"/>',
    '    <ellipse cx="612" cy="556" rx="28" ry="50" fill="#FFFDF4" opacity=".14"/>',
    '  </g>',
    /* AO：边缘暖灰 #B79A6C 15%，别让暖白直接切在黑身体上 */
    '  <path d="' + BELLY + '" fill="none" stroke="#B79A6C" stroke-width="10" opacity=".15"/>',
    '  <path d="' + BELLY + '" fill="none" stroke="#D9C6A0" stroke-width="1.4" opacity=".45"/>',
    '  <ellipse cx="600" cy="634" rx="46" ry="12" fill="#FFE8C7" opacity=".22"/>',
    '</g>',

    /* ── 脚：踝 (562/638,630)，脚底 660，向外撇，带接触阴影 ─ */
    '<g id="tux-foot-l" data-pivot="562 630">',
    '  <ellipse cx="546" cy="658" rx="30.6" ry="5.4" fill="#02100A" opacity=".46"/>',
    '  <path d="' + FEET_L + '" fill="url(#tg-foot)" stroke="#8A5522" stroke-width="1.7" stroke-linejoin="round"/>',
    '  <path d="M531 658.5l4.5-8M546 659l4-9" fill="none" stroke="#8A5520" stroke-width="1.8" stroke-linecap="round" opacity=".62"/>',
    '  <path d="M532.6 657.6l4-6.4M547.6 658.2l3.4-7" fill="none" stroke="#FFDCA8" stroke-width="1.1" stroke-linecap="round" opacity=".4"/>',
    '  <ellipse cx="543" cy="636" rx="15" ry="4" fill="#FFE3B4" opacity=".4"/>',
    /* 身体压住脚踝的 1–2px 遮挡缝 */
    '  <path d="M548 632C556 629 568 630 575 634" fill="none" stroke="#3E2412" stroke-width="2.6" stroke-linecap="round" opacity=".55"/>',
    '</g>',
    '<g id="tux-foot-r" data-pivot="638 630">',
    '  <ellipse cx="654" cy="658" rx="30.6" ry="5.4" fill="#02100A" opacity=".46"/>',
    '  <path d="' + FEET_R + '" fill="url(#tg-foot)" stroke="#8A5522" stroke-width="1.7" stroke-linejoin="round"/>',
    '  <path d="M669 658.5l-4.5-8M654 659l-4-9" fill="none" stroke="#8A5520" stroke-width="1.8" stroke-linecap="round" opacity=".62"/>',
    '  <path d="M667.4 657.6l-4-6.4M652.4 658.2l-3.4-7" fill="none" stroke="#FFDCA8" stroke-width="1.1" stroke-linecap="round" opacity=".4"/>',
    '  <ellipse cx="657" cy="636" rx="15" ry="4" fill="#FFE3B4" opacity=".4"/>',
    '  <path d="M652 632C644 629 632 630 625 634" fill="none" stroke="#3E2412" stroke-width="2.6" stroke-linecap="round" opacity=".55"/>',
    '</g>',

    /* ── 托盘：直径 184、厚 7，双手端在胸前（盘心 600,526） ── */
    '<g id="tux-tray" data-pivot="600 526">',
    '  <ellipse cx="600" cy="512" rx="86" ry="10" fill="#04100A" opacity=".18"/>',
    '  <ellipse cx="600" cy="534" rx="90" ry="11" fill="#3A2312"/>',
    '  <path d="M510 526a90 11 0 0 0 180 0v7a90 11 0 0 1-180 0z" fill="#6A472C"/>',
    '  <ellipse cx="600" cy="526" rx="92" ry="11" fill="url(#tg-tray-top)" stroke="#4A2E1A" stroke-width="1.4"/>',
    '  <ellipse cx="600" cy="525" rx="74" ry="7" fill="#8A5F3C" opacity=".22"/>',
    '  <path d="M548 529q52 9 104 0" fill="none" stroke="#FFF0D4" stroke-width="1.8" stroke-linecap="round" opacity=".55"/>',
    '  <path d="M522 521q78 13 156 0" fill="none" stroke="#FFF6E2" stroke-width="1.4" stroke-linecap="round" opacity=".3"/>',
    '  <path d="M514 524a86 10 0 0 0 172 0" fill="none" stroke="#3E2717" stroke-width="1.2" opacity=".45"/>',
    '</g>',

    /* ── 鳍：端盘子的姿势（鳍尖压在盘沿，画在托盘之后） ───── */
    '<g id="tux-flipper-l" data-pivot="498 486">',
    '  <path d="' + FLIP_L + '" fill="url(#tg-flipper)" stroke="#08150F" stroke-width="2" stroke-linejoin="round" opacity=".85"/>',
    '  <path d="M494 494C481 508 481 526 492 538" fill="none" stroke="#588B6C" stroke-width="1.8" stroke-linecap="round" opacity=".55"/>',
    '  <path d="M514 496C518 512 520 526 517 534" fill="none" stroke="#0A1711" stroke-width="2.2" stroke-linecap="round" opacity=".28"/>',
    '  <ellipse cx="504" cy="492" rx="11" ry="6" fill="#04100A" opacity=".25"/>',
    '</g>',
    '<g id="tux-flipper-r" data-pivot="702 486">',
    '  <path d="' + FLIP_R + '" fill="url(#tg-flipper)" stroke="#08150F" stroke-width="2" stroke-linejoin="round" opacity=".85"/>',
    '  <path d="M706 494C719 508 719 526 708 538" fill="none" stroke="#588B6C" stroke-width="1.8" stroke-linecap="round" opacity=".55"/>',
    '  <path d="M686 496C682 512 680 526 683 534" fill="none" stroke="#0A1711" stroke-width="2.2" stroke-linecap="round" opacity=".28"/>',
    '  <ellipse cx="696" cy="492" rx="11" ry="6" fill="#04100A" opacity=".25"/>',
    '</g>',

    /* ── 头（颈点 600,489）+ 眼 + 喙 ──────────────────────── */
    '<g id="tux-head" data-pivot="600 489">',
    '  <path d="' + HEAD + '" fill="url(#tg-head)"/>',
    '  <g clip-path="url(#tg-headclip)">',
    '    <rect x="516" y="331" width="168" height="158" fill="url(#tg-head-side)"/>',
    '    <ellipse cx="566" cy="352" rx="52" ry="26" fill="url(#tg-coolwash)" opacity=".6"/>',
    '  </g>',
    '  <path d="' + HEAD + '" fill="none" stroke="#08150F" stroke-width="2.4" stroke-linejoin="round" opacity=".5"/>',
    '  <ellipse cx="600" cy="348" rx="52" ry="16" fill="#4A7760" opacity=".18"/>',
    '  <ellipse cx="550" cy="450" rx="16" ry="9" fill="url(#tg-blush)" opacity=".85"/>',
    '  <ellipse cx="650" cy="450" rx="16" ry="9" fill="url(#tg-blush)" opacity=".85"/>',

    /* 左眼：缩小 12%，眼白/瞳孔/角膜反光 + 可下落的眼睑 */
    '  <g id="tux-eye-l" data-pivot="570 412">',
    '    <g clip-path="url(#tg-eyeclip-l)">',
    '      <ellipse cx="570" cy="412" rx="14.6" ry="16" fill="url(#tg-eye)"/>',
    '      <ellipse cx="570" cy="418" rx="13.4" ry="9" fill="#D9C49C" opacity=".45"/>',
    '      <circle cx="572.4" cy="414.4" r="7.8" fill="url(#tg-iris)"/>',
    '      <circle cx="572.4" cy="414.4" r="4.5" fill="#08110C"/>',
    '      <circle cx="575.4" cy="409.6" r="2.6" fill="#FFFEF8" opacity=".95"/>',
    '      <circle cx="569.6" cy="417.6" r="1.2" fill="#FFE8C7" opacity=".8"/>',
    '      <path class="tux-lid" d="M548 378h46v56q-23-8-46 0z" fill="url(#tg-lid)" transform="translate(0 -56)"/>',
    '    </g>',
    '    <path d="M557 397q13-8 26-2" fill="none" stroke="#0B1A12" stroke-width="1.5" stroke-linecap="round" opacity=".45"/>',
    '  </g>',
    /* 右眼 */
    '  <g id="tux-eye-r" data-pivot="630 412">',
    '    <g clip-path="url(#tg-eyeclip-r)">',
    '      <ellipse cx="630" cy="412" rx="14.6" ry="16" fill="url(#tg-eye)"/>',
    '      <ellipse cx="630" cy="418" rx="13.4" ry="9" fill="#D9C49C" opacity=".45"/>',
    '      <circle cx="632.4" cy="414.4" r="7.8" fill="url(#tg-iris)"/>',
    '      <circle cx="632.4" cy="414.4" r="4.5" fill="#08110C"/>',
    '      <circle cx="635.4" cy="409.6" r="2.6" fill="#FFFEF8" opacity=".95"/>',
    '      <circle cx="629.6" cy="417.6" r="1.2" fill="#FFE8C7" opacity=".8"/>',
    '      <path class="tux-lid" d="M608 378h46v56q-23-8-46 0z" fill="url(#tg-lid)" transform="translate(0 -56)"/>',
    '    </g>',
    '    <path d="M617 397q13-8 26-2" fill="none" stroke="#0B1A12" stroke-width="1.5" stroke-linecap="round" opacity=".45"/>',
    '  </g>',

    /* 喙：小、圆、短，下缘带一点阴影（喙根 600,440） */
    '  <g id="tux-beak" data-pivot="600 440">',
    '    <ellipse cx="600" cy="448" rx="18" ry="6" fill="#8A4E1A" opacity=".22"/>',
    '    <path d="M583 438C586 433 592 430 600 430C608 430 614 433 617 438C619 442 615 448 609 450C605 452 595 452 591 450C585 448 581 442 583 438Z" fill="url(#tg-beak)" stroke="#A9691F" stroke-width="1.3" stroke-linejoin="round" opacity=".95"/>',
    '    <path d="M592 445C596 448 604 448 608 445" fill="none" stroke="#A9691F" stroke-width="1.3" stroke-linecap="round" opacity=".55"/>',
    '    <ellipse cx="594" cy="437" rx="5" ry="2.4" fill="#FFF3CE" opacity=".6" transform="rotate(-14 594 437)"/>',
    '  </g>',
    '  <ellipse cx="600" cy="486" rx="46" ry="8" fill="#0A1711" opacity=".16"/>',
    '</g>',

    /* ── 耳机：粗绿圈压头顶，耳罩扣在两侧 ────────────────── */
    '<g id="tux-headphones" data-pivot="600 402">',
    '  <ellipse cx="517" cy="432" rx="18" ry="30" fill="#04100A" opacity=".22"/>',
    '  <ellipse cx="683" cy="432" rx="18" ry="30" fill="#04100A" opacity=".22"/>',
    '  <path d="M520 402C530 279 670 279 680 402" fill="none" stroke="#22432F" stroke-width="20" stroke-linecap="round"/>',
    '  <path d="M520 402C530 279 670 279 680 402" fill="none" stroke="url(#tg-sage)" stroke-width="14" stroke-linecap="round"/>',
    '  <path d="M524 392C533 289 667 289 676 392" fill="none" stroke="#A9D9BC" stroke-width="2.6" stroke-linecap="round" opacity=".42"/>',
    '  <path d="M528 408C537 300 663 300 672 408" fill="none" stroke="#2A513D" stroke-width="1.6" stroke-linecap="round" opacity=".35"/>',
    /* 左耳罩 */
    '  <g>',
    '    <rect x="492" y="394" width="50" height="76" rx="22" fill="url(#tg-cup)" stroke="#3E7159" stroke-width="1.8"/>',
    '    <rect x="501" y="403" width="32" height="58" rx="16" fill="#2A5541" opacity=".55"/>',
    '    <ellipse cx="521" cy="432" rx="12.5" ry="23" fill="#1C3C2E"/>',
    '    <ellipse cx="523" cy="432" rx="8" ry="17" fill="#1B3B2C"/>',
    '    <path d="M501 412C497 424 497 444 502 456" fill="none" stroke="#9FD0B3" stroke-width="2.4" stroke-linecap="round" opacity=".4"/>',
    '    <rect x="512" y="387" width="12" height="12" rx="5" fill="#4C8A68" stroke="#24503A" stroke-width="1.8"/>',
    '    <circle cx="533" cy="458" r="3" fill="#FFE8C7" opacity=".9"/>',
    '  </g>',
    /* 右耳罩 */
    '  <g>',
    '    <rect x="658" y="394" width="50" height="76" rx="22" fill="url(#tg-cup)" stroke="#3E7159" stroke-width="1.8"/>',
    '    <rect x="667" y="403" width="32" height="58" rx="16" fill="#2A5541" opacity=".55"/>',
    '    <ellipse cx="679" cy="432" rx="12.5" ry="23" fill="#1C3C2E"/>',
    '    <ellipse cx="677" cy="432" rx="8" ry="17" fill="#1B3B2C"/>',
    '    <path d="M667 412C663 424 663 444 668 456" fill="none" stroke="#9FD0B3" stroke-width="2.4" stroke-linecap="round" opacity=".4"/>',
    '    <rect x="676" y="387" width="12" height="12" rx="5" fill="#4C8A68" stroke="#24503A" stroke-width="1.8"/>',
    '    <circle cx="667" cy="458" r="3" fill="#FFE8C7" opacity=".9"/>',
    '  </g>',
    '</g>',

    /* ── 派对帽：斜戴，条纹 + 细帽檐 + 绒球 ───────────────── */
    '<g id="tux-hat" data-pivot="600 347">',
    /* 帽子投在头上的影（在帽沿正下方，不是一圈光） */
    '  <ellipse cx="601" cy="354" rx="42" ry="7" fill="#0B1A12" opacity=".3"/>',
    /* 帽锥本体 */
    '  <path d="' + HAT + '" fill="url(#tg-hat)"/>',
    '  <g clip-path="url(#tg-hatclip)">',
    '    <path d="M540 334C580 318 622 312 660 330" fill="none" stroke="#F0A99B" stroke-width="9" stroke-linecap="butt" opacity=".88"/>',
    '    <path d="M552 306C590 290 628 288 654 300" fill="none" stroke="#DCE8D6" stroke-width="7" stroke-linecap="butt" opacity=".92"/>',
    '    <path d="M572 278C596 267 616 265 636 273" fill="none" stroke="#DCE8D6" stroke-width="6" stroke-linecap="butt" opacity=".92"/>',
    '    <path d="M560 346C584 328 604 322 626 322L626 350L560 350Z" fill="#E6FAEB" opacity=".12"/>',
    '    <path d="M600 324C616 280 626 262 632 252" fill="none" stroke="#2F5A44" stroke-width="7" opacity=".18"/>',
    /* 布料褶皱：2–3 道很淡的受光折痕 + 一道暗折 */
    '    <path d="M566 330C580 314 596 306 612 302" fill="none" stroke="#EAF7EE" stroke-width="1.6" stroke-linecap="round" opacity=".16"/>',
    '    <path d="M590 300C604 288 618 280 630 276" fill="none" stroke="#EAF7EE" stroke-width="1.4" stroke-linecap="round" opacity=".13"/>',
    '    <path d="M608 268C618 258 626 252 632 249" fill="none" stroke="#EAF7EE" stroke-width="1.2" stroke-linecap="round" opacity=".12"/>',
    '    <path d="M576 336C592 322 610 312 626 306" fill="none" stroke="#2F5A44" stroke-width="2.2" stroke-linecap="round" opacity=".12"/>',
    '  </g>',
    /* 卷边帽檐：沿帽锥底口走的三道带子（厚度 + 布面高光），跟着锥底曲率，不会变圆盘 */
    '  <path d="M556 346C578 359 622 359 648 340" fill="none" stroke="#2B513D" stroke-width="13" stroke-linecap="round"/>',
    '  <path d="M556 344.4C578 357.4 622 357.4 648 338.4" fill="none" stroke="url(#tg-hat)" stroke-width="9" stroke-linecap="round"/>',
    '  <path d="M558 342.2C579 352.8 621 352.8 646 335.8" fill="none" stroke="#CFE9D8" stroke-width="1.8" stroke-linecap="round" opacity=".4"/>',
    '  <path d="M557 340.6C578 351.4 622 351.4 647 334.4" fill="none" stroke="#DCE8D6" stroke-width="1" stroke-linecap="round" opacity=".45"/>',
    '  <path d="M557 350.4C579 363.4 621 363.4 647 344.4" fill="none" stroke="#1C3324" stroke-width="1.4" stroke-linecap="round" opacity=".45"/>',
    '  <circle cx="634" cy="242" r="12.5" fill="url(#tg-pom)"/>',
    '  <circle cx="629" cy="238" r="4.6" fill="#FFFDF4" opacity=".8"/>',
    '  <path d="M645 248c6-4 13-1 11 5" fill="none" stroke="#E2C494" stroke-width="2.2" stroke-linecap="round"/>',
    '</g>',

    '</g>'
  ].join('');

  window.Assets = window.Assets || {};
  window.Assets.tux = {
    svg: svg,
    /* SVG 用户坐标下的旋转/缩放原点，直接喂给 GSAP 的 svgOrigin */
    parts: {
      'tux': [600, 500],
      'tux-shadow': [600, 657],
      'tux-body': [600, 566],
      'tux-belly': [600, 566],
      'tux-head': [600, 489],
      'tux-beak': [600, 440],
      'tux-eye-l': [570, 412],
      'tux-eye-r': [630, 412],
      'tux-flipper-l': [498, 486],
      'tux-flipper-r': [702, 486],
      'tux-foot-l': [562, 630],
      'tux-foot-r': [638, 630],
      'tux-hat': [600, 347],
      'tux-headphones': [600, 402],
      'tux-rimlight': [600, 500],
      'tux-tray': [600, 526]
    },
    /* 给 motion 的挂点说明：托盘盘面 y=527，蛋糕盘底 y=648 → dy=-121 */
    trayAnchor: { x: 600, y: 526, cakeOffsetY: -122 }
  };
})();
