# v3.6.9-gpl 发布验证

版本：`versionName = 3.6.9-gpl` / `versionCode = 85`（v3.6.8 = 84，下一号 85）。
产物：`dist/v3.6.9-gpl/Ncrust-v3.6.9-gpl-release.apk`
（12,023,472 B，SHA-256 `ed6d47fa7119612ead0144a23aa3ebb40b280685ea4a12b0f68443ae7b2ebf53`）。
包名 / 签名与 v3.6.8 相同，可直接覆盖安装。

## 症状与根因

用户实测：**滚动快时小球正常，滚动慢时小球闪现**。

三泳道为了密度用 `markerStride = 2`（隔一格画一个峰值标记），起点固定为 0。
窗口每滚动一格，同一格数据在窗口里的下标就变一位（偶数 ↔ 奇数）：

- 滚动前：数据格 D 在窗口下标 i，且 `i % 2 == 0` ⇒ 被采样，有小球/短横；
- 滚动一格后：D 到了 i-1（奇数）⇒ 采样循环从 0 开始、步长 2，D **不再被采样**；
  邻居 D±1 变成偶数下标，被点亮。

于是每滚一格，整条标记图案就**重新采样**一次。滚动慢时每 100ms 一次，肉眼就是
「小球闪一下」；滚动快时变化太快，反而不容易察觉。这与用户观察到的速度相关性
完全一致。

## 修法

采样起点跟着窗口累计平移数走：

```
start = shiftedCells % markerStride
```

设数据格在窗口里的下标为 i，平移 S 格后同一数据格的下标是 `i-1`；取
`start = S % stride` 后，采样到的数据格奇偶性保持不变。同一格数据无论滚到哪一格，
都被采样，小球跟着它连续左移，不再被重新采样。

实现收在 `BandMarkerGeometry.markerStart`（纯函数），`drawWaveformCurve` 新增
`markerStart` 参数，三泳道调用点用 `WaveformStore.shiftedCells()` 取值。
单带模式 `stride = 1`，起点恒 0，行为与改前逐像素一致。

## 验证

| 项 | 结果 | 证据 |
|---|---|---|
| 全量 JVM 单测 | **3003 用例 / 0 失败 / 239 类** | `test-summary.txt` |
| 新增采样回归 | `markerStart` 奇偶 + 「同一数据格跨平移保持采样状态」 | `BandMarkerGeometryTest` |
| `assembleRelease`（R8 + lintVital） | BUILD SUCCESSFUL | `SHA256SUMS` |
| `assembleDebug` | BUILD SUCCESSFUL | `SHA256SUMS` |
| `aapt2` | `versionCode='85' versionName='3.6.9-gpl'` | `apk-badging.txt` |
| `apksigner` | 与 v3.6.8 同一张证书 | `apk-signature.txt` |

## 尚未验证 / 风险

- 真机 PLC110 不在线；模拟器 emulator-5554 在本次验证前已断开，**没有做慢速滚动的
  逐帧放大对比**。修复只改峰值标记的采样起点，不改峰值数据、球物理或音频链路，
  风险面限定在标记绘制。
- 若真机上仍有闪现，下一个排查点是**标记可见性阈值**（`ballHalf * 2 > 3dp`）：
  低幅度段小球在阈值附近来回穿越时也会出现「亮/灭」，那需要滞回而不是采样相位。
  本次没有一并改，避免把两个独立原因混在一个版本里。
