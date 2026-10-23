# v3.7.0-gpl 发布验证

版本：`versionName = 3.7.0-gpl` / `versionCode = 86`（v3.6.9 = 85，下一号 86）。
产物：`dist/v3.7.0-gpl/Ncrust-v3.7.0-gpl-release.apk`
（12,039,856 B，SHA-256 `57d2aa710785fa03fbee97db56c94dfd3cab19196a2e0cec9a20d04f66592e01`）。
包名 / 签名与 v3.6.8/3.6.9 相同，可直接覆盖安装。

## 本版内容

按用户要求把波形**横向滚动速度**做成可选项，默认 1.5×：

- 设置页「外观与动效 → 波形滚动速度」：0.5× / 0.75× / 1× / 1.5× / 2×；
- 落盘键 `visualizer_speed_step`（0..4，默认 3 = 1.5×），坏值/越界夹回合法区间；
- `VisualizerPrefs.SPEED_FACTORS` 是唯一倍速表；`WaveformRing.setScrollSpeedFactor`
  只改 UI 消费节拍，不碰音频线程；
- >1× 时数据会先被取完，`pump` 缺样分支改为**重复最后一列**（hold），避免把加速
  做成空白断层；`bassTarget` 在 hold 列保持上一根真实值，节拍/粒子不会误判静音；
- 1× 及以下仍保持 v3.6.5 的「缺失位置留空」语义；
- 倍速写入后立即 `WaveformStore.setSpeed`，启动时由 `VisualizerSetting.read` 从盘恢复；
- `WaveformStrings` 新增 `visualizerSpeedLabel` / `visualizerSpeedDescription`（8 种语言），
  说明写清 >1× 会重复最后一列、形状会拉伸。

本版构建同时包含 v3.6.9 的峰值标记采样相位修复（慢速滚动时标记被重新采样导致
「小球闪现」）。

## 验证

| 项 | 结果 | 证据 |
|---|---|---|
| 全量 JVM 单测 | **3007 用例 / 0 失败 / 240 类** | `test-summary.txt` |
| 倍速夹取 / 二倍速消费 / 缺样 hold / 设置默认与往返 | 全绿 | `WaveformSpeedTest` |
| 设置键注册 / 行数 / 文案预算 / 8 语言 | 全绿 | `SettingsRegistryTest`、`StringsConstructorBudgetTest` |
| `assembleRelease`（R8 + lintVital） | BUILD SUCCESSFUL | `SHA256SUMS` |
| `assembleDebug` | BUILD SUCCESSFUL | `SHA256SUMS` |
| `aapt2` | `versionCode='86' versionName='3.7.0-gpl'` | `apk-badging.txt` |
| `apksigner` | 与 v3.6.8/3.6.9 同一张证书 | `apk-signature.txt` |

## 尚未验证 / 取舍

- 真机 PLC110 不在线；emulator-5554 已断开，**没有在真机上做「设置里切换倍速 → 波形
  立即变快/变慢」的目视确认**。设置写入后调用 `WaveformStore.setSpeed` 的链路有单测，
  但 UI 的实际观感仍需真机看一眼。
- >1× 时重复最后一列是**有意取舍**：数据源固定约 10 柱/秒，任何持续加速都必须重复
  或插值；本版选重复（不编造新的峰值形状），1× 档保留原始语义。
- 默认 1.5× 会对既有用户生效；想恢复原速可在设置里选 1×。
