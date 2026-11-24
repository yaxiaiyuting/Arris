# Arris 4.1.12-gpl 发布验收

2026-11-25。Android 10 及以上，versionCode 245，versionName `4.1.12-gpl`。

- 源码：`refactor/arris-source`，构建提交 `0ca0a6d`（自 v4.1.10 起 5 个提交：下拉刷新悬浮层 / 识曲交互 / 库搜索 / 服务侧复活 / 本版两个功能）。
- APK 10,835,308 字节，SHA-256 `b27ff8e437f4dd09ac79d7379e0f17bc6e9d0f538df542e621a809a6c95de1b7`。
- 签名：v2 通过，证书 `e62eca39…bbec82`（与 4.1.0–4.1.10 同一把）。
- 全量 JVM：**3795 例 / 0 失败 / 6 跳过**（373 suites）；`assembleRelease` 成功。

## 内容

- 库页表头随拖动收起（`ui/components/CollapsingHeader.kt` 新增）：听歌识曲 / 一起听 / 单曲·歌单·专辑·离线 tab 条整体上滑收起，单曲 tab 的搜索框与计数行进 LazyColumn 首项
- 单曲 tab：本地搜索（`library/LibrarySongSearch.kt`）+ 显式「加载更多」（替换原先的滚到末尾自动加载）
- 艺人页单曲：`/eapi/v1/artist/songs` 按 id 分页（一页 100、最多两页），回落名字搜索；`PROBE_LIMIT` 改为按侧各 100
- 识曲结果：点按即播（`providerTrackId` → 详情 → 入队，带播放意图代次）、长按去搜索
- 下拉刷新：指示器从列表第一项改为内容区顶部悬浮层（`PullToRefreshOverlay`）
- 后台复活：`player/PlaybackRecovery.kt`（15 秒判死、15s→180s 退避封顶、不设次数上限、30 分钟窗口），服务侧 `SourceRouter` 重新取链后续播

## 真机验证（PLC110 / Android 17 / ColorOS 16，装 245）

- 库页：上滑时表头先收、收完列表才滚；甩动后**不再停在半收起**（这条是装第一版补丁后现场发现并修的）；下拉到顶表头展开；收起后列表从状态栏下沿开始，一屏约 10 行（原 2.5 行）
- 装机自证：`pm path` pull 回 `base.apk` 与 `dist/` 内的包同哈希
- 断网测试只跑到「`pause_failed` 确实会发生」这一步（用户叫停，网络已恢复），复活链路的完整闭环（`Resurrect ok`）**未在真机观测到**

## 未做 / 未证实

- 艺人页 200 首、库页「加载更多」按钮、识曲点按播放三项**只有编译 + 单测**，没有真机截图
- 后台复活：只有判据与退避的单测；`Resurrect ok` 未在真机看到
- QQ 侧艺人曲目仍受服务端单页 30 首限制（`QqCatalogApi.artistSongs` 的 `take(200)` 永不生效，实测 `num` 可到 60），本版未改 QQ 文件
- 把搜索框放进列表的固有代价：滚出屏幕再滚回来时文字仍在（状态在页面级），但焦点与光标位置不保留
