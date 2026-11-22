# Arris 4.1.8-gpl 发布验收

2026-11-22。Android 10 及以上，versionCode 240，versionName `4.1.8-gpl`。

- 源码：`refactor/arris-source`，构建提交 `42fda8a`（自 4.1.6 起 8 个提交，含 4.1.7 未发布的两条播放修复）。
- APK 10,786,156 字节，SHA-256 `20f7c00282332c02a29f894050ed7aac6065e0a8302bb4d973b921699b33e36b`。
- 签名：v2 通过，证书 `e62eca39…bbec82`（与 4.1.0–4.1.6 同一把）。
- 全量 JVM：**3717 例 / 0 失败 / 6 跳过**（362 个 suite）；`assembleRelease` 成功。

## 内容

- 后台播放保护引导页（OEM 识别 + 三步说明 + 最近任务锁定 + 电池优化授权）
- 服务侧卡顿判据（READY 时钟不前进也判）+ 服务自愈 + 15 秒 tick 诊断
- 网络型失败退避（2s→30s，窗口 10 分钟，不清队列、不置降级角标）
- 通知内容类更新节流（≥5 秒一档，补发不丢行）

## 真机证据（PLC110 / Android 17 / ColorOS 16）

- 10 分钟熄屏后台播放 ×2：tick 37 / 35 条，**无时间空洞**，`stalledFor` 全程 0，position 与墙钟一致
- 通知重建：4.1.7 基线 12.3 次/分（密集段 1–2 s）→ 4.1.8 10.1 次/分（密集段严格 5.0 s）
- 断网 20s：走 v4.1.7 退避，网络恢复后自动从断点续播（13 s 内）
- ColorOS 组件可达性实测：`Settings$ManageApplicationsActivity` 导出但被重定向到设置首页；
  `com.oplus.battery` 的 StartupAppListActivity / PowerUsageModelActivity 带签名权限，第三方不可达

## 未做 / 未证实

- 「READY 时钟冻结且不报错」的真机现场**未复现**（两次 10 分钟正常），判据由 9 个 JVM 单测覆盖
- 通知再降频的三条备选（10 s floor / 熄屏不重发 / 完全不重发）未采用
- 厂商 ROM 的电池策略无法在 Droidloom cell 复现，结论只覆盖本机 ColorOS
