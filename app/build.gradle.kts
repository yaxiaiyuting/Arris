import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------- 安全加载签名配置 ----------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.takahashirinta.ncrust"
    compileSdk = 36

    // ---------- 签名配置 ----------
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.takahashirinta.ncrust"
        minSdk = 24
        targetSdk = 36
        // 本 fork 自有的版本线：基于上游 1.3.1 (versionCode 6)。
        // versionCode 必须严格大于上游，否则后续 fork 版本无法覆盖安装。
        // v1.9.0 的 versionCode 是**实测**定的，不是按任务书推的：任务书写「v1.8.0 = 21，本版 22」，
        // 但 aapt2 dump badging dist/Ncrust-v1.8.1-gpl-release.apk 实测 v1.8.1 已经是 22。
        // 照抄 22 会与已发布的 v1.8.1 撞号、导致无法覆盖安装，故本版取 23。
        // v1.9.1 是 v1.9.0 的 hotfix：修正镜像回退逻辑（jsdelivr 的 403/404 不能判「无此歌词」）。
        // 已发布的 v1.9.0-gpl 制品**不原地替换**，走新的 versionCode，保持发布产物可复现。
        // v1.9.2 修的是 v1.9.0 的分轨缺陷（TTML 胜出时整轨丢弃 ncm 的译文/音译）。
        // **第三次撞号教训**：任务书写「本版 v1.9.1」，但 aapt2 实测 v1.9.1-gpl 早已发布
        // （versionCode 24，修的是歌词镜像回退），照抄会与线上包撞号、无法覆盖安装 ⇒ 本版取 25。
        // v1.9.3 是渲染层受控解禁：只加音译行渲染（开关默认关），SweepTrack 核心算法一个字节未改。
        // **第四次**：任务书建议「versionCode 以 next-version.sh 输出为准」—— 本版起这就是硬流程，
        // 脚本输出的 26 与上面三个来源一致（最近 5 个 tag / dist 36 个 APK / 仓库当前 build.gradle
        // 的最大值都是 25）。动版本号之前必须先跑 tools/next-version.sh，不要再靠记忆推断。
        // v2.0.0 是 major 版：离线缓存做成可管理的完整能力（Phase 2）+ 三个用户报告的 bug 修复
        // （大屏保持横屏 / 乱序不再静默退回顺序播放 / 歌词加载期控制栏）+ 播放时禁止熄屏 +
        // 动态字号（实验性，默认关）。**仍然没有显式「下载」入口**（合规定位不变）。
        // **第五次按脚本定号**：冷启动跑 tools/next-version.sh，三源（最近 5 个 tag /
        // dist 38 个 APK 的 aapt2 badging / 仓库当前 build.gradle）最大值都是 26 ⇒ 本版取 27。
        // v2.0.1 是 v2.0.0 的 hotfix：修「大屏模式下逐字歌词在还没进入第一句时被拉到最顶端、
        // 第一句看不见」（根因 = 歌词面板固定 200dp 的顶部留白在大屏短面板里占掉 77%，
        // 加上前奏期间三条自动定位路径全部提前返回；详见 AGENTS.md 的 v2.0.1 节与仓库外的
        // PHASE0-REPORT-v2.0.1.md）。**SweepTrack.kt 核心算法一个字节未改**（v1.5.2 冻结）。
        // **第六次按脚本定号**：冷启动跑 tools/next-version.sh，三源（最近 5 个 tag /
        // dist 40 个 APK 的 aapt2 badging / 仓库当前 build.gradle）最大值都是 27 ⇒ 本版取 28。
        // v2.0.2 是媒体通知专项 hotfix：修「华为/荣耀双通知栏」+「通知栏歌词只有暂停/播放才
        // 刷新」。两条都是应用层 bug，已在华为平板 WGR-W09（HarmonyOS 4.2 / EMUI 14.2.0 /
        // Android 12 / API 31）与荣耀 AGT-AN00（MagicOS_9.0.0 / Android 15 / API 35）上真机
        // 复现并取证；根因、证据与未验证项见 AGENTS.md 的 v2.0.2 节、dist 下的
        // RELEASE-NOTES-v2.0.2-gpl.md 与仓库外的 PHASE0-REPORT-v2.0.2.md。
        // **第七次按脚本定号**：冷启动跑 tools/next-version.sh，三源（最近 5 个 tag /
        // dist 42 个 APK 的 aapt2 badging / 仓库当前 build.gradle）最大值都是 28 ⇒ 本版取 29。
        // v2.1.0 是 minor 版：接入 **qm 音源**（ncm + QQ 双音源并存、各自独立登录）。
        // 架构上引入了 MusicSourceProvider 抽象与 SourceRouter；渲染层核心
        // （SweepTrack 的扫词算法）**一个字节未改**；`applicationId`、签名、权限全部未动。
        // 关键设计：qm 的数字 id 用 `1L shl 62` 标志位与 ncm 的 id 空间**结构性隔离**，
        // 因此离线缓存 key / 歌词缓存 key / 续播进度表 / 队列判重这些既有结构一个都没改，
        // 也没有任何数据迁移（对照方案是在 5 个文件里各做一次 key 带音源 + 老 key 兼容读）。
        // **第十二次按脚本定号**：`tools/next-version.sh` 三源交叉验证的最大值是 33
        // （最近 tag = 33、dist 里 46 个 APK 的 aapt2 badging 最大值 = 33、
        // 仓库当前 build.gradle = 33）⇒ 本版取 **34**。
        //
        // v2.1.4 是一个**用户报障驱动的诊断版**：报障是「QQ 超级会员开不了母带，
        // 只能开极高，和免费用户没区别」。逐档位取链实测（登录态）显示服务端
        // `music_lev_hq=1 / music_lev_sq=0 / music_lev_hires=0 / identity.HugeVip=0`，
        // 即**账号权益本来就只到 HQ**，服务端是按权益如实下发 —— 真正的缺陷在客户端：
        // 音质角标把「请求档位」当「实际档位」显示，于是 320k 的 mp3 被写成「超清母带」。
        // 本版修掉这个撒谎的角标，并按用户要求让聚合搜索按「用户有哪些平台的会员」排序。
        //
        // **第十三次按脚本定号**：冷启动跑 `tools/next-version.sh`，三源交叉验证的最大值是 34
        // （最近 tag = 34、dist 里 48 个 APK 的 aapt2 badging 最大值 = 34、
        // 仓库当前 build.gradle = 34）⇒ 本版取 **35**。
        //
        // v2.1.5 是**跨源切歌专项修复版**：用户报障「qm 歌曲播放完自动切到 ncm 歌曲，
        // 音频已变但歌词仍是 QQ 那首」。根因是「当前歌的音源」由三个并列的可变字段表示，
        // 而**只有显式 playSong 会写它们** —— 无缝预载的自动接续只更新了 songId，
        // 于是取词仍按上一首的音源路由：拿上一首 QQ 曲目的 songmid 去问 QQ，
        // 拿回来的是上一首的歌词，而且因为请求确实是当前代而通过了所有闸门。
        //
        // 修法：把曲目身份收成一个不可变值 `TrackKey(source, id, sourceId, mediaId)`
        // （相等性**只看 source+id**），取词入口改成 `fetchLyrics(track)`、
        // 分叉依据从全局字段改成参数；`LyricLoadCoordinator` 把「世代 + 当前曲目 + UI 状态机」
        // 合成一个纯逻辑对象。`SweepTrack` 的扫词算法、`applicationId`、签名、权限全部未动。
        // 根因、A/B 真机证据与未验证边界见 docs/verification/v2.1.5/。
        // v2.1.6：媒体承重结构改造 —— 把并行的两条 MediaSession 合并成一条。
        // 决定性的华为白名单证据（third_app_filter.xml 是硬编码包名表）见
        // docs/verification/v2.1.6/。versionCode 出处：tools/next-version.sh 三源交叉
        // 验证最大值为 35（dist/Ncrust-v2.1.5-gpl-*.apk）⇒ 本版 36。
        // ⚠️ v2.2.1 起这里**只保留一处赋值**：v2.2.0 时同时留着 2.1.6 与 2.2.0 两段
        // `versionCode = …`（后写者生效 = 37），但 `tools/next-version.sh` 按行取值，
        // 于是它把「仓库当前」读成 36，与 APK badging 的 37 自相矛盾 —— 三源交叉校验
        // 的价值就在于三源互证，其中一源被自己的写法读错等于白做。历史值留在注释里。
        //   v2.1.6 ⇒ versionCode 36 / versionName "2.1.6-gpl"（历史，勿再写回）
        //
        // v2.2.0：qm 用户歌单/信息**只读**同步（探针先行）。
        // 新增：QQ 歌单列表 + 详情（分页）+「我喜欢」（dirId=201）+ 收藏歌单；
        // 本地缓存按 source+ownerId+playlistId 隔离并带 schema 迁移；离线可看、手动刷新；
        // 账号/歌单切换的世代竞态防护（generation + ownerId + key 三重判据）。
        // **不做**：任何写操作、跨源合并、最近播放（接口实测不存在）、华为控制中心卡片。
        //
        // versionCode 出处：`tools/next-version.sh`（**带 fetch**）三源交叉验证
        //   ① tag 指向的 build.gradle 最大值 = 36（v2.1.6-gpl / fae47b4）
        //   ② dist/*.apk 的 aapt2 dump badging 最大值 = 36（Ncrust-v2.1.6-gpl-*.apk）
        //   ③ 工作区 build.gradle = 36
        //   ⇒ max = 36 ⇒ 本版 **37**（取 max+1 是硬纪律：撞号会导致无法覆盖安装）。
        //
        // ⚠️ 本条**纠正过一次**：首次跑脚本时用了 `--no-fetch`，本地没有 v2.1.6 的 tag，
        //   脚本报 max=36 只因为读到了未提交的工作区改动，我据此以为 v2.1.6 尚未成立。
        //   带 fetch 后才发现 v2.1.6 **已经打 tag（fae47b4）并已有 draft release**。
        //   教训与 v1.9.2 那次一致：**动版本号之前必须先 fetch**，否则三源里的「tag」
        //   这一源是残缺的。本版因此也把基线从 v2.1.5 改基到 v2.1.6 ——
        //   否则 v2.2.0(37) 装到 v2.1.6(36) 之上会把 MediaSession 合并等修复**回退**掉。
        //
        // 版本线选择依据（为何独立 v2.2.0、不并入 v2.1.6）见
        // docs/verification/v2.2.0/VERSION-DECISION.md。
        // v2.2.1：P0 级联故障修复（QQ 音源循环切音质 / 自动切歌 / 音频焦点抢占）。
        // 根因 = 可视化 tee 把输出声明成单声道，而 QQ「臻品音质」档回的是 6 声道 FLAC，
        // media3 没有 6→1 的混音系数 ⇒ UnsupportedOperationException ⇒ AudioSink 不可恢复
        // ⇒ 之后每一档都失败 ⇒ 无上限降档 + 无熔断跳歌把故障放大成整条队列。
        // 探针、根因链、版本回溯与真机 A/B 见 docs/verification/v2.2.1/p0-quality-loop/。
        //
        // versionCode 出处：`tools/next-version.sh`（**带 fetch**）三源交叉验证
        //   ① 最近 5 个 tag 指向的 build.gradle 最大值 = 36
        //      （脚本按行取第一处赋值；v2.2.0 的 tag 里有 36/37 两处，下一行 ② 补上）
        //   ② dist/*.apk 的 aapt2 dump badging 最大值 = **37**（Ncrust-v2.2.0-gpl-release.apk）
        //   ③ 工作区 build.gradle（本版已收敛成单处赋值）= 37
        //   ⇒ max = 37 ⇒ 本版 **38**（取 max+1 是硬纪律：撞号会导致无法覆盖安装）。
        // v2.3.0：库界面信息架构重构 + 本地歌单编辑（只加不减 + tombstone）
        //          + 搜索/艺人/单曲的音源归属与版权标注 + 官方（原唱/翻唱）标签
        //          + 横屏歌词 5s 无触碰自动居中。
        // **不做**（任务书明确排除）：远程歌单写操作、跨源歌单合并、第三个音源、
        //   华为控制中心卡片。本地歌单只写本地 prefs，不碰任何远程写接口。
        // 探针：docs/verification/v2.3.0/PROBE-SUMMARY.md（五个问题全部有实测答案）。
        //
        // versionCode 出处：`tools/next-version.sh`（**带 fetch**）三源交叉验证
        //   ① 最近 5 个 tag 指向的 build.gradle 最大值 = 38（v2.2.1-gpl）
        //   ② dist/*.apk 的 aapt2 dump badging 最大值 = 38（Ncrust-v2.2.1-gpl-*.apk）
        //   ③ 工作区 build.gradle（上一版收敛成单处赋值）= 38
        //   ⇒ max = 38 ⇒ 本版 **39**（取 max+1 是硬纪律：撞号会导致无法覆盖安装）。
        //   实测记录：docs/verification/v2.3.0/verification/version-check.txt
        // v2.4.0（本版）：versionCode 出处：`tools/next-version.sh`（**带 fetch**）三源交叉验证 ——
        //   ① 最近 5 个 tag 内 build.gradle 最大值；② `dist/*.apk` 的 aapt2 badging（唯一可信的
        //   「这个号已经发布出去了」来源，本次实测 v2.3.0-gpl = 39）；③ 仓库当前 build.gradle。
        //   三者一致 = 39 ⇒ 本版 **40**。打 tag 前会用
        //   `git show v2.4.0-gpl:app/build.gradle.kts | grep version` 再自证一次。
        // v2.5.0（本版）：versionCode 出处：`tools/next-version.sh`（**带 fetch**）三源交叉验证 ——
        //   ① 最近 5 个 tag 内 build.gradle 最大值；② `dist/*.apk` 的 aapt2 badging（唯一可信的
        //   「这个号已经发布出去了」来源，本次实测 v2.4.0-gpl = 40，debug 与 release 两个包一致）；
        //   ③ 仓库当前 build.gradle = 40。
        //   脚本输出：`MAX versionCode (所有来源) = 40 [Ncrust-v2.4.0-gpl-debug.apk]` ⇒ 下一个可用 **41**。
        //   实测记录：docs/verification/v2.5.0/verification/version-check.txt
        //   打 tag 前会用 `git show v2.5.0-gpl:app/build.gradle.kts | grep version` 再自证一次。
        //
        // v2.5.1（本版）：页面切换动效**用户可配（默认启用）** + 关闭 v2.5.0 的四项遗留验证缺口。
        //
        // **为什么另起 v2.5.1 而不是重打 v2.5.0 的 tag**（刻意的决定，不是惯性）：
        //   任务书 §5.1 允许「仅补验证 + 设置项 + 单测」时并入 v2.5.0 draft 并重打 tag
        //   （v2.5.0-gpl 至今仍是 **Draft**，技术上可移动）。**不采纳**，两条理由：
        //   ① 本仓库自己的先例：v2.1.1-gpl / v2.1.2-gpl 的 tag 同样只对应 draft（release 已删），
        //      AGENTS.md 记的处置是「按本项目纪律不移动已发布的 tag，所以另起一版而不是改它」——
        //      tag 一旦推上去就算「已发布」，跟着 draft 状态走会让这条纪律变成可协商的。
        //   ② v2.5.0 的 draft release 里**已经上传了两个已签名 APK**（sha256 记在 v2.5.0/EVIDENCE.md），
        //      重打 tag 就要用**同一个 versionCode 41** 换掉它们 —— 于是「41」这一个号对应两份不同的
        //      产物，而「versionCode ↔ 产物」一一对应正是 versioning 纪律存在的意义。
        //   代价如实体现在这里：多占一个版本号，且 v2.5.0 的 draft 需要人工删除（见 release notes）。
        //
        // versionCode 出处：`tools/next-version.sh`（**带 fetch**）三源交叉验证 ——
        //   ① 最近 5 个 tag 内 build.gradle 最大值 = 41（v2.5.0-gpl）；
        //   ② `dist/*.apk` 的 aapt2 dump badging（唯一可信的「这个号已经发布出去了」来源，
        //      本次实测 v2.5.0-gpl 的 debug 与 release 两个包都是 41）；
        //   ③ 仓库当前 build.gradle = 41。
        //   脚本输出：`MAX versionCode (所有来源) = 41 [Ncrust-v2.5.0-gpl-debug.apk]` ⇒ 下一个可用 **42**。
        //   实测记录：docs/verification/v2.5.1/verification/version-check.txt
        //   打 tag 前会用 `git show v2.5.1-gpl:app/build.gradle.kts | grep version` 再自证一次。
        //
        // v2.5.2（本版）：**歌词自动居中的几何修正**（用户紧急反馈）。
        //
        // 症状：自动居中「只是把第一行居中」—— 多行歌词、尤其是带翻译的歌，
        //       大屏模式下整块明显往下坠。
        // 根因：定位语义是「**条目顶边**落在视口 leadFraction 处」（`leadOffsetPx`），
        //       而一个 LazyColumn 条目是 `原句 + 译文 + 音译`（含折行）的整块。
        //       大屏右栏视口约 232dp，三行条目近 112dp ⇒ 整块一直铺到视口底部。
        //       真机实测（S6 / 大屏模式 / 带翻译的歌）：条目中心落在面板 **67.6%** 处。
        // 修法：改为「**整条的中点**落在 leadFraction 处」（`blockTopPx`），
        //       并抽出发实测高度 → 同一帧纠正的路径；顶部渐隐带成为顶边下限。
        //       修后同场景实测 67.6% → **53.6%**（残差 34px 是字体 leading 的几何差）。
        //
        // versionCode 出处：`tools/next-version.sh`（**带 fetch**）三源交叉验证 ——
        //   ① 最近 5 个 tag 内 build.gradle 最大值 = 42（v2.5.1-gpl）；
        //   ② `dist/*.apk` 的 aapt2 dump badging 最大值 = 42（Ncrust-v2.5.1-gpl-*，
        //      v2.5.1 已于 2026-09-25 正式发布，所以这一版**必须**升号）；
        //   ③ 仓库当前 build.gradle = 42。
        //   脚本输出：`MAX versionCode (所有来源) = 42 [Ncrust-v2.5.1-gpl-debug.apk]` ⇒ 下一个可用 **43**。
        //   实测记录：docs/verification/v2.5.2/verification/version-check.txt
        //
        // v2.5.3（本版）：versionCode 出处：`tools/next-version.sh`（**带 fetch**）三源交叉验证 ——
        //   ① 最近 5 个 tag 内 build.gradle 最大值 = 43（v2.5.2-gpl）；
        //   ② `dist/*.apk` 的 aapt2 dump badging 最大值 = 43（Ncrust-v2.5.2-gpl-debug / -release，
        //      v2.5.2 已于 2026-09-25 正式发布，所以这一版**必须**升号）；
        //   ③ 仓库当前 build.gradle = 43。
        //   脚本输出：`MAX versionCode (所有来源) = 43 [Ncrust-v2.5.2-gpl-debug.apk]` ⇒ 下一个可用 **44**。
        //   实测记录：docs/verification/v2.5.3/version-check.txt + next-version.txt
        //
        // v2.5.4（本版）：versionCode 出处：`tools/next-version.sh`（**带 fetch**，铁律 9）三源交叉验证 ——
        //   ① 最近 5 个 tag 内 build.gradle 最大值 = 44（v2.5.3-gpl）；
        //   ② `dist/*.apk` 的 aapt2 dump badging 最大值 = 44（Ncrust-v2.5.3-gpl-debug / -release，
        //      v2.5.3 已发布，所以这一版**必须**升号）；
        //   ③ 仓库当前 build.gradle = 44。
        //   脚本输出：`MAX versionCode (所有来源) = 44 [Ncrust-v2.5.3-gpl-debug.apk]` ⇒ 下一个可用 **45**。
        //   实测记录：docs/verification/v2.5.4/next-version.txt + version-check.txt
        //
        // v2.5.5（本版）：versionCode 出处：`tools/next-version.sh`（**带 fetch**，铁律 9）三源交叉验证 ——
        //   ① 最近 5 个 tag 内 build.gradle 最大值 = 45（v2.5.4-gpl）；
        //   ② `dist/*.apk` 的 aapt2 dump badging 最大值 = 45（Ncrust-v2.5.4-gpl-release，
        //      v2.5.4 已发布，所以这一版**必须**升号）；
        //   ③ 仓库当前 build.gradle = 45。
        //   脚本输出：`MAX versionCode (所有来源) = 45 [Ncrust-v2.5.4-gpl-release.apk]` ⇒ 下一个可用 **46**。
        //   实测记录：docs/verification/v2.5.5/next-version.txt + version-check.txt
        //
        // v2.5.6（本版）：versionCode 出处：`tools/next-version.sh`（**带 fetch**，铁律 9）三源交叉验证 ——
        //   ① 最近 5 个 tag 内 build.gradle 最大值 = 46（v2.5.5-gpl）；
        //   ② `dist/*.apk` 的 aapt2 dump badging 最大值 = 46（Ncrust-v2.5.5-gpl-release/debug，
        //      v2.5.5 已发布，所以这一版**必须**升号）；
        //   ③ 仓库当前 build.gradle = 46。
        //   三源一致 ⇒ 脚本输出：`MAX versionCode (所有来源) = 46 [Ncrust-v2.5.5-gpl-debug.apk]`
        //   ⇒ 下一个可用 **47**（`--code` 亦输出 47）。
        //   实测记录：docs/verification/v2.5.6/version-check.txt
        //
        //   附注（不移动已发布 tag）：`git fetch` 时报
        //   `! [已拒绝] v2.5.5-gpl -> v2.5.5-gpl（会覆盖现有的标签）` ——
        //   本地 v2.5.5-gpl 是**轻量 tag**，远端是**附注 tag**，两者指向的提交相同
        //   （都是 de304d38），只是 tag 对象不同。按铁律 7「发布过的 tag 绝不移动」，
        //   本次**不**推送/不强推任何 tag，仅记录这一差异。
        // v2.6.0（本版）：**QQ 曲目「加入库」后刷新消失** + 歌手页「全部播放」+
        //   库页布局切换 + 歌单区块手动折叠。四份探针见 docs/verification/v2.6.0/。
        //
        // versionCode 出处：`tools/next-version.sh`（**带 fetch**，铁律 9）三源交叉验证 ——
        //   ① 最近 5 个 tag 内 build.gradle 最大值 = 47（v2.5.6-gpl）；
        //   ② `dist/*.apk` 的 aapt2 dump badging 最大值 = 47（Ncrust-v2.5.6-gpl-debug / -release，
        //      v2.5.6 已发布，所以这一版**必须**升号）；
        //   ③ 仓库当前 build.gradle = 47。
        //   三源一致 ⇒ 脚本输出：`MAX versionCode (所有来源) = 47 [Ncrust-v2.5.6-gpl-debug.apk]`
        //   ⇒ 下一个可用 **48**。
        //   实测记录：docs/verification/v2.6.0/verification/version-check.txt
        //
        //   附注（不移动已发布 tag）：`git fetch` 再次报
        //   `! [已拒绝] v2.5.5-gpl -> v2.5.5-gpl（会覆盖现有的标签）` ——
        //   与 v2.5.6 时记录的是同一件事：本地是轻量 tag、远端是附注 tag，
        //   指向的提交相同（`de304d38`）。按铁律 7 本次**不**推送/不强推任何 tag。
        //   v2.6.1 定号：`bash tools/next-version.sh`（**先 fetch**）三源交叉验证
        //   最大值 = 48（tag `v2.6.0-gpl` / `dist/Ncrust-v2.6.0-gpl-{debug,release}.apk`
        //   的 aapt2 badging / 仓库上一版 build.gradle 三者一致）⇒ 本版 **49**。
        //   实测记录：docs/verification/v2.6.1/verification/next-version.txt
        //   打 tag 前用 `git show v2.6.1-gpl:app/build.gradle.kts | grep version` 自证过。
        //
        //   附注（不移动已发布 tag）：`git fetch` 再次报
        //   `! [已拒绝] v2.5.5-gpl -> v2.5.5-gpl（会覆盖现有的标签）` ——
        //   与 v2.5.6 / v2.6.0 时记录的是同一件事：本地是轻量 tag、远端是附注 tag，
        //   指向的提交相同。按铁律 7 本次**不**推送/不强推任何 tag。
        //
        //   v2.6.2（本版）：versionCode 出处：`tools/next-version.sh`（**带 fetch**）三源交叉验证 ——
        //   `--json` 输出 `{"max_version_code":49,"max_source":"Ncrust-v2.6.1-gpl-debug.apk",
        //   "next_version_code":50,...,"apk_count":83}`，即
        //     ① tag `v2.6.1-gpl` 的 `app/build.gradle.kts` = 49；
        //     ② `dist/Ncrust-v2.6.1-gpl-{debug,release}.apk` 的 aapt2 badging = 49；
        //     ③ 仓库上一版的 `app/build.gradle.kts` = 49。
        //   三源一致 ⇒ 下一个可用 **50**。
        //   实测记录：docs/verification/v2.6.2/verification/next-version.txt
        //   打 tag 前用 `git show v2.6.2-gpl:app/build.gradle.kts | grep version` 自证过。
        //
        //   v2.8.0（本版）：versionCode 出处：`tools/next-version.sh`（**带 fetch**）三源交叉验证 ——
        //     ① 最近 5 个 tag 的 `app/build.gradle.kts` 最大 = 50（`v2.6.2-gpl`）；
        //     ② **已发布 APK** 的 `aapt2 dump badging` = 50（本轮重新下载 release 资产
        //        `Ncrust-v2.6.2-gpl-release.apk` 实测；红线 19：不看 release 标题里写的号）；
        //     ③ 工作区 `app/build.gradle.kts` = 50。
        //   三源一致 ⇒ 下一个可用 **51**。
        //   ⚠️ v2.7.x 从未存在：tag / release 列表里没有 v2.7.0，任务书直接给到 v2.8.0，
        //   版本号按「max + 1」取 51（不是 52，也不是沿用 50）。
        //   实测记录：docs/verification/v2.8.0/verification/next-version.txt
        //
        //   ⚠️ **本版签名与历史版本不同**：原 release 密钥（`ncrust-release.jks`）随工作区一起丢失
        //   （从不在 git：`.gitignore:18-20` 排除 `*.jks`/`keystore.properties`），用户改用新密钥
        //   `ncrust-release-v2.jks`（cert SHA-256 `e62eca39…bbec82`）。
        //   后果：**老用户无法覆盖安装，必须先完全卸载**（会清掉登录态与离线缓存）。
        //   `applicationId` / 权限 / 华为卡片一律未动。
        //
        //   v2.9.0 定号：`tools/next-version.sh` 三源交叉校验 ——
        //     ① 最近 5 个 tag：v2.8.0-gpl=51 / v2.6.2-gpl=50 / v2.6.1-gpl=49 / v2.6.0-gpl=48 / v2.5.6-gpl=47
        //     ② 已发布 APK 实测：Ncrust-v2.6.2-gpl-release.apk=50（v2.8.0 的还是 draft，本地在仓库外的 dist/）
        //     ③ 工作区：51 / 2.8.0-gpl
        //   三源最大值 = 51 ⇒ 下一个可用 **52**。
        //   （打 tag 前会用 `git show v2.9.0-gpl:app/build.gradle.kts | grep version` 自证一次。）
        //
        //   v3.0.0 定号：`tools/next-version.sh`（**带 fetch**）三源交叉校验 ——
        //     ① 最近 5 个 tag：v2.9.0-gpl=52 / v2.8.0-gpl=51 / v2.6.2-gpl=50 / v2.6.1-gpl=49 / v2.6.0-gpl=48
        //     ② 已发布 APK 实测：Ncrust-v2.6.2-gpl-release.apk=50（`aapt2 dump badging`；
        //        v2.8.0 / v2.9.0 的还是 draft，本地包在仓库外的 dist/ —— 红线 19：不看 release 标题）
        //     ③ 工作区：52 / 2.9.0-gpl
        //   三源最大值 = 52 ⇒ 下一个可用 **53**。
        //   实测记录：docs/verification/v3.0.0/verification/next-version.txt
        //   （打 tag 前会用 `git show v3.0.0-gpl:app/build.gradle.kts | grep version` 自证一次。）
        //
        // v3.1.0 定号（`tools/next-version.sh`，**先 fetch 再跑**；实测记录
        // docs/verification/v3.1.0/verification/next-version.txt）：
        //   ① 最近 5 个 tag：v3.0.0=53 / v2.9.0=52 / v2.8.0=51 / v2.6.2=50 / v2.6.1=49
        //   ② 已发布 APK 实测：Ncrust-v2.6.2-gpl-release.apk=50
        //      （v2.8.0 / v2.9.0 / v3.0.0 的本地包在仓库外的 dist/ —— 红线 19：不看 release 标题）
        //   ③ 工作区：53 / 3.0.0-gpl
        //   三源最大值 = 53 ⇒ 下一个可用 **54**。
        // v3.2.0：**第七次按脚本定号**。冷启动跑 tools/next-version.sh（fetch 后），
        // 三源（最近 5 个 tag 指向的 build.gradle：54/53/52/51/50；dist/*.apk 的
        // aapt2 badging 实测最大值 50；仓库当前 54）最大值 = 54 ⇒ 本版取 **55**。
        // 本版范围：QQ VIP 版权误判 P0（铁律 20/21）、界面律动独立开关 P1（铁律 22）、
        // B站扫码登录 P1（铁律 23）、歌词全屏迷你封面错位 P0-A、简洁档仍抖动 P0-B、
        // B站搜索恒 0 条与「只搜B站」卡死 P0-C/P0-D。
        // v3.2.1：**第八次按脚本定号**。冷启动跑 `tools/next-version.sh`（**带 fetch**）——
        //   ① 最近 5 个 tag 指向的 build.gradle.kts：v3.2.0=55 / v3.1.0=54 / v3.0.0=53 /
        //      v2.9.0=52 / v2.8.0=51；
        //   ② 已发布 APK 的 `aapt2 dump badging` 实测（唯一可信的「这个号已经发布出去了」
        //      来源，红线 19：不看 release 标题）：本轮脚本只扫到本地 dist/ 里的一只
        //      （Ncrust-v2.6.2-gpl-release.apk=50）；v3.2.0 的包在仓库外的 dist/ 且脚本
        //      的 glob 没吃到 —— 但 ① 的 tag v3.2.0-gpl=55 已经把它钉住了；
        //   ③ 工作区：55 / 3.2.0-gpl。
        //   三源最大值 = 55 ⇒ 下一个可用 **56**。
        //   实测记录：docs/verification/v3.2.1/next-version.txt
        //   （打 tag 前会用 `git show v3.2.1-gpl:app/build.gradle.kts | grep version` 自证一次。）
        // 本版范围：PlayerCard 巨型 composable 拆分 P0（铁律 24/25）、
        // wideLeftFraction 对称正下限 P0-A（铁律 26）、B站扫码登录「打开即失效」P0、
        // JIT 对照实验 C、方法大小监控机制（铁律 24 的门禁）。
        // v3.2.2：**第九次按脚本定号**。冷启动跑 `tools/next-version.sh`（**带 fetch**，铁律 9）——
        //   ① 最近 5 个 tag 指向的 build.gradle.kts：v3.2.1=56 / v3.2.0=55 / v3.1.0=54 /
        //      v3.0.0=53 / v2.9.0=52；
        //   ② 已发布 APK 的 `aapt2 dump badging` 实测：dist/ 里
        //      Ncrust-v3.2.1-gpl-release.apk=**56**、Ncrust-v3.2.0-gpl-release.apk=55
        //      （本轮脚本自己只扫到 v2.6.2=50，56/55 是本代理手工补测的 —— 口径与脚本一致，
        //      都是「包的 versionCode 实测值」，不看 release 标题）；
        //   ③ 工作区：56 / 3.2.1-gpl。
        //   三源最大值 = 56 ⇒ 下一个可用 **57**。
        //   实测记录：docs/verification/v3.2.2/next-version.txt
        //   （打 tag 前会用 `git show v3.2.2-gpl:app/build.gradle.kts | grep version` 自证一次。）
        // 本版范围：波形条改造为频段曲线（连续曲线 + 主导频段着色 + 平滑/滞回/短过渡防闪 +
        // 档位与开关联动；铁律 28/29/30/31）。**不新增持久化键**：频段着色复用既有的
        // `motion_wave_bands` 开关与档位映射，因此没有新迁移、没有新文案。
        // v3.2.3：**第十次按脚本定号**。冷启动跑 `tools/next-version.sh`（**带 fetch**，铁律 9）——
        //   ① 最近 5 个 tag：v3.2.2=57 / v3.2.1=56 / v3.2.0=55 / v3.1.0=54 / v3.0.0=53；
        //   ② 已发布 APK 实测：dist 里 Ncrust-v3.2.2-gpl-release.apk=**57**（本轮脚本自带 glob
        //      只吃到 v2.6.2=50，57 是按同一口径手工补测的）；
        //   ③ 工作区：57 / 3.2.2-gpl。
        //   三源最大值 = 57 ⇒ 下一个可用 **58**。
        // **为什么另起一版而不是改 v3.2.2**：v3.2.2-gpl 的 tag 已经推上去了，而它的 release
        // 因为本机 api.github.com 被劫持**从未创建**（见 docs/verification/v3.2.2/RELEASE-STATUS.md）。
        // 按本仓库纪律「不移动已推的 tag」，用户随后的两条实测反馈（默认档看不到频带、
        // 刷新率低）一律走新版本 —— 与 v2.1.1/v2.1.2 折进 v2.1.3 是同一处置。
        // 本版范围：三条频带泳道 + 连续滚动（亚格插值）+ 低端机帧间隔 33ms→16ms
        // （铁律 28 的"release 包验证帧时间"）。**仍然没有新增持久化键。**
        // v3.2.4：两条实测缺陷 —— P0「B 站歌曲无法播放」+ P1「新设备（高刷新率）音频条抖动」。
        // **第八次按脚本定号**：冷启动跑 tools/next-version.sh，三源（最近 5 个 tag =
        // v3.2.3..v3.1.0 的 58/57/56/55/54、已发布 APK 的 aapt2 badging、工作区 58）
        // 最大值 = 58 ⇒ v3.2.4 取 **59**。
        // ── v3.3.0（本版）────────────────────────────────────────────────────────
        // 定号依据：`tools/next-version.sh` 三源交叉校验（fetch 后）
        //   ① 最近 5 个 tag：v3.2.4-gpl=59 / v3.2.3=58 / v3.2.2=57 / v3.2.1=56 / v3.2.0=55
        //   ② 已发布 APK 的 aapt2 badging：v2.6.2-gpl-release.apk=50
        //   ③ 工作区：59
        // 最大 = 59 ⇒ v3.3.0 取 **60**；v3.3.1 取 **61**（均已发布）。
        // ── v3.4.4 ──────────────────────────────────────────────────────────────
        // ⚠️ 本段正文是 **v3.4.2** 的定号记录（标题历次滚动时正文没同步更新）。
        // v3.4.4 的实际取值：versionCode 66 / versionName "3.4.4-gpl"，
        // 内容是小球改牛顿落体 + 弹性碰撞（用户定的观感）。
        // 定号依据：三源交叉校验最大值 = 63 ⇒ 本版取 **64**。
        // 版本名取 **3.4.2**（patch）：修 v3.4.1 引入的**闪退** ——
        // `peaks` 数组容量是 `barCount`，而三泳道路径写 `peaks[total]`（total = barCount×3）
        // ⇒ 越界写 ⇒ 一播放就崩。已把容量改成 `pointCount` 并加显式守卫。
        // 同时保留 v3.4.1 的波形形状修复（相位改成几何平移）。
        // ── v3.4.5 ──────────────────────────────────────────────────────────────
        // 定号依据：三源交叉校验最大值 = 66 ⇒ 本版取 **67**。
        // 内容：B 站合成 id 通过跨源闸门（P0）+ 收藏链路三处（移植上游 de193ff / 6b5b470 读端）。
        // ── v3.4.6（本版）────────────────────────────────────────────────────────
        // 定号依据：`tools/next-version.sh` 三源交叉校验（fetch 后）
        //   ① 最近 5 个 tag：v3.4.5-gpl=67 / v3.4.4-gpl=66 / v3.4.3-gpl=65 /
        //      v3.4.2-gpl=64 / v3.4.1-gpl=63
        //   ② 已发布 APK 的 aapt2 badging：v2.6.2-gpl-release.apk=50
        //   ③ 工作区：67
        // 最大 = 67 ⇒ 本版取 **68**。
        // 版本名取 **3.4.6**（patch）：**纯修复与观感调整，无新功能**，
        // 不改持久化结构、不改包名与签名（可直接覆盖安装 v3.4.5）。
        // 内容：① 波形小球不再粘在柱顶（接触时刻碰撞 + 脱离地面 + 重标定 g=10 / e=0.7）；
        //       ② 短横改成真正的峰值保持（不再与小球同步移动）；
        //       ③ 振幅留 12% 余量（竖屏不再顶满）；
        //       ④ B 站音频区不再把文件字节数当码率；
        //       ⑤ 音质标签旁显示实际播放码率。
        // ── v3.4.7（本版）────────────────────────────────────────────────────────
        // 定号依据：`tools/next-version.sh` 三源交叉校验（fetch 后）
        //   ① 最近 5 个 tag：v3.4.6-gpl=68 / v3.4.5-gpl=67 / v3.4.4-gpl=66 /
        //      v3.4.3-gpl=65 / v3.4.2-gpl=64
        //   ② 已发布 APK 的 aapt2 badging：v2.6.2-gpl-release.apk=50
        //   ③ 工作区：68
        // 最大 = 68 ⇒ 本版取 **69**。
        // 版本名取 **3.4.7**（patch）：**只修三泳道波形的横向滚动**，
        // 不改持久化结构、不改包名与签名（可直接覆盖安装 v3.4.6）。
        // 内容：删掉 `frameClockMs` / `clockOffsetMs` / `pendingArrivalMs` 三个字段 ——
        //   相位的分子改成由帧循环传入的**真实帧时间戳**与「最后一根被消费的柱的到达时刻」
        //   相减（两个量同源、都不经过本类累加），到达帧不再被清零、位移不再丢。
        // v3.4.8（70）：**改名 + 换图标 + 三个用户报的 B 站问题**。
        //   ① 品牌：Ncrust → **Arris**（显示名 / 8 语言 / 图标 / README）。
        //      ⚠️ `applicationId` / `namespace` / 24 个 prefs 文件名**一个都没动**
        //      （有 `RenameGuardTest` 钉着）⇒ 可直接覆盖安装 v3.4.7，数据零迁移。
        //   ② 图标：正圆唱片 + 内嵌朝右直角三角 + 圆心唱片孔；三层自适应
        //      （含 Android 13+ 的 `<monochrome>`），并补上 **API 24/25 的密度桶回落**
        //      —— 仓库此前只有 `mipmap-anydpi-v26`，Android 7.x 上 launcher 拿不到图标。
        //   ③ B 站：大会员 Hi-Res 无损（读 `dash.flac.audio`，此前从没被读）、
        //      字幕语言可选（此前写死中文优先）、音质上限与优先 FLAC 两个用户参数。
        // v3.4.9（71）：**qm 登录态续期**（用户报「qq 音乐登录身份一周左右就掉」）
        //   + 「QQ 音乐 App 扫码」登录（可续期的那条扫码路）。
        //   ⚠️ 没有改动任何持久化结构或包名 ⇒ 可直接覆盖安装 v3.4.8，数据零迁移。
        //   新增的落盘只有一个 key（`ncrust_qq_prefs` / `qq_refresh_credential`），
        //   老用户没有它就是「不能续期」，行为与 v3.4.8 完全一致 —— 这是本次迁移的验收口径。
        //   版本号出处：`tools/next-version.sh`（带 fetch）三源交叉验证 ——
        //   最近 5 个 tag 的最大值 70（v3.4.8-gpl）/ dist 里 aapt2 实测的最大值 69
        //   （v3.4.7 是**最后一个真正进了 dist 的包**，v3.4.8 只有 tag、没有制品）
        //   / 工作区当前值 70 ⇒ 三源最大 70 ⇒ 本版取 **71**。
        versionCode = 71
        versionName = "3.4.9-gpl"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * v2.1.5：lint 基线。
     *
     * **HEAD（v2.1.4）上 `lintDebug` 本来就是红的**：57 个 error，全部是既有问题 ——
     * `GradleDependency`(42) / `UseTomlInstead`(38) / `NewApi`(14) /
     * `AndroidGradlePluginVersion`(3) / `UnusedResources`(2) 等依赖版本与 opt-in 类提示，
     * 与本次改动无关（基线报告留档在 `docs/verification/v2.1.5/lint-report-HEAD-unmodified.txt`）。
     *
     * 用基线而不是「关掉 lint」或「批量 suppress」：基线是按 (文件, 问题类型, 消息) 记账的，
     * **本版新增的文件不在基线里**，所以它们身上任何新问题仍然会让构建失败 ——
     * 「lint 必须通过」这条验收因此仍然有约束力，而不是被一纸豁免架空。
     */
    lint {
        baseline = file("lint-baseline.xml")
        // 基线只挡 error；warning 继续可见（不因为加了基线就把它们藏起来）。
        warningsAsErrors = false
        abortOnError = true
    }

    buildTypes {
        release {
            // R8 全量优化：删除死代码、内联、混淆、shrink 资源。
            // 低端机冷启动的最大杠杆——DEX 体积可减 30%+，类加载时间线性下降。
            // proguard-rules.pro 保护 Gson model 反射与 Retrofit interface 注解。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    // v1.3.0 · B1：单元测试里 android.util.Log 等框架方法是抛异常的桩，会让「走日志的纯逻辑」
    // 直接 RuntimeException（本次踩到：parseWriteResponse 的空 body 分支）。置 true 后桩方法
    // 返回默认值，测的是逻辑而不是 Android 运行时。仅影响 testDebugUnitTest，不影响 APK。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        // 让 AboutScreen 通过 BuildConfig.VERSION_NAME 动态显示版本号，
        // 避免每次发版忘记同步硬编码。
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    // Kanesumi —— Metro 组件库。通过组合构建从 ../Kanesumi-sec-a 接入
    // (见 settings.gradle.kts 的 includeBuild)。core/anim 由 controls/structure
    // 的 api 依赖自动带出,这里只列上游两件。
    implementation("io.github.takahashirinta:kanesumi-controls:0.1.0-SNAPSHOT")
    implementation("io.github.takahashirinta:kanesumi-structure:0.1.0-SNAPSHOT")

    implementation("androidx.core:core-ktx:1.13.0")
    // 登录二维码: eapi unikey 接口不返回图片, 客户端本地生成(官方客户端同款做法)
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.activity:activity:1.9.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Media3 —— 1.5.0 是能与下面 FFmpeg 扩展版本严格对齐的最低版本（扩展版本号规则为
    // <media3版本>+<修订>，Maven Central 上没有 1.4.x 的构建）。
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    // v1.6.0 · D1：离线缓存（SimpleCache / CacheDataSource / StandaloneDatabaseProvider）。
    // 这两个之前是 media3-exoplayer 的传递依赖，显式声明以免上游改依赖树时静默消失。
    implementation("androidx.media3:media3-datasource:1.5.0")
    implementation("androidx.media3:media3-database:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")

    // FFmpeg 软件音频解码扩展（Jellyfin 构建，GPL-3.0，与本项目 GPLv3 兼容）。
    // 目的：API 24–26（Android 7.0/7.1）系统只带 OMX.google.flac.encoder，
    // 没有 FLAC 解码器，无损档位只能降级到 mp3；随包带 FFmpeg 后即可真正解 FLAC。
    // 覆盖 arm64-v8a / armeabi-v7a / x86 / x86_64 四个 ABI。
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // CameraX——手机端扫码授权（扫描平板登录二维码后经局域网回传 cookie）
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Coil
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    // Palette & Media
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.media:media:1.7.0")

    // MediaSession extension
    implementation("androidx.legacy:legacy-support-v4:1.0.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Baseline Profile installer——把 src/main/baseline-prof.txt 打包进 APK，
    // 首次启动时 profileinstaller 会请求 ART AOT 编译这些类，消除冷启前 30s 的 JIT 抖动。
    // 生成方式：手写 class-level 预加载列表（当前）；后续可接 :baselineprofile 模块自动采样。
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")

    testImplementation("junit:junit:4.13.2")
    // v1.3.0 · B1：纯 JVM 单测要真正跑 JSON 解析。Android SDK 里 org.json 是抛异常的桩
    // （unit test 里 new JSONObject(...) 直接 throw），所以测试 classpath 补一份实现。
    // 许可证：Public Domain（JSON.org 原文 "The Software shall be used for Good, not Evil."），
    // 仅 testImplementation，不进 APK，见 THIRD-PARTY-LICENSES.md R4。
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}