# v3.6.3-gpl 发布验证

版本：`versionName = 3.6.3-gpl` / `versionCode = 79`（三源核验：v3.6.2 tag 的 build.gradle = 78 为最大值 ⇒ 79）。
本版只改显示层：波形滚动、生日页淡入 / resize、Tux 彩蛋布局；持久化结构、包名与签名保持原样，可直接覆盖安装 v3.6.2。

发布产物：`dist/v3.6.3-gpl/Ncrust-v3.6.3-gpl-release.apk`（SHA-256 `6d8598207a9c31655dbc87faf50b67e1226749f0e5cf0794c8e8aa892f06e63f`）。

## 构建与测试

| 项 | 结果 | 证据 |
|---|---|---|
| 全量 JVM 单测 | **2994 用例 / 0 失败 / 0 错误 / 238 个测试类** | `test-summary.txt` |
| `assembleRelease`（R8 + lintVital） | BUILD SUCCESSFUL | `SHA256SUMS` / 产物 |
| `assembleDebug` | BUILD SUCCESSFUL | `SHA256SUMS` |
| `lintDebug` | 0 error / 61 warning（均为既有告警） | `lint-results-debug.xml` |
| `aapt2` 复核 | `versionCode='79' versionName='3.6.3-gpl'` | `apk-badging.txt` |
| `apksigner` 复核 | 签名证书 SHA-256 `e62eca39…bbec82`（与 v3.6.2 同一张） | `apk-signature.txt` |

## 波形滚动（成批到达）

`WaveformScrollBatchArrivalTest` 用真实 `WaveformRing` + 60Hz 帧时间线，覆盖 3 根/300ms、2 根/200ms、
4 根/400ms、1 根/100ms 四种工况。改前（旧模型）3/300ms 量到 8 帧位移 0.0000 + 一帧 2.00 格；
改后同一份仿真：

```
3 根/300ms：相位分母=100.00ms 均步=0.1667 格 抖动=2.73% 停顿=0 倒退=0
2 根/200ms：相位分母=100.00ms 均步=0.1667 格 抖动=2.78% 停顿=0 倒退=0
4 根/400ms：相位分母=100.00ms 均步=0.1667 格 抖动=2.68% 停顿=0 倒退=0
1 根/100ms：相位分母=100.00ms 均步=0.1667 格 抖动=2.83% 停顿=0 倒退=0
```

另有：一帧最多平移一格、首帧填满最新一屏、长卡顿（>250ms）/积压超过一屏时重建到最新、
`Δ平移 + Δ相位 = dt/间隔` 的逐帧可加性（`WaveformScrollPhaseAnchorTest`）。

## 生日页淡入与 resize

`browser/fade-opacity.txt`：360×640，`#stage` 入场透明度 0 → 0.17 → 0.49 → 0.78 → 1.0（约 350ms），
无单帧硬切、无 pageerror。`browser/summary.json` 是 Playwright 集成检查的完整输出：
320×568 / 360×640 / 390×844 / 430×932 / 844×390 / 1600×900 六档 × 8 个时间点截图与边界，
外加 14 组本地化竖屏布局；puff / hold / 重复 puff / done / replay / dispose 生命周期全部通过，
请求只访问本地 origin。

resize 侧：移除 `VFX.seek(0)+VFX.seek(t)`，改为 `resize()+repaint(t)`，并做 120ms 去抖 +
尺寸未变直接返回（旧路径每次 resize 会 `resetSim()` 清空 flashes、重放时重复 `Lighting.flash`）。

## Tux 彩蛋布局

`CelebrationLayoutGeometryTest` 钉住几何：

| 工况 | 视口 | 标题 | footer 预留 | 插画 | 总高 |
|---|---|---|---|---|---|
| 320×568（系统栏 92） | 476 | 153 | 200 | **123** | **476（恰好放下）** |
| 360×640 | 548 | 153 | 200 | 195 | 548 |
| 极小视口 | 400 | 180 | 220 | 72（地板） | 520（允许滚动兜底） |

footer 预留高度按阶段给（Tux 88dp；生日 WAITING 200dp / 祝福与完成 216dp），320ms 动画过渡、
内容底对齐；插画地板 200→72dp；`CelebrationArtView` 未量到舞台时只画底色，不再用「屏幕居中」兜底。

## 未验证（如实记录）

- **真机波形观感**：本环境无 adb 设备，波形只在横屏大屏 / 平板宽屏挂载；依据是 JVM 仿真与单测，
  「顺不顺」仍需用户真机确认。
- **真机 WebView surface 重建**：Compose Dialog 的 inset 抖动导致的黑闪无法在本环境复现；
  本轮只修了页面侧的硬切与 resize 重放。
- **真机 Tux 动画观感**：footer 预留动画、插画缩放是否自然，需要真机看；几何有单测。
- 按用户要求本轮不做真机测试。
