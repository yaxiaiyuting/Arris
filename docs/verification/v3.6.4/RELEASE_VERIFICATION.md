# v3.6.4-gpl 发布验证

版本：`versionName = 3.6.4-gpl` / `versionCode = 80`（v3.6.3 tag = 79，下一号 80）。
本版只修生日庆祝页在 WebView 上的**成块缺画**；持久化结构、包名与签名保持原样，可直接覆盖安装 v3.6.3。

发布产物：`dist/v3.6.4-gpl/Ncrust-v3.6.4-gpl-release.apk`
（11,947,560 B，SHA-256 `ecfaa488b9de17054dcc9c095430c2a71f25340114da199cf2d8212a5f330583`）。

## 症状与根因

用户录像（1080×2378，10s）：右上角「关闭」稳定、字幕继续，但背景成块消失再出现。
模拟器复现同形状，同时 logcat 持续报：

```
chromium: [ERROR:tile_manager.cc(826)] WARNING: tile memory limits exceeded, some content may not draw
```

逐层量到 9 个 **1082×1859 的整屏合成层** + 一个 **2391×1500 的场景层**：

- `main.js` 给 9 个 `data-depth` SVG 组逐帧写 CSS transform（每层提成合成层）；
- `lighting.js` 的两层全屏 SVG 用 `mix-blend-mode: screen`；
- `finish.js` 的三层全屏 `lighten/soft-light/screen`；
- `.grain` 是整屏噪声图。

图块预算被击穿后 Chromium 丢弃部分 tile、后续帧再补 —— 录像里的矩形缺块。

## 修法（仅 `?app=1`；浏览器预览保持原效果）

1. `main.js`：应用内不再逐帧变换 9 个舞台层，去掉 `will-change`；舞台静态，
   Tux / 蛋糕自身的 GSAP 动画、字幕、吹气、烟花全部保留。
2. `arris.css`：`#svg-env` / `#svg-scene` 所有后代与 `#lx-light` / `#lx-ember` 的
   `mix-blend-mode` 改为 `normal`；隐藏 `.grain` 与 `.fin-*`。
3. `finish.js`：应用内不创建三层全屏调色层。

## 证据

`tile-warnings.txt`：同一台模拟器、同一份 4s 重载窗口、每次 `logcat -c`：

| 版本 | tile 警告（三次） |
|---|---|
| v3.6.3 资产 | 129 / 126 / 122 |
| v3.6.4 资产 | **2 / 2 / 2** |

`before-block.png` 是用户录像 2s 处的缺块帧；`after-static-stage.png` 是新包 9s 处
（Tux 从右侧走入、背景完整）。同一份 12s screenrecord 无矩形缺块。
`browser/summary.json` 是 Playwright 集成检查：6 种尺寸 × 8 个时间点 + 14 组本地化
竖屏布局，puff/hold/replay/dispose 生命周期全绿，无控制台错误。

## 构建与测试

| 项 | 结果 | 证据 |
|---|---|---|
| 全量 JVM 单测 | **2994 用例 / 0 失败 / 238 个测试类** | `test-summary.txt` |
| `assembleRelease`（R8 + lintVital） | BUILD SUCCESSFUL | `SHA256SUMS` |
| `assembleDebug` | BUILD SUCCESSFUL | `SHA256SUMS` |
| `aapt2` | `versionCode='80' versionName='3.6.4-gpl'` | `apk-badging.txt` |
| `apksigner` | 与 v3.6.3 同一张证书 | `apk-signature.txt` |

## 代价 / 尚未验证

- 应用内舞台的**相机视差与手持漂移**被去掉（背景改为静态），这是拿「不闪」换「不晃」；
  浏览器预览不受影响。Tux / 蛋糕 / 字幕 / 吹气 / 烟花仍按原时间线播放。
- 真机（PLC110）本轮只通过用户录像确认现象，未在真机上做逐帧复测；模拟器为 API 33、
  1080×1920、dpr 2.625。用户设备的 SoC / WebView 版本可能与模拟器不同。
- 未做长时间（48s 全片）逐帧统计；12s 首屏与 WAITING 场景已复测无缺块。
