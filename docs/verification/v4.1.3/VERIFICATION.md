# Arris 4.1.3-gpl 本地签名测试包（源码重构验收）

2026-11-13。Android 10 及以上，versionCode 228，versionName `4.1.3-gpl`。

- 源码：`refactor/arris-source` 分支，版本提交 `3438f39`；生产代码为交接提交 `f1b92fb`。
  自 4.1.2 正式版（`50460a5`）起共 50 个提交，其中本轮交接文档记为 12 个源码提交。
- 包名 `com.takahashirinta.ncrust`，minSdk 29，targetSdk 36；APK 10,671,728 字节，
  SHA-256 `7febd644f26437ddaec815c486a067f6cc2ba03048771a309c6f48bb1b52d30b`。
- `apksigner verify` 通过（APK Signature Scheme v2），证书 SHA-256
  `e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`，与 4.1.0/4.1.2 一致。
- `next-version.sh` 三源交叉（tag / 已发布 APK 227 / 工作区 227）⇒ 228。
- 离线全量 JVM：3,611 项，3,607 通过、4 跳过、0 失败、351 个类；`assembleRelease` 成功。

## 本轮内容（详见 CHANGELOG 与交接文档）

- 后台队列：队列、游标、随机轮次同批保存；游标更新只写游标键（5,000 首队列三次移动，
  整列键写入 4 次 → 1 次）；播放整张歌单时若续播记录为空则跳过队列遍历。
- 收藏与离线缓存：收藏写入绑定发起时的登录账号，同曲串行、批量去重；离线列表等首个音频
  片段落盘后再显示曲目。
- 歌词与页面：AMLL 预取与实际起播共享下载（镜像请求 2 → 1）；单曲页合并重复详情请求、
  歌词独立加载；搜索历史按受影响分区更新；清掉队列与预取里没有调用方的代码。

## 已做 / 未做

- 已做：签名构建、包名/版本/API 下限复核、全量 JVM 单测、装入本机 cell（`droidloomctl install`，
  保留应用数据）并启动，库内 1,058 首正常显示。
- 未做：真机弱网与频繁切网复测；本包未跑 Android instrumentation（上一轮记录为隔离 Android 14
  模拟器 14 项通过）。
