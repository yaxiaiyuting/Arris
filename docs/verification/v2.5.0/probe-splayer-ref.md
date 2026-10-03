# probe-splayer-ref：SPlayer-Next 与 Material 3 形状/动效/取色规范核实

> **本文性质**：外部事实核实报告，不是设计提案。目的只有一个 —— 把任务书里关于
> 「SPlayer-Next 的设计系统」以及「Material 3 圆角 / M3 Expressive 动效 / Material You 取色」
> 的**可核实断言**逐条对照一手来源（GitHub 源码、Google 官方仓库、developer.android.com），
> 分清**已证实 / 已证伪 / 未能证实**三类。
>
> **方法**：`curl` 拉 raw.githubusercontent.com 上的真实源文件 + 抓 developer.android.com 参考页。
> 每一条事实都附来源 URL。凡是没能从一手来源读到的，一律进 §9「未能证实」，**不猜**。
>
> **探测日期**：2026-09-25（UTC）。仓库活跃度类数据是当日快照，会变。
>
> 本文件被 `probe-theme.md` §3.3 引用为「M3 官方 shape scale 核实结果」的出处。

---

## 结论摘要（先行）

| 任务书断言 | 核实结果 | 一句话依据 |
|---|---|---|
| 存在一个叫 SPlayer-Next 的 Android 音乐播放器 | ❌ **证伪** | SPlayer-Next 是 **Electron + Vue 3 桌面播放器**，仓库内 0 个 Gradle/Kotlin/AndroidManifest 文件 |
| SPlayer-Next 是 Material 3 Expressive / Material You 应用 | ⚠️ **部分成立但措辞错误** | 它确实用 Material 的颜色算法做封面取色，但它是**网页技术栈**，没有 Compose，没有 M3 组件，没有 M3 Expressive 动效 |
| 取色用 `QuantizerCelebi` + HCT | ✅ **证实（针对 SPlayer-Next）** | `src/utils/color.ts` 直接 `import { QuantizerCelebi, Hct } from "@material/material-color-utilities"` |
| 取样尺寸是 **50×50** | ❌ **证伪** | 常量 `COVER_SAMPLE_SIZE = 64`；「50×50」只出现在**过期的 JSDoc 注释**里 |
| 流程是「像素量化 → 颜色评分 → 主题生成」 | ⚠️ **部分成立** | 量化 ✅、主题生成 ✅，但**评分用的是自己写的加权函数，不是库里的 `Score` 类** |
| 用 `Score` 类选最佳色 | ❌ **证伪（针对 SPlayer-Next）** | SPlayer-Next **没有** import `Score`；`Score` 出现在**另一个项目** SPlayer-for-Android 里 |
| M3 圆角：xs4 / s8 / m12 / l16 / xl28 dp | ✅ **证实** | androidx `ShapeTokens.kt` + MDC-Android `Shape.md` 双向印证，**逐值一致** |
| M3 Expressive 动效是「弹簧驱动、spatial/effects 四层」 | ❌ **「四层」证伪，「弹簧驱动」证实** | 官方是 **3 档速度（fast/default/slow）× 2 类（spatial/effects）= 6 条 spring**，MDC 文档原文写「a total of six spring attributes」 |
| 每条 spring 有对应的 tween 回退（时长 + 缓动） | ❌ **在 androidx Compose 中证伪** | `MotionScheme` 的 6 个方法**全部**返回 `spring(...)`，源码里没有任何 tween 回退路径 |
| `QuantizerCelebi` + HCT 来自 material-color-utilities | ✅ **证实** | Google 官方仓库 `material-foundation/material-color-utilities`，Apache-2.0 |
| `androidx.palette:palette-ktx:1.0.0` 提供 QuantizerCelebi/HCT | ❌ **证伪** | `androidx.palette` 是**另一套**旧 API（Vibrant/Muted 等 target profile），与 HCT 无关 |

**给下游的一句话**：任务书里那份「SPlayer-Next 设计系统」描述，是把
**SPlayer-Next（桌面/Electron）**、**SPlayer-for-Android（Vue3+Capacitor）** 和
**material-color-utilities（Google 库）** 三者的特征**混在了一起**，
并且引用了 SPlayer-Next 源码里一段**已经与实现脱节的注释**（50×50 + `Score`）。
圆角五档是**真的**，可以直接用；动效「四层」是**假的**，真实结构是 3×2=6 条 spring。

---

## §1 SPlayer-Next 到底是什么

### 1.1 同名/近名项目辨析（重要）

搜索 "SPlayer" 会同时命中**四个互不相同的项目**。任务书把它们混为一谈了：

| # | 项目 | 仓库 | 形态 / 技术栈 | 许可证 | 是否 Android | 是否 Compose |
|---|---|---|---|---|---|---|
| A | **SPlayer-Next** | [SPlayer-Dev/SPlayer-Next](https://github.com/SPlayer-Dev/SPlayer-Next) | **跨平台桌面**：Electron + Vue 3 + TypeScript + Rust 原生模块（FFmpeg） | **AGPL-3.0** | ❌ | ❌ |
| B | SPlayer（前身） | [imsyy/SPlayer](https://github.com/imsyy/SPlayer) / [SPlayer-Dev/SPlayer](https://github.com/SPlayer-Dev/SPlayer) | Web/桌面，Vue 3 | AGPL-3.0 | ❌ | ❌ |
| C | SPlayer for Android | [SPlayer-Dev/SPlayer-for-Android](https://github.com/SPlayer-Dev/SPlayer-for-Android) | **Android**，但是 **Vue 3 + Capacitor 8**（WebView 混合应用），ExoPlayer + WebView 双引擎 | AGPL-3.0 | ✅ | ❌ |
| D | Next Player | [anilbeesetti/nextplayer](https://github.com/anilbeesetti/nextplayer) | **Android 原生 Kotlin**（视频播放器，非音乐） | 见仓库 | ✅ | 部分（Android 原生 + Compose） |

> **最容易踩的坑**：任务书写「Android SPlayer-Next」——
> 不存在这个项目。最接近的是 **C（SPlayer-for-Android）**，但它**不是 Compose**；
> 名字里带 "Next" 的是 **A（桌面）** 和 **D（视频播放器）**。

### 1.2 SPlayer-Next（A）的确定事实

来源：[README.md](https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-Next/main/README.md)、
[package.json](https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-Next/main/package.json)、
[LICENSE](https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-Next/main/LICENSE)、
[仓库页](https://github.com/SPlayer-Dev/SPlayer-Next)

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | `https://github.com/SPlayer-Dev/SPlayer-Next` | package.json `repository.url` |
| 自我描述 | "Cross-platform desktop music player with rich lyric support and wide audio format compatibility" | README |
| 技术栈 | Electron（`electron-vite`、`electron-builder`）+ Vue 3（`vue-tsc`）+ Rust 原生模块（`cargo test --workspace`） | package.json scripts |
| 包名 / 产品名 | `splayer-next` / `SPlayer-Next`，desktopName `top.imsyy.splayer_next` | package.json |
| 作者 / 主页 | imsyy（imsyy1024@gmail.com），https://splayer-next.imsyy.top | package.json |
| 版本 | package.json `1.1.0`；最新正式 release `v1.1.0`，另有 `v1.2.0-nightly.904` | [releases.atom](https://github.com/SPlayer-Dev/SPlayer-Next/releases.atom) |
| 许可证 | **AGPL-3.0**（LICENSE 文件正文为 GNU AFFERO GPL v3） | LICENSE |
| 活跃度 | 最后提交 `2026-09-24 16:17:59 +0800`（`ceb9d72` "Merge pull request #319 from SPlayer-Dev/feat/audio-preload-dual-slot"） | [commits/main.atom](https://github.com/SPlayer-Dev/SPlayer-Next/commits/main.atom) + 浅克隆 `git log -1` |
| Star | 1.2k（2026-09-25 快照，会变） | [shields.io](https://img.shields.io/github/stars/SPlayer-Dev/SPlayer-Next.json) |
| 原生模块 | `audio-engine`（FFmpeg 解码/播放/FFT/封面提取）、`media-ctrl`（系统媒体控制 + Discord RPC）、`taskbar-lyric`（Windows 任务栏歌词） | README |
| 主题相关功能 | README 列出 "🎨 **Adaptive theming** — cover-based colors, Light / Dark / Auto" | README |

**平台否证（硬证据）**：对浅克隆工作树执行
`find . -iname "*.gradle*" -o -iname "AndroidManifest.xml" -o -iname "*.kt" -o -iname "pubspec.yaml"`
在排除 `node_modules` 后**返回空**。即：仓库内**没有任何** Gradle 构建脚本、Android 清单、
Kotlin 源文件或 Flutter 工程文件。**SPlayer-Next 不可能是 Android 应用，也不可能是 Compose 应用。**

### 1.3 SPlayer for Android（C）的确定事实

来源：[README.md](https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-for-Android/master/README.md)、
[package.json](https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-for-Android/master/package.json)

| 项 | 值 |
|---|---|
| 版本 | `3.0.0-rc.4` |
| 描述 | "Android-first SPlayer build with Capacitor and embedded local API" |
| 技术栈 | Vue 3 + **Capacitor 8**（`@capacitor/android@^8.4.0`）+ `nodejs-mobile-cordova` 内嵌 ncm API |
| 最低系统 | **Android 10（API 29）** |
| 许可证 | AGPL-3.0 |
| 播放引擎 | 原生 **ExoPlayer** + WebView 双引擎 |
| Star / 活跃度 | 93 star；最后提交 2026-08 |
| 取色实现 | `@material/material-color-utilities@^0.4.0` + **Web Worker**（见 §2.3） |

> 注意：README 中作者自述身体与心理状态欠佳、更新推迟。这是**维护风险**信号，不涉及技术。

### 1.4 「SPlayer-Next 的设计系统」这个说法为什么不成立

SPlayer-Next 是 Web 技术栈的桌面应用：它的「设计系统」是
**CSS 自定义属性 + UnoCSS**（仓库内有 `uno.config.ts`，`applyThemeToDOM()` 往
`document.documentElement` 写 `--s-*` 变量并切 `.dark` class）。
它**没有** Material 3 的 shape scale、**没有** M3 Expressive 的 motion token、
**没有** Material 组件库。它只借用了 Google 的**颜色算法**（material-color-utilities）。

因此：**任务书里「SPlayer-Next 使用 M3 Expressive 圆角 + 弹簧动效」这一整条推理链，前提就是断的。**
圆角与动效的规格只能回到 Google 一手来源去核实（§4、§5），**不能**用 SPlayer-Next 当证据。

---

## §2 封面取色算法：逐条核实

### 2.1 任务书断言 vs 实测

任务书原文断言：*"QuantizerCelebi + HCT 色彩空间"，提取流程为 "50×50 Canvas → 像素量化 → 颜色评分 → 主题生成"*。

| 断言片段 | SPlayer-Next（桌面） | SPlayer-for-Android | 判定 |
|---|---|---|---|
| `QuantizerCelebi` | ✅ 有，`quantize(pixels, 128)` | ✅ 有，`quantize(pixels, 128)` | **证实** |
| HCT 色彩空间 | ✅ 有，`Hct.fromInt` / `Hct.from(h,c,t)` | ✅ 有，`Hct.from(hue, chroma, tone)` | **证实** |
| **50×50** Canvas | ❌ `COVER_SAMPLE_SIZE = 64` | ❌ `COVER_SAMPLE_SIZE = 32` | **证伪**（两个项目都不是 50） |
| 像素量化 | ✅ | ✅ | **证实** |
| 颜色评分 | ⚠️ 自写加权函数 | ✅ 用库里的 `Score.score()` | **两者不同**，任务书描述只对 C 成立 |
| 主题生成 | ✅ `themeFromSourceColor()` | ✅ `themeFromSourceColor()` | **证实** |

**「50×50」的来源推断（高置信）**：SPlayer-Next 的 `extractColorFromImageElement` 函数上挂着
一段 JSDoc：`缩放到 50×50 降低计算量，经 QuantizerCelebi 量化 + Score 评分`。
但同一个文件里的常量是 `const COVER_SAMPLE_SIZE = 64;`，且**从未 import `Score`**。
→ **这句注释同时说错了两件事（尺寸、评分方式），任务书是照着这句过期注释写的。**

### 2.2 SPlayer-Next 实际实现（逐段）

来源：[`src/utils/color.ts`](https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-Next/main/src/utils/color.ts)

```ts
import {
  argbFromHex, themeFromSourceColor, QuantizerCelebi, Hct, type Theme,
} from "@material/material-color-utilities";
```

常量（原文）：

| 常量 | 值 | 作用 |
|---|---|---|
| `DEFAULT_PRIMARY` | `"#fe7971"` | 无封面时的兜底主色 |
| `coverColorToken` | `let = 0` | **竞态 token**，每次请求自增，回调里比对 |
| `COVER_SAMPLE_SIZE` | **`64`** | canvas 边长（注释说 50，**不一致**） |
| `COVER_EDGE_MARGIN` | `3` | 裁掉外圈 3px，避边框 |
| `MIN_COVER_CHROMA` | `8` | 候选色最小 HCT chroma |
| `MIN_COLORFUL_POPULATION_RATIO` | `0.12` | 彩色像素占比下限，低于此判为「单调封面」 |

**中心加权采样**（原文 `sampleWeight`）：按到中心的归一化距离分三档权重

```ts
if (distance < 0.34) return 3;
if (distance < 0.58) return 2;
return 1;
```

即中心像素被 push 3 次、边缘 1 次，用**重复入数组**的方式实现加权（而不是给 quantizer 传权重）。

**自写评分器 `pickRepresentativeCoverColor`（不是库的 `Score`）**：

```ts
const hct = Hct.fromInt(argb);
const populationScore = Math.pow(count / maxCount, 0.72);
const chromaScore     = Math.min(hct.chroma / 52, 1);
const toneScore       = Math.max(0, 1 - Math.abs(hct.tone - 58) / 58);
const score = populationScore * 0.58 + chromaScore * 0.28 + toneScore * 0.14;
```

选色前置过滤：`chroma >= 8` **且** `10 <= tone <= 94`；且彩色像素总占比
`colorfulCount / totalCount >= 0.12`，否则返回 `null`（→ 回退兜底色）。

**主色后处理（把它压进「能当基色」的范围）**：

```ts
const toCoverBaseColor = (argb) => {          // 背景基色
  const hct = Hct.fromInt(argb);
  const tone    = Math.min(72, Math.max(28, hct.tone));
  const chroma  = Math.min(64, Math.max(12, hct.chroma));
  return argbToHex(Hct.from(hct.hue, chroma, tone).toInt());
};
const toCoverUiColor = (hex) => {             // 前景 UI 色
  const hct = Hct.fromInt(argbFromHex(hex));
  const tone = 88;
  const chroma = Math.min(30, Math.max(14, hct.chroma * 0.48));
  return argbToHex(Hct.from(hct.hue, chroma, tone).toInt());
};
```

**色板生成：用 `secondary` palette 而非 `primary`**（原文注释：`// 用 secondary palette 生成主色`）：

```ts
const theme: Theme = themeFromSourceColor(argbFromHex(safeHex));
const { hue, chroma } = theme.palettes.secondary;
const toneColor = (tone) => /* Hct.from(hue, chroma, tone) → "R G B" */;
const primary            = isDark ? toneColor(90) : toneColor(10);
const primaryContainer   = isDark ? toneColor(30) : toneColor(90);
const onPrimary          = isDark ? toneColor(10) : toneColor(100);
const onPrimaryContainer = isDark ? toneColor(90) : toneColor(10);
```

> 注意 `onPrimary` 在浅色下取 `tone 100`（纯白）—— 这对 WCAG 对比度是**有风险**的做法，
> 依赖 `primary` 恰好落在 tone 10（很暗）才成立。

**其他工程细节（可直接借鉴）**：

- Canvas 上下文用 `getContext("2d", { willReadFrequently: true })`。
- `drawImage` + `getImageData` 包在 `try/catch` 里 —— 注释明确写「跨域无 CORS 头会污染 canvas」。
- 用完把 `canvas.width = 0; canvas.height = 0;` **释放 GPU 资源**。
- 跨域封面走「**主进程拉字节 → Blob → 同源 blob URL → canvas**」绕开 tainted canvas，
  并在所有分支 `URL.revokeObjectURL(blobUrl)`。
- 异步竞态用单调递增 `coverColorToken` 比对，过期回调直接 return。

### 2.3 SPlayer-for-Android 实际实现（与前者差异很大）

来源：[`src/utils/coverColor.worker.ts`](https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-for-Android/master/src/utils/coverColor.worker.ts)、
[`src/utils/color.ts`](https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-for-Android/master/src/utils/color.ts)

```ts
import { themeFromSourceColor, QuantizerCelebi, Hct, Score, type Theme }
  from "@material/material-color-utilities";

export const COVER_SAMPLE_SIZE = 32;   // ← 不是 50，也不是 64
```

核心算法（**这才是任务书描述的「量化 → 评分 → 主题生成」的真身**）：

```ts
const quantizedColors = QuantizerCelebi.quantize(pixels, 128);
const sortedQuantizedColors = Array.from(quantizedColors).sort((a, b) => b[1] - a[1]);

// 灰度封面检测：出现频率最高的 5 色，若每色 max(R,G,B)-min(R,G,B) < 5 则判为单调
const mostFrequentColors = sortedQuantizedColors.slice(0, 5).map((x) => argbToRgbTuple(x[0]));
if (mostFrequentColors.every((x) => Math.max(...x) - Math.min(...x) < 5)) return MONOTONOUS_THEME;

// 颜色评分：只取前 50 个候选喂给官方 Score
const ranked = Score.score(new Map(sortedQuantizedColors.slice(0, 50)));
const topColor = ranked[0];
const theme = themeFromSourceColor(topColor);
return getThemeSchema(theme, variant);   // variant ∈ primary|secondary|tertiary|neutral|neutralVariant|error
```

**工程化程度高于桌面版**：

| 机制 | 实现 |
|---|---|
| **Web Worker 卸载** | `getCoverColorDataByWorker()`：`new CoverColorWorker()` → `postMessage(request, [buffer])` 用 **Transferable ArrayBuffer 零拷贝** |
| **超时保护** | `reject(new Error("Cover color worker timeout"))` |
| **降级** | Worker 失败 → `catch` 走 `getCoverColorDataFromImageData()` **主线程回退** |
| **缓存** | `readCoverColorCache(coverUrl)` 命中则「**跳过图片加载与 quantize 重计算**」；`writeCoverColorCache` 有 LRU 淘汰 |
| **空闲调度** | 全部包在 `runWhenIdle(...)` 里执行 |
| **竞态** | `coverColorRequestId` 单调递增比对 |
| **灰度兜底** | `MONOTONOUS_THEME` 常量（纯色模式 / 灰度封面共用同一套回退色） |

**默认配色推导（`getThemeSchema`）**：取 `theme.palettes[variant]` 的 `{hue, chroma}`，
`main = tone(90)`，浅色 `primary = tone(10) / background = tone(94) / surface-container = tone(90)`，
深色 `primary = tone(90) / background = tone(20) / surface-container = tone(16)`。

### 2.4 两个 SPlayer 取色实现对比

| 维度 | SPlayer-Next（桌面） | SPlayer-for-Android |
|---|---|---|
| 采样 | **64×64** | **32×32** |
| 边缘处理 | 裁 3px + 中心加权 | 无（全图） |
| 量化 | `QuantizerCelebi.quantize(pixels, 128)` | 同 |
| 选色 | **自写加权评分**（population .58 / chroma .28 / tone .14） | **官方 `Score.score()`** |
| 灰度检测 | 彩色占比 < 0.12 → null | 前 5 高频色近灰 → `MONOTONOUS_THEME` |
| 执行线程 | 主线程同步（`extractColorFromImageElement`） | **Web Worker + Transferable + 超时** |
| 缓存 | 无 | **有（LRU，按 URL）** |
| 主题推导 | `palettes.secondary` | `palettes[variant]`（可选 6 种） |
| 依赖版本 | `@material/material-color-utilities@0.4.0`（pnpm-lock 钉死） | `^0.4.0` |

---

## §3 material-color-utilities 到底提供了什么

**仓库**：<https://github.com/material-foundation/material-color-utilities>
**许可证**：**Apache-2.0**（[LICENSE](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/LICENSE)）
**语言实现**（[README](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/README.md)）：C++ / Dart / Java / Swift / TypeScript / Kotlin 均有
**npm**：`@material/material-color-utilities@0.4.0`（[typescript/package.json](https://raw.githubusercontent.com/material-foundation/material-color-utilities/typescript/package.json) 原文 `"version": "0.4.0"`）
**Star**：2.3k；最后提交 2026-08；**GitHub Releases 页为空**（无 tag 发布，版本只走各语言包管理器）

README 的能力组件表（原文）：

| 组件 | 用途（原文摘译） |
|---|---|
| **blend** | 在 HCT 中插值、协调（harmonize）、动画、渐变 |
| **contrast** | 度量对比度、取得有对比度的颜色 |
| **dislike** | 检查并修正「普遍令人厌恶」的颜色 |
| **dynamiccolor** | 按 UI 状态（深色模式、风格、偏好、对比度要求）取得动态颜色 |
| **hct** | 基于 **CAM16 × L\*** 的新色彩空间（hue, chrome, tone），考虑观察条件 |
| **palettes** | TonalPalette（仅 tone 变化）+ CorePalette（构建 Material 配色所需的调色板组） |
| **quantize** | 把图像变成 N 色；**由 Celebi 组成，Celebi 先跑 Wu、再跑 WSMeans** |
| **scheme** | 从单个颜色或 core palette 生成静态/动态配色方案 |
| **score** | 为配色目的对颜色排序 |
| **temperature** | 取得邻近色与互补色 |
| **utilities** | Color（色彩空间转换）、Math（hue 归一化、clamp）、String |

> 上面「quantize：Celebi runs Wu, then WSMeans」是 README 原文，直接印证 §3.3。

### 3.1 `QuantizerCelebi`

来源：[java/quantize/QuantizerCelebi.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/quantize/QuantizerCelebi.java)

类文档原文（要点）：

> An image quantizer that improves on the quality of a standard K-Means algorithm by
> setting the K-Means initial state to the output of a **Wu quantizer**, instead of random centroids.
> Improves on speed by several optimizations, as implemented in **Wsmeans, or Weighted Square Means**…
> This algorithm was designed by **M. Emre Celebi**, and was found in their **2011 paper,
> "Improving the Performance of K-Means for Color Quantization"** (arXiv:1101.0395)

完整实现（**这就是它的全部内容**，说明它是个组合器而非独立算法）：

```java
public static Map<Integer, Integer> quantize(int[] pixels, int maxColors) {
  QuantizerWu wu = new QuantizerWu();
  QuantizerResult wuResult = wu.quantize(pixels, maxColors);
  // Set<Integer> wuClustersAsObjects = wuResult.colorToCount.keySet(); → int[] wuClusters
  return QuantizerWsmeans.quantize(pixels, wuClusters, maxColors);
}
```

| API | 细节 |
|---|---|
| 签名 | `public static Map<Integer, Integer> quantize(int[] pixels, int maxColors)` |
| `pixels` | **ARGB 格式**的 int 数组（Java 侧返回裸 `Map`；TS 侧返回 `QuantizerResult`） |
| `maxColors` | 文档原文：*"The number of colors to divide the image into. **A lower number of colors may be returned.**"* |
| 返回 | key = ARGB 颜色，value = 原图中对应该颜色的**像素数** |
| 关于「默认色数」 | 库**没有**给 `maxColors` 设默认值。两个 SPlayer 都传 **`128`**（§2.2、§2.3）。这是**调用方约定**，不是库常量 |

> ⚠️ **纠正任务书**：Celebi 本身**不**做评分，也**不**选「最佳色」。
> 它只输出 `颜色 → 像素数` 的直方图。选最佳色是 `Score`（或调用方自写逻辑）的事。

### 3.2 `QuantizerWsmeans`（WSMeans）

来源：[java/quantize/QuantizerWsmeans.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/quantize/QuantizerWsmeans.java)

| 项 | 值 |
|---|---|
| 全称 | **W**eighted **S**quare **Means** —— 带优化的 K-Means |
| 常量 | `MAX_ITERATIONS = 10`；`MIN_MOVEMENT_DISTANCE = 3.0` |
| 签名 | `public static Map<Integer, Integer> quantize(int[] pixels, int[] startingClusters, int maxColors)` |
| 随机性 | 源码注释：*"Uses a **seeded random number generator** to ensure consistent results."* → **结果可复现** |
| 与 Celebi 的关系 | Celebi = **Wu 提供的确定性初值** + WSMeans 迭代（替代随机质心，因此更快更稳） |

### 3.3 `QuantizerWu`

来源：[java/quantize/QuantizerWu.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/quantize/QuantizerWu.java)

文档原文：*"divides the image's pixels into clusters by **recursively cutting an RGB cube**, based on
the weight of pixels in each area of the cube. The algorithm was described by **Xiaolin Wu in
Graphic Gems II, published in 1991**."*

| 项 | 值 |
|---|---|
| 签名 | `public QuantizerResult quantize(int[] pixels, int maxColors)`（实现 `Quantizer` 接口） |
| 直方图降位 | `INDEX_BITS = 5`（每通道 5 bit），`INDEX_COUNT = 33`，`TOTAL_SIZE = 35937`（≈32k 桶） |
| 注释原文 | 16M 色立方体太大，历史最佳实践是每通道取 8 bit 中的 5 bit，把直方图降到约 32,000 体积 |

### 3.4 `QuantizerResult`

来源：[java/quantize/QuantizerResult.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/quantize/QuantizerResult.java)

```java
public final class QuantizerResult {
  public final Map<Integer, Integer> colorToCount;
}
```

以及包内接口 [Quantizer.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/quantize/Quantizer.java)：

```java
interface Quantizer { public QuantizerResult quantize(int[] pixels, int maxColors); }
```

### 3.5 HCT 色彩空间

来源：[java/hct/Hct.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/hct/Hct.java)

**定义（类文档原文）**：*"A color system built using **CAM16 hue and chroma**, and **L\* from L\*a\*b\***."*

| 分量 | 含义 | 来源 | 取值范围（源码注释原文） |
|---|---|---|---|
| **H** = hue | 色相 | **CAM16** 的 hue | `0 <= hue < 360`，非法值自动修正 |
| **C** = chroma | 彩度（"Informally, **colorfulness**"） | **CAM16** 的 chroma | `0 <= chroma < ?`；**无固定上界**，注释原文："Chroma has a **different maximum for any given hue and tone**"；TonalPalette 注释说 sRGB 色域内约 `0 到 ~130` |
| **T** = tone | 色调（感知亮度） | **CIE L\*a\*b\* 的 L\*** | `0 <= tone <= 100` |

**为什么用 L\* 而不是 Y（源码文档原文，很关键）**：

> Using L\* creates a link between the color system, contrast, and thus accessibility.
> Contrast ratio depends on relative luminance, or Y… L\*, or perceptual luminance can be calculated from Y.
> Unlike Y, **L\* is linear to human perception**, allowing trivial creation of accurate color tones.
> **A difference of 40 in HCT tone guarantees a contrast ratio >= 3.0, and a difference of 50 guarantees a contrast ratio >= 4.5.**

> 最后这句是**可执行的验收依据**：用 tone 差 ≥ 50 做前景/背景配对，
> 就能拿到 WCAG AA 正文对比度 4.5:1。Ncrust 的 `onAccentColor`(WCAG 4.5:1) 约束可以完全交由 tone 算术保证。

**主要 API**：

| 方法 | 签名 / 说明 |
|---|---|
| 构造 | `public static Hct from(double hue, double chroma, double tone)` —— 内部走 `HctSolver.solveToInt(h, c, t)`；**返回的实际 chroma 可能低于请求值**（色域裁剪） |
| 从 int | `public static Hct fromInt(int argb)` |
| 取值 | `getHue() / getChroma() / getTone()` |
| 转出 | `public int toInt()` |
| 特殊情况 | `Hct.isYellow(hue)` / `Hct.isBlue(...)`：TonalPalette 中 `tone == 99 && isYellow(hue)` 时取 `tone(98)` 与 `tone(100)` 的**平均**（黄色在 tone 99 处有非线性） |
| 观察条件 | 有 `ViewingConditions` 概念（默认观察条件下构造） |

> **注意**：`Hct.from(h, c, t)` **不是**无损往返。`Hct.fromInt(x).toInt() == x` 成立；
> 但 `Hct.from(hct.hue, hct.chroma, hct.tone).toInt()` 在色域外会被裁剪。
> SPlayer 的两种后处理（§2.2）正是利用这一点**主动**把颜色 clamp 进安全区。

### 3.6 `Score` 类（选「最佳色」的真正位置）

来源：[java/score/Score.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/score/Score.java)

类文档原文：*"Given a large set of colors, **remove colors that are unsuitable for a UI theme**,
and **rank the rest based on suitability**. Enables use of a **high cluster count** for image
quantization, thus ensuring colors aren't muddied, while curating the high cluster count to a much
smaller number of appropriate choices."*

**常量（源码原文）**：

```java
private static final double TARGET_CHROMA = 48.;          // A1 Chroma
private static final double WEIGHT_PROPORTION = 0.7;
private static final double WEIGHT_CHROMA_ABOVE = 0.3;
private static final double WEIGHT_CHROMA_BELOW = 0.1;
private static final double CUTOFF_CHROMA = 5.;
private static final double CUTOFF_EXCITED_PROPORTION = 0.01;
```

**重载**（全部 `Map<颜色, 像素数>` 入参）：

```java
static List<Integer> score(Map<Integer,Integer> colorsToPopulation);
static List<Integer> score(Map<Integer,Integer> colorsToPopulation, int desired);
static List<Integer> score(Map<Integer,Integer> colorsToPopulation, int desired, int fallbackColorArgb);
static List<Integer> score(Map<Integer,Integer> colorsToPopulation, int desired, int fallbackColorArgb, boolean filter);
```

**默认值**：`desired = 4`；`fallbackColorArgb = 0xff4285f4`（源码注释原文：*"Fallback color is **Google Blue**"*）。

**算法四步（源码逐段）**：

1. **每个颜色转 HCT**，同时统计 `huePopulation[360]` 与 `populationSum`。
2. **hue 邻域激活（"excited proportion"）** —— 关键细节：
   ```java
   for (int i = hue - 14; i < hue + 16; i++)
     hueExcitedProportions[sanitizeDegreesInt(i)] += proportion;
   ```
   即每个色相把自身占比累加到**左右共 30° 的邻域**（注释原文：*"Hues with more usage in
   neighboring **30 degree slice** get a larger number."*）。
3. **过滤 + 打分**：
   ```java
   if (filter && (hct.getChroma() < CUTOFF_CHROMA || proportion <= CUTOFF_EXCITED_PROPORTION)) continue;
   double proportionScore = proportion * 100.0 * WEIGHT_PROPORTION;              // ×0.7
   double chromaWeight = hct.getChroma() < TARGET_CHROMA ? WEIGHT_CHROMA_BELOW : WEIGHT_CHROMA_ABOVE;
   double chromaScore = (hct.getChroma() - TARGET_CHROMA) * chromaWeight;        // 低于/高于 48 用不同权重
   double score = proportionScore + chromaScore;
   ```
   → **chroma 高于 48 时每多一点加 0.3；低于 48 时每少一点只扣 0.1**（有意偏向高彩度但不惩罚低彩度）。
4. **保证色相分散**：从 `differenceDegrees = 90` 递减到 `15`，反复挑选
   （注释原文：*"select the colors with the **largest distribution of hues** possible.
   Starting at 90 degrees (maximum difference for 4 colors) then decreasing down to a 15 degree minimum."*）
   直到选够 `desired` 个。

**返回契约**：按适配度降序排列，**至少返回一个颜色**；若全部不适配则返回兜底色。

> 两个 SPlayer 里，只有 **SPlayer-for-Android** 用它（`Score.score(map)`，desired 走默认 4，取 `ranked[0]`）。
> SPlayer-Next 完全没用。

### 3.7 `CorePalette` / `TonalPalette`

来源：[java/palettes/CorePalette.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/palettes/CorePalette.java)、
[java/palettes/TonalPalette.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/palettes/TonalPalette.java)

**TonalPalette 定义**（类文档原文）：*"A convenience class for retrieving colors that are
**constant in hue and chroma, but vary in tone**. … **intended for use in a single thread due to its
stateful caching**."*

> ⚠️ **这是官方明确的线程约束**：`TonalPalette` 内部有 `Map<Integer,Integer> cache`，
> **不是线程安全的**。跨线程共享需要每个线程各自构造。

| API | 说明 |
|---|---|
| `TonalPalette.fromInt(int argb)` | 取该色的 hue+chroma |
| `TonalPalette.fromHct(Hct hct)` | 同上 |
| `TonalPalette.fromHueAndChroma(double hue, double chroma)` | 显式指定；内部用 `new KeyColor(hue, chroma).create()` 求 keyColor |
| `int tone(int tone)` | 取指定 tone 的 ARGB；**带缓存**；`tone==99 && isYellow(hue)` 时返回 `tone(98)` 与 `tone(100)` 的平均 |
| `Hct getHct(double tone)` | 返回 HCT 而非 ARGB |
| `double getChroma() / getHue()` | 调色板的 chroma / hue |

**CorePalette**（注意：**官方已标记 `@Deprecated`**，文档原文 *"Use `dynamiccolor.DynamicScheme` for
color scheme generation. Use `palettes.CorePalettes` for core palettes container class."*）：

类文档原文：*"5 sets of tones are generated, **all except one use the same hue as the key color**,
and all vary in chroma."* 字段为 `a1 a2 a3 n1 n2 error`。

**构造逻辑（源码原文，这就是 palette 派生规则）**：

```java
// CorePalette.of(argb)        — isContent = false 【Material 3 默认 / TonalSpot 血统】
a1 = TonalPalette.fromHueAndChroma(hue,        max(48., chroma));  // primary
a2 = TonalPalette.fromHueAndChroma(hue,        16.);               // secondary
a3 = TonalPalette.fromHueAndChroma(hue + 60.,  24.);               // tertiary  ← 色相旋转 +60°
n1 = TonalPalette.fromHueAndChroma(hue,        4.);                // neutral
n2 = TonalPalette.fromHueAndChroma(hue,        8.);                // neutralVariant
error = TonalPalette.fromHueAndChroma(25,      84.);               // error（固定 hue 25 / chroma 84）

// CorePalette.contentOf(argb) — isContent = true【content 风格】
a1 = fromHueAndChroma(hue, chroma);
a2 = fromHueAndChroma(hue, chroma / 3.);
a3 = fromHueAndChroma(hue + 60., chroma / 2.);
n1 = fromHueAndChroma(hue, min(chroma / 12., 4.));
n2 = fromHueAndChroma(hue, min(chroma / 6., 8.));
```

| palette | 角色 | hue | chroma（`of` / M3 默认） | chroma（`contentOf`） |
|---|---|---|---|---|
| a1 | primary | source hue | `max(48, C)` | `C` |
| a2 | secondary | source hue | `16` | `C/3` |
| a3 | tertiary | **source hue + 60°** | `24` | `C/2` |
| n1 | neutral | source hue | `4` | `min(C/12, 4)` |
| n2 | neutralVariant | source hue | `8` | `min(C/6, 8)` |
| error | error | **25（固定）** | **84（固定）** | 同 |

> 任务书若声称「primary chroma 36」——那是**新版 `ColorSpec2021` 的 TonalSpot**
> （见 §3.8），不是旧版 `CorePalette`（旧版是 `max(48, C)`）。两者都真实存在，别混用。

### 3.8 `Scheme` / `SchemeTonalSpot`：角色 → tone 映射

**旧版静态 `Scheme`**（来源：[java/scheme/Scheme.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/scheme/Scheme.java)）——
这份「角色 → tone」表是最稳定、最值得直接照抄的部分：

**浅色（light）**

| 角色 | 来源 | tone | 角色 | 来源 | tone |
|---|---|---|---|---|---|
| `primary` | a1 | **40** | `onPrimary` | a1 | **100** |
| `primaryContainer` | a1 | **90** | `onPrimaryContainer` | a1 | **10** |
| `secondary` | a2 | 40 | `onSecondary` | a2 | 100 |
| `secondaryContainer` | a2 | 90 | `onSecondaryContainer` | a2 | 10 |
| `tertiary` | a3 | 40 | `onTertiary` | a3 | 100 |
| `tertiaryContainer` | a3 | 90 | `onTertiaryContainer` | a3 | 10 |
| `error` | error | 40 | `onError` | error | 100 |
| `errorContainer` | error | 90 | `onErrorContainer` | error | 10 |
| `background` / `surface` | n1 | **99** | `onBackground` / `onSurface` | n1 | **10** |
| `surfaceVariant` | n2 | 90 | `onSurfaceVariant` | n2 | 30 |
| `outline` | n2 | **50** | `outlineVariant` | n2 | **80** |
| `shadow` / `scrim` | n1 | 0 | `inversePrimary` | a1 | 80 |
| `inverseSurface` | n1 | 20 | `inverseOnSurface` | n1 | 95 |

**深色（dark）**：`primary` 80 / `onPrimary` 20 / `primaryContainer` 30 / `onPrimaryContainer` 90；
`secondary`/`tertiary`/`error` 同构（80/20/30/90）；
`background`/`surface` = n1 **10**，`onSurface` = n1 **90**；`surfaceVariant` = n2 30，`onSurfaceVariant` = n2 80；
`outline` = n2 **60**，`outlineVariant` = n2 **30**；`inverseSurface` = n1 90，`inversePrimary` = a1 40。

**`SchemeTonalSpot`（当前 M3 默认变体）** ——
来源：[java/scheme/SchemeTonalSpot.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/scheme/SchemeTonalSpot.java)

类文档原文只有一句：*"**A calm theme, sedated colors that aren't particularly chromatic.**"*

构造签名：

```java
public SchemeTonalSpot(Hct sourceColorHct, boolean isDark, double contrastLevel)
public SchemeTonalSpot(Hct sourceColorHct, boolean isDark, double contrastLevel,
                       SpecVersion specVersion, Platform platform)
public SchemeTonalSpot(List<Hct> sourceColorHctList, boolean isDark, double contrastLevel)
public SchemeTonalSpot(List<Hct> sourceColorHctList, boolean isDark, double contrastLevel,
                       SpecVersion specVersion, Platform platform)
```

它**不再硬编码 tone**，而是把 6 个 palette 的推导委托给 `ColorSpecs.get(specVersion)` 的
`getPrimaryPalette / getSecondaryPalette / getTertiaryPalette / getNeutralPalette /
getNeutralVariantPalette / getErrorPalette`，并把 `Variant.TONAL_SPOT` 传进去。

**TonalSpot 的 palette 量化值**（来源：[java/dynamiccolor/ColorSpec2021.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/dynamiccolor/ColorSpec2021.java)）：

| palette | TONAL_SPOT 的 hue / chroma（源码原文） |
|---|---|
| primary | `fromHueAndChroma(sourceColorHct.getHue(), **36.0**)` |
| secondary | `fromHueAndChroma(sourceColorHct.getHue(), **16.0**)` |
| tertiary | `fromHueAndChroma(sanitizeDegreesDouble(hue + **60.0**), **24.0**)` |
| neutral | `fromHueAndChroma(sourceColorHct.getHue(), **6.0**)` |
| neutralVariant | `fromHueAndChroma(sourceColorHct.getHue(), **8.0**)` |
| error | `Optional.empty()` → 走库内固定 error（hue 25 / chroma 84 血统） |

其他变体同一函数的取值（节选，便于对比）：`RAINBOW` primary chroma 48、`NEUTRAL` 12、
`MONOCHROME` 0、`VIBRANT` 200、`EXPRESSIVE` hue 走 `DynamicScheme.getRotatedHue(...)` 分段旋转；
`FRUIT_SALAD` 等见源码。

### 3.9 `DynamicScheme` 与 `Variant`

来源：[java/dynamiccolor/DynamicScheme.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/dynamiccolor/DynamicScheme.java)、
[java/dynamiccolor/Variant.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/dynamiccolor/Variant.java)

`DynamicScheme` 类文档原文：

> Provides important settings for creating colors dynamically, and **6 color palettes**.
> Requires: 1. A color. (source color) 2. A theme. (**Variant**) 3. Whether or not its dark mode.
> 4. **Contrast level. (-1 to 1, currently contrast ratio 3.0 and 7.0)**

公开字段：

| 字段 | 说明 |
|---|---|
| `DEFAULT_SPEC_VERSION` | `SpecVersion.SPEC_2021` |
| `DEFAULT_PLATFORM` | `Platform.PHONE` |
| `sourceColorArgb` / `sourceColorHct` / `sourceColorHctList` | 源色（新版支持**多个**源色） |
| `variant` | `Variant` 枚举 |
| `isDark`, `platform`, `specVersion` | |
| `contrastLevel` | 文档原文：*"-1 represents minimum contrast. 0 represents standard (i.e. **the design as spec'd**), and 1 represents maximum contrast."* |
| `primaryPalette` / `secondaryPalette` / `tertiaryPalette` / `neutralPalette` / `neutralVariantPalette` / `errorPalette` | 6 个 `TonalPalette` |

静态工厂：`DynamicScheme.from(other, isDark)`、`DynamicScheme.from(other, isDark, contrastLevel)`、
`DynamicScheme.getRotatedHue(sourceColorHct, hueBreakpoints, hues)`（分段色相旋转）。

`Variant` 枚举全部取值（[Variant.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/dynamiccolor/Variant.java) 原文顺序）：

```
MONOCHROME, NEUTRAL, TONAL_SPOT, VIBRANT, EXPRESSIVE, FIDELITY, CONTENT, RAINBOW, FRUIT_SALAD, CMF
```

> 任务书若提到 "fruitSalad" 小写命名，那是 **Dart 侧**的写法；Java/TS 侧是 `FRUIT_SALAD` / `fruitSalad`。
> `CMF` 是较新加入的变体。变体数量**以源码为准：10 个**。
>
> **版本演进**：`CorePalette`（旧，已 `@Deprecated`）→ `Scheme`（静态角色表）→
> `DynamicScheme` + `MaterialDynamicColors`（按 `ColorRole` 动态解析，引入 `ContrastCurve`、
> `ToneDeltaPair`、`TonePolarity`、`SpecVersion`）。
> 新版还拆出了 `ColorSpec2021 / ColorSpec2025 / ColorSpec2026` 三份规范实现，
> `ColorSpecs.get(specVersion)` 做选择。

### 3.10 JS/TS 侧入口：`themeFromSourceColor`

来源：[typescript/utils/theme_utils.ts](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/typescript/utils/theme_utils.ts)

这是**两个 SPlayer 唯一实际调用的入口**：

```ts
export interface Theme {
  source: number;
  schemes: {light: Scheme; dark: Scheme;};
  palettes: {
    primary: TonalPalette; secondary: TonalPalette; tertiary: TonalPalette;
    neutral: TonalPalette; neutralVariant: TonalPalette; error: TonalPalette;
  };
  customColors: CustomColorGroup[];
}

export function themeFromSourceColor(source: number, customColors: CustomColor[] = []): Theme {
  const palette = CorePalette.of(source);
  // ...
}
```

> **重要事实**：`themeFromSourceColor` 内部走的是**已废弃的 `CorePalette.of()`**，
> 不是 `DynamicScheme`。所以 SPlayer 系列拿到的调色板是 §3.7 的旧版规则
> （primary chroma `max(48, C)`），而**不是** §3.8 的 TonalSpot（chroma 36）。
> 若下游要「和 SPlayer 一致」，就必须走 `CorePalette` 语义，**不要**照抄 TonalSpot 的 36。

### 3.11 性能与线程（官方明文）

| 来源 | 原文约束 |
|---|---|
| `TonalPalette` 类文档 | *"**intended for use in a single thread** due to its stateful caching"* |
| `androidx.palette` `Palette` 类文档 | *"Generation should **always be completed on a background thread**, ideally the one in which you load your image on."* |
| `androidx.palette` `Palette.Builder.generate(PaletteAsyncListener)` | 已废弃，文档指引改用标准 `java.util.concurrent` 或 Kotlin 并发工具 |
| `QuantizerWsmeans` 源码 | 用**带种子的** RNG → 同输入同输出，**可复现**（对单测友好） |

### 3.12 `androidx.palette` 与 material-color-utilities 是两回事（关键澄清）

任务书把两者混为一谈。事实：

| | `androidx.palette:palette-ktx:1.0.0` | `material-color-utilities` |
|---|---|---|
| 来源 | AndroidX（[Palette 参考页](https://developer.android.com/reference/androidx/palette/graphics/Palette)） | Google material-foundation |
| 算法 | `ColorCutQuantizer`（中位切分血统）+ Target profile 打分 | Wu / WSMeans / Celebi 量化 + HCT + Score |
| 输出 | **固定 6 个 Swatch profile**：Vibrant / Vibrant Dark / Vibrant Light / Muted / Muted Dark / Muted Light | 任意数量候选色 + 6 个 TonalPalette + 完整角色配色 |
| 有无 HCT | ❌ **完全没有** | ✅ 核心 |
| 语言 | Java/Kotlin（Android 专用，依赖 `android.graphics.Bitmap`） | 多语言，**纯算法无平台依赖** |
| 默认参数 | `DEFAULT_RESIZE_BITMAP_AREA = 112 * 112`（= 12544px）、`DEFAULT_CALCULATE_NUMBER_COLORS = 16`（[Palette.java 源码](https://raw.githubusercontent.com/androidx/androidx/androidx-main/palette/palette/src/main/java/androidx/palette/graphics/Palette.java)） | `quantize(pixels, maxColors)` 无默认值 |
| 可调项 | `maximumColorCount(int)`、`resizeBitmapArea(int)`、`resizeBitmapSize(int)`（已废弃，推荐用 area）、`setRegion(...)`、`addFilter(...)`、`addTarget(...)`（[Palette.Builder](https://developer.android.com/reference/androidx/palette/graphics/Palette.Builder)） | 采样由调用方自己控制 |

> **结论**：Ncrust 已有的 `androidx.palette:palette-ktx:1.0.0` **不提供** `QuantizerCelebi`、`Hct`、
> `Score` 中的任何一个。想在 Kotlin 里用 HCT，必须**移植**（material-color-utilities 的 Kotlin 实现
> 存在，或自行移植 Java 版）。
> `androidx.palette` 的 `112×112 = 12544px` 面积上限，正是「**采样尺寸**」这个设计点的官方先例
> —— 与 SPlayer 的 64×64 / 32×32 是同一类优化。

---

## §4 Material 3 圆角规范核实

### 4.1 五档基础值：**断言完全正确** ✅

**证据 A —— androidx Compose Material 3 源码**
[`tokens/ShapeTokens.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ShapeTokens.kt)
（文件头 `// VERSION: 14_1_0`，`GENERATED CODE - DO NOT MODIFY BY HAND`）：

```kotlin
internal object ShapeTokens {
    val CornerExtraSmall = RoundedCornerShape(4.0.dp)
    val CornerSmall      = RoundedCornerShape(8.0.dp)
    val CornerMedium     = RoundedCornerShape(12.0.dp)
    val CornerLarge      = RoundedCornerShape(16.0.dp)
    val CornerLargeIncreased      = RoundedCornerShape(20.0.dp)
    val CornerExtraLarge          = RoundedCornerShape(28.0.dp)
    val CornerExtraLargeIncreased = RoundedCornerShape(32.0.dp)
    val CornerExtraExtraLarge     = RoundedCornerShape(48.0.dp)
    val CornerFull  = CircleShape
    val CornerNone  = RectangleShape
    // 单边变体：CornerExtraSmallTop / CornerLargeTop / CornerLargeStart / CornerLargeEnd
    // CornerSize 版本：CornerValueExtraSmall=4 / Small=8 / Medium=12 / Large=16 / LargeIncreased=20
    //                 ExtraLarge=28 / ExtraLargeIncreased=32 / ExtraExtraLarge=48 / None=0
}
```

`ShapeKeyTokens.kt` 中的 token key 全集（**15 个**）：
`CornerExtraExtraLarge, CornerExtraLarge, CornerExtraLargeIncreased, CornerExtraLargeTop,
CornerExtraSmall, CornerExtraSmallTop, CornerFull, CornerLarge, CornerLargeEnd, CornerLargeIncreased,
CornerLargeStart, CornerLargeTop, CornerMedium, CornerNone, CornerSmall`。

**证据 B —— MDC-Android 官方文档**
[`docs/theming/Shape.md`](https://raw.githubusercontent.com/material-components/material-components-android/master/docs/theming/Shape.md)：

| Style Name | Corner Sizes |
|---|---|
| `ShapeAppearance.Material3.Corner.None` | 0dp |
| `ShapeAppearance.Material3.Corner.ExtraSmall` | **4dp** |
| `ShapeAppearance.Material3.Corner.Small` | **8dp** |
| `ShapeAppearance.Material3.Corner.Medium` | **12dp** |
| `ShapeAppearance.Material3.Corner.Large` | **16dp** |
| `ShapeAppearance.Material3.Corner.LargeIncreased` | **20dp** |
| `ShapeAppearance.Material3.Corner.ExtraLarge` | **28dp** |
| `ShapeAppearance.Material3.Corner.ExtraLargeIncreased` | **32dp** |
| `ShapeAppearance.Material3.Corner.ExtraExtraLarge` | **48dp** |
| `ShapeAppearance.Material3.Corner.Full` | **50%**（"circle with rounded corners or rhombus with cut corners"） |

并给出对应的 theme 属性：`shapeAppearanceCornerExtraSmall` / `...Small` / `...Medium` / `...Large` /
`...LargeIncreased` / `...ExtraLarge` / `...ExtraLargeIncreased` / `...ExtraExtraLarge`，
以及 `shapeCornerSizeExtraSmall`=4dp … `shapeCornerSizeExtraExtraLarge`=48dp。

### 4.2 M3 Expressive 新增档位：**存在，共 3 个** ✅

| token | 值 | 出现处 |
|---|---|---|
| `largeIncreased` | **20dp** | androidx `CornerLargeIncreased` / MDC `Corner.LargeIncreased` |
| `extraLargeIncreased` | **32dp** | androidx `CornerExtraLargeIncreased` / MDC `Corner.ExtraLargeIncreased` |
| `extraExtraLarge` | **48dp** | androidx `CornerExtraExtraLarge` / MDC `Corner.ExtraExtraLarge` |

外加 **`full` = 50%**（`CircleShape`）。所以当前完整的圆角词表是 **9 档**：
`none(0) / extraSmall(4) / small(8) / medium(12) / large(16) / largeIncreased(20) /
extraLarge(28) / extraLargeIncreased(32) / extraExtraLarge(48) / full(50%)`。

> **对 Ncrust 的直接意义**：`probe-theme.md` §3.3 里为本版新增的 `pill` token（50%）
> **就是官方已有的 `full`**。命名建议向官方对齐，避免自造名字与官方语义漂移。

### 4.3 已证伪 / 未能直接抓取的说明

- ❌ **未能直接抓取 m3.material.io 的规格页正文**：`https://m3.material.io/styles/shape/corner-radius-scale`
  及其所有子路径（`overview`、`overview-principles`、`shape-scale-tokens`、`shape-morph`）
  在 curl 下都只返回 **~62KB 的 JS 空壳**（页面标题正常，正文由客户端渲染）。
  Wayback Machine 的快照（如 `20250601093730`）**同样是 JS 空壳**（CDX 记录的 `length` 仅 9039 字节）。
  → **故本文对 shape scale 的结论不引用 m3.material.io，而以上面两个 Google 官方代码仓库为准。**
  m3.material.io 的 URL 仅作为「设计规格出处」列出，**其正文内容本文未能验证**。
- ⚠️ 本文**没有**核实「哪个组件用哪一档圆角」（如卡片 12dp、FAB 16dp、对话框 28dp 之类的
  组件级映射表）。`Shape.md` 只列出 token 值与 theme 属性，未给组件映射。若要组件映射，
  需要能渲染 m3.material.io，或逐组件读 androidx material3 源码。

---

## §5 Material 3 Expressive 动效核实

### 5.1 「四层」断言：**已证伪** ❌

**MDC-Android 官方文档**
[`docs/theming/Motion.md`](https://raw.githubusercontent.com/material-components/material-components-android/master/docs/theming/Motion.md)
原文（逐句）：

> The spring (or physics) motion system is a set of **six** opinionated spring attributes…
> The damping ratio describes how rapidly spring oscillations decay. Stiffness defines the strength of the spring.
>
> The spring system provides springs in **three speeds - fast, slow, and default**. A speed is chosen based on
> the size of the component being animated or the distance covered. Small component animations like switches
> should use the **fast** spring, full screen animations or transitions should use the **slow** spring, and
> everything in between should use the **default** spring.
>
> Additionally, for each speed there are **two types of springs - spatial and effects**. **Spatial** springs are
> used for animations that **move something on screen** - like the x & y position of a View. **Effects** springs
> are used to animate properties such as **color or opacity where the property's value should not be overshot**
> (e.g. a background's alpha shouldn't bounce or oscillate above 100%).
>
> This makes for a total of **six spring attributes**.

**androidx Compose 源码**同时只有两个 token 对象，各 6 个常量：
[`ExpressiveMotionTokens.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ExpressiveMotionTokens.kt) 与
[`StandardMotionTokens.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/StandardMotionTokens.kt)。

→ **正确表述：3 档 × 2 类 = 6 条 spring。不是 4 层。**

**「四层」的最可能来源**（推断，标注为推断）：旧版 M3 的**时长** token 恰好是**四档**
（`Short` / `Medium` / `Long` / `ExtraLong`），每档 4 个值 = **16 个 duration token**（见 §5.4）。
任务书很可能把「时长四档」误记成了「弹簧四层」。

### 5.2 Expressive spring 完整数值表 ✅

来源：[`tokens/ExpressiveMotionTokens.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ExpressiveMotionTokens.kt)
（文件头 `// VERSION: v0_14_0`，生成代码）—— **以下是源文件全文数值，逐字照录**：

| 类别 | 档位 | Token 名 | dampingRatio | stiffness |
|---|---|---|---|---|
| **spatial** | fast | `SpringFastSpatialDamping` / `SpringFastSpatialStiffness` | **0.6f** | **800.0f** |
| **spatial** | default | `SpringDefaultSpatialDamping` / `SpringDefaultSpatialStiffness` | **0.8f** | **380.0f** |
| **spatial** | slow | `SpringSlowSpatialDamping` / `SpringSlowSpatialStiffness` | **0.8f** | **200.0f** |
| **effects** | fast | `SpringFastEffectsDamping` / `SpringFastEffectsStiffness` | **1.0f** | **3800.0f** |
| **effects** | default | `SpringDefaultEffectsDamping` / `SpringDefaultEffectsStiffness` | **1.0f** | **1600.0f** |
| **effects** | slow | `SpringSlowEffectsDamping` / `SpringSlowEffectsStiffness` | **1.0f** | **800.0f** |

**可直接抄的 Compose 写法**（摘自 androidx
[`MotionScheme.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/MotionScheme.kt)
的 `ExpressiveMotionSchemeImpl`）：

```kotlin
private val fastSpatialSpec = spring<Any>(
    dampingRatio = ExpressiveMotionTokens.SpringFastSpatialDamping,   // 0.6f
    stiffness    = ExpressiveMotionTokens.SpringFastSpatialStiffness // 800.0f
)
```

**规律（读出来的，非文档明说）**：
- **spatial** 档 damping < 1（0.6 / 0.8 / 0.8）→ **允许轻微过冲**（overshoot），这是 Expressive 的「弹性」来源；
- **effects** 档 damping = **1.0**（临界阻尼）→ **零过冲**，因为透明度/颜色过冲会显示成「>100%」的错值；
- effects 的 stiffness **恰好是 spatial 的 4~4.75 倍**（3800/800=4.75、1600/380≈4.21、800/200=4.0），
  effects 明显更快；
- 档位顺序按 stiffness 递减：fast > default > slow。

### 5.3 对照：Standard spring（不是 Expressive）

来源：[`tokens/StandardMotionTokens.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/StandardMotionTokens.kt)

| 类别 | 档位 | damping | stiffness | 与 Expressive 的差异 |
|---|---|---|---|---|
| spatial | fast | **0.9f** | **1400.0f** | Expressive 更「软」（0.6/800） |
| spatial | default | **0.9f** | **700.0f** | Expressive 0.8/380 |
| spatial | slow | **0.9f** | **300.0f** | Expressive 0.8/200 |
| effects | fast | 1.0f | 3800.0f | **完全相同** |
| effects | default | 1.0f | 1600.0f | **完全相同** |
| effects | slow | 1.0f | 800.0f | **完全相同** |

> **关键洞察**：Standard 与 Expressive **在 effects 上完全一致**，只差在 **spatial**。
> 也就是说「Expressive 动效」的实质 = **spatial 弹簧更慢 + 阻尼更低（允许过冲）**。
> 对 Ncrust 这种「零弹簧现状」（`probe-motion.md`：`spring(` 命中 0）来说，
> 引入 Expressive 与引入 Standard 的**成本相同、只有 3 个数字不同**。

**同时，MDC-Android Android View 侧的 `?attr/motionSpring*` 默认值 = Standard 的数值**
（[Motion.md](https://raw.githubusercontent.com/material-components/material-components-android/master/docs/theming/Motion.md) 表格原文）：

| 属性 | 默认值 |
|---|---|
| `?attr/motionSpringFastSpatial` | `damping: 0.9, stiffness: 1400` |
| `?attr/motionSpringFastEffects` | `damping: 1, stiffness: 3800` |
| `?attr/motionSpringSlowSpatial` | `damping: 0.9, stiffness: 300` |
| `?attr/motionSpringSlowEffects` | `damping: 1, stiffness: 800` |
| `?attr/motionSpringDefaultSpatial` | `damping: 0.9, stiffness: 700` |
| `?attr/motionSpringDefaultEffects` | `damping: 1, stiffness: 1600` |

文档同时给出**选档规则**（原文）：
> A speed is chosen based on the **size of the component** being animated or the **distance covered**.
> Small component animations like switches → fast；full screen animations or transitions → slow；
> everything in between → default。
> 例：*"if animating a button's shape **and** color when pressed, use **two** springs: a
> `motionSpringFastSpatial` to animate the shape/size and a `motionSpringFastEffects` to animate the color."*

### 5.4 tween 回退：**androidx Compose Material 3 里不存在** ❌

**证据**：`MotionScheme` 接口的 6 个方法（`defaultSpatialSpec` / `fastSpatialSpec` / `slowSpatialSpec` /
`defaultEffectsSpec` / `fastEffectsSpec` / `slowEffectsSpec`）在
[`MotionScheme.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/MotionScheme.kt)
中的返回类型是 `FiniteAnimationSpec<T>`，而**两个实现对象 `StandardMotionSchemeImpl` 与
`ExpressiveMotionSchemeImpl` 的 12 个字段全部是 `spring<Any>(dampingRatio=…, stiffness=…)`**。
**源码中不存在任何 `tween(...)` 分支，也没有按平台/API level 切换的实现。**

[`developer.android.com` 的 MotionScheme 参考页](https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme)
（`Added in 1.5.0-alpha29`，artifact `androidx.compose.material3:material3`）的 API 摘要也只有这 6 个方法，
**没有任何 duration/easing 参数或重载**。

→ **结论：任务书「每条 spring 有对应 tween 回退（时长 + 缓动）」在 androidx Compose 中不成立。**
「spring 为主 + effects 用 duration/easing」这个说法确实出现在**第三方**资料里
（如 pub.dev 的第三方包 [material_3_expressive / M3EMotion](https://pub.dev/documentation/material_3_expressive/latest/foundations_interaction_m3e_motion/M3EMotion-class.html)，
原文：*"Expressive motion favours spring based physics for spatial movement while keeping
duration/easing pairs for effects such as opacity and color."*），
但**这是第三方解读，与 Google 的 androidx 实现不一致**（androidx 的 effects 也是 spring，只是 damping=1.0）。
本文不采信第三方说法作为规格。

### 5.5 旧版 M3 时长/缓动 token（若确实需要 tween，用这套）

如果你要的是「时长 + 缓动」的确定性数值，官方有**另一套**（非弹簧）token，来自
[`tokens/MotionTokens.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/MotionTokens.kt)
（文件头 `// VERSION: v0_103`）与 `Motion.md`：

**16 个 duration（原文数值，单位为 ms）**

| 档 | 1 | 2 | 3 | 4 |
|---|---|---|---|---|
| Short | 50 | 100 | 150 | 200 |
| Medium | 250 | 300 | 350 | 400 |
| Long | 450 | 500 | 550 | 600 |
| ExtraLong | 700 | 800 | 900 | 1000 |

**缓动曲线（CubicBezierEasing，Compose 源码原文）**

| Token（Compose） | 值 | MDC 属性名 |
|---|---|---|
| `EasingEmphasizedCubicBezier` / `EasingStandardCubicBezier` | `(0.2, 0.0, 0.0, 1.0)` | `motionEasingEmphasizedInterpolator`（MDC 用 path 近似）/ `motionEasingStandardInterpolator` |
| `EasingEmphasizedAccelerateCubicBezier` | `(0.3, 0.0, 0.8, 0.15)` | `motionEasingEmphasizedAccelerateInterpolator` |
| `EasingEmphasizedDecelerateCubicBezier` | `(0.05, 0.7, 0.1, 1.0)` | `motionEasingEmphasizedDecelerateInterpolator` |
| `EasingStandardAccelerateCubicBezier` | `(0.3, 0.0, 1.0, 1.0)` | `motionEasingStandardAccelerateInterpolator` |
| `EasingStandardDecelerateCubicBezier` | `(0.0, 0.0, 0.0, 1.0)` | `motionEasingStandardDecelerateInterpolator` |
| `EasingLegacyCubicBezier` | `(0.4, 0.0, 0.2, 1.0)` | — |
| `EasingLegacyAccelerateCubicBezier` | `(0.4, 0.0, 1.0, 1.0)` | — |
| `EasingLegacyDecelerateCubicBezier` | `(0.0, 0.0, 0.2, 1.0)` | — |
| `EasingLinearCubicBezier` | `(0.0, 0.0, 1.0, 1.0)` | `motionEasingLinearInterpolator` |

> **对 Ncrust 直接有用**：`probe-motion.md` 实测本项目**已经在用**
> `CubicBezierEasing(0.2f, 0f, 0f, 1f)`（出现 6 次）。
> 这条曲线**和官方的 `EasingStandardCubicBezier` / `EasingEmphasizedCubicBezier`
> 数值完全相同**，也和 MDC 的 `motionEasingStandardInterpolator` 相同。
> 也就是说：本项目的「主运动曲线」不是自造的，**就是 M3 官方曲线**——这是一个可以写进文档的巧合级印证。

### 5.6 `MotionScheme` 的公开 API（Compose 侧真实接口）

```kotlin
interface MotionScheme {
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T>
    fun <T> fastSpatialSpec():    FiniteAnimationSpec<T>
    fun <T> slowSpatialSpec():    FiniteAnimationSpec<T>
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T>
    fun <T> fastEffectsSpec():    FiniteAnimationSpec<T>
    fun <T> slowEffectsSpec():    FiniteAnimationSpec<T>

    companion object {
        fun standard():   MotionScheme   // StandardMotionSchemeImpl
        fun expressive(): MotionScheme   // ExpressiveMotionSchemeImpl
    }
}
```

- 内部 token key（[`MotionSchemeKeyTokens.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/MotionSchemeKeyTokens.kt)）：
  `DefaultSpatial / FastSpatial / SlowSpatial / DefaultEffects / FastEffects / SlowEffects`（**6 个**，再次印证非四层）。
- 装配入口：`MaterialExpressiveTheme(colorScheme, motionScheme, shapes, typography, content)`
  —— [参考页](https://developer.android.com/reference/kotlin/androidx/compose/material3/MaterialExpressiveTheme.composable)，
  `Added in 1.5.0-alpha29`，包 `androidx.compose.material3`。
- 另有 `expressiveLightColorScheme()` / `expressiveDarkColorScheme()` 配色函数
  （[package-summary](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary)）。
- `MotionScheme.fromToken(MotionSchemeToken)` 是一个 `internal` 扩展函数，**不可外部调用**。

---

## §6 其他可复用的东西（面向 Android Compose 音乐播放器）

### 6.1 封面取色的工程化检查清单（从两个 SPlayer 归纳出的可迁移做法）

来源：§2.2、§2.3 两份源码。

| # | 做法 | 出处 | 为什么重要 |
|---|---|---|---|
| 1 | **降采样后再量化**，不要拿原图 | SPlayer-Next 64×64；SPlayer-A 32×32；androidx.palette 112×112 面积 | 量化成本随像素数线性增长；封面动辄 1024×1024 |
| 2 | **裁掉边缘**再采样 | SPlayer-Next `COVER_EDGE_MARGIN = 3` | 封面边框/白边会污染主色 |
| 3 | **中心区域加权** | SPlayer-Next `sampleWeight` 3/2/1 分档 | 主体在中间，角落是装饰 |
| 4 | **灰度/单调封面必须有兜底色** | SPlayer-Next 彩色占比 <0.12 → null；SPlayer-A 前 5 高频色近灰 → `MONOTONOUS_THEME` | 黑白封面会让整个 App 变成灰的；需要显式回退 |
| 5 | **后处理 clamp**：把种子色压进可用 tone/chroma 区间 | SPlayer-Next `toCoverBaseColor` tone∈[28,72] chroma∈[12,64] | 极端封面（纯黑/纯荧光）会产出不可读的 UI |
| 6 | **派生前景色时另设 tone**，不要复用种子 tone | SPlayer-Next `toCoverUiColor` tone=88、chroma=min(30, C*0.48) | 背景与前景需要拉开 tone 差（§3.5：tone 差 50 → 4.5:1） |
| 7 | **放后台线程** | SPlayer-A Web Worker；androidx.palette 文档明文 | 量化是 CPU 密集，主线程会掉帧 |
| 8 | **结果按 URL 缓存** | SPlayer-A LRU `coverColorCache` | 同一首歌反复进出播放页 |
| 9 | **加超时 + 降级路径** | SPlayer-A worker timeout → 主线程回退 | Worker 可能因 OOM/异常静默挂掉 |
| 10 | **竞态 token**（单调递增 id，回调里比对） | 两个项目都做了 | 快速切歌时旧结果会覆盖新结果 |
| 11 | **空闲调度** | SPlayer-A `runWhenIdle(...)` | 取色不该和转场/解码抢帧 |
| 12 | **可复现**：用带种子的 RNG | `QuantizerWsmeans` 源码 | 单测可钉住确定性输出 |
| 13 | **释放 canvas** | SPlayer-Next `canvas.width = 0` | 释放 GPU 纹理 |
| 14 | **跨域/位图不可读要 try-catch** | SPlayer-Next 注释 + catch | 对应 Android 侧的 `Bitmap` 回收/`IllegalStateException` |

### 6.2 Compose 侧可用的官方 API（与取色主题相关）

| API | 出处 | 说明 |
|---|---|---|
| `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` | [package-summary](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary) | `@RequiresApi(31)`，从**系统壁纸**取色，**不是**从封面取色；且属于 material3 |
| `expressiveLightColorScheme()` / `expressiveDarkColorScheme()` | 同上 | M3 Expressive 静态配色 |
| `lightColorScheme(...)` / `darkColorScheme(...)` | 同上 | 显式角色配色，参数含 `primary / onPrimary / primaryContainer / … / surface / onSurface / surfaceVariant / onSurfaceVariant / surfaceTint / inverseSurface / inverseOnSurface / error / …` —— **这份角色清单可以作为自研配色体系的命名基准** |
| `androidx.palette` `Palette.from(bitmap).generate()` | [Palette](https://developer.android.com/reference/androidx/palette/graphics/Palette) | 已在 Ncrust classpath 上；输出 6 个 Swatch profile |
| `Palette.Builder.maximumColorCount(n)` / `.resizeBitmapArea(area)` / `.setRegion(l,t,r,b)` / `.addFilter(...)` | [Palette.Builder](https://developer.android.com/reference/androidx/palette/graphics/Palette.Builder) | 采样与区域控制的官方旋钮 |

> ⚠️ **注意**：`dynamicLightColorScheme` **不解决封面取色**——它是 Android 12+ 的
> **系统动态取色（Material You / 壁纸）**。任务书若把它当成「封面取色」方案，那是概念混淆。

### 6.3 已记录的坑（有来源）

| 坑 | 来源 |
|---|---|
| `TonalPalette` 有内部缓存，**非线程安全**，官方要求单线程使用 | [TonalPalette.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/palettes/TonalPalette.java) 类文档 |
| `Hct.from()` 可能返回**低于请求的 chroma**（色域裁剪），不能假设往返无损 | [Hct.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/hct/Hct.java) `@param chroma` 原文 |
| 黄色（`isYellow`）在 tone 99 有非线性，官方特殊处理成 tone 98/100 平均 | `Hct.java` / `TonalPalette.java` |
| `Score` 在 colors **全部不适配**时会返回 **Google Blue**（`0xff4285f4`），不是「空」 | [Score.java](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/java/score/Score.java) 文档 |
| `QuantizerCelebi.quantize` **可能返回少于 `maxColors` 个颜色** | `QuantizerCelebi.java` `@param maxColors` 原文 |
| 跨域图片会 **taint canvas**，`getImageData` 抛异常 | SPlayer-Next 源码注释 + try/catch |
| `androidx.palette` 的异步 `generate(PaletteAsyncListener)` 基于 `AsyncTask`，**已废弃** | [Palette.Builder](https://developer.android.com/reference/androidx/palette/graphics/Palette.Builder) |
| `resizeBitmapSize` 对异常长宽比处理不好，**推荐 `resizeBitmapArea`** | 同上（原文 *"Using resizeBitmapArea is preferred since it can handle abnormal aspect ratios more gracefully."*） |

---

## §7 可直接借鉴的要素

> 只列**已证实**、且与 Ncrust 现状不冲突或有明确改造路径的东西。

1. **M3 圆角九档词表**（数值可直接照抄，来源：androidx `ShapeTokens.kt` + MDC `Shape.md`）
   `none 0 / extraSmall 4 / small 8 / medium 12 / large 16 / largeIncreased 20 /
   extraLarge 28 / extraLargeIncreased 32 / extraExtraLarge 48 / full 50%`。
   → 建议把 `probe-theme.md` 新增的 `pill` **改名为 `full`**，与官方语义对齐。

2. **`full` = `CircleShape` = 50%**：官方已有，不必自造。

3. **HCT 的 tone 差对比度保证**（官方明文可验收）：
   **tone 差 ≥ 50 ⇒ 对比度 ≥ 4.5:1（WCAG AA 正文）；tone 差 ≥ 40 ⇒ ≥ 3.0:1（大字号/非文本）**。
   → 这可以替代（或作为）Ncrust `onAccentColor` 里手算 WCAG 的实现，纯算术、零 `android.*` 依赖，
   与 `probe-theme.md` P4-1「颜色数学必须纯 Kotlin」的约定**天然吻合**。

4. **量化→评分→主题生成的三段式管线**，以及各段的**确定参数**：
   - 量化：`QuantizerCelebi.quantize(argbPixels, 128)`
   - 评分：官方 `Score.score(colorToCount)`（默认 desired=4，取第一个）
   - 主题：`themeFromSourceColor(topColor)` → `palettes.{primary,secondary,tertiary,neutral,neutralVariant,error}`
   → 三个数字（128 / 4 / 6 个 palette）都有源码出处，可直接落成单测常量。

5. **`CorePalette.of()` 的 palette 派生规则**（primary `max(48,C)` / secondary 16 / tertiary hue+60 chroma 24 /
   neutral 4 / neutralVariant 8 / error hue25 chroma84）—— 若要与 SPlayer 取色**保持一致**，用这套；
   若要用 M3 现行 TonalSpot，则用 primary 36 / secondary 16 / tertiary hue+60 chroma 24 / neutral 6 /
   neutralVariant 8。**两套都真实存在，必须二选一并写进文档，不能混用。**

6. **旧版 M3 静态 `Scheme` 的角色→tone 表**（§3.8 浅色/深色两张表）——
   这是最省事的「一次查表得到整套配色」方案，且 tone 值是官方原值。

7. **M3 官方动效 token 两套并存的事实**：
   - 要**弹簧**：6 条 spring（§5.2 / §5.3），Standard 与 Expressive **effects 完全相同**，只差 spatial 三个数。
   - 要**时长+缓动**：16 个 duration + 9 条 cubic-bezier（§5.5）。
   → 两套都是官方的，**不是二选一的教义问题**，可以按「新交互用弹簧、存量 tween 收敛到官方曲线」渐进。

8. **本项目现有的 `CubicBezierEasing(0.2f, 0f, 0f, 1f)` 就是官方的
   `EasingStandardCubicBezier` / `EasingEmphasizedCubicBezier`**（数值逐位相同）。
   → `AppMotion.kt` 可以把它**登记为「已符合 M3 官方曲线」**，而不是当成本项目自造值。

9. **封面取色工程化 14 条**（§6.1）—— 尤其是
   **降采样 → 灰度兜底 → 后处理 clamp → 后台线程 → 缓存 → 超时降级 → 竞态 token** 这七条，
   是两个真实项目各自踩过并写进代码的。

10. **`androidx.palette` 的采样先例**：`DEFAULT_RESIZE_BITMAP_AREA = 112*112`、
    `DEFAULT_CALCULATE_NUMBER_COLORS = 16`。
    → 给「采样尺寸该取多少」提供了官方数量级参考（1 万像素量级，不是 100 万）。

11. **`QuantizerWsmeans` 用带种子 RNG ⇒ 结果可复现** —— 对「落盘 key 名断言」式单测文化友好，
    取色管线的确定性可以被钉死。

12. **`themeFromSourceColor` 在 TS 侧走的是已废弃的 `CorePalette.of()`** ——
    如果 Ncrust 要复刻 SPlayer 的取色结果，必须复刻 `CorePalette` 语义（含 `max(48, C)` 与
    `error` 固定 hue25/chroma84），**不能**用 `DynamicScheme` 的 TonalSpot 替代。

---

## §8 与本项目的冲突点

> 前提（已实测核对，非任务书转述）：
> `app/build.gradle.kts` 中 **`material3` 声明 0 处**、`androidx.compose.material3` import **0 行**
> （`grep` 全仓库仅命中 `CLAUDE.md`/`AGENTS.md`/`TASK.md` 中「不引入」的说明文字）；
> 依赖里有 `io.coil-kt:coil-compose:2.6.0`（`app/build.gradle.kts:276`）与
> `androidx.palette:palette-ktx:1.0.0`（`app/build.gradle.kts:283`）；
> Kanesumi 正典为「直角、无圆角」，`RoundedCornerShape` 全仓库 0 命中。

### 冲突 1（最高优先级）：任务书的核心论据是**引用了错误的项目与过期的注释**

任务书把「SPlayer-Next」当作 M3 Expressive 圆角/动效的权威样例。实测：

- SPlayer-Next **是 Electron + Vue 3 桌面应用**，仓库内 0 个 Gradle/Kotlin/AndroidManifest 文件，
  **不可能**有任何 Android 侧的形状/动效规格；
- 任务书描述的取色流程（含「50×50」与「`Score` 评分」）**逐字出现在 SPlayer-Next 源码的一段
  JSDoc 注释里**，而该文件的实际常量是 `64`、且**从未 import `Score`**。

→ **处理**：圆角与动效规格只能回到 Google 一手来源（本文 §4 §5 已做完）。
任务书里凡以「SPlayer-Next 如此」为理由的条目，**理由作废，需重新论证**。
（结论本身可能仍然正确 —— 例如圆角五档确实正确 —— 但**理由是错的**，不能作为验收依据。）

### 冲突 2：**「圆角化」本身违反 Kanesumi 正典**，且没有 material3 可用

- Kanesumi 正典：「直角、无圆角、无阴影、无边框」，且「图片不裁圆角」被**写进源码注释**
  （`OfflineCacheOverlay.kt:327`「Kanesumi 铁律：图片不裁圆角」）。全仓库 `RoundedCornerShape` **0 命中**。
- 应用**没有** `androidx.compose.material3`，因此 **`Shapes` / `MaterialTheme.shapes` /
  `ShapeKeyTokens` 全部不可用**。唯一的形状 API 是 `androidx.compose.foundation.shape.RoundedCornerShape`
  （foundation，已是既有依赖）。
- 本文 §4 的九档圆角是 **material3 的 token 定义**，**不是** foundation 提供的常量。
  照抄只能是把 dp 数值**硬编码进 app 侧的 `AppShapes.kt`**。

→ **处理**：`probe-theme.md` §3.2 已经给出的方案（新增 `ui/theme/AppShapes.kt` 作为**唯一**圆角落点 +
`AppShapesSingleSourceTest` 扫源码树禁止散落 `RoundedCornerShape(`）是**唯一可行**的路线，
本文确认其技术前提成立：**dp 值可以照抄，API 必须自建**。
另需同步修改「图片不裁圆角」那几处正典注释（`probe-cover.md` §4 已列出 6 处），
否则代码与自己的注释矛盾。

**另一个必须处置的细节**：M3 的 `large`(16dp) 与 `extraLarge`(28dp) 是**圆角**；
而 Expressive 新增的 `full` 是 **50% 圆**。任务书同时要求「封面 16dp 圆角」与
「按钮/搜索框/音源标签药丸形」——这两者在 Kanesumi 的直角体系里是**两种不同的违规**，
但都与「无圆角」正典冲突。**冲突是设计决策，不是技术障碍**，须由人裁定，本文不代为决定。

### 冲突 3：动效规格的真实结构是 **6 条 spring**，而本项目现状是 **0 处 spring / 41 处手写 tween**

- 任务书说「四层」，真实是 **3 档 × 2 类 = 6 条**（已被 MDC 官方文档与 androidx 源码双向证伪）。
  若照「四层」去建 `AppMotion.kt`，会建出一个**不对应任何官方结构**的抽象。
- 更根本的冲突：本项目**当前 `spring(` 命中 0 处**（`probe-motion.md`），
  全部动效是时长驱动 `tween`。引入弹簧 = 引入一种**本项目完全没有的动效范式**，
  而不是「把现有取值收敛命名」。这与 `probe-motion.md` 的核心判断
  （「动效词汇表存在但没被用起来，`AppMotion` 应是**归纳**而非**发明**」）**直接矛盾**。
- 且任务书声称的「spring → tween 回退」在 androidx 中**不存在**（§5.4），
  所以「用 tween 做弹簧的回退」这条路**没有官方数值可抄**。

→ **处理（三条互斥路线，需裁定）**：
  - **(a) 走官方 tween 路线**：不引入弹簧，把现有 41 处 tween 收敛到官方
    16 duration + 9 easing（§5.5）。**成本最低，且完全符合 `probe-motion.md` 的「归纳」原则**。
    现有主曲线 `CubicBezier(0.2,0,0,1)` 已经**就是**官方曲线。
  - **(b) 走官方 spring 路线**：只引入 **Standard** 的 3 条 spatial spring
    （effects 与 Expressive 相同且本项目暂无对应场景），把 `AppMotion.kt` 设计成
    「**既能给 spring 也能给 tween**」的双出口 —— 但必须明确：**这是自建设计，不是抄官方**，
    因为官方 `MotionScheme` 只有 spring 一个出口。
  - **(c) 走 Expressive spatial spring**：若确实要「M3 Expressive 观感」，
    只需 3 个数字：`fast 0.6/800`、`default 0.8/380`、`slow 0.8/200`。
    代价与 (b) 相同，差别仅在这 3 个数。

### 冲突 4（次要但明确）：`androidx.palette` 不能提供 HCT，取色管线必须自建

- 本项目的现有依赖 `androidx.palette:palette-ktx:1.0.0` **不包含** `QuantizerCelebi` / `Hct` / `Score`，
  它是另一套旧 API（Vibrant/Muted 等 6 个 profile + `ColorCutQuantizer`）。
- material-color-utilities 有 Kotlin 实现，但**引入它 = 新增一个第三方依赖**（Apache-2.0，
  与本项目 GPLv3 兼容，但需要走依赖评审 + 记入 `THIRD-PARTY-LICENSES.md`）。
- 若选择自行移植（`probe-theme.md` P4-1 提到 `ui/.../AccentSource.kt` 已有纯 Kotlin 颜色数学的先例），
  则必须自己实现 HCT↔ARGB 的求解器（CAM16 + L\*，涉及 `HctSolver` 的求根），
  **这是一个有相当实现风险的移植**，远不止「几个 clamp」。
→ **建议**：明确区分两条通路（Palette 继续服务通知栏着色，HCT 服务应用主题），
   并**不要**把「两条通路给出同一个颜色」设为验收项（`probe-theme.md` §P5 已持此立场，本文支持）。

---

## §9 未能证实 / 未能抓取

> 以下项目**没有**取得一手证据，**不得**在下游文档中被当作事实引用。

1. **m3.material.io 的规格页正文全部未能抓取**。
   `m3.material.io/styles/shape/corner-radius-scale`、`/styles/shape/overview`、
   `/styles/shape/overview-principles`、`/styles/motion/overview`、
   `/styles/motion/overview/how-it-works`、`/styles/motion/overview/specs`、
   `/blog/m3-expressive-motion-theming` 在 curl 下均只返回 **~62KB 的客户端渲染空壳**；
   Wayback Machine 的快照同样是空壳（CDX 记录 `length` 仅 6–9KB）。
   → 本文所有 M3 形状/动效结论**均来自 Google 代码仓库**（androidx、MDC-Android），
   **不来自** m3.material.io。若要引用 m3.material.io 的正文，需要能执行 JS 的环境。

2. **M3 组件级圆角映射表未核实**（哪个组件用哪一档，如卡片/FAB/对话框/BottomSheet）。
   `Shape.md` 只给 token 值，androidx `ShapeTokens.kt` 只给 token 定义。未逐组件读源码。

3. **「四级/四层动效」这个说法的出处未找到**。
   已确认官方是 3 档 × 2 类，但**未能**找到任何 Google 一手来源提出过「四层」结构。
   本文给出的「可能源自旧版 4 档 duration（Short/Medium/Long/ExtraLong）」是**推断，非事实**。

4. **spring → tween 的官方映射数值不存在**。
   androidx `MotionScheme` 无 tween 分支；MDC `Motion.md` 也未给「spring 对应哪个 duration」的对照表。
   → 「每条 spring 的 tween 回退」在官方文档中**查无此物**。

5. **`MotionScheme` 的 `@Added in 1.5.0-alpha29` 之外的版本演进未核实**
   （何时稳定、在不同 material3 版本间是否变过数值）。本文数值取自 `androidx-main` 快照，
   文件头标注 `v0_14_0`，可能与具体 release 版本存在差异。

6. **`MaterialDynamicColors` / `ColorSpec2021` 的完整角色→tone 映射未逐条核实**。
   新版引入了 `ContrastCurve` 与 `ToneDeltaPair`（如 `new ToneDeltaPair(primaryContainer(), primary(), 10.0, TonePolarity.NEARER, false)`），
   **不再是固定 tone**。本文 §3.8 的表格来自**旧版** `Scheme.java` 的硬编码值，
   可用于理解结构，但**不代表**当前 `DynamicScheme` 在任意 `contrastLevel` 下的实际输出。
   新版还有 `ColorSpec2021 / ColorSpec2025 / ColorSpec2026` 三份规范，本文只读了前者的 palette 推导部分。

7. **`Theme`（TS）与 `DynamicScheme`（Java）的输出是否等价未验证**。
   §3.10 已证明 `themeFromSourceColor` 内部用废弃的 `CorePalette.of()`，
   但**未**实测 Java `SchemeTonalSpot` 与 TS `themeFromSourceColor` 在同一源色下
   是否给出相同色值（两者 palette chroma 规则不同：`max(48,C)` vs `36`，**推断不同**，但未实测）。

8. **SPlayer-Next 的「50×50」注释是何时写下的、是否曾经与代码一致，未核实**。
   浅克隆为 `--depth 1`，无历史。仅拉到了 `src/utils/color.ts` 的提交**日期列表**
   （最近一次 `2026-07-23`，共 18 条），**未**拉取 diff 内容。

9. **SPlayer-Next / SPlayer-for-Android 的 star 数与「最后提交」是 shields.io 快照，会变**。
   仓库活跃度类数字不要写进验收标准。

10. **`androidx.palette` 的 `ColorCutQuantizer` 与 `QuantizerWu` 的取色差异未做 A/B**。
    两者算法血统不同（中位切分 vs Wu 方差切分），同一封面**预期**给出不同种子色，但未实测。
    （`probe-theme.md` §P5 已声明这不是本版目标。）

11. **第三方 Dart 包 `material_3_expressive` 的说法未采信**。
    它声称「effects 用 duration/easing 对、spatial 用 spring」，与 androidx 实现
    （effects 也是 spring，damping=1.0）**不一致**。本文将其列为第三方解读，
    **不**作为规格依据。它还给了一个 androidx 中不存在的 `aospSpatial`（称 damping 1.0 /
    stiffness 380，而 androidx 的对应值是 damping 0.8 / stiffness 380）——**该包数值与 androidx 冲突，勿引用**。

12. **SPlayer-Next 是否曾经是 Android 项目、或有无 Android 分支/衍生，未核实**。
    （只核实了当前 `main` 分支与仓库文件树。）

---

## §10 本次抓取的 URL 清单（可复核）

### SPlayer 系列

- https://github.com/SPlayer-Dev/SPlayer-Next
- https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-Next/main/README.md
- https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-Next/main/package.json
- https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-Next/main/LICENSE
- https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-Next/main/src/utils/color.ts
- https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-Next/main/src/types/theme.ts
- https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-Next/main/src/settings/categories/appearance.ts
- https://github.com/SPlayer-Dev/SPlayer-Next/releases.atom
- https://github.com/SPlayer-Dev/SPlayer-Next/commits/main.atom
- https://github.com/SPlayer-Dev/SPlayer-Next/commits/main/src/utils/color.ts
- https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-for-Android/master/README.md
- https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-for-Android/master/package.json
- https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-for-Android/master/src/utils/color.ts
- https://raw.githubusercontent.com/SPlayer-Dev/SPlayer-for-Android/master/src/utils/coverColor.worker.ts
- https://raw.githubusercontent.com/imsyy/SPlayer/master/README.md
- https://github.com/anilbeesetti/nextplayer
- https://raw.githubusercontent.com/anilbeesetti/nextplayer/main/README.md
- https://img.shields.io/github/{stars,last-commit,license,v/release}/SPlayer-Dev/SPlayer-Next.json （及 SPlayer-for-Android / imsyy/SPlayer / material-foundation/material-color-utilities）

### material-color-utilities（全部 `main` 分支）

- https://github.com/material-foundation/material-color-utilities
- https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/README.md
- https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/LICENSE
- .../main/java/quantize/QuantizerCelebi.java
- .../main/java/quantize/QuantizerWsmeans.java
- .../main/java/quantize/QuantizerWu.java
- .../main/java/quantize/Quantizer.java
- .../main/java/quantize/QuantizerResult.java
- .../main/java/score/Score.java
- .../main/java/hct/Hct.java
- .../main/java/palettes/CorePalette.java
- .../main/java/palettes/TonalPalette.java
- .../main/java/scheme/Scheme.java
- .../main/java/scheme/SchemeTonalSpot.java
- .../main/java/dynamiccolor/Variant.java
- .../main/java/dynamiccolor/DynamicScheme.java
- .../main/java/dynamiccolor/ColorSpec2021.java
- .../main/java/dynamiccolor/ColorSpecs.java
- .../main/typescript/utils/theme_utils.ts
- .../main/typescript/package.json

### androidx / MDC-Android（材料 3 形状与动效）

- https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ShapeTokens.kt
- .../tokens/ShapeKeyTokens.kt
- .../tokens/ExpressiveMotionTokens.kt
- .../tokens/StandardMotionTokens.kt
- .../tokens/MotionTokens.kt
- .../tokens/MotionSchemeKeyTokens.kt
- .../compose/material3/MotionScheme.kt
- .../compose/material3/tokens/MotionScheme.kt（该路径下无内容）
- https://raw.githubusercontent.com/material-components/material-components-android/master/docs/theming/Shape.md
- https://raw.githubusercontent.com/material-components/material-components-android/master/docs/theming/Motion.md
- https://raw.githubusercontent.com/androidx/androidx/androidx-main/palette/palette/src/main/java/androidx/palette/graphics/Palette.java

### developer.android.com

- https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme
- https://developer.android.com/reference/kotlin/androidx/compose/material3/MaterialExpressiveTheme.composable
- https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
- https://developer.android.com/reference/androidx/palette/graphics/Palette
- https://developer.android.com/reference/androidx/palette/graphics/Palette.Builder
- （404，已确认不可用）https://developer.android.com/develop/ui/compose/animation/spring

### 列出但**未能取得正文**（仅作规格出处标注，内容未验证）

- https://m3.material.io/styles/shape/corner-radius-scale
- https://m3.material.io/styles/shape/overview
- https://m3.material.io/styles/shape/shape-morph
- https://m3.material.io/styles/motion/overview
- https://m3.material.io/styles/motion/overview/how-it-works
- https://m3.material.io/styles/motion/overview/specs
- https://m3.material.io/blog/m3-expressive-motion-theming
- https://m3.material.io/styles/motion/easing-and-duration/tokens-specs

### 第三方（仅作对照，**不作为规格依据**）

- https://pub.dev/documentation/material_3_expressive/latest/foundations_interaction_m3e_motion/M3EMotion-class.html

---

## §11 本仓库内的核对证据（Ncrust 侧）

| 事实 | 命令 / 位置 | 结果 |
|---|---|---|
| 无 material3 依赖 | `grep -rniE "androidx\.compose\.material3\|compose\.material3" --include=*.kts --include=*.kt --include=*.toml` | 仅命中 `CLAUDE.md` / `AGENTS.md` / `TASK.md` 中的**说明文字**，无构建声明、无 import |
| palette-ktx 在 classpath | `app/build.gradle.kts:283` | `implementation("androidx.palette:palette-ktx:1.0.0")` |
| coil-compose 在 classpath | `app/build.gradle.kts:276` | `implementation("io.coil-kt:coil-compose:2.6.0")` |
| Metro 组件库接入方式 | `app/build.gradle.kts:226` 注释 | 「Kanesumi —— Metro 组件库。通过组合构建从 `../Kanesumi-sec-a` 接入」 |
| `RoundedCornerShape` 0 命中 | 见 `probe-cover.md` §4 / `probe-theme.md` §3.1 | 全应用 0 处 |
| `spring(` 0 命中 / `tween(` 41 处 | 见 `probe-motion.md` P1 | 全部为时长驱动 |
| 现有主曲线 = 官方曲线 | `probe-motion.md` P1.2 | `CubicBezierEasing(0.2f, 0f, 0f, 1f)` 出现 6 次，数值等于官方 `EasingStandardCubicBezier` |
| `pill` 与官方 `full` 同义 | `probe-theme.md` §3.3 新增 token | 官方 token 名为 `full` / `CornerFull` = `CircleShape` |
