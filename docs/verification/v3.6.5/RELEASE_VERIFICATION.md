# v3.6.5-gpl 发布验证

版本：`versionName = 3.6.5-gpl` / `versionCode = 81`（v3.6.4 = 80，下一号 81）。
产物：`dist/v3.6.5-gpl/Ncrust-v3.6.5-gpl-release.apk`
（11,947,560 B，SHA-256 `83de2c11905540c62d806077e0249257076c76aa41ab3ec49b6a1ad4c191e299`）。
包名 / 签名与 v3.6.3、v3.6.4 相同，可直接覆盖安装。

## 三条修复

### 1. 波形小球 / 横条闪现

缺样列（v3.6.3 数据迟到继续滚动）平移窗口时只移了窗口值与画面值：

- `displayVel`（画面值速度）没有随格子平移 ⇒ 新最右格继承上一格的上升速度；
- 三条泳道的 `dash` 没有随格子平移（旧代码实际移的是单曲线 `dash`）⇒ 短横留在旧峰值；
- `ball` / `ballVel` / `ballRest` 只移 `[0,last)`，新格保留上一格的球状态。

下一帧 `coerceIn(ground, 1f)` 把继承来的高点压到新柱高，画面上就是右边缘的小球/短横闪一下。
修法：`displayVel`、`ball`、`ballVel`、`dash` 与格子一起平移；新格从地面 0 起步；
缺样列 `display` 直接置 0，不留 130ms 假回落。新增回归：
`blank underrun column carries no stale ball or dash`。

### 2. 音频未加载完，歌词先唱

歌词外推只看 `isPlaying`。按下播放、音频仍在缓冲时 `isPlaying` 已经为 true，
外推从 0 开始跑。`LyricsView` 新增 `isBufferingFlow`（StateFlow 引用，订阅在歌词内部，
不带动播放器整树重组），缓冲中 / 首条位置采样到达前不外推；音频真正出声后再跟。

### 3. 横屏曲终，音频到下一首但播放界面不换

音频已经切到下一首、`playbackQueue` 里却找不到该项时（预载槽位 / 跨源接续 /
队列尚未追加），`currentSong` 停在上一首。现在用 ViewModel 的 `currentTrackKey` +
名称/作者/封面兜底构造，界面与音频一致；队列索引保持原样，下一次正常推进自行对齐。

## 验证

| 项 | 结果 | 证据 |
|---|---|---|
| 全量 JVM 单测 | **2999 用例 / 0 失败 / 239 类** | `test-summary.txt` |
| 波形缺样 / 静音 / 暂停 / 安静重绘 | 全绿（含新增缺样列峰值回归） | `test-summary.txt` |
| `assembleRelease`（R8 + lintVital） | BUILD SUCCESSFUL | `SHA256SUMS` |
| `assembleDebug` | BUILD SUCCESSFUL | `SHA256SUMS` |
| `aapt2` | `versionCode='81' versionName='3.6.5-gpl'` | `apk-badging.txt` |
| `apksigner` | 与 v3.6.3/v3.6.4 同一张证书 | `apk-signature.txt` |

## 尚未验证 / 风险

- 真机（PLC110）本轮不在线；模拟器 API 33 只做了手动切歌与队列核对，
  **没有在真机横屏自然曲终复测**。③ 的兜底路径以代码与日志为准，建议装包后
  在真机横屏让一首歌自然播完再确认一次。
- 波形修复由单测（缺样列峰值/短横归零）覆盖；「小球与横条闪现」是视觉现象，
  模拟器上未做逐帧放大对比。
- 歌词缓冲门控会让首句在极端情况下晚 100~500ms（等首条位置采样），
  这是为了避免「先唱」而接受的代价。
