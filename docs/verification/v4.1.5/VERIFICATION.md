# Arris 4.1.5-gpl 发布验收

2026-11-15。Android 10 及以上，versionCode 230，versionName `4.1.5-gpl`。

- 源码：`refactor/arris-source`，构建提交 `11871e1`（自 `v4.1.4-gpl` 起 19 个提交）。
- 包名 `com.takahashirinta.ncrust`，minSdk 29，targetSdk 36；APK 10,720,912 字节，
  SHA-256 `eaa98029c7a8bd87f5e4c107fd5628759aa5aedcd89f9df227c7eb37cba9bea9`。
- 签名：APK Signature Scheme v2 通过，证书 `e62eca39…dfbbec82`（与 4.1.0–4.1.4 同一把）。
- `next-version.sh` 三源交叉（tag / 已发布 APK 229 / 工作区 229）⇒ 230。
- 全量 JVM：3,689 项（3,683 通过、6 跳过、0 失败，359 个类）；`assembleRelease` 成功。

## 依赖补丁（构建可复现性）

本版包含一处 **Kanesumi（`io.github.takahashirinta:kanesumi-*`，以 includeBuild 引入）**
的本地修复：`MetroIndication` 的交互反馈由串行 `collect` 改为 `collectLatest`，连点只播最新一次。
该修改未推送到上游 `GuitaristRin/Kanesumi-sec-a`（不是我们的仓库），补丁随仓库归档：
`patches/kanesumi-indication-collectLatest.patch`。**没有它构建出的包点击反馈仍会排队。**

## 内容

- 歌单详情长按多选（云端 / 本地各一套批量动作，单条菜单挪到行尾）
- 自建歌单封面取第一首歌专辑图，空歌单用名字占位
- 离线 URL 记住可播状态（只对服务端拒绝型错误记失败）
- 点击反馈、下拉刷新指示器、输入框聚焦区域三处交互修复
- 上游 `spec/fixtures/{quality,queue,lrc}` 接入与 18 条一致性断言

## 已做 / 未做

- 已做：签名构建、包名/版本/API 下限复核、全量 JVM、装入本机 cell（Droidloom）后人工路径验证
  （多选、封面、下拉刷新、连点反馈均由用户真机确认）。
- 未做：弱网与频繁切网、荣耀/小米胶囊外观、厂商系统冻结进程时的后台表现；本轮未跑 Android instrumentation。
