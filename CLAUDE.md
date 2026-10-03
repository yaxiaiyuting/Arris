# AGENTS.md

This file provides guidance to coding agents (Codex, Claude Code, opencode, …) working in this repository. It reflects the code as of **v1.3.1** (`versionCode = 6`). When in doubt, the source wins — update this file if you find a stale claim.

## Build & Test Commands

```bash
./gradlew assembleDebug            # debug APK  -> app/build/outputs/apk/debug/
./gradlew assembleRelease          # release APK (R8 minified), needs keystore.properties
./gradlew test                     # JVM unit tests
./gradlew connectedAndroidTest     # instrumented tests (device/emulator required)

# Macrobenchmark (performance; drives a signed release build, not connectedCheck)
benchmark/run_benchmark.sh [startup|scroll|expand|all]     # default: all

# Standalone Go backend — NOT used by the app (see ncrust-api/ below)
cd ncrust-api && go build -o ncrust-api .
```

Toolchain:

| | |
|---|---|
| compileSdk / targetSdk | 36 (Android 15) |
| minSdk | 24 |
| Java / jvmTarget | 11 |
| Kotlin | 1.9.24 |
| Compose BOM | 2024.12.01 (compiler ext 1.5.14) |
| Android Gradle Plugin | 8.5.0 |
| Gradle | wrapper 9.3.1 |

**Composite build:** `settings.gradle.kts` does `includeBuild("../Kanesumi-sec-a")` with explicit `dependencySubstitution`, so the app builds against the sibling `Kanesumi-sec-a` checkout (not a published artifact). Cloning Ncrust alone is not enough — `Kanesumi-sec-a` must sit next to it. Delete the `includeBuild` block once Kanesumi is on Maven Central; the coordinates in `app/build.gradle.kts` then work unchanged.

## Repository Layout

```
Ncrust/
├── app/                  # the Android application (Kotlin + Jetpack Compose)
├── benchmark/            # macrobenchmark module (3 benchmarks + run_benchmark.sh)
├── ncrust-api/           # standalone Go reverse proxy — orphaned, not wired into the app
├── build.gradle.kts      # top-level plugin declarations
├── settings.gradle.kts   # :app, :benchmark + Kanesumi composite build
├── gradle.properties     # jvmargs, AndroidX, suppressUnsupportedCompileSdk=36
├── keystore.properties   # gitignored, release signing (storeFile/storePassword/keyAlias/keyPassword)
└── local.properties      # gitignored, sdk.dir
```

- **`app/`** — everything documented below.
- **`benchmark/`** — `StartupBenchmark` (cold start, 8 iters), `HomeScrollBenchmark` (frame timing, 6 iters), `ExpandPlayerBenchmark` (player expand/collapse, 6 iters, HOT start). Results land in `benchmark/output/<device>/` (gitignored) as `*-benchmarkData.json` + Perfetto traces. `benchmark/cookie.secret` (gitignored) injects a login cookie for realistic runs; `cookie.secret.example` documents it.
- **`ncrust-api/`** — a self-contained Go 1.21 service (stdlib only) that reverse-proxies ncm with eapi encryption and TTL caching. It includes a checked-in 9.75 MB binary. **The Android app never calls it** — the app talks directly to `music.163.com` / `interface*.music.163.com`. Treat it as an unused side artifact / future scaffold; do not assume it is part of the runtime.

## Versioning & Release

Single source of truth: `app/build.gradle.kts` → `defaultConfig.versionName` / `versionCode`.

- `AboutScreen.kt` reads `BuildConfig.VERSION_NAME` — **never hardcode a version constant**. This needs `buildFeatures.buildConfig = true`.
- Release flow: bump `versionCode` + `versionName` → commit `build: 升级至 vX.Y.Z ...` → `./gradlew assembleRelease` → **校验工作区干净 && `HEAD` 的 `app/` 源码 == 产物源码** → **最后**才 `git tag` + push → `gh release create vX.Y.Z --draft <apk>` → user smoke-tests and publishes manually.
  ⚠️ **v2.1.3 在这一步踩过一次**：把 `git tag` 和修复提交写在同一条命令里，于是 tag 落在
  **版本号提交之前**，`git show vX.Y.Z:app/build.gradle.kts` 里的 `versionCode` 还是上一版的。
  因为那个 release 还是 draft（从未发布），纠正方式是**删掉 tag 重建到正确的提交**
  （`git push origin :refs/tags/…` 再推一次）—— 注意这**只对从未发布的 tag 成立**；
  一旦 release 发布过，就只能另起新版本，绝不移动 tag。
  **教训：tag 必须打在「产出这批 APK 的那个提交」上，而那个提交一定在 `git log` 里位于
  `build: 升级至 …` 之后。打 tag 前先 `git show vX.Y.Z:app/build.gradle.kts | grep version` 自证一次。**
- Current: `versionName = "2.1.3-gpl"`, `versionCode = 33`. Latest release: `v2.1.3-gpl`.
  （`v2.1.1-gpl` / `v2.1.2-gpl` 的 tag 存在但**均未发布**（只有 draft，已删），内容全部包含在 v2.1.3 里 ——
  它的 tag 已经推上去了，按本项目纪律**不移动已发布的 tag**，所以另起一版而不是改它。）
  （**注意 versionCode 必须递增**：v1.6.1 = 19，所以 v1.7.0 是 20 —— 任务书里写「v1.7.0 = 19」是错的，
  19 已经被 v1.6.1 占用，照抄会导致无法覆盖安装。同理本版 **23**：任务书说「v1.8.0 = 21、本版 22」，
  但 `aapt2 dump badging` 实测 v1.8.1 已经是 **22**，照抄 22 会与线上包撞号、无法覆盖安装。
  **第三次（v1.9.2）**：任务书写「当前状态 v1.9.0 已发布、本版 v1.9.1」，但 `git tag` +
  `aapt2 dump badging` 实测 v1.9.1-gpl 早已发布（versionCode **24**，修的是歌词镜像回退判定）。
  所以本版是 **v1.9.2-gpl / versionCode 25**。**动版本号之前永远先实测 `dist/` 里的最新包**。）
- **动版本号之前必须先跑 `bash tools/next-version.sh`**（v1.9.3 固化；脚本在**仓库外**
  `<repo>-gpl/tools/`，按本仓库惯例不入 git —— `.gitignore` 里有 `.sh`，且 `tools/` 全是探针脚本、
  可能带账号凭证）。它交叉验证**三个来源**并取 `max + 1`，任何一个都不是可信的单点：
  ① 最近 5 个 tag 指向的 `app/build.gradle.kts`；② `dist/*.apk` 的 `aapt2 dump badging`
  （唯一可信的「这个号已经发布出去了」事实来源）；③ 仓库当前 `build.gradle.kts`。
  三个来源在 v1.7.0 / v1.9.0 / v1.9.2 **各撞过一次号**，根因全是冷启动时只凭记忆、或只看单一来源。
  用法：`tools/next-version.sh`（人类可读报告）/ `--code`（只输出数字，给脚本消费）/ `--json` / `--no-fetch`。
  实测（v1.9.3 冷启动）：三源一致 = 25 ⇒ 下一个可用 **26**。

## Commit Convention

All development history lives in `git log`. **Do not create a parallel `*_log.md` file.**

Conventional Commits, lowercase type prefix, Chinese subject:

| Prefix | When to use |
|---|---|
| `feat:` | User-visible new capability (features, UI additions, new APIs) |
| `fix:` | Bug fix; no new behaviour beyond restoring correctness |
| `chore:` | Housekeeping (delete unused files, rename dirs, gitignore) |
| `docs:` | Docs-only changes (README, AGENTS.md, in-code comments) |
| `build:` | Build system / dependencies / version bumps |
| `refactor:` | Code-shape change without behavioural change |
| `perf:` | Performance-only optimisation |
| `style:` | Formatting, whitespace, comment tweaks |

- Subject: prefix + one-sentence Chinese summary. Body (blank line, then paragraphs) explains **why** — commits should be readable a year later without opening a PR. Reference issues with `Fixes #N` / `#N`.
- **One logical section = one commit, committed proactively without waiting to be asked.** Don't batch unrelated changes (e.g. "extract txt + fix color + update docs") into one commit. Split by logical unit, not by file count: changes that must compile together are one section; independently revertable changes are separate.
- Multiple related fixes may share a commit only when they are the same logical change; pick the dominant type by user impact.

## What This App Is

Ncrust is a third-party Android client for **two** music sources — ncm (ncm) and
qm (qm) — each with its own independent login. It is built around three design priorities:

1. **Kanesumi Design** — right-angle cuts, no curves, no rounded corners, information-first.
2. **GPU zero-recomposition** — animations driven by a single `progress: Float` through `graphicsLayer`, not state-driven recomposition.
3. **Three-layer graphics architecture** — main page / player card / navigation bar are independent composable layers, enabling gesture transitions without interference.

Feature surface at v1.3.1: home discovery (daily songs / recommended playlists / new songs / private FM), three-type search with 500 ms debounce + history, cloud-synced library (liked songs / subscribed albums / user playlists), full-screen player with gapless playback and an 8-level quality ladder, 5 playback modes, bilingual lyrics, system media controls + Android Auto / Automotive, WebView + QR login, runtime theming (6 colors × 3 modes), and 8 runtime languages.

## Terminology: Kanesumi Design

The design language is **Kanesumi Design** (canon: Ether monorepo root `KANESUMI_DESIGN.md`), historically "Metro Design". Notes for agents:

- `Metro*` identifiers (`MetroText`, `MetroTheme`, `MetroIndication`, easing constants `MetroDefault`/`MetroCubic`…) are code/component names — **keep, do not rename**.
- New docs/comments: write 「Kanesumi Design / Kanesumi 风格」, not 「Metro Design / Metro 风格」. The easing family is 「UWP 缓动」 (`UwpEasing`), from the Metro era.
- The `Metro*` UI components now live in the external **Kanesumi** library (see below), not in this repo.

## Architecture

### Entry Point & Orchestration

`MainActivity.kt` is a lean entry point (the activity class is ~130 LOC) plus the `MainScreen()` composable in the same file. `MainActivity`:

- applies the orientation policy (phones `< 600dp` locked portrait; large screens unlocked) via `smallestScreenWidthDp`, re-run in `onConfigurationChanged`;
- requests `POST_NOTIFICATIONS` on API 33+;
- calls `RetrofitClient.init(this)` and `AppWarmup.start(this)`;
- builds the theme stack: `LocalStrings` + `metroViewConfiguration` → `NcrustTheme` → `MetroTheme` → `MainScreen` + `SplashScreen` + first-run battery prompt.

`MainScreen()` owns all app-level orchestration: bottom navigation / wide-screen sidebar, the `progress: Animatable<Float>` that drives the player card, the playback queue, the 5 playback modes, the global song menu sheet, clipboard link handling, and the About / WebView-login overlays.

Also at file scope: `formatDuration(ms)`, and `object QueueModes { CYCLE=0, SINGLE=1, SHUFFLE=2, LINE=3, INFINITY=4 }`.

### Package Layout

Under `com.takahashirinta.ncrust/`:

| Package | Purpose |
|---|---|
| `network/` | Retrofit interface (`NcmApi`), eapi/weapi endpoints (`PlaylistApi`), `RetrofitClient` gateway, `CoverUrls`, response models |
| `network/crypto/` | `EapiCrypto` (AES-128-ECB + MD5 sign), `WeapiCrypto` (double AES-CBC + RSA) |
| `player/` | `PlaybackService` (MediaLibraryService + ExoPlayer), `PlaybackStateManager`, `SongUrlFetcher`, `PlayReporter` |
| `auth/` | `CookieManager` (SharedPreferences), `QrPair` / `QrPairClient` / `QrPairServer` (LAN QR handoff) |
| `library/` | `LibraryManager` (cloud-synced liked songs + subscribed albums), `SearchHistoryManager` |
| `cache/` | `ContentCache` — in-memory network snapshot (home + LRU detail caches + user profile)；**v1.6.0 · D1 / v2.0.0 · T3**：`OfflineKeys`（cache key 纯逻辑）、`OfflineUrlStore`（key→最后成功播放的 URL，≤300 LRU）、`OfflineAudioCache`（media3 `SimpleCache`，`filesDir/offline/audio`）、`OfflineLibrary`（离线曲目索引，≤300 LRU） |
| `lyric/` | `LrcParser` (`[MM:SS.mm]` → `LrcLine.timeMs`), `YrcParser` / `YrcAligner` (word-level), `SweepTrack` (sweep cursor), `LyricsDisplayPrefs`; **v1.9.0**: `TtmlParser` / `TtmlScanner` (AMLL TTML), `AmllTtmlClient`, `LyricSourceChain`, `LyricRequestGate`; `LyricsCache` (200-entry persistent cache, LRC + TTML) |
| `warmup/` | `AppWarmup` — cold-start preload singleton |
| `power/` | `BackgroundActivity` — battery-optimisation whitelist intents |
| `ui/navigation/` | `NavRoutes` route constants and `MainNavGraph` |
| `ui/player/` | `PlayerCardOverlay`, `PlayerCard`, `FullPlayerControls`, `LyricsView`, `QueueView`, `SlimProgressBar` |
| `ui/screen/` | One file per screen (Home, Search, Library, User, Album/Artist/Playlist/Song detail, About, Splash) |
| `ui/viewmodel/` | `PlayerViewModel`, `SearchViewModel`, `SongViewModel` |
| `ui/components/` | Reusable composables (`SongCard`, `DetailScaffold`, `SongMenuSheet`, `PlayAllDialog`, `QrLoginDialog`, `QrScannerScreen`, `ArtistSearchItem`, `BackgroundActivityDialog`) |
| `ui/theme/` | `NcrustColors`, `NcrustTypography`, `ThemeManager`, `ThemeColorSelector`, `MarkdownText` |
| `ui/i18n/` | Runtime i18n: `Strings` data class, per-language files, `LanguageManager` |
| `ui/ResponsiveContent.kt` | 360dp (phone) / configurable (wide) max-width centre container |
| `ui/BottomOverlayInset.kt` | `BottomOverlayInsetDp` constant (144dp narrow / 64dp wide) |

### Kanesumi Library (external)

The app's shared UI components and animation vocabulary have **migrated out of this repo** into `Kanesumi-sec-a` (Apache-2.0), consumed via composite build:

- `kanesumi-core` — `MetroTheme`/`MetroColors`/`MetroTypography`, `MetroText`/`MetroIcon`, `MetroInsets`, `MetroIndication`.
- `kanesumi-anim` — `sokuou` package: `UwpEasing`, `SokuouPresets`/`SokuouTweens`, `sokuouSpring`, `MetroFling`/`MetroScroll`, `metroViewConfiguration`.
- `kanesumi-controls` — `MetroSurface`, `MetroButton`, `MetroTabRow`, `MetroSwitch`, `MetroIconButton`, `MetroDropdownMenu`, `MetroBottomSheet`, `MetroLyricsPanel`, `MetroDialog`, …
- `kanesumi-structure` — `MetroShell`, `MetroAppBar`, `MetroDetailScaffold`, `MetroTopScrim`, `MetroBottomNav`, `MetroSidebar`.

**There is no `androidx.compose.material3` dependency in the app** and no `material3` import anywhere. The only Material artifact left is `androidx.compose.material:material-icons-extended` (icons only). The former in-repo `ui/anim/sokuou/` directory no longer exists.

When editing UI, prefer Kanesumi components/presets. **Do not batch-refactor existing hand-tuned player-card animations** — they are load-bearing.

### Player Card Component Tree

- **`PlayerCardOverlay`** — positions the card via `graphicsLayer { translationY = collapsedOffsetY + (0 - collapsedOffsetY) * progress.value }`. Thin wrapper, no animation logic.
- **`PlayerCard`** — gesture handling (vertical drag, 25% snap threshold), cover-art animation, mini bar, lyrics/queue toggle, wide-screen two-column layout. Owns `lyricAnimProgress` (0 = large cover → 1 = small cover), `queueSlideProgress` (0 = lyrics → 1 = queue), and `wideSplit`. `StableCover` holds the previous cover across song changes (`COVER_HOLD_MS = 400L`). All animation values are read inside `graphicsLayer`.
- **`FullPlayerControls`** — transport buttons, seekable progress bar, position/duration/quality leaf composables (each collects its own StateFlow), lyrics/queue/library toggles, `compact`/`landscape` variants.
- **`LyricsView`** — wraps Kanesumi `MetroLyricsPanel`. Golden-section positioning, 5 s manual-scroll pause, tap-to-seek, bilingual `tlyric` merge.
- **`QueueView`** — three-zone queue (past / current / upcoming), drag-reorder limited to the upcoming zone, INFINITY placeholder, auto-centre on open.
- **`SlimProgressBar`** — Canvas-drawn 40dp-touch progress bar with a lazily-started buffering pulse.

`progress: Animatable<Float>` (0 = mini, 1 = full) is owned by `MainScreen` and passed through `PlayerCardOverlay` → `PlayerCard`. Card expand/collapse: 400 ms `CubicBezierEasing(0.2, 0, 0, 1)` / 260 ms `FastOutSlowInEasing`.

### Animation Pattern

The player card uses a single `progress: Float` driven by `Animatable`. All visual properties (card size, position, opacity) are computed inside `graphicsLayer { }` — **never `animateFloatAsState`**. This is the core GPU zero-recomposition pattern. Easing is `tween + CubicBezierEasing` (controlled deceleration, no spring/bounce). Cover art always fills the full screen width with no clipping; transitions use centre-based `TransformOrigin(0.5f, 0.5f)` with computed translations.

For anything that is *not* the player card, use the Kanesumi `sokuou` presets (`SokuouTweens.CoverFade`, `SokuouPresets.*`, `mapRange`/`mapRangeClamped`) instead of ad-hoc `tween(...)`.

### Network Layer

Two API styles coexist:

- **REST via Retrofit** (`NcmApi`, base `https://music.163.com/`): search (`api/cloudsearch/pc`, types 1/10/100), song detail (`api/v3/song/detail`), lyrics (`api/song/lyric`), album detail (`api/v1/album/{id}`), artist albums (`api/artist/albums/{id}`).
- **eapi via manual OkHttp POST** (`PlaylistApi` + `RetrofitClient.eapiPost`): playlists, recommendations, daily songs, personal FM, similar songs, song URL, album sub/unsub, like. **weapi** (`RetrofitClient.weapiPost`) is used for QR login (`/api/login/qrcode/unikey`, `/api/login/qrcode/client/login`) and the similar-song fallback.

### Playlist 写操作（v1.3.0 · B1）

歌单管理（创建 / 删除 / 增删曲 / 改名 / 改描述 / 改隐私）走 `PlaylistEditApi` + `PlaylistWriteGate`，
端点与行为全部为 2026-09 登录态实测，不是文档推断：

| 操作 | 端点 | 参数 | 备注 |
|---|---|---|---|
| 创建 | `/eapi/playlist/create` | `name`(必填) `privacy`(0/10) | 顶层 `id` 与 `playlist.id` 相等 |
| 加/删曲 | `/eapi/playlist/manipulate/tracks` | `op`(add/del) `pid` `trackIds` `imme` | 返回 `count` = 操作后曲目数 |
| 删除 | `/eapi/playlist/delete` | **`pid`** | 非本人 → 401 无权限操作歌单 |
| 批量删除 | `/eapi/playlist/remove` | **`ids`**=JSON 串 | 对非本人 pid 返回 200 却无动作，不可用于判权限 |
| 改名 / 描述 / 隐私 | `/eapi/playlist/update/name`、`/desc/update`、`/update/privacy` | `id` + 对应字段 | 三个独立端点，`/playlist/update` **不存在**（404） |

- **鉴权**：写操作的 HTTP 状态码恒为 200，成败只看 `body.code`。需要 cookie 追加身份
  （`ClientIdentity.extraCookieFor`）**或** `csrf_token`，缺一即 403 `illegal request!`
  （实测只带原 cookie 必 403）；**不需要** `eapiPostOfficial` 那套 body header。
  `csrf_token` 的值服务端不校验，但照带 `__csrf` 最稳。
- **`op` 白名单是硬约束**：实测任何非 `add` 的值（含空串、未知值）都会被服务端**当成删除执行**。
  所以请求体只能由 `addSongsPayload`/`removeSongsPayload` 构造，绝不透传外部输入。
- **502 = 幂等成功**：`歌单内歌曲重复`/`歌单歌曲重复` 两种文案都出现过；部分重复时返回 200 且新歌入库。
- **写入判定不能用 detail**：`/eapi/v6/playlist/detail` 有陈旧缓存 —— 歌单**删除后 20 分钟仍返回
  code 200 + 完整内容**，写入后 `trackCount` 也滞后数秒。判定「是否生效」只能用响应体的 `count`
  或 `/eapi/user/playlist`。另外 detail 的 `n` 上限 1000、`s`/`offset` 全部无效，**没有可翻页的
  曲目列表端点**，>1000 首的歌单拿不全（判重只能放弃，交给 502 兜底）。
- **405 限流按身份维度**：`操作过于频繁，请稍后再试`；实测同一时刻 pc 身份持续 405，换成
  os=android/新 deviceId 立刻通过（eapi/weapi/明文 api 三条路报错一致）。客户端只做「串行 +
  最小间隔 2s」（`PlaylistWriteGate`）并在 405 时提示等待，**不做自动重试**。
- **归属判定**：收藏的歌单里 `userId`/`creator.userId` 都是原作者，判自建必须
  `creator.userId == 我 && !subscribed`（`PlaylistInfo.isOwnedBy`）。

`RetrofitClient` hosts:

| Constant | Value |
|---|---|
| `BASE_URL` | `https://music.163.com` |
| `API_URL` | `https://interface.music.163.com` (default eapi host) |
| `INTERFACE_URL` | `https://interface3.music.163.com` (eapi when `useInterface = true`, i.e. song URL fetch) |

- `init(context)` loads the cookie from `CookieManager`; `updateCookie(...)` replaces it (login/logout).
- `eapiPost(path, payload, useInterface)` encrypts via `EapiCrypto`, sends form `params=<hex>`, PC UA + Referer + Cookie.
- `eapiPostOfficial(path, payload)` mimics the official iPhone client (header with `os=iphone`, `appver=8.9.60`, deviceId/osver from cookie, random `requestId`); used for `likeSong`. Encrypted write responses are decrypted with `EapiCrypto.decryptResponse`.
- `weapiPost(path, payloadJson, extraCookies)` produces `params` + `encSecKey` via `WeapiCrypto`.
- `get(path)` plain GET; `postWeblog(url, logsJson)` form POST for playback reporting.
- A cookie interceptor injects `Cookie`/`User-Agent`/`Referer`; `HttpLoggingInterceptor` is `BuildConfig.DEBUG`-only.

`EapiCrypto`: AES key `e82ckenh8dichen8`, `/eapi/` → `/api/` path rewrite, signature `MD5("nobody" + path + "use" + json + "md5forencrypt")`, payload `path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest`, AES-128-ECB/PKCS5 → hex.

`WeapiCrypto`: random 16-char secret, double AES-128-CBC/PKCS5 with preset key `0CoJUm6Qyw8W8jud` then the secret, and `encSecKey` = raw RSA of the reversed secret (256 hex, no PKCS#1 padding, not base64).

### ✅ 详情页播放器死带（v1.3.0 已修 · B-1，此节保留根因与实测数据）

折叠态播放器卡片的 `Box(Modifier.fillMaxSize())` + `graphicsLayer { translationY = collapsedOffsetY }`
（[PlayerCard.kt:404-422](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)、
[PlayerCardOverlay.kt:37-43](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCardOverlay.kt)）
会在屏幕 **y ≳ collapsedOffsetY**（本机 S6/G9209 实测 ≈1872px）以下形成一条**死带**：肉眼看不见，
**但仍参与命中测试，且先于下层详情页拿到事件**（Alpha=0 在 Compose 里不会退出命中测试）。

实测证据（同一页面、同一 ⋮ 按钮）：

| 触摸 y | 按钮节点收到事件 | 页面级 Initial pass | 结果 |
|---|---|---|---|
| 1850（带上沿之上） | ✅ pressed → CLICK | — | 菜单弹出 |
| 1938（带内） | ❌ 一条都没有 | ❌ 也没有 | 完全无响应 |

**规则**：详情页（歌单/专辑/歌手）的交互元素不得落在该带内。需要底部操作时用**顶部 scrim**
（y≈208–400，实测可用）或**列表行入口**承载。

**已踩坑**：

- 歌单详情页「编辑歌单」⋮ 原本放在 `DetailHeader.headerActions`（y≈1842–2016）→ 点不动
  （2/6 命中，全在带上沿）；现已移到 `DetailScaffold.onTopEndAction`（TopScrim 右上）。
- AlbumDetail 的收藏按钮（y≈1616–1808）侥幸落在带上沿之上，**能点但不可靠** —— 换首歌单、
  换封面高度就可能落进带内。
- 同一原因：`MetroBottomSheet`（如歌单操作菜单）画在本页 `LazyColumn` 之下会被盖住，
  且自身也落在带里 —— 详情页不要用底部菜单承载操作，改用 `MetroDialog`。

**根因与修复（v1.3.0 · B-1）**：那一段里其实一个子节点都没有 —— mini bar 与 Column 的内容都被
statusBarsPadding() 下推到 2016，吃事件的是卡片根 Box 自己的两个 pointerInput（上拉手势 / 展开态吞事件），
命中区就是整个 fillMaxSize 根 Box。修法两步：① 折叠态不挂载展开态子树（mini bar 永远挂载）；
② 折叠态给根 Box 一个 statusBar 高的 top padding 收窄这两个 pointerInput，再用等量 offset 把子节点
放回原位（视觉与布局零变化）。实测（API 24 模拟器，同一坐标 y=1188 同长度上滑）：修复前把整卡拉起，
修复后事件还给下层；mini bar 上滑展开、点按展开均正常。「清空队列没有真正移除 overlay」已由 commit A 修掉。

### Playlist 管理（v1.3.0 · B2–B5）

歌单的创建/编辑/删除/增删曲全部走 `PlaylistEditApi`，UI 入口固定四处：

| 入口 | 位置 | 行为 |
|---|---|---|
| 保存当前队列为歌单 | 全屏播放器队列区 ⊕（`onSavePlaylist`，此前是空壳） | 快照队列 → 命名对话框 → 创建 → 批量加歌 |
| 新建空歌单 | 收藏页歌单 tab 网格第一格「＋」 | 复用同一对话框，不加歌 |
| 加入歌单 | 任意歌曲长按菜单第一项（由 `MainActivity.showSongMenu` 统一追加，全 Screen 覆盖） | `AddToPlaylistSheet` 只列**本人自建**歌单 + 顶部「新建歌单」 |
| 编辑 / 删除 / 改隐私 / 移除曲目 | 歌单详情页**顶部 scrim 右上「⋮」**（`DetailScaffold.onTopEndAction`）与曲目长按菜单 | ⋮ 直接开 `EditPlaylistDialog`（名称+简介+隐私，**只发改动过的字段**）+ 底部「删除歌单」（二次确认）；移除曲目在曲目长按菜单，均仅自建歌单可见 |

- **归属判定**：`PlaylistInfo.isOwnedBy(uid)` = `creator.userId == uid && !subscribed`。收藏的歌单
  里 `userId`/`creator.userId` 都是原作者，用 `userId == 我` 会把别人的歌单显示成可编辑。
- **判重不做预判**：`>1000` 首的歌单没有可靠判重手段（见上），重复添加由服务端 502 兜底，
  客户端把它当幂等成功、提示「已加入歌单」——与真的加进去在用户视角无区别，还省一次往返。
- **缓存**：任何写操作成功后调用 `ContentCache.invalidatePlaylist(id)`。服务端 detail 本身有陈旧
  缓存，本地这份不丢就会出现「改名成功、进详情还是旧名」。
- **收藏页的 playlist tab 过滤 `specialType != 0`**，所以「我喜欢的音乐」不在网格里（它是单曲 tab
  的数据源，见 `PlaylistApi.getLikedPlaylistId`）。

### Playback

`PlaybackService` is a **`MediaLibraryService`** (media3), not a plain `MediaSessionService`. It owns:

- an `ExoPlayer` with audio focus, `handleAudioBecomingNoisy`, `WAKE_MODE_NETWORK`, and `DefaultLoadControl(30_000, 60_000, 2_500, 15_000)`; **no on-disk media cache**;
- a media3 `MediaLibrarySession` (Android Auto / Automotive browse tree: root → 每日推荐 / 私人 FM / 我喜欢的音乐, resolving `song:<id>` through `SongUrlFetcher`) and a legacy `MediaSessionCompat` (notification / lock-screen);
- a foreground MediaStyle notification (channel `ncrust_playback`, dominant-colour colorization, artwork bitmap);
- a **2 Hz** progress ticker (`delay(500)`);
- error reporting: both `onPlayerError` and `onAudioSinkError` invoke the static `onPlaybackError(songId)`.

`PlayerViewModel` holds all playback state as `MutableStateFlow` (isPlaying, position, duration, progress, buffering, current song metadata, lyrics, `currentQualityIndex`, `needsPreload`). `PlaybackStateManager` persists the last song + queue to SharedPreferences via Gson so playback survives process kill.

**Song URL resolution** (`SongUrlFetcher`) starts at the selected quality and falls back down the ladder. Exact ladders:

```
dolby    -> dolby, hires, lossless, exhigh, higher, standard
jymaster -> jymaster, hires, lossless, exhigh, higher, standard
jyeffect -> jyeffect, lossless, exhigh, higher, standard
hires    -> hires, lossless, exhigh, higher, standard
lossless -> lossless, exhigh, higher, standard
exhigh   -> exhigh, higher, standard
higher   -> higher, standard
standard -> standard
```

- **Device FLAC gate**: skips `lossless`/`hires`/`jyeffect`/`jymaster` when no MediaCodec `audio/flac` decoder exists (API < 27 or stripped ROM), so ExoPlayer never gets a FLAC stream it can only render as silence.
- **`sky`（沉浸环绕声）：已探明，不实现为独立档位（v1.2.1 TODO 关闭）**。服务端**没有**独立的沉浸声音频文件：账号态 36 首 `maxBrLevel=sky` 曲目的 privilege 里 `maxbr`/`pl` 上限就是 320000，`song.sq`/`song.hr` 均为 null —— 即这些曲子的最高源就是 `exhigh` 320k mp3。实测 `level=sky` 与 `exhigh` 取回的是**同一个文件**（CDN 回源比对文件名/大小/哈希，6/6 一致）；即使请求 `level=sky` 时换成 `encodeType=mp4`、换 appver（3.0.6/8.9.60/9.1.20/9.3.40）或换成 pc/android/iphone 身份，结果都不变；在有母带源的曲目上请求 `level=sky` 会返回该曲最高可用源（jyeffect/jymaster FLAC），说明 sky 请求的语义就是「取该曲最高源」。官方客户端听感上的「环绕感」来自其客户端 DSP 渲染，Ncrust 无法复现，因此加进档位表只会把一个 320k mp3 标成沉浸环绕声、误导用户 —— 故 v1.3.0 明确不实现。复现方式见仓库外 `tools/probe-quality.py` 与本轮 level×encodeType×身份矩阵。
  **客户端侧配套（v1.3.0 · B4）**：档位上限为 `sky` 的曲子，用户在设置里选 hires/无损时，`QualityAssessment` 会把 `sky` 归一化成 `exhigh` 再比较上限（`CAPABILITY_ALIASES`），角标显示「该曲无此档位」；选 exhigh 则不加角标。归一化只改判定、不改档位表（设置页仍是 8 档，不含 sky）。
- **Auto quality downgrade**: `PlayerViewModel.handlePlaybackError` retries the same song one rung lower on `qualityRetryLadder` (reverse order, deduped 3 s per `songId@level`), skipping to the next song only when even `standard` fails. `AudioSink` failures are covered too. The badge is decided by `QualityAssessment` from the **actual file params** (br/type) plus the song cap: a `sky`-capped song requested at hires/无损 shows 「该曲无此档位」, not 「已降级」/「无权限」.
- **Level-aware preload cache**: `PreloadCacheEntry` records `requestedLevel`; a downgrade retry never replays an already-failed higher-tier URL. TTL = 5 min. Gapless preload window = **60 s** (`PRELOAD_THRESHOLD_MS`), despite some older comments saying 20 s.
- **待播槽位不变量（v1.5.2 串台修复）**：ExoPlayer 播放列表里**当前项之后至多一首预载项**，
  且 `PlaybackService.pendingNext*` 必须与它一一对应（判定抽在 `player/PreloadSlot.kt`，JVM 单测覆盖）。
  同一首下一曲会被 MainScreen 预载两次（切歌瞬间 + 最后 60s），第二次必须幂等忽略、换歌必须替换
  （`removePendingItems`），绝不重复 `addMediaItem`；预载项自带 `mediaId=song:<id>` 与 `MediaMetadata`，
  `onMediaItemTransition` 只在「起播项 == 槽位项」时才写元数据。破坏它 = 用户报告的
  「UI/歌词/媒体卡片显示下一首、耳朵还是上一首」串台（根因链与真机 logcat 见 TASK.md 第 8 节）。
- A song that yields no playable URL at any tier is skipped — **never** fall back to `.../song/media/outer/url?id=X.mp3` (it 302→404s HTML and buffers forever).

Quality preferences (indices into the 8-level ladder) live in `ncrust_settings` (migrated once by `QualityLadder.migrate` at app start):

| Index | Display (zh) | API level |
|---|---|---|
| 0 | 压缩 | `standard` |
| 1 | 较好 | `higher` |
| 2 | 更好 | `exhigh` |
| 3 | 无损 | `lossless` |
| 4 | 高解析 | `hires` |
| 5 | 高清环绕声 | `jyeffect` |
| 6 | 超清母带 | `jymaster` |
| 7 | 杜比全景声 | `dolby` |

Defaults: Wi-Fi = 3 (lossless), Mobile = 1 (higher). The selected label shows as a badge in `FullPlayerControls`.

#### Playback reporting (webLog)

`PlayReporter` replays the official web player's `webLog` so local listening feeds ncm recommendations. On natural end **or** progress ≥ 80%, it POSTs a form `logs=JSON([{action:"play", json:{type:"song", wifi:0|1, download:0, id, time, end:"playend", mainsite:"1", mainsiteWeb:"1"}}])` to `https://clientlogusf.music.163.com/api/feedback/weblog?csrf_token=<csrf>`. **No eapi/weapi encryption.** Fire-and-forget on a `ncrust-weblog` thread, deduped per song, never blocks playback.

### Queue Management

All queue operations live in `MainScreen` (not `PlayerViewModel`): `replaceQueueAndPlay`, `playSongItem`, `insertNext`, `appendToQueue`, `insertAllNext`, `appendAllToQueue`, `removeFromQueue`, `moveInQueue`, `playFromQueue`, `playNext`, `playPrevious`, `generateShuffledIndices`, `launchInfinity`, `startFm`.

**Critical invariant:** `playbackQueue[currentQueueIndex]` must always equal the currently playing song. When deduping a queue mutation, record the current song's id first, filter, then re-locate the index — a naive `.filter` can drop an earlier duplicate and point `currentQueueIndex` at the wrong item. Every mutation calls `PlaybackStateManager.saveQueue(...)`, and any mutation in SHUFFLE mode regenerates the shuffled-index list.

5 playback modes (`QueueModes`): `CYCLE` (0), `SINGLE` (1), `SHUFFLE` (2), `LINE` (3, stops at tail), `INFINITY` (4, FM radio / similar-song continuation). `launchInfinity()` appends at the tail from `getPersonalFm` (FM mode) or `getSimilarSongs(seed)`, falling back to `getDailyRecommendSongs`, deduped and in-flight guarded.

### Content Cache & Load Transitions

`ContentCache` is an in-memory snapshot (not persistence — that is `LibraryManager`). It holds the three home lists + `homeWarmedUpAt` (15 s freshness window), LRU-32 caches for album / playlist-songs / artist-albums, and the user profile. It is cleared on `onTrimMemory` / `onLowMemory` / manual cache clear.

Load pattern for any network screen:

1. Read `ContentCache.getX(id)` as initial state.
2. If present, render immediately; do not show a loader.
3. Regardless, kick off a background refresh and write back on success.
4. Let `LazyColumn` diff by key.
5. Wrap loading↔content in `Crossfade(animationSpec = SokuouTweens.CoverFade)` — never a hard `if (isLoading) return Loader()`.

`DetailScaffold` takes `hasCachedContent: Boolean`; when `true` it suppresses the loader even if `isLoading`. Detail screens pass `hasCachedContent = (data != null)`.

### State Management

No DI framework — singletons as service locators: `RetrofitClient`, `CookieManager`, `LibraryManager`, `PlaybackStateManager`, `ContentCache`, `ThemeManager`, `LanguageManager` (via `LocalStrings`). No Room; all persistence is SharedPreferences + Gson.

SharedPreferences files:

| File | Owner(s) | Contents |
|---|---|---|
| `ncrust_prefs` | `CookieManager` | `user_cookie` |
| `ncrust_settings` | `ThemeManager`, `LanguageManager`, `PlayerViewModel`, `UserScreen`, `MainActivity` | theme index/mode, language, quality, gapless, lyrics translation, `lyrics_word_by_word`, `lyrics_word_animation` / `lyrics_font_scale` / `lyrics_sweep_quality` (v1.5.1/2), `lyrics_ttml_enabled` / `lyrics_ttml_first` (v1.9.0), `lyrics_romanization` (v1.9.3), `battery_prompt_done`, **`offline_cache_mb`（v1.6.0 · D1 写入 / v2.0.0 · T3 起设置页可改：离线音频缓存上限 MB，合法 64..8192，默认 512，非法值回落默认）** |
| `ncrust_library` | `LibraryManager` | `saved_songs`, `saved_albums`, `liked_ids` |
| `ncrust_playback_state` | `PlaybackStateManager` | last song + `queue` / `queue_index` |
| `ncrust_lyrics_cache` | `LyricsCache` | `entries` (≤ 200；LRC/译文/逐字与 TTML 共用同一张表，TTML 另有 7 天 TTL) |
| `search_history` | `SearchHistoryManager` | `songs`, `albums`, `artists` (≤ 10 each, 14-day TTL) |
| **`ncrust_offline`** | `OfflineUrlStore` / `OfflineLibrary` | **v1.6.0 · D1 / v2.0.0 · T3**：`urls`（key→最后一次成功播放的 URL，≤300 LRU）、`tracks`（离线曲目索引：songId/name/artist/albumPicUrl/durationMs/level/cacheKey/completedAt，≤300 LRU）。两条清单**必须一起删**（删曲目必删 URL 死条目） |

#### 歌词缓存字段迁移策略（v1.9.3 固化：**加字段 = 加迁移逻辑 = 加单测**）

`ncrust_lyrics_cache` 是一张**跨版本存活**的表：LRC 条目只有 200 条的 LRU 上限、**没有 TTL**，
所以「新版本给 `CachedLyrics` 加一个字段」在真机上的表现就是「老条目里那个字段是 null」。
两次实测踩坑（都不是理论风险，是真机上复现过的）：

| 版本 | 新字段 | 踩到的坑 |
|---|---|---|
| v1.9.0 | `ttml: String?` + `ttmlAt: Long = 0` | Gson 走 Unsafe 反序列化、不调用构造函数 ⇒ 老条目缺 key 时字段是 null。必须**可空 + 有默认值**，否则读的地方 NPE 或把「没有 TTML」误判成新鲜缓存 |
| v1.9.2 | `romalrc: String?` | 老条目该字段为 null，语义是「**字段缺失**」而不是「这首歌没有音译」。PCL110 实测 64 条里 **63 条**是老条目，`1959528822` 因此只拿到 TTML 的 16 行音译、ncm 那 41 行 romalrc 明明在服务端却用不上。修法 `LyricsCache.needsRomalrcRefetch(entry)`：缺失按 miss 重取一次（一次性、自愈），网络失败回落老缓存（`degraded()`） |

**通用规则**（下一个加字段的人照着做）：

1. 新字段一律**可空 + 默认值**（Gson 走 Unsafe，不调用构造函数）；
2. 判「老条目」只看**字段缺失**（null），**不能**用「字段为空串」—— 空串往往是「服务端确实没有这份数据」
   的权威结论，误判会让每次都重取；
3. 缺字段的条目按 **miss 处理、重取一次**，补完即恢复缓存命中；重取失败必须能回落到老缓存；
4. 迁移判定抽成**纯函数**并加单测（`LyricsCacheModelTest` 已有 16 个用例覆盖「缺失 vs 空」的两义性）；
5. **能不加字段就不加**：v1.9.3 的音译显示是纯显示偏好（存 `ncrust_settings` 的 `lyrics_romanization`，
   不进歌词缓存），因此本版没有任何新缓存字段、没有新迁移逻辑 —— 这是这条规则的正向用法。

### Auth & Login

`CookieManager` stores the raw cookie in `ncrust_prefs`. Login paths:

1. **WebView login** (phone default, and "通用登录" on tablet) — `MainActivity` shows a WebView at `https://music.163.com/#/login`, reads `MUSIC_U=` on `onPageFinished`, saves + `RetrofitClient.updateCookie`, then triggers `LibraryManager.refreshFromCloud`.
2. **QR login** (wide screens `>= 600dp`) — `QrLoginDialog` requests a `unikey` via weapi, renders the QR with ZXing, starts a `QrPairServer`, and polls `client/login` every 2 s (max 150 ticks): 800 expired / 802 scanned / 803 success.
3. **Phone-scanning-tablet handoff** — `QrAuthorizeScreen` (CameraX + ZXing) scans the tablet's QR, extracts the unikey, and sends the phone's cookie over the LAN. Protocol in `QrPair`: UDP broadcast on port `47821` (prefix `NCRUSTPAIR1?` / `NCRUSTPAIR1!`) to discover the tablet, then a TCP connection carrying a length-prefixed AES-128-CBC payload (key = `SHA-256(unikey)[0:16]`, random IV prepended). `cleartextTraffic` is enabled for this.

**Manual cookie paste no longer exists** — it was removed in `a16e381`. Do not reintroduce it in docs.

### i18n System

Runtime-based (not Android resource strings), under `ui/i18n/`:

- `Strings.kt` — one data class with every UI string (≈165 properties; some are lambdas such as `trackCount: (Int) -> String`).
- One file per locale defining a top-level `Strings(...)` value.
- `LanguageManager.kt` — `languagePresets`, `LocalStrings` (default `zhCN`), SharedPreferences helpers.

**8 locales:** `zh-CN` 简体中文, `zh-TW` 繁體中文, `en-US` English, `ja-JP` 日本語, `ja-MY` 万葉仮名, `ko-KP` 조선어, `de-DE` Deutsch, `ru-RU` Русский. (The legacy `en-UK` code maps to `en`.)

To add a locale: create `xx_XX.kt` with a `Strings(...)` and add a `LanguagePreset` to `languagePresets`. To add a string: add the property to `Strings.kt` and to **every** locale file. Composables read `val s = LocalStrings.current` — never hardcode UI strings.

### Theming & Responsive Layout

- **6 theme colors** (`themeColorPresets`): 云杉 `#1DB954`, 钴蓝 `#3B82F6`, 绯红 `#EF4444`, 琥珀 `#F59E0B`, 堇紫 `#8B5CF6`, 素白 `#FFFFFF`.
- **3 theme modes** (`ThemeMode`): `SYSTEM`, `DARK`, `LIGHT`. Dark base is OLED black `#000000`; light base is warm off-white `#F6F2E9`.
- `NcrustTheme(primaryColor, isDark)` derives `NcrustColors`; `toMetroColors(isDark)` bridges them into Kanesumi `MetroColors` so `Ncrust*` and `Metro*` components stay visually consistent.
- `ResponsiveContent`: `< 600dp` → centred `widthIn(max = 360.dp)`; `>= 600dp` with `maxWidth` → centred at that width; without → fills with 24dp horizontal padding.
- Wide screens (`windowWidthDp >= 600`) replace the bottom nav with a 200dp `MetroSidebar`; the content column gets `padding(start = 200.dp)`.
- `BottomOverlayInsetDp` = **144dp** narrow (80 nav + 56 mini + 8 buffer) / **64dp** wide (56 mini + 8). All scrollable content must use it as `contentPadding`; **never** append a manual end-of-list `Spacer`.

## Compose 触摸陷阱（实测汇编 · v1.5.0 整理）

> 这一节是本 fork 踩过的**全部**触摸/命中测试坑，每条都给：症状 → 根因 → 修法 → 触发版本 → 相关文件。
> 「触发版本」是 `git log -S` 反查出来的**实际引入/修复版本**，不是回忆；与口头描述不符的地方以本节为准。
> 背景：本应用的播放器卡片是 `fillMaxSize` + `graphicsLayer` 平移到屏幕底部的独立图层，
> 详情页/导航栏都在它下面 —— 这是全部坑的共同温床。

### 1. `alpha = 0` 不会退出命中测试

| | |
|---|---|
| **症状** | 肉眼什么都看不见的地方照样吃事件：折叠态在底部导航栏附近乱按会点到**不可见的**歌词行/队列行；详情页底部按钮「点了没反应」 |
| **根因** | Compose 的命中测试只看布局与 `pointerInput`，**不看 `alpha`**。`graphicsLayer { alpha = 0f }` 只影响绘制，节点仍然是可命中的；`Modifier.alpha()` 同理 |
| **修法** | 隐藏必须走「不挂载」或「收窄命中区」，不能只改透明度。播放器里 mini bar 是**故意**保留 alpha 渐隐的（它在 Composition 中常驻、透明度只在绘制阶段控制，动画期零重组），代价是它始终可命中 —— 所以它必须永远可见可用，不能当「隐藏层」用 |
| **触发版本** | 自播放器卡片分层架构（v1.0.x 上游）起存在；v1.3.0（`5d93ae3`）修掉展开态子树的这一份 |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（mini bar 叠加层、`graphicsLayer { alpha = ... }`） |

### 2. `fillMaxSize` + `translationY` 形成「屏幕坐标死带」

| | |
|---|---|
| **症状** | 有歌在播时，详情页 **y ≳ collapsedOffsetY** 一带的交互元素完全收不到事件（S6/G9209 实测 y≈1938 的歌单详情 ⋮ 点不动；API 24 模拟器同一坐标上滑也不生效） |
| **根因** | 卡片是 `Box(Modifier.fillMaxSize())`，用 `graphicsLayer { translationY = collapsedOffsetY }` 整体下移。**`graphicsLayer` 的平移同时作用于绘制与命中测试** —— 卡片的逻辑布局仍在 (0,0)-(W,H)，但命中区跟着视觉一起被推到屏幕下半部，整块压在详情页之上且先于下层拿到事件 |
| **修法** | ① 折叠态不再挂载展开态子树；② 折叠态给根 Box 加 `statusBar` 高的 top padding 收窄两个 `pointerInput` 的命中区，再用等量 `offset(y = -inset)` 把子节点放回原位（视觉与子节点布局零变化）。见第 6 条 |
| **触发版本** | 随分层架构引入；v1.3.0（`5d93ae3`）修复。S6 实测 `collapsedOffsetY = 1920px`、mini bar 内容上沿 `2016px`，死带正好是中间 24dp |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)、[PlayerCardOverlay.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCardOverlay.kt) |

### 3. `graphicsLayer` 平移参与命中测试，且 `onGloballyPositioned` 在动画期间滞后

| | |
|---|---|
| **症状** | 展开态下关掉歌词后，**底部播放控制栏与底部导航栏一起失效**（歌词开着反而正常）；动画进行中判断「点是否落在卡片可见区」会得到旧值 |
| **根因** | 两个独立问题叠在一起：① 根节点在 `progress > 0.99f` 时吞掉所有事件，唯一的豁免区 `isOverPanel` 却挂在 `isPanelInteractive` 上、进而挂在 `lyricsEnabled` 上 —— 关歌词 ⇒ 豁免区整个消失 ⇒ 全吞；② `graphicsLayer` 的平移**不重新触发布局**，`onGloballyPositioned` 写下的坐标在动画期间是滞后的，直接读它算可见区会在动画中判错 |
| **修法** | 把「豁免区」按**语义**拆成两个函数，绝不混用：`isOverPanel`（面板语义，只给拖拽检测器用，依赖 `isPanelInteractive` 是对的）与 `isOverCardVisibleArea`（几何语义，只给「展开态吞事件」的消费者用）。几何计算用 `cardRootOrigin.y * (1f - progress.value)` **插值**，不直接读 `cardRootOrigin.y` |
| **触发版本** | 自展开态吞事件引入；v1.1.1（`709c20b`）修复 |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（`isOverPanel` / `isOverCardVisibleArea` / `isOverCollapsibleControls` 三个语义注释块） |

### 4. 隐藏层用「条件挂载」而不是 `alpha`（B-1 的取舍）

| | |
|---|---|
| **症状** | 同第 1 条。折叠态在屏幕下半部乱按会点到看不见的歌词行/队列行 |
| **根因** | 折叠态只把展开态子树用 `alpha≈0` 隐藏，子树仍然参与命中测试 |
| **修法** | `progress < 0.01f` 时**整棵展开态子树不挂载**（顶部标题栏 / 歌词·队列双面板 / 底部播放控件 / 大封面信息），mini bar 永远挂载。**代价（有意接受）**：首次展开要付一次 composition + layout + draw，压不进单帧时会看到一次轻微掉帧（冷启动由 Splash + AppWarmup 兜底） |
| **触发版本** | v1.3.0（`5d93ae3`）。**任务书把这条记在 v1.1.1，实际是 v1.3.0** |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)、[PlayerCardOverlay.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCardOverlay.kt) |

### 5. 折叠态「隐藏子树的命中区」比可视区大

| | |
|---|---|
| **症状** | 明明已经没有可视子节点的那一段（死带），事件还是被卡片吃掉 |
| **根因** | 那一段里**一个子节点都没有** —— mini bar 与 Column 的内容都被 `statusBarsPadding()` 下推到 2016，而吃事件的是卡片**根 Box 自己**的那两个 `pointerInput`（上拉手势 / 展开态吞事件），命中区就是整个 `fillMaxSize` 根 Box。所以「不挂载子树」单独用**修不掉** |
| **修法** | 见第 6 条（命中区让位）。判据是「有没有子节点」不够，必须看「有没有 `pointerInput` 挂在被平移过的根节点上」 |
| **触发版本** | 随分层架构引入；v1.3.0（`5d93ae3`）修复 |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（根 Box 的 `collapsedHitGate` 与两个 `pointerInput`） |

### 6. `padding` + 等量 `offset` 收窄命中区（视觉零变化）

| | |
|---|---|
| **症状** | 需要一个「只改命中区、不改任何视觉与布局」的手段 |
| **根因** | —（这是修法本身） |
| **修法** | 折叠态给根 Box 加 `Modifier.padding(top = hitGateInsetDp)`（只收窄挂在**同一节点**上的 `pointerInput` 命中区），再用 `Modifier.offset(y = -hitGateInsetDp)` 把子节点放回原位。子节点的最终位置、卡片背景的 `graphicsLayer` 平移都不受影响 ⇒ **视觉与子节点布局零变化**。展开态与动画中段不加 padding，「整屏吞事件」的行为原样保留 |
| **触发版本** | v1.3.0（`5d93ae3`）。API 24 模拟器实测：同一坐标 y=1188 同长度上滑，修复前把整卡拉起、修复后事件还给下层；mini bar 上滑展开与点按展开均无回归 |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（`collapsedHitGate` / `hitGateInsetDp`） |

### 7. 小控件要把触摸区扩到 ≥48×24dp，且必须能被无障碍服务看见

| | |
|---|---|
| **症状** | 全屏播放器底部的控制栏把手（视觉 40×3dp 的小横条）用户「找不到 / 划不动」；TalkBack 用户把控制栏收起后**再也拿不回来** |
| **根因** | 视觉元素做得再小，命中区也不能跟着变小；此外只画一个 `Box` 而不加 `clickable`/语义，等于对无障碍服务不存在 |
| **修法** | 视觉保持 40×3dp，**命中区只增不减**：外层全宽 × 24dp 拖拽带（保持既有手感，宽度故意不缩到 48dp —— 1440px 宽的 S6 上 48dp 只有 192px，v1.4.2 的用户反馈正是「划下去就划不上来」）+ 内层显式 48×24dp 点按命中盒；并加 `semantics { contentDescription = ... }`。触摸契约写进注释，防止后续被「优化」掉 |
| **触发版本** | 把手本身 v1.4.2（`094675e`）加入，v1.4.2（`b513359`）修「恢复手势被整卡拖拽抢走」；命中区显式化 + 无障碍语义 v1.5.0 · C2（`fd15b89`） |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（控制栏把手 Box） |

### 8. 手势吸附阈值必须按「可拖动行程」核对，不能凭比例拍脑袋（v1.7.0 · P0）

| | |
|---|---|
| **症状** | 收起态 mini bar **向上拖不出播放器**（松手弹回），用户描述为「只能拖下去不能拖上来」「手势像是被歌词界面吃掉了」；控制栏把手「上拖收起」时灵时不灵 |
| **根因** | 命中测试完全正常，问题在**吸附判定**：卡片要走完 `totalDragDistancePx = contentHeightPx × 0.85`（PCL110 = 2380px）才让 progress 从 0 到 1，而旧判据要求 `progress >= 0.5` 才展开 ⇒ 手指要跨 **1190px ≈ 340dp**。真机实测 150/250/400px 上滑只到 progress 0.045/0.087/0.147，全部被判成弹回。另外检测器挂在被 `graphicsLayer` 平移的节点里，**拖动中局部坐标随卡片一起移动**，用 `position` 差分算速度会系统性低估 |
| **修法** | ① 阈值改成方向敏感的**小比例**（上滑 8% / 下滑 8% / 控制栏收起 12%，恢复保持 5%）；② 补**甩动**判据（≥600px/s 按方向直接提交）；③ 速度从 **progress 域**反推（`progressDelta × 行程 ÷ 耗时`）；④ 越过触摸 slop 的那段位移补进 progress（注入/稀疏事件下否则「划了但没动」）。判定抽成 [PlayerDragSnap.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerDragSnap.kt) + JVM 单测 |
| **触发版本** | 自播放器分层架构起；v1.7.0（`0a167ec`）修复 |
| **相关文件** | [PlayerCard.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（整卡拖拽 / `controlsCollapseDrag`）、[PlayerDragSnap.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerDragSnap.kt) |

### 一句话总结（给改播放器的自己）

`graphicsLayer` 的 `translationY`/`scale`/`alpha` 里，**只有 `alpha` 不影响命中测试**；
`translationY` 会把命中区一起搬走，`fillMaxSize` 的根节点即使一个子节点都没有也照样吃事件。
凡是「看不见的地方还能点」或「看得见的地方点不到」，先问三件事：
**这个节点挂载了吗？它的 `pointerInput` 在哪一层？它的命中区被 `graphicsLayer` 搬到哪去了？**
凡是「拖不动 / 拖一半弹回 / 时灵时不灵」，再补问两件事：
**这个手势的行程阈值换算成 dp 是多少？检测器所在节点的坐标系在拖动过程中会不会跟着动？**

## v1.4.0 新增（本 fork）

| 项 | 说明 |
|---|---|
| 底部控制栏可收起 | 窄屏全屏播放器下向上拖控制栏 → 控制栏滑出、面板长高到全屏；右下角悬浮播放键向下拖恢复。controlsCollapse(Animatable) + graphicsLayer 平移 + Modifier.collapsibleHeight（layout 阶段读 Animatable，零重组）；控制栏区域的整卡拖拽由 isOverCollapsibleControls 让路。宽屏不启用。 |
| 暂停态 seek 立即生效 | 进度 ticker 只在 isPlaying 时广播，暂停态 seek 后 UI 收不到新位置。修法：PlaybackService 两条 seek 路径后 publishProgressNow() + PlayerViewModel.seekTo 乐观更新；showBuffering 不再把 isSeeking 当缓冲（点进度条不再有脉冲动画）。 |
| 播放全部先播后补 | 首页/库页的 ▶ 直接播放：命中 ContentCache / 本地收藏单曲立即开播，未命中先 Toast 再拉取；收藏单曲剩余详情后台补齐后追加队尾。移除全局 pendingPlayAllSongs 二次确认（详情页各自的 PlayAllDialog 保留）。 |
| 音乐人推荐卡片 | 首页推荐流按「收藏艺人 ∩ 风格锚点」本地判定插入一张艺人卡（点击进 ArtistDetailScreen）。配置在 ncrust_settings：artist_reco_enabled / artist_reco_target_id / artist_reco_anchor_ids(CSV)，默认全空 → 其他用户不显示也不发请求。自动锚点：目标艺人热门曲 → simiSong → 同风格艺人（7 天 TTL）。**v1.5.0 起**自动锚点按频次排序 + 截断到 20 个 + 空结果不冲缓存，见下节。 |
| 相似艺人端点纠正 | 任务里写的 /eapi/simi/artist 不存在（404）；真实端点是 /eapi/discovery/simiArtist，参数名 artistid，匿名 301、需登录，返回 artists[]（≤20，平均约 300ms）。B0 实测目标艺人 122618229 自己的相似列表为空、在 top20 收藏艺人的相似列表里 0 次命中 → 原方案不可行，改走锚点降级方案。 |
| 艺人端点可用性 | GET /api/artist/{id} 与 GET /api/artist/albums/{id} 可用；/eapi/v1/artist/detail、/eapi/artist/albums（PlaylistApi 里那两个旧函数）已 400 失效（当前无人调用，ArtistDetailScreen 走 Retrofit 的 REST 路径）。 |

## v1.5.0 新增（本 fork）

### 音乐人推荐：自动锚点推导的实测与收敛（任务 A）

**S6 真机实测（SM-G9209 / Android 7.0 / 登录态，2026-09-22）**——本节所有结论都来自这次实测，不是推断。
方法：root 写入 `artist_reco_enabled=true` + `artist_reco_target_id=122618229`、清空
`artist_reco_anchor_ids`，冷启一次后读回 `ncrust_settings.xml`。

| 项 | 实测值 |
|---|---|
| 目标艺人 | `122618229` = **SKULLCHAIN颅链**（musicSize 13，热门曲 Home تۋعان جەر / CALL THE FATALITY / JAR JAR PHONK） |
| 推导结果（9 个，落盘顺序） | `60952064,53432819,1132066,12003027,29878,93805,99989,13518632,99999` |
| 反解艺人名 | PFJ_5 / 333xd / Fayzz / 奈热乐队 / Boris Brejcha / HIM / Sum 41 / Charix / System of a Down |
| 推导耗时 | 冷启后 12 s 内完成并落盘（`artist_reco_auto_anchor_at` 刷新） |
| 目标艺人是否被误收进锚点 | 否（`aid != target` 生效） |
| 卡片是否触发 | **是** —— 该账号收藏单曲里有 `PFJ_5(60952064)`，`shouldShow` 命中，首页「音乐人推荐」渲染出 **SKULLCHAIN颅链**（截图复核） |

结论：**推导合理**（全部是真实艺人，风格上扎堆在摇滚/金属/电子，且确实在真实收藏上命中），
因此保留自动推导并做三点收敛，而不是降级成「默认关闭自动推导、只留手动锚点」：

1. **去重**：以艺人 id 为 key 收进 `LinkedHashMap`，同一艺人无论命中多少次只留一个；
2. **排序 + 截断**：按「在几首种子曲的相似列表里出现过」降序（`sortedByDescending` 是**稳定**排序，
   同频次保持首次出现顺序 → 同一份服务端数据每次结果完全一致），再截断到 `AUTO_MAX_ANCHORS = 20`。
   原实现没有上限，3 种子曲 × 20 相似曲最多能收 60 个；锚点集就是命中判定集，越大越容易误命中。
   S6 实测只有 9 个，**低于上限 → 对现有账号是零行为变化**；
3. **缓存**：7 天 TTL 不变，另加两点 —— ① `Mutex` 单飞，快速连续切页只推导一次（此前 TTL 写回前
   每个并发调用都会各跑一遍网络）；② **推导结果为空时保留旧缓存**，不再把好数据冲成空、让卡片
   静默消失到 TTL 结束。

顺带补了可观测性：推导成功打 `Log.i(ArtistReco)`（此前只有失败才 `Log.e`，线上无法判断卡片为何不出现）。
纯逻辑抽成 `ArtistReco.rankAnchors(hits, target, max)`，由 JVM 单测
[ArtistRecoTest.kt](app/src/test/java/com/takahashirinta/ncrust/reco/ArtistRecoTest.kt) 覆盖去重/排序/稳定性/截断/过滤。

**S6 上的复现方式**（S6 已 root，Magisk）：改 prefs 前先 `su -c cp` 备份 `ncrust_settings.xml`，
`am force-stop` 之后再写入，冷启后读回；登录态（`ncrust_prefs.xml` 的 `user_cookie`）全程不动。

### 歌词增强：逐字（yrc）+ 翻译（任务 B）

#### 实测结论（2026-09，真实响应，非文档推断）

| 项 | 结论 |
|---|---|
| 端点 | `POST /api/song/lyric`（REST，应用现有路径）。**`/eapi/song/lyric` 也能用，但必须做 `/eapi/`→`/api/` 的签名路径重写**；`/eapi/song/lyric/v1` 会混入富文本 JSON 格式，不要用 |
| 逐字字段 | `yrc`，格式 `[行起始ms,行时长ms](词起始ms,词时长ms,0)词文本...`。**词起始是绝对毫秒**（3161/3162 行首段 start == 行首；段起始单调不减 0 违例）；括号里第三个数字 **25080/25080 恒为 0**，无语义。⚠️ `行首+行时长 == 末段 start+末段 dur` 只有 **2931/3162 = 92.7%** 成立，其余是行尾留白（+30~480ms）与 ±1ms 舍入 —— 所以**行尾要按末段 `start+dur` 算**，别把 `行首+行时长` 当行尾 |
| `yrc.version` | **是这首歌词的修订号，不是格式版本号**：实测取值 2~113 无规律，不要拿它做格式判断 |
| `ytlrc` 对齐性 | 它每行时间戳**精确落在某个 yrc 行首上**（20 首合计 1060/1060），而 `tlyric` 几乎从不对齐（0–3/33–75）⇒ 两者是两份独立资产，**不可互换**；本仓库用 `tlyric`（因为展示行来自 lrc，不是 yrc） |
| 参数语义 | 是**开关**：传 `lv`→lrc、`kv`→klyric、`tv`→tlyric、`rv`→romalrc、**`yv`→yrc + ytlrc + yromalrc 三个一起**。单独传 `ytv`/`yrv` 什么都不返回；不传 `yv` 时响应里**连 `yrc` 这个 key 都不存在** |
| `ytlrc` 不是逐字 | 它是**行级** LRC（`[MM:SS.mmm]整行译文`），实测 23 首有 ytlrc 的歌 100% 命中行级文法、0 行命中 yrc 文法。所以唯一的逐字数据源就是 `yrc` |
| 匿名可用 | 完全不带 Cookie 与登录态返回**逐字节相同**（code 200）。歌词接口不需要登录 |
| 覆盖率 | yrc 与发行年份**不严格相关**：2024-2025 首发新歌（周深《小美满》、Billie Eilish《CHIHIRO》、Taylor Swift《Fortnight》等）大多**没有** yrc；而《屋顶》《修炼爱情》《演员》《浮夸》《孤勇者》《后来的我们》等有 |
| 「确无歌词」 | code 仍是 200，靠 `uncollected:true` / `pureMusic:true` 与 `lrc.lyric="[00:00.00]暂无歌词"` 判别，此时响应里没有 yrc/tlyric 等 key |

#### 对齐：**不能按时间戳匹配**（踩坑记录）

直觉做法「按时间戳把 yrc 行挂到 lrc 行上」**实测不成立**：《屋顶》55 行里只有 3 行时间戳完全相同，其余相差 20–310 ms（yrc 是逐字轨，本来就更精确）。

但实测同时确认了更强的事实 —— **yrc 与 lrc 的「行」是一一对应的**：

| 歌曲 | lrc 行数 | yrc 行数 | 按文本命中 | 时间差 |
|---|---|---|---|---|
| 屋顶 `5257138` | 55 | 55 | 55/55 | +20…+310 ms |
| 遇见 `287035` | 28 | 28 | 11/28（其余只差空格） | −269…+530 ms |

所以 [YrcParser](app/src/main/java/com/takahashirinta/ncrust/lyric/YrcParser.kt) 按 **行序**对齐：行数必须相等 + 时间漂移抽样达标（`MAX_LINE_DRIFT_MS=2000`、`MIN_ALIGN_RATIO=0.8`），否则整首放弃逐字。

**S6 真机踩到的第二个坑（v1.5.0 修复）**：yrc 把空格**粘在前一段尾部**（`(0,1000,0) 作词 `、
`(1000,1000,0): `），LRC 那份是 trim 过的（`作词 : 易家扬`）。定位时必须用 `w.text.trim()`，
否则这些词必然 indexOf 失败 → 整行放弃逐字。实测《修炼爱情》因此只挂上 **47/70** 行，
改成 trim 后同一首歌 **70/70**。纯空白段直接跳过（它没有自己的字符区间，高亮由前一段覆盖）。

文本也要重新映射：yrc 拼出来的是「词的原始拼接」，LRC 那份是「补回空格的美化版」
（`听见冬天的离开` vs `听见 冬天的离开`）。**展示文本仍用 LRC 的**（与 v1.4.1 完全一致），词的字符区间用
「游标 + indexOf」映射到 LRC 文本上；任何一个词定位失败就放弃该行的逐字 —— 宁可没有逐字，也不给错位高亮。

#### 渲染：`drawWithContent` 两层（零重组）

`MetroLyricsPanel`（Kanesumi）只接受纯文本、无法从外部注入单行渲染，所以逐字高亮需要面板本身参与。
本仓库**把面板整体搬了进来**：`ui/player/NcrustLyricsPanel.kt`（派生自 Kanesumi `MetroLyricsPanel`，Apache-2.0）。
为什么是复制而不是改 Kanesumi：Kanesumi 走组合构建、不在本仓库版本控制内，改它会让 v1.5.0 的发布产物**不可复现**
（`git clone Ncrust` 不再足以构建）。除单行渲染外，滚动 / 定位 / 缩放 / 渐入 / a11y 行为与原版逐行一致。

单行渲染 `LyricLineBody`：底色用 `BasicText` 正常画一遍（未唱部分弱化到 45% α），再在 `drawWithContent` 里
**在 draw 阶段**读播放位置，用 `TextLayoutResult.getPathForRange(0, 已唱字符数)` 取出已唱字符的版面路径，
`clipPath` 之后把同一份 layout 用高亮色重画一遍。三个好处：

1. **零重组** —— 逐字推进只让这一行重绘，不触发任何重组，与面板既有的 `graphicsLayer` 缩放动画、
   以及播放器整体的 GPU 零重组原则一致；
2. **不自己排版** —— 换行 / 断字 / CJK 避头尾全部交给 Compose，逐字高亮天然跟着版面走；
3. **一次画完** —— `getPathForRange` 把跨行的一段字符合成一条 Path，开销不随词数增长。

只有**当前行**做逐字高亮：已唱完的行会切成 `pastLineColor`，若在那些行上再叠高亮色会把整行重新点亮成
primary，破坏过去行的弱化。

#### 与 v1.4.1 的 2Hz 采样 / 漂移外推如何共存（B3）

`LyricsView` 的「按需唤醒」循环（v1.4.1 引入，用于跨行精确对齐 + 暂停态 seek 立即同步）原本只按
**行时间戳**唤醒。逐字高亮要求精度到「词」，但**不能改成逐帧轮询** —— 现在把唤醒表从「行边界」细化成
「行边界 ∪ 词边界」（`TreeSet` 去重升序），仍然是按需唤醒：静态时零状态写入、零帧调度，只在真正跨行/跨词
的那一刻动一次（词密度约每秒 3–8 个，远低于 60 fps）。2Hz 采样到达时外推锚点被重置回真实值，
所以细粒度外推不会累积误差。**v1.4.1 的漂移对齐逻辑一行未动。**

#### 开关与降级

- 设置项 `lyrics_word_by_word`（默认**开**），8 语言文案 `lyricsWordByWordLabel` 已补齐；
- 关掉、或该曲没有 yrc、或某一行对齐失败 → 行内渲染退回与 v1.4.1 **逐字节一致**的整行 `MetroText`；
- `YrcParser.attachWords` **不增删任何一行、不改任何一行的文本与时间戳**，这是「关掉开关等于没这功能」的保证；
- `LyricsCache.CachedLyrics` 新增 `yrc`，声明为可空并在读取处 `orEmpty()` —— 老缓存（无该字段）命中时
  退化成「没有逐字数据」，不会因 Gson 走 Unsafe 反序列化而 NPE。

单测 [YrcParserTest.kt](app/src/test/java/com/takahashirinta/ncrust/lyric/YrcParserTest.kt) 用真实样本覆盖
绝对毫秒语义、信息行 trim、按行序对齐、少空格时的字符区间映射、定位失败放弃、行数不等放弃、乱码容错。
### 离线下载：实测调研与架构选型（任务 D，v1.5.0 只做 D1+D2）

> **结论先行：v1.5.0 不实现下载功能，只交付调研与选型。** 原因见文末「为什么不在 v1.5.0 落地」。
> 本节所有数据都是 2026-09 真实登录态实测 + 逐行源码审计，不是文档推断，可直接作为 v1.5.1 的施工依据。

#### D1-1 现有下载能力：**零**

| 检索 | 命中 |
|---|---|
| `下载` / `离线` / `(?i)offline` | **0** |
| `(?i)download` | **1** —— [PlayReporter.kt](app/src/main/java/com/takahashirinta/ncrust/player/PlayReporter.kt) 的 webLog 上报字段 `.put("download", 0)`，与下载无关 |

**没有磁盘音频缓存（已用代码证实，不是猜）**：`PlaybackService.kt` 全文 918 行的 media3 import 里没有
`androidx.media3.datasource.cache.*`（无 `Cache`/`SimpleCache`/`CacheDataSource`）、也没有 `androidx.media3.exoplayer.offline.*`；
ExoPlayer 构造时只设 `DefaultRenderersFactory`（FFmpeg）/`DefaultExtractorsFactory`/`setLoadControl`/`setWakeMode`，**没挂任何缓存数据源**。
全仓库 grep `SimpleCache|CacheDataSource|DownloadManager|DownloadRequest|androidx.work|androidx.room` → **0 命中**。

真正的磁盘占用只有三处，都不含音频：封面图磁盘缓存（`cacheDir/image_cache`，100 MB）、6 个 SharedPreferences、自定义背景图（`filesDir/`）。

→ **在线播放完全依赖每次现场取链的临时 URL，离线零可用性。**

#### D1-2 `/eapi/song/enhance/download/url/v1` 实测结构

⚠️ **先记一条会让全部 eapi 请求 404 的坑**：签名/明文里的路径必须是 `/api/...`，而 HTTP URL 是 `/eapi/...`
（[EapiCrypto.kt](app/src/main/java/com/takahashirinta/ncrust/network/crypto/EapiCrypto.kt) 的 `urlPath = parsedUrl.path.replace("/eapi/", "/api/")`）。
照抄「两处路径相同」的直觉写法会拿到 `{"code":404,"message":"接口未找到！"}`。

请求：`POST /eapi/song/enhance/download/url/v1`，body `{"id": <long>, "level": "<档位>"}`。

| 参数 | 必填 | 实测 |
|---|---|---|
| `id` | ✅ | **单值**。传 `ids`（复数数组串）→ `{"msg":"参数错误","code":400}` |
| `level` | ✅ | 缺 → 400；用 `br` 代替也 400 |
| `encodeType` | ❌ | **被完全忽略**：`mp3`/`flac`/`aac`/`mp4` 四种取值响应逐字节相同；响应里的 `encodeType` 恒为 `"mp3"`，**不能拿来判格式** |
| `header` | ❌ | 带上与不带响应逐字节相同 |

响应只有两个顶层 key：`{"code":..., "data":{...}}`。**`data` 是对象**（不同于 player 端点的数组），
且**恒为 36 个字段**（成功/`-105`/`-110` 都一样，失败时全部置 0/null，字段不消失）—— 解析器可以无条件按 36 字段读。

关键字段：`url`（**http 不是 https**）、`br`、`size`（实测与实际下载字节数一致）、`md5`（实测与实际内容一致）、
`code`、`type`（**权威容器字段**）、`level`（**实际授予档位**）、`time`、`fee`、`payed`、`freeTrialInfo`（**download 端点恒 null**）、
**`expi` = 1200**（URL 有效期 20 分钟）、`sr`、`gain`、`peak`、`freeTrialPrivilege`、`musicId`。

**两条硬结论**：

1. **超出该曲上限的档位被静默封顶**，不报错也不给降级标记 —— 响应 `level` 回落到实际授予档位。
   下载必须持久化 `data.level`（实际值），不能存用户请求的档位。`level=sky` 恒失败（`-110`），与既有结论一致。
2. **`download` 端点「要么给完整文件、要么什么都不给」**：未登录 + VIP 曲给 `data.code=-105` + `url=null`，
   **永不返回 45 秒试听片段**（`player/url/v1` 对同一首会给 45 s 试听）。这正是离线下载需要的语义 ——
   否则会存下一个 45 秒的坏文件。

与现有 `player/url/v1` 的差异：**字段集合完全相同（36/36，双向差集为空）**，差异只在请求/响应形状与权限语义 ——

| 维度 | `download/url/v1` | `player/url/v1` |
|---|---|---|
| 请求体 | `{"id": <long>}` 单值 | `{"ids": "[...]"}` 数组串，可批量 |
| `data` 形状 | **对象** | **数组** |
| 完全无 cookie | 顶层 `{"data":null,"code":301}`（一律拒绝） | 免费曲仍 200 + url |
| 有身份无 `MUSIC_U` + VIP 曲 | `data.code=-105`，`url=null`，**无试听** | 200 + 45 s 试听片段 |
| 无版权曲（`st=-200`） | `data.code=**-110**` | `data.code=**404**`（**码不同，别混用判定**） |

#### D1-3 断点续传：**完全可行，且 URL 轮换不影响续传**（实测）

| 实验 | 结果 |
|---|---|
| `Range: bytes=0-1023` / `bytes=1000000-1001023` / `bytes=-1024` / `bytes=0-` | **全部 206** + 正确 `Content-Range`；但**没有 `Accept-Ranges` 头**（不能靠探测它，直接发 Range） |
| `md5` / `size` 是否可信 | 全量下载 5,217,010 字节后比对：`size` 一致、`md5` 一致 ⇒ **服务端白送完整性校验** |
| 同 URL 分段拼接 | `cat part1 part2` 的 md5 与 `data.md5` 一致 |
| **跨 URL 续传**（下到一半换新 URL 继续） | 用 URL_A 下 `0-1999999`、URL_B 下 `2000000-`，拼接后 **size 与 md5 都一致** ⇒ **URL 过期只需重新取链，不用重下** |
| URL 是否稳定 | 每次都变（host 在 `m704`/`m804` 间轮换、路径时间戳每次不同）⇒ **绝不能把 URL 当持久标识** |
| URL 过期机制 | 签名在**路径**里（14 位时间戳恒 = 签发时刻+1500 s；篡改任一字节即 403）；**query 参数是装饰**（删掉/篡改仍 206） |
| CDN 是否自鉴权 | 是（不带 Cookie/Referer 直接 curl 也能下）；**https 同样可用**（建议升级，避免明文） |

→ 施工要点：持久化 `(songId, requestedLevel, actualLevel, type, br, size, md5)`，**不持久化 URL**；
每次开始/恢复都重新取一次链，再按已下载偏移发 `Range`；下完比对 `size` + `md5`。

#### D1-4 两条容易被忽略的既有约束

- **共享 OkHttp 客户端的 read timeout 只有 30 s**（[RetrofitClient.kt](app/src/main/java/com/takahashirinta/ncrust/network/RetrofitClient.kt)）——
  32 MB 无损在弱网下必然超时。下载**必须自建 OkHttpClient**（长 read timeout），不要复用。
- **离线音频应放 `filesDir/offline` 而不是 `cacheDir`**：「清除缓存」只删 `cacheDir/{WebView,http}`（不会误删），
  但「缓存占用」统计的是**整个 cacheDir**（会把离线音频算成缓存 → 用户以为可清、实际清不掉）。
- 下载**不应套用 `SongUrlFetcher` 的 FLAC 设备门控**：下载只是写字节、不经 ExoPlayer，API<27 设备也能下无损。

#### D2 架构选型：**WorkManager + Room**（官方推荐，断点续传友好，Android 7.0 兼容）

实测事实支撑这个选择：

| 判断项 | 依据 | 结论 |
|---|---|---|
| 后端是否支持离线 | Range 206 + md5/size 校验 + 跨 URL 续传通过 | ✅ |
| 是否需要新存储 | `SongItem` 只有 5 个展示字段（无 size/md5/level），SharedPreferences 整表 Gson 不适合大规模清单 | ✅ 需要 Room |
| 是否需要后台调度 | 无任何既有 Work/Job 调度基础设施 | ✅ 需要 WorkManager |
| 许可证 | 实查 Google Maven POM：`room-runtime` 与 `work-runtime-ktx` 均为 **Apache-2.0**；Apache 官方 GPL 兼容性页原文「Apache 2 software can therefore be included in GPLv3 projects」⇒ **单向兼容** | ✅ 可引入 |

**唯一未验证的工程风险（必须在 v1.5.1 第一步解决）**：本仓库是 Kotlin **1.9.24** / AGP **8.5.0** / jvmTarget 11，
而 Room 2.7+/2.8+ 与 WorkManager 2.10+ 通常要求更新的 AGP/Kotlin；引入时很可能需要**降级选版**
（如 Room 2.6.x + WorkManager 2.9.x）或同步升级 AGP/Kotlin。**没有跑过真实构建之前不要合并依赖改动。**
另外 Room 需要注解处理器（KSP 或 kapt），本仓库当前两者都未配置。

表结构（草案）：

```
download_task(
  songId INTEGER PK, name TEXT, artist TEXT, album TEXT, coverUrl TEXT,
  requestedLevel TEXT, actualLevel TEXT, type TEXT, br INTEGER, size INTEGER, md5 TEXT,
  filePath TEXT, downloadedBytes INTEGER, status INTEGER,   -- 0 排队/1 下载中/2 完成/3 失败/4 已删除
  error TEXT, createdAt INTEGER, updatedAt INTEGER
)
```

落地顺序（对应任务书的 4 个 commit）：

1. `feat(download): 下载管理器核心` —— `DownloadApi`（复用 `EapiCrypto` + download 端点）、`DownloadStore`（Room）、
   `DownloadWorker`（WorkManager，Wi-Fi 约束 + 前台通知 + Range 续传 + md5 校验）、自建 OkHttpClient；
2. `feat(download): 批量下载入口` —— 在 `MainActivity` 的 `[加入歌单] + menuSongActions + [转到歌手/专辑]` 拼接处插全局「下载」，
   一处改动覆盖全部 7 个页面；歌单/专辑详情页的批量下载要先**把 `DetailScaffold.onTopEndAction` 泛化成多动作**
   （现在只支持一个右上角动作，歌单页已被「编辑歌单 ⋮」占用），且**不能**放 `DetailHeader.headerActions`（在播放器死带里）；
3. `feat(download): 下载队列页` —— 复用 `DetailScaffold`；
4. `feat(download): 存储管理页` —— 已下载列表 + 占用统计 + 删除，与设置页「缓存占用」口径分开。

播放侧接入（**这才是「支持离线播放」的真正开关**）：`PlaybackService` 收到播放请求时先查 Room 有没有已完成的本地文件，
有就播 `fileUri`，没有才走 `SongUrlFetcher`。当前完全没有这条路径。

#### 为什么不在 v1.5.0 落地

1. 引入 Room + WorkManager 需要先解决 Kotlin 1.9.24 / AGP 8.5.0 的版本兼容并新配注解处理器 ——
   这是**唯一未验证的工程风险**，而任务书的红线是「引入新依赖但无法确认兼容性时停下」；
2. 任务书明确允许：「如果上下文不足：只做 D1 + D2 调研报告，实现留 v1.5.1」；
3. 与其塞一个未在真机验证过的下载器进 v1.5.0，不如把已实测的协议结论与表结构沉淀下来，
   让 v1.5.1 从「已知可行」起步。

## v1.5.1 新增（本 fork）

### A · 歌词逐字三模式 + PCL110（API 36）失效根因

**症状**：S6（Android 7.0）逐字生效但"硬切"、不够顺滑；PCL110（Android 16）**完全没有逐字效果**。

**诊断（PCL110 真机，临时探针实测，不是推断）**：

| 检查项 | 结果 |
|---|---|
| 逐字分支是否进入 | 进入 —— `words` 非空（yrc 在 API 36 上照样解析成功） |
| `TextLayoutResult.getPathForRange(0, n)` | **正常**：`pathOk=true pathEmpty=false bounds=Rect(0,0,112,131)`，无异常 |
| 结论 | 失效点在 v1.5.0 的**绘制组合**：同一个 BasicText 节点里 `clipPath(path)` + `drawText(lr, color=...)`。取路径那一步是好的，所以"API 36 上 getPathForRange 失效"这个假设**不成立**；真正要弃用的是那套画法 |

**v1.5.1 的重写**（[NcrustLyricsPanel.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt)）：

- 不再用路径 API；硬切走 `clipRect`，软边走 `canvas.saveLayer` + `drawRect(blendMode = DstIn)`；
- 高亮层与底层**各自用同一 TextStyle（只差颜色）排版**，颜色烤进 style —— 不再依赖 drawText 的颜色覆盖；
- **不用 `CompositingStrategy.Offscreen`**：它在低版本会回落成 Auto，DstIn 就作用到整个目标画布、
  把底下的文字一起擦掉；显式 saveLayer 在 API 24 上也稳；
- 换行用 `getLineForOffset` / `getLineTop` / `getLineBottom` 逐行处理，软边不跨行；
- 播放位置仍然只在 draw 阶段读（零重组不变）；高亮层 `clearAndSetSemantics`，一行文本不会给无障碍报两遍。

**三模式**（设置项 `lyrics_word_animation`，prefs 存 Int）：

| 值 | 模式 | 说明 |
|---|---|---|
| 0 | 渐变扫过（**默认**） | 词内按时间线性推进，渐变带 12% 行宽 |
| 1 | 逐字硬切 | v1.5.0 的逐词点亮风格，向后兼容 |
| 2 | 关闭逐字 | 当前行整行高亮，行内渲染同 v1.4.1 |

老设置迁移：v1.5.0 的布尔 `lyrics_word_by_word=false` → 模式 2，其余 → 模式 0（只迁移一次）。

**双端实测**（S6 + PCL110）：三种模式都即时生效；用逐帧像素剖面验证扫过点逐帧右移、
硬切边界是"一刀切"（无中间灰阶）。

### B · 控制栏把手与全面屏手势冲突（PCL110）

**症状**：从把手起手的上滑被系统"回到桌面"抢走，把手只能点按（用户描述为"滑动只能单向"）。

**实测与结论**（PCL110 / Android 16 / `navigation_mode=2`）：

1. `Modifier.systemGestureExclusion()` **确实注册成功**（`dumpsys window` 实测
   `mSystemGestureExclusion=SkRegion((0,2716,1272,2800))`），但系统**照样吞掉底部 0~13dp 起手的上滑**
   —— 单靠它修不好。上滑起手点实测：13dp 必被吞、28dp 必交给应用。
2. 所以真正生效的是**按导航模式把 24dp 拖拽带整体上抬 32dp**；排除区声明保留（对左右边缘与部分 ROM 仍有效）。
3. 三键导航与 API < 29（没有 `navigation_mode` 设置项）不上抬，视觉与行为不变。

**实测**：上拖收起 ✓ / 下拖恢复 ✓ / 点按切换 ✓。A/B 对照：v1.5.0 同一位置的上滑 → launcher。

### C · 无网络启动

**诊断（emulator-5554 / API 24，v1.5.0 release 包实测）**：

- 冷启动**不阻塞**：`am start -W` TotalTime ≈ 0.5s，splash 正常退出（AppWarmup 有 3s 兜底，
  且无网时 OkHttp 是快速失败，不是 30s 超时）；无 ANR、无 crash、无主线程网络调用。
- 真正的问题是**失败静默**：首页三块请求失败后 `error` 只写进 state、界面上没有任何消费者，
  三块数据全空 → 首页只剩页头 + 私人 FM 卡片，**没有空态、没有原因、没有重试**，一直这样（实测 22s 不变）。

**修复**：

1. `NetworkAvailability.isOnline()`（[NetworkAvailability.kt](app/src/main/java/com/takahashirinta/ncrust/network/NetworkAvailability.kt)）——
   无网时 AppWarmup 与首页**都不发请求**，直接进降级态。判据只用 `NET_CAPABILITY_INTERNET`，
   **故意不要求 VALIDATED**（国内 ROM 上 validated 常年 false，用它会把"有网"误判成"没网"）；
2. `HomeSnapshot`（[HomeSnapshot.kt](app/src/main/java/com/takahashirinta/ncrust/cache/HomeSnapshot.kt)）——
   首页四块数据落盘（`ncrust_home_cache`，每块 ≤60 条），冷启动先灌回 ContentCache，
   断网重启也能看到上次的内容；
3. 首页降级空态（图标 + 原因 + 重试），并在有网但请求挂住时 **8s** 撤掉转圈
   （`HOME_LOAD_TIMEOUT_MS`），不再一路等到 OkHttp 的 30s。

### D · 媒体控制中心显示当前歌词行

**调研结论（AOSP 源码级）**：Android 13+ 的 SystemUI 媒体面板**只读 TITLE（第一行）与 ARTIST（第二行）**，
`DISPLAY_SUBTITLE` 被完全忽略；MediaMetadata/Media3 都没有歌词字段；Android 16 的 Live Updates
明确排除 MediaStyle ⇒ 小米超级岛 / OPPO 流体云 / vivo 原子通知对第三方要么要白名单 + MiPush、
要么只能走不含 MediaStyle 的 Live Updates，**一律不接入，不引入厂商 SDK**。

**实现**：设置项 `lyrics_in_media_session`（**默认关**）。打开后 ARTIST = 「艺人 · 当前歌词行」
（艺人保留在前），并顺带写 `DISPLAY_SUBTITLE` 兜住老车机与蓝牙 AVRCP；只在**跨行**时更新
（ViewModel 在 2Hz 采样上算当前行，值不变不写；服务侧本来就有等值去重）⇒ 行不变时零 `setMetadata`。
关掉 / 无歌词：字段完全回落原样。

**实测（PCL110）**：`dumpsys media_session` → `metadata: size=5, description=修炼爱情, 林俊杰 · 我们那些信仰要忘记多难, null`；
下拉 QS 的媒体卡片显示「修炼爱情 / 林俊杰 · 谁说太阳…」。

### E · 歌词字号调节

设置项 `lyrics_font_scale`（Float，5 档 0.7/0.85/1.0/1.2/1.5，默认 1.0）+ 歌词界面右上角的
**A- / A+**（44×28dp、到端点置灰、带无障碍描述；放右上角是为了避开底部的控制栏把手与系统手势区）。
两者共用一条 StateFlow：**立即生效**、跨重启保持；落盘 **150ms 防抖**（连点只写一次）。
字号直接乘在 fontSize/lineHeight/译文号上 ⇒ 行高、居中偏移、自动换行、逐字裁剪全部由
`TextLayoutResult` 自行重算，当前行的缩放动画与基础字号本来就是乘数关系，无需补偿。

**实测（PCL110）**：0.7x / 1.5x 视觉差异明显、1.5x 下长句正常折行且逐字高亮正常；
A+ ×2 → prefs 1.5、A- ×4 → prefs 0.7。S6 上设置项与下拉渲染正常。

## ⚠️ 操作红线：不要用 `sed -i` 改应用私有目录里的文件（v1.5.0 实测踩到）

Android 应用私有目录（`/data/data/<pkg>/shared_prefs/*.xml` 等）的文件带 **SELinux MLS 类别**，
例如 `u:object_r:app_data_file:s0:c512,c768`。`sed -i` 的语义是「写临时文件 + rename 覆盖」，
rename 出来的新文件会丢掉 `:c512,c768`（变成 `u:object_r:app_data_file:s0`），应用**立刻读不到自己的文件**：

```
W SharedPreferencesImpl: Attempt to read preferences file …/ncrust_settings.xml without permission
E SharedPreferencesImpl: Couldn't rename file … to backup file
E audit  : avc: denied { rename } … ino=… scontext=u:r:untrusted_app:s0:c512,c768 …
```

实际表现极具误导性：应用照常启动、照常播放（用的是内存里的默认值），但**所有设置静默失效**
（本次是「推荐卡开关明明是 true 却不推导」）。

**正确做法**：

1. 首选**不要碰 prefs** —— 走应用内的设置入口；
2. 必须改文件时，用**原地截断写**（`cat > file`、`tee`）而不是 `sed -i`，保留 inode 与 SELinux 标签；
3. 万一手滑了，按同目录下正常文件恢复（owner / mode / 标签三件套）：

```bash
su -c 'chown $(stat -c %u REF):$(stat -c %g REF) TARGET'
su -c 'chmod $(stat -c %a REF) TARGET'
su -c 'chcon $(ls -Z REF | cut -d" " -f1) TARGET'    # 或直接 restorecon TARGET
```

本次即用此法恢复，设置与登录态均完好无损。

## Key Constraints & Pitfalls

- **Kanesumi Design**: no rounded corners in the player; no spring/bounce; cover always fills the full screen width (`fillMaxWidth().aspectRatio(1f)`, scale 1.0 in large mode).
- **GPU zero-recomposition**: read animation values only inside `graphicsLayer`; never `animateFloatAsState` for the player card. High-frequency StateFlows are subscribed in leaf composables / `draw` scope, not in the parent.
- **PlayerCard collapse gating**: below `progress = 0.01`, the whole expanded subtree (LyricsView / QueueView / FullPlayerControls / 大封面信息) is **not mounted**; mini bar 永远挂载。代价是首次展开付一次 composition+layout+draw（v1.3.0 · B-1 的取舍，换掉详情页死带）。
- **Borderless list style**: `SongCard` LIST/COMPACT rows have `0dp` left padding (72dp cover flush to the edge); only the right keeps 16dp. `DetailScaffold` has no `TopAppBar` Surface — a floating back arrow (`MetroTopScrim`) overlays the content. Grid tiles use `spacedBy(2.dp)`. Home/Library use a 34sp page header instead of an app bar.
- **Bottom overlay inset**: the mini bar (56dp) sits above the M3-era 80dp nav bar, drawn as a sibling overlay, so `Scaffold.innerPadding.bottom` does **not** reserve space for them. Use `BottomOverlayInsetDp` as `contentPadding`.
- **System-bar compensation**: `collapsedOffsetY = contentHeightPx - sysNavPx - navBarHeightPx(56/0) - miniBarHeightPx(56) - sysStatusPx`. `sysStatusPx` cancels the `.statusBarsPadding()` applied inside `PlayerCard` to the mini-bar overlay. **Automotive (AAOS) caveat**: CarSystemUI does not deliver WindowInsets, so on `UI_MODE_TYPE_CAR` the content height comes from the measured root height (`rootHeightPx`), not `screenHeightDp`; phone/tablet keep `screenHeightDp` so car changes don't leak. Touch any of these values carefully.
- **Search debounce**: 500 ms in `SearchViewModel` — do not remove.
- **No explicit coroutines dependency**: coroutines ship with the Kotlin stdlib configuration here.
- **`ContentCache` is not persisted**; `LibraryManager` is the persistence layer.
## v1.6.0 新增（本 fork）

### D4 · 逐字歌词覆盖率：`YrcAligner`（LCS 行对齐）

**接口层天花板是 43%**（收藏库 100 首实测）。`lv/kv/tv=-1` 与覆盖率无关 —— v1.5.0 起就已经在传
`yv=-1`；`kv=-1`(klyric) 100 首全空；换 eapi/iPhone/Android 身份、换 `/api/song/lyric/v1`、
换 GET/POST 都不多给一个 yrc 字段。**所以别再往「换参数/换通道/接外部源」上花时间**
（LRCLIB 等只有行级时间轴，拿不到逐字）。

真正的瓶颈是「yrc 行数必须 == lrc 行数」这条判据：实测 yrc 常少一行（间奏没有逐字轨）、多几行
（重复段），或 lrc 多一行「作词 : xxx」元信息。改成**两条路各算一遍、取挂得多的那条**：

| 路径 | 做法 |
|---|---|
| `index` | v1.5.x 原行为：行数相等 + 时间漂移抽样达标 → 按行序 i↔i（逐字节保留） |
| `lcs` | 按**归一化文本**（只删空白字符）做最长公共子序列，再按时间漂移逐对过滤 |

实测：可用歌数 37 → **42**/100，行级挂载率 77.9% → **90.0%**；《Sound Of Silence》
`line count mismatch lrc=40 yrc=39, skip` → `39/40 lines got word timing (yrc=39, lcs)`。
「择优」规则是为了**不回归**：实测《You Never Can Tell》index=22 行 > lcs=17 行。

契约（不许改）：`attachWords` 不增删任何一行、不改任何一行的文本与时间戳；挂不上的行原样返回。
归一化**只删空白**，不去标点、不去大小写 —— 宁可少挂几行，也不让不同源的两行被判成同一行。

### D2 · S6 帧预算：98% jank 的归因（**结论：不要为此改歌词面板**）

S6（API 24 / debug / 1440×2560）`dumpsys gfxinfo framestats`，22s 窗口：

| 状态 | 帧数 | Janky | 整帧 50th | GPU 段 50th |
|---|---|---|---|---|
| 库列表滚动（播放器收起） | 1080 | 1.02% | 5.2ms | 2.7ms |
| 展开播放器 + 队列滚动（**无歌词**） | 1224 | 25.8% | 18.0ms | 11.8ms |
| 展开播放器 + 歌词（逐字渐变） | 981 | 98.06% | 18.6ms | 14.5ms |
| 展开播放器 + 歌词（逐字关） | 117 | 84.6% | 22.8ms | 12.6ms |

- 整屏重绘的单价是**设备属性**：无歌词的队列滚动已经 11.8ms GPU；加逐字渐变只 +2.7ms；
- 歌词面板的作用是**让昂贵帧连续发生**（981 vs 117 帧）—— Janky% 由 25.8% 跳到 98%；
- UI 线程始终 1–2ms，瓶颈纯 GPU（Slow issue draw commands 73%）⇒ **「减少重组」「给某行加
  layer」这类改动没有意义**（逐行缩放层探针实测 977 vs 981 帧，无差异）；
- ⇒ 不存在「小改一处到 60fps」的热点。**不要为了让 S6 的 Janky% 好看而牺牲软边/逐帧推进**。
- 也**不要**用「帧间隔」自动判降级：S6 是流水线式掉帧（帧回调仍 60Hz、每帧延迟 19.5ms），
  帧间隔量不出来（这条已经踩过，实现到一半按证据回退）。

### D1 · 离线缓存 = **已播放音频流**的缓存（不是下载）

- 新 `cache/` 包：`OfflineKeys`（纯逻辑）、`OfflineUrlStore`、`OfflineAudioCache`（media3
  `SimpleCache` + `CacheDataSource`）；
- **必须自定义 cache key**：网易播放 URL 每次取都变（host 轮换 + 路径签发时间戳 + 20 分钟过期），
  media3 默认按 URL 做 key 会让缓存**永远不可能命中**。URL 的 query 是装饰（CDN 只认路径签名），
  所以挂 `ncrustkey=song:<id>:<level>`；**档位必须进 key**（无损与高解析是两个文件）；
- 缓存放 `filesDir/offline/audio`（**不要**放 cacheDir，系统清缓存会误删），但设置页的
  「缓存占用」统计与「清除缓存」必须覆盖它 —— 统计口径与可清理范围要一致；
- 离线回放：起播时记录 (key → 最后一次成功播放的 URL)（300 条 LRU）。取链失败时兜底，
  且**必须** `OfflineAudioCache.contains(key)` 才起播 —— 否则就是拿过期 URL 赌网络 403/404
  的无限缓冲（v1.3.0 禁止的坏链接）；
- `FLAG_IGNORE_CACHE_ON_ERROR`：写缓存失败绝不影响播放；
- 上限 `ncrust_settings:offline_cache_mb`（默认 512，非法值回落默认）；
- 歌词本身已经离线可用（`LyricsCache` 持久化，200 条），不需要额外做。

### D3 · Android 16 实时更新（Live Updates）

- `Notification.ProgressStyle` 存在但**没有** `setProgressMax`（0–100 百分比语义）；
- 闸门：`NotificationManager.canPostPromotedNotifications()`（API 36+）+ 权限
  `android.permission.POST_PROMOTED_NOTIFICATIONS`（**真机实测名**，不是流传的
  `..._ONGOING_NOTIFICATIONS`）；API 36 的 Builder **没有** `setRequestPromotedOngoing`；
- 一条通知只能有一个 Style，MediaStyle 与 ProgressStyle 无法并存 ⇒ 媒体通知保持 MediaStyle，
  另发一条 id 固定、ongoing、IMPORTANCE_MIN、只更新不提醒的通知进实时更新区
  （`LiveUpdateNotifier`）；API < 36 或系统不允许时整类静默不工作；
- 厂商灵动岛（HyperOS 超级岛 / OPPO 流体云 / vivo 原子通知 / 华为实况窗）**无公开 SDK**，
  不接入（与 v1.5.1 D-media2 一致）。


## v1.7.0 新增（本 fork）

三个方向：**P0 手势 bug（最高优先级）· P1 横屏大屏幕模式 · P2 Material3 理念美化**。

### P0 · 收起播放器拖不上来

完整根因/修法/实测见「Compose 触摸陷阱」第 8 条与 TASK.md 第 13.1 节。一句话：
**不是命中测试，是吸附阈值**（旧判据要求手指跨 340dp），判定抽到
[PlayerDragSnap.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerDragSnap.kt)（+23 条 JVM 单测）。
两条**必须保住**的实现细节：

1. 吸附判定读的是**本地累计的拖动进度**（`dragProgress`），不是 `progress.value` ——
   `progress.snapTo` 是 `launch` 出去的异步写，慢设备上一次拖动结束时 Animatable 还没追上手指，
   用它判定会出现「明明拖了 400px，判据只看到 150px」（S6 实测）。
2. 速度从 **progress 域**反推（`progressDelta × totalDragDistancePx ÷ 耗时`）：
   检测器挂在被 `graphicsLayer` 平移的节点里，拖动中局部坐标随卡片一起移动，`position` 差分会低估速度。

### P1 · 横屏「大屏幕模式」（桌面播放器）

| 项 | 说明 |
|---|---|
| 进入 | 播放器展开后，底部操作行第 4 个「⤢」按钮（`FullPlayerControls` 的 `onToggleBigScreen`） |
| 退出 | 同一按钮（横屏在控制条右端）· 系统返回键 · **把手机转回竖屏** |
| 布局 | 左栏：大封面 + 歌名/作者 + **就地音质选择器**；右栏：复用既有歌词/队列面板（yrc 逐字、翻译、A-/A+ 全保留）；底部控制条横跨右栏 |
| 方向 | 进入时 `SENSOR_LANDSCAPE` 立刻转过去 → 由 `OrientationEventListener` 放宽成 `SENSOR`（**绝不长期锁横屏**）；`applyOrientationPolicy()` 在大屏期间让路；`onConfigurationChanged` 优先判退大屏 |
| 判定 | **第三个谓词** `PlayerLayout.isBigScreenActive(requested, orientationLandscape)`，只让播放器读；全仓库那 7 处 `screenWidthDp >= 600` **一处没改** |
| 顺手修 | 分栏**语义中线**：旧实现两处用 `screenWidthPx / 2`，而真实边界是 `0.44 × 宽`，44%~50% 那条窄带里歌词滚动会被整卡拖拽抢走（第 3 条坑的变种）。现在统一走 `PlayerLayout.splitBoundaryPx()` |
| 纯逻辑 | [PlayerLayout.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerLayout.kt)（12 条单测）、[BigScreenOrientation.kt](app/src/main/java/com/takahashirinta/ncrust/BigScreenOrientation.kt)（6 条单测） |
| 实测 | PCL110：进入即 2800×1272、左封面 952px(272dp)、右栏 yrc 歌词 + A-/A+ 并排、音质就地切换 `wifi_quality` 6→2 并按新档续播、返回键/按钮都能退出、大屏帧 Janky 0% |

⚠️ **大屏模式与「宽屏两栏」是两件事**：宽屏（平板/折叠/车机，`screenWidthDp >= 600`）仍走原来的
`isWidePlayer` 分支；手机横屏大屏模式走新分支。改其中一个不要顺手改另一个。

### P2 · Material3 理念美化（**不引 material3**）

**结论先行：官方 `androidx.compose.material3` 不引入。** Kanesumi 是自研的 M3 风格设计系统
（库自己的 AGENTS.md 写着「零 M3」），同时装两套 = 双主题源 + 圆角/ripple 冲突。
M3 在本仓库只当**取值参照系**。落地的是三件与 Kanesumi「无圆角无阴影」不冲突的事：

1. **tonal 层级做进颜色**（不是阴影）：`NcrustColors` 新增
   `surfaceContainerLowest/Low/High/Highest + outline/outlineVariant`（**默认值 = 深色现值**，
   老调用点零变化）；`toMetroColors()` 把 `surfaceVariant ← surfaceContainerHighest`、
   `divider ← outlineVariant`。深色因此有了 4 级层次：
   **页面 `#000000` → 卡片 `#1A1A1A` → 弹窗 `#242424` → 菜单/下拉 `#2E2E2E`**
   （修掉「深色下 surface == surfaceVariant，弹窗与页面同色」这个老问题）；浅色弹窗 `#FFFDF8 → #F2EBDE`。
2. **对比度**：5 处把「primary 上的前景色」硬编码成 `Color.Black` 的地方改用
   `LocalMetroColors.current.onPrimary`。6 个预设色下逐像素不变（它们本来就判黑），
   但「跟随封面 / 跟随系统」取色会产出中亮度色（例 `#3D3D99` 黑字仅 2.33:1），现在自动改白字 9.03:1。
3. **无障碍/触控**：`SettingSwitchRow` 整行加 `toggleable + Role.Switch`（Kanesumi 的 `MetroSwitch`
   是裸 `pointerInput`、零 semantics，TalkBack 摸不到；**不改库**是因为改了发布产物不可复现）；
   4 个 <48dp 的点击目标抬到 48dp（主题模式选择器 / 取色来源选择器 / 两个对话框按钮）。

**明确不做**：大圆角（Kanesumi 是直角设计语言，全 app 只有 `SongCard` 播放按钮一处 CircleShape，
属图标语义保留）、`material3` 依赖、typography 迁移（`LocalNcrustTypography` 至今 0 处读取，
要动 `MainActivity` 给 `MetroTheme` 传参才生效，收益低风险高，本轮不碰）、
任何 `tween` 时长调整（避免无谓回归）、`SweepEasing`（逐字歌词的光标是**音频时间轴**问题，
不是 UI 转场，绝不能被 M3 动效统一掉）。

- **Stale comments**: some comments say "last 20 s" for the gapless preload window (actual 60 s) and "4 Hz" for progress ticks (actual 2 Hz). Trust the code.
- **`SongDetailScreen` / `NavRoutes.song(...)` are registered but unreachable** — clipboard song links load into the player instead.
- **`ncrust-api/` is not part of the app** — see Repository Layout.
## v1.8.0 新增（本 fork）

四个交互任务 + 一个 API 24 bugfix：**T1 沉浸式 · T2 竖屏音质 · T3 音频可视化 · T4 自动旋转 · T5 S6 状态栏歌词**。

### T4 · 自动旋转（双向）

| 项 | 说明 |
|---|---|
| 开关语义（调研后定为**选项 C**） | **开** = 应用跟随传感器旋转（SCREEN_ORIENTATION_SENSOR）+ 在播放器界面转横屏自动进大屏；**关** = 手机锁竖屏（= v1.7.0 行为）+ 只能用 ⤢ 手动进大屏 |
| ⚠️ 应用内开关 ≠ 系统开关 | 用 SENSOR（**不是** USER），即应用自己决定是否跟随传感器，**既不读也不写** Settings.System.ACCELEROMETER_ROTATION |
| 用户报的「竖屏→横屏不生效」根因 | v1.7.0 里手机恒被锁 SCREEN_ORIENTATION_PORTRAIT，窗口根本不会转 ⇒ 没有配置变化 ⇒ 自动进大屏无从触发（横屏→竖屏能退，是因为那时方向已被大屏模式放宽成 SENSOR） |
| 触发范围 | **只限播放器界面**：playerExpanded（MainScreen 用 snapshotFlow{progress.value > 0.99f} 回传）+ 窗口已横屏 + 开关开 + 还没进大屏，四者同时成立 |
| 手动退出的抑制 | **没有抑制标志**（那种写法会「忘了清 ⇒ 自动进入从此失效」）：LaunchedEffect 的三个 key 在「用户按按钮/返回键退出」时都不变 ⇒ 不会重新触发。转回竖屏再转横、或收起播放器再展开 = 新的显式意图 |
| 防抖 | AUTO_ENTER_SETTLE_MS = 250ms：方向变化时 LaunchedEffect 自动取消上一个协程 ⇒ **合并**连续变化并重新计时（不是丢弃，丢弃会把状态留在错误的一侧） |
| 纯逻辑 | BigScreenOrientation.kt（orientationFor / shouldAutoEnterBigScreen + 单测）· RotationSetting.kt（唯一读写入口：落盘 + 进程内 Compose 广播，三人消费者共享） |
| UI | 设置页整行 toggleable+Role.Switch；播放器竖屏控制栏第 5 个图标 + 大屏左栏音质 chip 右侧（同一个 RotationToggleButton，两态图标，56dp/40dp 命中盒 + semantics） |
| 退出大屏的方向恢复 | auto-rotate 开 → 交还传感器（手机还横着就保持横屏，此时走**宽屏两栏**播放器）；关 → 回 PORTRAIT |

### T1 · 大屏模式沉浸式（隐藏状态栏）

- **只隐藏状态栏，不隐藏导航栏**：导航栏是 S6 三大金刚键 / 手势返回的载体，也是 ⤢ 之外唯一的明确退出路径；底部控制条的横向拖拽也贴着屏幕下沿（藏了会被系统「临时唤出」抢手势）。
- **sticky**（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）：下拉能临时看时间/电量，几秒后自动收回，不会把布局挤矮。
- **API 24 与 API 30+ 走同一条代码**：WindowInsetsControllerCompat 在 androidx.core 内部按版本分派（30+ → WindowInsetsController；24~29 → setSystemUiVisibility + IMMERSIVE_STICKY）。业务代码里**不要**再手写 Build.VERSION.SDK_INT 分支。
- 生效条件与 PlayerCard 的 bigScreenActive 完全一致（用户意图 + 窗口真的横过来），避免「按钮刚点、窗口还没转」的几百毫秒里状态栏先消失。
- onWindowFocusChanged(true) 幂等重放一次：部分 ROM 从最近任务回来会重置窗口标志。
- 实测：PCL110（Android 16 / 手势导航）与 **S6（Android 7.0 / 三大金刚键）** 都打出 immersive: status bar hidden；返回键退出后 immersive: status bar shown。

### T2 · 竖屏音质就地选择器

- 竖屏控制栏中间那个音质 chip 此前点了**跳设置页**（onNavigateToUser）；现在改成与横屏大屏同一个 PlayerQualityChip（Kanesumi MetroSelectorFlyout，UWP ComboBox 移植）。onNavigateToUser 这条链路已整条删除。
- 三处共用：竖屏控制栏 / 宽屏控制条右端 / 横屏大屏左栏 —— 档位表、降级角标、「档位真变了才重取链」的行为天然一致。
- 两件容易写错的事写在组件 KDoc 里：① 高亮档位必须**打开那一刻现读**（currentQualityPreferenceIndex()）；② FLAC 设备门控提示（API<27 选了无损也不会有）放在组件内 —— 放调用点必然漏一处。
- 触摸：外层 ≥48dp 命中盒（视觉 chip 仍是 ~22dp 小色块，与 v1.7.0 逐像素一致）+ semantics{contentDescription = qualitySectionTitle}（此前只有横屏那份有）。

### T3 · 大屏幕模式音频可视化

**数据来源结论（调研，别再找别的 API）**：任务书假设的 AudioListener.onAudioSamples **在 media3 里根本不存在** —— javap 逐个版本核实 1.4.1 / 1.5.0 / 1.6.0 / 1.7.0 / 1.8.x / 1.9.0 / 1.11.1 都没有 AudioSamples / AudioListener 类。官方可用的是 **TeeAudioProcessor + WaveformAudioBufferSink**（1.4.1 起就有，1.11.1 签名逐字一致）。

| 项 | 结论 |
|---|---|
| 权限 | **零新增权限**（在应用自己的音频处理链上取数据）。备选 android.media.audiofx.Visualizer 从 AOSP 7.1.1 起就要求 **RECORD_AUDIO**（不是「Android 10+ 才要」），与项目红线冲突，**未采用** |
| FFmpeg 路径 | 完全兼容：FfmpegAudioRenderer extends DecoderAudioRenderer，而 DefaultRenderersFactory 反射创建它时传的是**同一个 AudioSink 实例** ⇒ MediaCodec 与 FFmpeg 两条解码路径都经过 tee |
| 注入点 | VisualizerRenderersFactory.kt override buildAudioSink（**base 的三个参数必须逐项复刻**，否则静默改掉音频输出行为） |
| 线程 | 回调在 ExoPlayer:Playback（THREAD_PRIORITY_AUDIO）⇒ WaveformRing.push 只做一次数组写 + 一次 volatile 自增：**零分配 / 零锁 / 零 IO** |
| 环形缓冲 | 单写者无锁；UI 卡顿时**丢最旧的**，绝不回压音频线程；NaN/Inf 与非 [0,1] 全部夹紧 |
| 渲染 | Canvas + drawRect；WaveformStore.generation **只在 draw 阶段读**（只重绘不重组）；柱高数组 remember 出来逐帧原地更新 |
| 帧率 | 20fps（VISUALIZER_FRAME_INTERVAL_MS = 50），与数据速率 1:1；暂停/缓冲走 delay 并把窗口**指数衰减**到 0（不是瞬间清零，避免闪），归零后不再要求重绘 |
| 幅度 | 用 **sqrt** 映射到高度：音乐 RMS 常落在 0.05~0.3，线性只能画出一排小方块 |
| 布局 | 封面下、歌名/作者上；高度 = screenHeightDp × 11% 夹在 32~56dp（PCL110 横屏 363dp→40dp、S6 480dp→53dp），封面区 weight(1f) 自动让位 ⇒ 不溢出 |
| 开关 | 设置页 audio_visualizer（默认**开**）。关掉时整块**不挂载**（不是 alpha=0），连帧时钟都不跑 |
| S6 实测 A/B（同协议：重启 → 展开播放器自动进大屏 → 播放 → gfxinfo 25s） | 可视化**开**：485 帧 / Janky 44.12% / 50th **15ms** / 90th 25ms；**关**：157 帧 / Janky 78.98% / 50th **20ms** / 90th 36ms ⇒ 可视化把重绘摊平成稳定的 20fps 增量重绘，单帧反而更短，**不劣化 S6 基线**（对比 v1.6.0 的 S6 GPU 瓶颈记录：展开播放器+歌词 50th 18.6ms / 98% jank） |

### T5 · S6（API 24）状态栏歌词不更新（bugfix）

**根因（探针 + 平台侧证据）**：歌词行数据链是通的（2Hz 位置流 → 当前行 → PlaybackService.mediaLyricLine，会话 metadata 也一直在更新），但**通知正文只在 6 个事件点重新 post，没有一处是「跨行」**；而 **API < 28 的 SystemUI 不会从 MediaSession metadata 重建媒体通知**（S6 真机 SystemUI dex 里 NotificationMediaManager 命中数 = 0；AOSP 里这个类 **android-9.0.0_r1 才出现**）。于是通知停在最后一次 post 的那一行 —— 「暂停/播放恰好会走 onIsPlayingChanged → updateNotify()」正是用户看到「只有暂停再播放才刷新」的原因。

修法：跨行时补一次重 post，**只在 SDK_INT < P 生效**（28+ 的系统自己会重建，重 post 反而让媒体轮播卡反复刷新）；配套 setOnlyAlertOnce(true) + LYRIC_NOTIFY_MIN_INTERVAL_MS = 250 限流（Android 7 有 post_frequency 限流）+ debug 包 NcrustLyricNotify 计数日志。实测 S6：一次播放 14 次跨行重 post，通知栏标题随歌词推进（截图复核）。

### v1.8.0 的未验证项（如实）

1. **真人手持旋转没有做**：测试机平放在桌上，自动进大屏的**传感器路径**（OrientationEventListener 放宽）无法物理触发。已验证的是「窗口变成横屏 ⇒ 自动进大屏」这条（S6 上通过「展开播放器时窗口已经是横屏」触发成功，且关闭大屏后保持宽屏两栏布局符合设计）。**请手持转一次确认。**
2. T2 的 FLAC 提示在真机上未触发（PCL110 与 S6 都有 FLAC 解码器）。
3. 大屏下的 S6 A/B 只测了 25s 窗口，未跑 perfetto；未做「可视化 ON 但歌词完全静止」的极端对照。
4. 可视化只出现在大屏模式左栏（竖屏不出现，设计如此）。
5. 与本次改动无关的老功能（榜单、推荐卡、离线缓存、媒体中心歌词等）本轮未逐项复测。

## v1.8.1 修复（本 fork）

### T3 · 音频可视化「帧率太低 / 一跳一跳」

**用户反馈**：「感觉刷新率也就是帧率有点太低了，请调高使得流畅」。**先量了全 app 基线再动手**：

| 场景（PCL110 / release） | 帧率 | Janky | 50th |
|---|---|---|---|
| 库列表滚动 | — | 2.61% | 5ms |
| 竖屏播放器 + 逐字歌词 | 51fps | 0.49% | 6ms |
| **大屏 + 可视化（v1.8.0）** | **~20fps** | — | — |

⇒ 全 app 帧率健康，问题**只在可视化**。根因两条，都不是"GPU 不够"：

1. **重绘被限成 20fps**（`VISUALIZER_FRAME_INTERVAL_MS = 50`），而数据是 **20 柱/秒** —— 一个阶跃一个阶跃地画，看着就是跳的；
2. **柱高是硬切换**，柱与柱之间没有任何过渡。

修法（三层，缺一层都还是跳）：

| 层 | v1.8.0 | v1.8.1 | 为什么 |
|---|---|---|---|
| 数据速率 `BARS_PER_SECOND` | 20 | **30** | 真正的瓶颈是**信息速率**，不是重绘帧率。30 是 media3 建议区间（10–30）上限 |
| 重绘上限 | 50ms（20fps） | **16ms（60fps）**；`SDK_INT < 26` 或 `isLowRamDevice` → 33ms（30fps） | 60fps 是"顺滑"的基准；低端机档位是**静态判据**（不按实测帧间隔自动降级 —— S6 是流水线式掉帧，帧间隔量不出压力，v1.6.0 已踩过并回退） |
| 柱高 | 硬切 | **时间常数平滑**（起音 22ms / 回落 130ms，`1-exp(-dt/tau)`） | 数据只有 30Hz，靠**插值**把 30 个采样点连成连续运动；时间常数形式保证 60fps 与 30fps 画的是同一条曲线 |

**顺手修掉一个实现期被单测抓到的真实缺陷**：重绘判据原本写成「本帧位移 > epsilon 才需要重绘」，
结果回落尾段每帧位移越来越小，`pump` 在柱子还停在 **~1.5%** 时就报"不用重绘" ⇒ **暂停后柱子冻在非零值**。
改成「这一根还没到位（`current != target`）就继续重绘」+ 收敛截断（`SETTLE_EPSILON`）后：
衰减 45 帧精确归零、之后不再重绘。

**实测（两台真机，25s 窗口）**

| 设备 | v1.8.0 | v1.8.1 |
|---|---|---|
| PCL110（API 36 / release）大屏 | ~20fps | **61fps / Janky 0.00% / 50th 20ms**；两帧相隔 150ms 波形区域有 ~1000/14000 采样点变化 ⇒ **柱间确实在连续运动** |
| S6（API 24 / debug）大屏 | 20fps / 44.12% janky / 50th 15ms | **29.2fps / 20.99% janky / 50th 13ms**（更好，不是"用帧率换观感"） |

### 关于「用户页长时间显示加载中」（非本 fork 的 bug，记录备查）

用户曾报「用户界面长时间显示加载中，约半分钟」。核实结论：**不是 v1.8.0 的回归**，
是 `UserScreen` 的 `PlaylistApi.getUserProfile()` **真实网络等待**（用户随后自行确认为网络问题并作废）。

证据与机制，写在这里免得下次再查一遍：

- `loadProfile()` 有 `finally { isLoadingProfile = false }`，所以「一直转圈」= **请求本身慢**，不是状态没收尾；
- 共享 OkHttp 客户端的 **read timeout 是 30s**，所以"约半分钟"正好是一个超时窗口的量级；
- 同一时刻 S6（同一账号、同一 WiFi）4 秒内就出结果 ⇒ 服务端/链路侧，非客户端逻辑。

**没有为此改任何代码。** 唯一可讨论的点是「加载中没有任何超时/失败提示」——
现在的行为是失败后静默回落到未登录态，属于既有设计，不在本轮范围。

## v1.9.0 新增（本 fork）

**AMLL TTML 逐字歌词源 + TTML → YRC → LRC 三级回退链**。渲染层**零改动**：TTML 解析结果直接落成既有的
`LrcLine` / `LrcWord`，`SweepTrack` / `NcrustLyricsPanel` 完全不需要知道歌词来自哪个源。

### 版本：`1.9.0-gpl` / versionCode **23**

| 项 | 实测值 |
|---|---|
| versionName / versionCode | `1.9.0-gpl` / **23**（`app/build.gradle.kts`） |
| v1.8.1 的 versionCode | **22**（`aapt2 dump badging dist/Ncrust-v1.8.1-gpl-release.apk` 实测） |
| applicationId | `com.takahashirinta.ncrust`（不变） |
| 签名证书 SHA-256 | `e75af3ff…5511`（与 v1.0.4 起历次发布同一张；PCL110 上安装的 23 与 v1.8.1 release 实测一致） |

⚠️ **任务书的「v1.8.0 = 21，本版 22」是过时的**：v1.8.0 确实是 21，但 **v1.8.1 已经用掉 22**
（`1341169 build: 升级至 v1.8.1-gpl (versionCode 22)`）。照抄 22 会与线上包**撞号、装不上**；
versionCode 必须严格递增。

### 覆盖率实测（三口径，**不许写「大幅提升」**）

样本 = PCL110 实机导出的**单账号真实收藏 98 首**（偏华语流行 + 部分欧美摇滚 / 电子）。
YRC 可用判据用**当前实现语义**（v1.6.0 `YrcAligner`：LCS 保序对齐 + 漂移 ≤ 2000ms + 挂载率 ≥ 50%），
不是 v1.5.x 的「行数必须完全相等」。

| 口径 | 命中 | 覆盖率 |
|---|---|---|
| 仅 YRC 可用 | 40 / 98 | **40.8%** |
| 仅 AMLL TTML 可用 | 10 / 98 | **10.2%** |
| **YRC ∪ TTML（逐字总覆盖）** | **45 / 98** | **45.9%** |
| 两者都无逐字（只能整行 LRC） | 53 / 98 | 54.1% |

- **TTML 的净增量 = 5 首 = +5.1 个百分点**（40.8% → 45.9%，相对 +12.5%），不是任务书预期的量级；
- 拆分：仅 YRC 35 首 · 仅 TTML 5 首 · **两者都有 5 首** ⇒ **TTML 命中里有一半（5/10）是 YRC 已有覆盖的「重复投资」**；
- 覆盖率与热度强相关：10 首 TTML 命中全部 `pop=100`，冷门曲基本没有 TTML；
- **已知偏差（如实）**：98 首来自单个账号，不代表全体用户；**ACG / vocaloid / 日系用户的实际 TTML 覆盖率会显著更高**；
- 任务书「88% 的歌看不到逐字」的前提也已过时：当前实测是 **54.1%**（YRC 的 40.8% 已经覆盖大部分）。

**不做「按歌名 / 歌手模糊匹配」的兜底**：AMLL README 明确警告不同版本 / 不同音源的歌词时间轴不可混用，
同名异版的 TTML 会导致**逐字错位** —— 那比没有逐字更糟。宁可少 5 首，不给错位高亮。

### 三级回退链（纯函数，JVM 可单测）

```
TTML 总开关关闭                     → YRC → LRC
TTML 开启 + ttmlFirst=true（默认）   → TTML → YRC → LRC
TTML 开启 + ttmlFirst=false         → YRC → TTML → LRC
```

- **决策规则**（`LyricSourceChain.pick`）：**先从「有逐字」的候选里按优先级取第一个；都没有，才退到第一个
  「有内容」的候选；再没有 → 无歌词。** 语义要点：LRC 是整行，只要还有任何逐字候选可用，就不该退到 LRC
  （哪怕它排在更前面 —— 比如 TTML 只有整句、YRC 有逐字时选 YRC）。
- `hasWordLevel` **必须由解析器判「有没有词」**（`TtmlParser.hasWordLevel`），不能拿「解析成功」冒充 ——
  AMLL DB 里有只有 `<p>` 没有 `<span>` 的逐句投稿，用它替换 LRC 只会白丢 ncm 的行级数据。
- **关掉总开关时 TTML 根本不进 `order`**，不是「排在最后」：用户关掉的源一个字节都不该拉。
- 不做的事：不删改既有 YRC / LRC 路径；TTML 取不到 / 解析失败 / 没有逐字一律**静默回退**，不弹错、不新增 Toast。

### 数据来源与许可

| 项 | 结论 |
|---|---|
| 数据源 | [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db)（社区维护的公开静态文件仓库） |
| 许可 | **CC0-1.0**，明确允许音乐播放器使用；**不引入任何新的运行时依赖** |
| 取数方式 | 按 ncm `songId` 直接 GET `ncm-lyrics/<id>.ttml`（只读公开静态文件） |
| 红线 | **不爬虫、不模拟登录、不绕过任何保护**；不发 ncm 请求、不碰 cookie / 用户凭据 |
| 超时 | 自建 OkHttp（连接 5s / 读 10s），不复用 `RetrofitClient` 的 30s/30s —— 补充源拿不到必须立刻回退 |

### 镜像表与 HTTP 语义（2026-09 curl 实测）

| # | 镜像 | 与主镜像 |
|---|---|---|
| 1 | `https://amlldb.bikonoo.com/ncm-lyrics/<id>.ttml` | 基准 |
| 2 | `https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/ncm-lyrics/<id>.ttml` | sha256 **逐字节一致** |
| 3 | `https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/ncm-lyrics/<id>.ttml` | sha256 **逐字节一致** |
| 4 | `https://amlldb.bikonoo.com/lyrics/ncm-lyrics/<id>.ttml` | 直连路径（免 302） |

```
文件存在  → HTTP 200（无重定向），body 以 <tt 开头
文件不存在 → HTTP 302 → /lyrics/ncm-lyrics/<id> → 最终 404，body = 「歌词不存在」
```

**判据必须是「跟随重定向后的最终状态码 == 200 且 body 以 `<tt` 开头」**，只看 3xx 会把「不存在」当「存在」。
某面镜子一旦给出确定性答复（404，或 200 但不是 TTML）**立刻收工、不再试其它镜子** —— 四个镜像实测内容逐字节一致，
继续试不可能变出歌词，只会白烧流量。404 是常态（本机 90%+ 收藏都没有），走 `Log.d`；只有网络异常才 `Log.w`。

### TTML DB 真实规模（官方 tarball 完整枚举，非抽样）

| 目录 | 文件数 | 说明 |
|---|---|---|
| `ncm-lyrics/` | **20,326** | 按**ncm ID** 命名的派生产物，每 ID 最多 6 种格式 |
| `qq-lyrics/` | 16,833 | qm ID |
| `am-lyrics/` | 14,906 | Apple Music ID |
| `spotify-lyrics/` | 14,121 | Spotify ID |
| `raw-lyrics/` | 3,291 | 社区原始投稿（文件名 = 时间戳-作者ID-哈希.ttml） |
| **全仓库** | **69,572** | |

- `ncm-lyrics/` 的 20,326 个文件里，**`.ttml` 只有 3,544 个唯一数字 ID**（其余是同一批 ID 的
  `.yrc` / `.qrc` / `.lys` / `.lrc` / `.eslrc` 派生物）
  ⇒ **能按 ncm `songId` 直接拉取的 TTML 上限 = 3,544 首**。
- ⚠️ **别误读 `/api/lyrics-status` 的 `ttmlFilesCount: 3288`**：那个计数**只数了 `raw-lyrics/` 那一层**
  （3,291 条投稿），**不是** `ncm-lyrics/` 的规模，更不是全库规模 —— 这两个数必须分开说，否则覆盖率会算错一个数量级。

### 解析器：自研纯 Kotlin 手写扫描器（零依赖、无 Android 框架依赖、JVM 可单测）

`TtmlParser.parse` 是冻结契约（调用方只认它），实现委托给 `TtmlScanner`（约 600 行，内部可独立演进）。

| | 手写扫描器（选定） | `android.util.Xml` / `XmlPullParser` | kxml2（testImplementation） |
|---|---|---|---|
| 新依赖 | **零** | 零 | +1（仅测试） |
| JVM 可单测 | ✅ | ❌（框架 API 在单测里是抛异常的桩） | ✅ |
| **测的与线上跑的是同一份实现** | ✅ | — | ❌（单测跑 kxml2、线上跑 Android） |
| API 24 兼容风险 | 无 | 无 | 无 |

- **为什么不选 `android.util.Xml`**：它是 Android 框架 API，JVM 单测里只是桩
  （`unitTests.isReturnDefaultValues = true` 只让它返回默认值，并不会真解析）⇒「线上能跑、单测跑不了」。
- **为什么不选 kxml2**：单测验的是 kxml2 的行为、线上跑的是 Android 的 XmlPullParser，**两份不同实现**；
  对本版专门要求的容错输入（截断、多余结束标签、裸 `&`）处理并不一致，保真度反而更差。
- 手写扫描器让**同一份实现既被测又上线**；TTML 用到的 XML 子集很小（元素 / 属性 / 文本 / CDATA / 注释 / 实体）。
- 契约：**任何输入都返回 `null` 而绝不抛异常**；入口有长度（4 MiB）/ 元素数（10 万）/ 嵌套深度（24）三重上限；
  单个 `<p>` 坏掉只跳过这一行。时间格式 `MM:SS.fff` / `HH:MM:SS.fff` / offset-time，另外**额外**接受裸数字（按秒）。

### 两条硬不变量（本版最容易踩的坑）

**① TTML 的 `LrcLine.words` 必须「文本顺序 == 时间顺序」。**
冻结的 `SweepTrack.build` 会做 `words.sortedWith(compareBy({ it.startMs }, { it.charStart }))`
（[SweepTrack.kt:244](app/src/main/java/com/takahashirinta/ncrust/lyric/SweepTrack.kt#L244)）——
只要有一个词的开始时间早于文本序在它前面的词，排序就会把它的字符区间搬到前面，逐字高亮表现为**倒着扫**。
现实来源只有 `ttm:role="x-bg"`（背景人声）：子 span 按文本顺序进本行，但背景人声的时间经常排在文本末尾却先唱
（实测《孤勇者》L51：主唱唱到 03:44.938，文本序最后的背景词是 03:42.019）。解析器用**单调过滤把时间倒退的词丢弃**
（[TtmlScanner.kt:191-197](app/src/main/java/com/takahashirinta/ncrust/lyric/TtmlScanner.kt#L191-L197)），
**行文本仍完整保留**，只是那几个字不给逐字高亮 —— 宁可少高亮几个字，也不给一次倒扫。
`x-bg` 本版**不单独建模**，背景人声与主唱混在同一行（有意的取舍，收益是背景人声也能逐字高亮）。

**② `PlayerViewModel` 里供 `fetchLyrics` 使用的字段必须声明在 `init` 块之前。**
Kotlin 属性初始化与 `init` 块按**声明顺序**执行，而 `init` 里的
`viewModelScope.launch { fetchLyrics(...) }` 在 `Main.immediate` 下**可能在构造期就同步执行**
（构造本身就在主线程）—— 声明在 `init` 之后的**引用类型**字段此刻还是 `null` ⇒ **NPE 崩溃**。
v1.9.0 首次真机安装就是这么崩的：
`NullPointerException: Attempt to invoke virtual method 'long ...LyricRequestGate.begin()' on a null object reference`
at `PlayerViewModel.<init>` → `fetchLyrics`。老代码里 `lyricsFetchingSongId` 是 `Long`
（读成 0 只是判等失真、不崩），所以这个坑一直潜伏；v1.9.0 新增的 `lyricReqGate` 与 `STALE_NETEASE_LYRICS`
是**对象引用**，一读就炸。**单测抓不到**（项目没有 Robolectric，构造 AndroidViewModel 需要真 Application），
**只有真机冷启能暴露**。修法：把 `lyricsFetchingSongId` / `lyricReqGate` / `STALE_NETEASE_LYRICS`
一起挪到 `init` 之前并写明注释
（[PlayerViewModel.kt:240-269](app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt#L240-L269)），
**新增供 `fetchLyrics` 使用的字段时一律放这一段**。

### 实现要点（缓存 / 竞态 / 两相落地）

| 项 | 做法 |
|---|---|
| 缓存 | 复用既有 `ncrust_lyrics_cache`（≤200 条 LRU）；`CachedLyrics` 新增 `ttml: String?` + `ttmlAt: Long = 0`，**可空 + 有默认值**（老缓存 Gson 走 Unsafe，缺字段 = 没 TTML，不崩也不误判新鲜）；**TTL 7 天**，另留 `getTtmlStale` 给离线兜底 |
| 暂存 | 预取下一首时往往还没有 LRC 正式条目；**绝不为了存 TTML 建 `lrc=""` 的正式条目**（那是「确无歌词」的权威标记），先落在 `ttml:<songId>` 暂存键，写正式条目时合并 |
| 预取 | 复用既有预载时机预取**下一首**（`AmllTtmlClient.prefetch`，单独 `launch`，不挤占取链）；已新鲜则幂等跳过，同一首一轮播放被调多次也只打一次网络 |
| 竞态 | `LyricRequestGate`（`AtomicLong`）：每次 `fetchLyrics` 先 `begin()` 领号，每个挂起点之后校验 `isCurrent(seq)`，不匹配整包丢弃。**去重（同歌在途）与闸门（旧歌响应盖新歌）是两件事**，且去重必须排在 `begin()` **之前** |
| 两相落地 | 第一相只用 ncm 候选（YRC / LRC）决策、**选中立刻显示**（时机与 v1.8.1 一致）；第二相才拉 TTML，连同第一相候选重新 `pick`，TTML 赢了才替换。**绝不把 TTML 放在第一相之前** —— 最坏 4 面镜子 × 5s 连接会把「本来有歌词」变成「一直转圈」 |
| 译文 | TTML 的 `x-translation` 是独立行级轨道，按 `timeMs` 与正文行精确配对，对不上的丢弃；**TTML 译文与 ncm `tlyric` 不会同时显示**（TTML 赢了就只有 TTML 的译文） |

### 设置项（`ncrust_settings`）

| 键 | 默认 | 说明 |
|---|---|---|
| `lyrics_ttml_enabled` | **开** | 总开关。关掉后一个 TTML 请求都不发（`order` 里根本没有 TTML） |
| `lyrics_ttml_first` | **开** | 「TTML 优先」（`TTML → YRC → LRC`）；关掉则是 `YRC → TTML → LRC` |

- 8 语言文案已补齐（`lyricsTtmlEnabledLabel` / `lyricsTtmlFirstLabel`）；两个开关切换后都会 `retryLyrics()` 重拉当前歌；
- **「TTML 优先」在总开关关闭时整行不挂载**（不是 `alpha=0`）—— 见「Compose 触摸陷阱」第 1 条：
  `alpha=0` 的节点照样参与命中测试，会在播放器死带里变成一个看不见的开关。

### 测试与真机实测

**JVM 单测**：`./gradlew test --offline` 实测 **250 个全绿**（`testDebugUnitTest`：250 tests / 0 failures / 0 errors / 0 skipped）。
v1.9.0 新增 **66 个**：

| 测试 | 条数 | 覆盖 |
|---|---|---|
| `TtmlScannerTest` | 14 | 真实 AMLL 样本 + 畸形输入（截断 / 多余结束标签 / 裸 `&` / CDATA / 实体） |
| `TtmlWordOrderTest` | 8 | **文本顺序 == 时间顺序**不变量、x-bg 时间倒退丢弃、行文本完整保留 |
| `AmllTtmlClientTest` | 15 | 镜像回退、404 即收工、非 TTML 的 200 拒绝、注入假 fetcher（**不发真实网络请求**） |
| `LyricsCacheModelTest` | 9 | TTML TTL / 纯函数 `isTtmlFresh` / 老缓存缺字段 |
| `LyricSourceChainTest` | 10 | `order` 三态、`pick` 跨源优先逐字、LRC 恒兜底 |
| `LyricRequestGateTest` | 5 | 连续 `begin` 只有最后一个有效 / `invalidate` / 并发唯一递增 |
| `LyricsSourcePrefsTest` | 5 | 两个开关的读写与坏值回落 |

> 任务书写「249 个、新增 65 个」是旧数；本次实跑的权威结果是 **250 / 新增 66**（任务书清单里 7 个文件相加就是 66）。

**真机实测（PCL110 / Android 16 / API 36）**：

| 项 | 结果 |
|---|---|
| 覆盖安装 | **22 → 23 成功**（未卸载；`dumpsys package` 实测 `versionCode=23`，`lastUpdateTime` 2026-09-23 22:16） |
| 签名指纹 | 与 v1.8.1 release 一致（APK 实测 SHA-256 `e75af3ffbcf76a36a567188d88d132adf3c7484c53c20a3a083cb1d222025511`） |
| yrc-less 的歌 | `36990266`（Faded）走 TTML，并渲染出**逐字 + 中文翻译**（该 TTML 样本实测含 54 处 `x-translation`） |
| 快速切歌 | 10 连切无崩溃、无串词 |
| 没有 TTML 的歌 | 404 **静默回退**到 YRC / LRC，只打 `Log.d`，无 Toast、无错误 |
| S6（API 24） | **未跑 TTML 专项**（`dumpsys package` 实测该机仍是 `versionCode=22` / `1.8.1-gpl`） |

### v1.9.0 的已知未验证项（如实）

1. **S6（API 24）未跑 TTML 专项**：TTML 路径的 API 24 兼容性没有真机验证（扫描器是纯 Kotlin、无框架依赖，风险低，但**没跑过就是没跑过**）。
2. **TTML 逐字渐变的帧率未与 YRC 对比**：渲染层零改动意味着它走同一条 `SweepTrack` 路径，但 TTML 的词密度普遍高于 yrc，帧率没有实测对照。
3. **离线飞行模式未实测**：`getTtmlStale` 的离线兜底路径只有单测覆盖，没有真机飞行模式验证。
4. **`x-bg` 背景人声不单独建模**：与主唱混在同一行，时间倒退的词被丢弃（有意取舍，不是 bug）。
5. **TTML 译文与 ncm `tlyric` 不会同时显示**：TTML 赢了就只有 TTML 的 `x-translation`；双源译文合并没有实现。
6. 未做「按歌名 / 歌手模糊匹配」的兜底（有意不做，不是遗漏）。


---

## v1.9.1 新增（本 fork · v1.9.0 的 hotfix）

| | |
|---|---|
| versionName | `1.9.1-gpl` |
| versionCode | **24** |
| 改动面 | **只有歌词镜像的回退判定 + 一行可观测性日志**。渲染层 / 播放路径 / 缓存格式 / 设置项一律未动 |

### 修 · 一个镜像的 404 会让这首歌静默丢掉 TTML

v1.9.0 的 `AmllTtmlClient` 对**任何**镜像的 404 都判「这首歌没有 TTML」并立刻 `return null`，
不再试后面的镜像。这个判定只在**权威镜像**上成立 —— 第 3 面 jsdelivr 有自己的**单包 50 MB 上限**，
对**确实存在**的文件也会返回 `403 Package size exceeded the configured limit of 50 MB`。

最坏路径：`amlldb` 抖动 → `github-raw` 抖动 → `jsdelivr` 对存在文件报 403/404 → 判「无此歌词」收工
→ **第 4 面 `amlldb-alt` 永远没机会被请求**，这首歌静默退回整行 LRC。

**这是本版独立验证者发现的，不是实现者自测出来的**，值得连教训一起记下来：

> Phase 0 时我抽了 10 首**热门**歌对 4 面镜像各拉一次、sha256 全部一致，据此写下
> 「备用镜像与主镜像内容逐字节一致 ⇒ 继续试不可能变出歌词」。
> 验证者按**可用性**维度重测：抽样 60 个确认存在于 DB 的 ID，有 **4 个**出现
> 「其余三面均 200、只有 jsdelivr 拿不到」（3× 403 超限 + 1× 网络异常）。
> **抽样偏差出现在「热度」这个维度上** —— 热门歌四面齐全，恰好掩盖了 jsdelivr 的超限行为。
> 教训：**内容一致 ≠ 可用性一致**；验证镜像要多面**同时**成功才算等价。

复现脚本：仓库外 `tools/verify-mirror-equivalence.py`（可重跑，60 个 ID 可稳定复现 4 个）。

### 改法与镜像表契约（**新增镜像时必须遵守**）

1. `Mirror` 新增 `authoritative` 字段：**只有直出官方仓库的三面**（`amlldb` / `amlldb-alt` /
   `github-raw`）的 404 才可判「这首歌没有 TTML」；
2. **非权威镜像**（`jsdelivr`）的 404 / 403 / 200-非TTML 一律当「这面镜子服务不了这个文件」，
   **继续试下一面**（即使它已经是最后一面 —— 语义写对，避免以后在它后面加镜像时重新踩坑）；
3. **顺序**：jsdelivr 排在**最后**。常见情形（有 / 没有 TTML）在第 1 面就终结，
   它是否抽风不影响主路径；作为兜底仍有价值（实测在弱网下救回过命中）。
4. 测试把顺序与权威位**当契约锁住**（`镜像表约定——前 3 面权威、jsdelivr 非权威且排最后`），
   并逐面验证「权威镜像的 404 仍然立即收工」这个**省流量的正确行为没被修坏**。

### 顺带补的可观测性（原 v1.9.0 未验证项 U3）

v1.9.0 没有任何一行日志说明「最终选了哪个源」—— 验证者因此无法验证优先级开关是否真的生效。
现在每个 `fetchLyrics` 会打：

```
I PlayerViewModel: 歌词源 songId=X phase=1 picked=KIND lines=N words=B
I PlayerViewModel: 歌词源 songId=X phase=2 picked=TTML ... (覆盖了 phase=1)
I PlayerViewModel: 歌词源 songId=X phase=2 picked=null (TTML 未胜出，保留 phase=1)
```

第三行是必要的：**拉了 TTML 但没赢**（没有逐字 span、或被 YRC 压过）必须能归因，
否则「用户开了 TTML 却仍是整行」在线上无从判断。

### v1.9.1 的测试与实测

| 项 | 结果 |
|---|---|
| JVM 单测 | **255 个全绿**（v1.9.0 = 250，本版 +5；`AmllTtmlClientTest` 15 → 20） |
| 新增用例 | 5 条全部是本次缺陷的回归，最关键一条复刻最坏路径并断言「四面都被请求过」 |
| PCL110 覆盖安装 | v1.9.0(23) → v1.9.1(24) **Success**；签名 `e75af3ff…5511` 未变；权限集与 v1.9.0 **逐条一致** |
| 真机日志 | `歌词源 songId=22765922 phase=1 picked=YRC lines=3 words=false` + `无此歌词 … mirror=amlldb code=404`（该曲确无 TTML；同时印证权威镜像 404 后不再空跑其余三面） |

### v1.9.1 仍未验证

- **优先级开关两种模式的端到端对照**：日志与机制已在真机验证会输出，但「同一首同时有 YRC 与 TTML 的歌，
  分别切 `lyrics_ttml_first` true/false 看 `picked=` 变化」这一步**本 session 未取到证据**
  （需要 `316100 雨爱` 这类歌；设备 UI 自动化切歌不稳定，prefs 注入又被 App 启动时覆盖）。
  复现方法：用 `316100` 播放并分别切两种模式，看 `歌词源 … picked=` 那行。
- v1.9.0 其余未验证项（S6 TTML 视觉确认、帧率对比、离线飞行模式、双源译文合并等）**均未因本版改变**。

## v1.9.2 新增（本 fork · 修 v1.9.0 的分轨缺陷）

### 症状与根因

v1.9.0 的 `PlayerViewModel.applyTtmlLyrics()` 在 TTML 胜出时**整体覆盖**译文轨：

`@kotlin
translatedLyrics.value = doc.translations.filter { it.timeMs in lineTimes }
`@

TTML 那份没有 `x-translation` 时这个列表就是空的 ⇒ 第一相刚从 ncm 拿到的 `tlyric` 被**整轨清空**。
调研实测（667 首候选池 + 42 份 TTML 缓存）：该判据命中 **1 首** = `22704409 DAY BY DAY`
（TTML 79 行逐字、0 翻译；ncm tlyric 79 行）—— 与 `RESEARCH-ttml-vs-netease.md` §3.4 一致。

### 音译轨的真相：v1.9.0/v1.9.1 **从来没有渲染过音译**

| 检查 | v1.9.1 的实际情况 |
|---|---|
| `TtmlDoc.romans` 的消费者 | **只有单测**（TtmlScannerTest / TtmlWordOrderTest） |
| `PlayerViewModel` 里的音译状态 | 不存在（只有 `translatedLyrics`） |
| `LyricResponse` 模型字段 | `lrc` / `tlyric` / `yrc` —— **没有 `romalrc`** |
| `LyricsView` / `NcrustLyricsPanel` | 只有 `translation` 一个副文本槽 |

所以调研报告「TTML 胜出会把音译整轨清空」在**用户可见层面并不成立** —— 它是**数据层/潜在**缺陷。
本版把音译轨做成完整的数据层轨道（取数 + 缓存 + 合并 + 来源标记 + 单测 + 日志），
**但 UI 仍然不显示音译**：要显示必须给 `LyricsView` / `NcrustLyricsPanel` 加副文本槽，
而「渲染层 diff 必须为空」是本版硬约束。渲染接线留给解禁渲染层的版本（届时 `LyricsView` 只多收一个参数）。

### 偏离记录：任务书要求「整轨二选一」，实现改为「逐行合并」

| | |
|---|---|
| 任务书原文 | TTML 与 ncm 两条副文本轨按「**整轨二选一**」取用（TTML 有就用 TTML 那一整轨，没有就整轨用 ncm） |
| 实际实现 | **逐行合并**：TTML 里能落到主轨时间轴上的行原样保留；主轨里没有任何 TTML 副文本的行算缺口，缺口按文本/行序逐行回退到 ncm |
| 偏离理由 | 实测 `1959528822` 紫荆花盛开：TTML 有 **16 行 `x-roman`**，ncm 有 **41 行 `romalrc`**，且 TTML 那 16 行与 ncm**逐字相同**（是子集）。轨级规则取 16 行 ⇒ **丢掉 13 行明明能对上的音译** |
| 偏离代价 | 合并从「选一条轨」变成「逐行配对」，需要 `YrcAligner.lcsPairs` 的 LCS 文本配对（复用 v1.6.0 逐字对齐那一份实现），并新增 `LyricTrackSource.MIXED` 这个来源标记 |
| 影响面 | **严格不劣**：覆盖满时一行不多一行不少（单测直接与 v1.9.0 的老表达式逐行比对）；只有缺口才补，补不上就丢这一行，不猜不过桥 |
| 审计证据 | `LyricTrackMergeTest` 13 例（4 首真实样本夹具 + 合成用例）；期望值另由仓库外 `tools/expected-merge.py` 独立复算 |
| 已在何处声明 | 本节 + v1.9.2 release notes 的「与任务书的偏离」小节；v1.9.3 起凡偏离任务书都在 AGENTS.md 留一条同款记录 |
### 分轨合并规则（`lyric/LyricTrackMerge.kt`，纯函数）

1. **TTML 行优先**：能落到主轨时间轴上的行原样保留（顺序、重复行、内容都不动）⇒
   「TTML 有译文」的歌输出与 v1.9.0 **逐行相同**（单测直接与老表达式比对）；
2. **缺口按文本/行序回退**：主轨里没有任何 TTML 副文本的行才算缺口；用 `YrcAligner.lcsPairs`
   （归一化去空白 + LCS 保序，与 v1.6.0 逐字对齐**同一份实现**）在两源**主轨文本**上配对，
   配对成功才补一行，并把时间戳改写成**主轨那一行**的（副文本轨自己的时间戳一律不用）；
3. **逐行丢弃**：文本对不上、或 ncm 那一行没有内容 ⇒ 这一行没有副文本，不猜、不过桥、不硬塞。

**为什么是逐行而不是「整轨二选一」**：轨级规则在实测数据上会丢东西 —— `1959528822 紫荆花盛开`
的 TTML 有 **16 行 `x-roman`**、ncm 有 **41 行 romalrc**（且 TTML 那 16 行文本与 ncm**逐字相同**，
是子集），轨级规则取 16 行、丢掉 13 行能对上的。逐行合并严格不劣：覆盖满时一行不多一行不少。

> ⚠️ **数据勘误**：`RESEARCH-ttml-vs-netease.md` R311 写「紫荆花盛开 TTML 只有 1 处 `x-roman`」是错的
> （把「池里 1/100 的样本带 x-roman」误抄成单曲数字）。实测该文件有 **16 处**，上一轮 `analysis.json`
> 也记 `x_roman_n: 16`。这个数字直接决定合并规则，所以必须纠正。

### 关键实测（PCL110 真机 + 缓存复算）

| songId | 主轨 | 译文轨 | 音译轨 | 说明 |
|---|---|---|---|---|
| 22704409 DAY BY DAY | TTML/79 words=true | **NETEASE/64** | **NETEASE/52** | v1.9.0 是 **0 行**；直接修复 |
| 1959528822 紫荆花盛开 | TTML/57 words=true | none/0 | TTML/16 → 修缓存后 **MIXED/29** | ncm tlyric 本就为 0 |
| 36990266 Faded | TTML/54 | TTML/54 | none/0 | 两源译文 54/54 相同，不重复 |
| 16686599 Numb | TTML/47 | TTML/49 | none/0 | 两源行数不等（47/35）不崩 |
| 2645500113 跳楼机 | TTML/57 | none/0 | none/0 | 两源都无译文 ⇒ **不产生空轨** |

42 份 TTML 缓存的统计：TTML 无译文但有 ncm tlyric = **1 首**（22704409）；两源都有译文 = 21 首
（其中 8 首 TTML 覆盖行数少于 ncm）；TTML 有可用音译 = 4 首；**ncm 有 romalrc 而 TTML 无音译 = 1 首
（22704409，53 行）** —— 这才是音译回退最干净的验证样本。

### 缓存：新字段与**升级迁移**（本版真机发现的第二个 bug）

`CachedLyrics` 新增 `romalrc` / `translationSource` / `romanSource`，一律**可空 + 有默认值**
（Gson 走 Unsafe，老缓存缺 key 必须是 null 而不是崩）。后两个是**观测字段**（枚举名，见
`LyricTrackSource.cacheTag`），记录两条轨上一次实际展示用的源，**不参与任何决策**。

真机发现的坑：**LRC 条目没有 TTL**（只有 200 条的 LRU 上限），所以 v1.9.1 及更早写下的条目
`romalrc` 永远是 null。PCL110 实测 **64 条里 63 条是老条目**，`1959528822` 因此只拿到 TTML 的 16 行音译、
ncm 那 41 行 romalrc 明明在服务端却用不上。修法：`LyricsCache.needsRomalrcRefetch(entry)`
（纯函数，判「字段缺失」而不是「字段为空」—— 空串是「确实没有音译」的权威结论）为 true 的条目
**按 miss 处理重取一次**，补完即恢复缓存命中；**网络失败时回落到这份老缓存**（`degraded()`），
不让离线用户从「有歌词」变成「没歌词」。代价：升级后每首老歌第一次播放多一次歌词请求（一次性、自愈）。

### 与序列号闸门 / 渲染层的约束

- ncm 三条轨（主轨 + tlyric + romalrc）在**同一次** `withContext(Dispatchers.Default)` 里解析完，
  第一相与第二相共用 ⇒ 合并只发生在**一次 fetch 内部**，不存在「A 歌的译文配 B 歌的主轨」；
- 合并结果在 `withContext` 之后**复查** `lyricReqGate.isCurrent(seq)` 与 `currentSongId` 再落地；
- 产出的每一行 `timeMs` 都取自主轨 ⇒ `LyricsView` 的 `translatedLyrics.associateBy { timeMs }`
  一行都不用改（`SweepTrack` / `LyricsView` / `NcrustLyricsPanel` 的 diff 为空）；
- **不新增依赖、不新增权限、不新增网络请求**：`romalrc` 本来就在 `/api/song/lyric` 的响应里
  （实测 `rv=0` 与 `rv=-1` 对同一首歌返回**逐字节相同**的 body，4/4 首 sha256 一致），
  所以本版**没动请求参数**，只是把此前没解析的字段接上。

### 附带发现：AMLL DB 的 `.yrc` **不能**替代 ncm 的 yrc

AMLL DB 的 `ncm-lyrics/` 目录除了 TTML 还存了每首歌的 yrc/qrc/lys/lrc/eslrc sidecar，容易被当成
「yrc 服务端开关抖动时的补充路径」。调研已验证**不可替代**（`PHASE0-REPORT-v1.9.0.md` §10）：
21 首 API-yrc 里 18 首 DB 无 yrc 文件、3 首有但**逐字节不同**，且 DB 的 yrc 只存在于有 TTML 的曲目
（3356 ⊂ 3544）。**不要**把这批 sidecar 当成 yrc 的替代源接进来。

### 各语种覆盖现实（**只写在文档里，不做 UI 提示、不加弹窗、不加设置项**）

数据源：`RESEARCH-ttml-vs-netease.md` §2.2 / §3.5（100 首收藏样本 + 667 首池）。

| 语种 | ncm YRC | 逐字并集（YRC ∪ TTML） | tlyric |
|---|---|---|---|
| 中文 | 16/30 = **53.3%** | 66.7% | 池内 232 首只有 4 首（2%） |
| 英文 | 24/30 = **80.0%** | 83.3% | 池内 274 首 248 首（91%） |
| 日文 | 1/20 = **5.0%** | 5.0% | 90% |
| 韩文 | **0/10 = 0%** | 0% | 100% |

⇒ TTML 的定位是「**英文歌的逐字 + 翻译源**」，覆盖率上限只有 10.2%（与热度强相关），
**不是覆盖率主力，也不能宣传成「大幅提升逐字覆盖率」**（真实增量 +5.1pp）。
日韩用户拿不到逐字（TTML 在日韩基本 0 命中、YRC 也是 0–5%），但译文与音译接近 100% ——
译文质量的天花板在 ncm。**不做按语言分流**（§6.1 数据不支持：语言维度相关的是覆盖率，不是「哪个源更好」），
**不做语言检测**（脚本判定本身就会误判），**不做同名异版模糊匹配**（会导致逐字错位）。

### v1.9.2 的测试与实测

| 项 | 结果 |
|---|---|
| JVM 单测 | **276 个全绿**（v1.9.1 = 255，本版 +21：`LyricTrackMergeTest` 13 + `LyricsCacheModelTest` 16−9+8） |
| 合并单测的样本 | 4 首真实样本夹具（`app/src/test/resources/lyric-tracks/`，由 `tools/gen-merge-fixtures.py` 从调研原始响应生成）；期望值另由 `tools/expected-merge.py` 独立复算 |
| 合并单测的关键断言 | 22704409 译文 64 / 音译 52；1959528822 音译 29 = TTML 16 + ncm 补 13；Faded 与 v1.9.0 逐行相同；Numb 行数不等不崩 |
| PCL110 真机 | `轨道 translation=NETEASE/64 roman=NETEASE/52`（22704409）；翻译渲染截图复核（韩文逐字行下方出现中文译文） |
| 渲染层 | `SweepTrack` / `LyricsView` / `NcrustLyricsPanel` `git diff` **为空** |

### v1.9.2 仍未验证

- **音译轨没有 UI**（见上）：本版的音译回退只能在单测与日志层验证，**没有任何截图能证明它显示出来**；
- **P1 时间轴对拍未做**：环境无播放/录音能力（`RESEARCH` §1.4 已声明），只做了跨源对照，
  **不能证明 TTML 与 ncm 谁更准**；
- **1959528822 的 MIXED/29 只在单测夹具上复算过**：真机第一次跑的是 v1.9.1 老缓存（得到 TTML/16），
  修掉缓存迁移后需要再看一次真机日志才算端到端确认；
- **S6（API 24）未跑本版**：本版只装了 PCL110；
- v1.9.0/v1.9.1 的其余未验证项均未因本版改变。

## v1.9.3 新增（本 fork · 音译显示，渲染层受控解禁）

### 做了什么

v1.9.2 把音译轨做进了数据层（TTML `x-roman` + ncm `romalrc`，缺口按文本逐行回退），
但当时的硬约束是「渲染层 diff 必须为空」，于是数据只躺在缓存与日志里。本版**解禁音译行渲染**：
原文 → 译文 → 音译，三行同在一个 LazyColumn item 内，跟着主行一起滚动 / 缩放 / 变色。

| 项 | 决策 |
|---|---|
| 显示位置 | **原文下方小字，不替换原文**（PHASE0-REPORT-v1.9.3.md §2 逐条核对：逐字高亮的几何来自**原文那一个 BasicText** 的 TextLayoutResult，替换原文等于让 v1.5.2 的成果失去几何来源） |
| 副文本顺序 | 原文 → **译文 → 音译**（与 ncm 官方客户端三层顺序一致） |
| 开关 | `lyrics_romanization`，**默认关**：默认关时副文本槽条件挂载、连 Map 都不建，渲染路径与 v1.9.2 逐字节一致 |
| 字号 | 音译 18sp / 行高 24sp（译文 20/26、原文 32/42），三者同乘 v1.5.1 的 fontScale ⇒ A- / A+ 一起缩放 |
| 可点性 | 音译行**不新增任何 pointerInput**：它落在主行 Box 的 detectTapGestures 内，点它 == 点主行 |
| 不显示的三类行 | 开关关 / 纯空白 / 与原文 trim 后逐字相同（英文歌的 `romalrc` 有时就是原文）—— 规则抽在 `lyric/LyricSubtitleText.kt`，JVM 单测覆盖 |

### 渲染层解禁的**范围**（审计用）

- **改了**：`NcrustLyricsPanel.kt`（音译槽 + `NcrustLyricLine.romanization` + a11y 走纯函数）、`LyricsView.kt`（两个新参数 + 按 timeMs 配对）、`PlayerCard.kt`、`PlayerViewModel.kt`、`UserScreen.kt`、`ui/i18n/`（8 语言）、`LyricsDisplayPrefs.kt`；
- **一个字节都没改**：`lyric/SweepTrack.kt`（v1.5.2 冻结的渐变算法）、`LyricLineBody` 的 karaoke / drawSweep / drawHardCut 分支、`LyricTrackMerge.kt`、`LyricsCache.kt`、`player/MediaDisplayLines.kt`（v1.8.0 · T5 状态栏歌词）。

**为什么音译行不影响 SweepTrack 的行高计算**：本应用的歌词定位从头到尾是「时间戳二分 + 视口比例滚动」
（`currentLineIndex` 只吃 timestamps 数组、自动滚动按 viewportSize × 0.36 算偏移、渐隐高度按面板视口算），
没有任何一处按行高反推位置；音译是原文节点的**兄弟节点**，不进那份 layout。因此本版不需要碰 SweepTrack，
红线（若必须改 SweepTrack 的行高计算就先停下报告）没有触发。

### 数据层与缓存：本版没有新字段

音译数据来自 v1.9.2 就绪的 `PlayerViewModel.romanizedLyrics`，渲染层只按 `timeMs` 配对取用、**不重新合并**。
开关是纯显示偏好（存 `ncrust_settings`，不进歌词缓存）⇒ **没有新的缓存字段、没有新的迁移逻辑** ——
这正是下面那条「加字段 = 加迁移逻辑 = 加单测」规则的正向用法（能不加就不加）。

### v1.9.3 的测试与实测（2026-09-24）

| 项 | 结果 |
|---|---|
| JVM 单测 | **287 个全绿**（v1.9.2 = 276，本版 +11：`LyricSubtitleTextTest`，含 4 首真实夹具的端到端断言） |
| release 覆盖升级 | PCL110：25 → 26 `adb install -r` Success，签名 `e75af3ff…5511` 未变 |
| debug 覆盖升级 | S6：25 → 26 Success（**补上 v1.9.2 的遗留缺口 D1**：S6 装的是 debug 包，所以 release 覆盖必然被签名拒；改用 debug 包即闭环） |
| 1959528822 紫荆花盛开 | 粤拼 29 行，PCL110 + S6 截图复核（`zi ging fa piu yoeng` 等） |
| 22704409 DAY BY DAY | 韩文罗马音 52 行 + 译文 64 行同屏，顺序为 原文 / 译文 / 音译 |
| 36990266 Faded（无音译） | 开关开着也不多一行、不产生空行（日志 `roman=none/0`） |
| 默认关 | 同一首歌同一位置无音译行（与开启态截图对照） |
| A- / A+ | S6 实测音译随主歌词同比放大 |
| 逐字渐变回归 | S6 真机播放 0:28：当前行绿色渐变扫过、音译行同色跟随（SweepTrack 未改，行为一致） |
| 帧时间 A/B（S6，滚动歌词，各 120 帧） | **无劣化**：total p50 14.9 vs 15.2 ms、p90 19.0 vs 19.1 ms、p99 22.4 vs 28.9 ms |

### v1.9.3 仍未验证（如实）

- **大屏 / 横屏**：PCL110 测试中途 USB 掉线未再回连，横屏大屏模式的音译显示没有真机证据；
- **状态栏歌词（v1.8.0 · T5）**：`MediaDisplayLines` 未改，但本轮没重跑；
- **逐字三档**只跑了「自动」档，高级 / 兼容两档未逐一切换（切换逻辑本版未改，与音译槽无交互）；
- **TalkBack** 未实机验证（a11y 文本有单测断言：音译关闭时与 v1.9.2 表达式逐字节一致）；
- **带声调符号的拼音样本未取到**：本轮音译全是 ASCII（粤拼数字声调 / 韩文罗马音 / 英文），
  Latin Extended-A（ā á ǎ à）的系统字体覆盖未在真机验证；遇到豆腐块的退路是把声调降级为数字；
- **PCL110 的帧时间对照没跑完**（掉线前只有一组无效样本）⇒ 性能结论只来自 S6 —— 低端机是更严的判据；
- **YRC 服务端间歇开关（v1.9.2 遗留 D2）**本轮未复测；音译与 yrc 是两条独立轨，不受影响。

### 与任务书的偏离

- 任务书建议「选项 1（原文下方小字）」—— **采纳**；
- **任务书未要求的一条显示规则**：「音译与原文 trim 后逐字相同则不显示」是自主加的（英文歌的 `romalrc` 有时等于原文，
  显示两遍是噪音）。对本轮 3 首真实样本零影响（音译都与原文不同）；
- 任务书要求一个 `chore(tools)` commit，但 `tools/` 按仓库惯例不入 git（`.gitignore` 里有 `.sh`）⇒
  脚本留在仓库外，「动版本号前必须跑它」的规则写进 Versioning 一节，由该 commit 承载。

## v2.0.0 新增（本 fork · T1 用户报告的三个 bug / T2 播放时禁止熄屏 / T4 动态字号）

**版本**：`2.0.0-gpl` / versionCode **27**（冷启动先跑 `tools/next-version.sh`，三源交叉校验的最大值都是 26）。
分支 `feature/v2.0.0-offline-and-fixes`。四个方向：T1 用户报告的三个 bug · T2 播放时禁止熄屏 ·
T3 离线缓存 Phase 2 · T4 动态字号（实验性）。

### T1-A · 大屏模式：auto-rotate 关时**保持横屏**（偏离 v1.8.0 的 P1 契约）

**用户报告与澄清**：原始描述「横屏模式锁了之后手机竖回来横屏也自动复原了」有两个相反方向，
用户澄清为「我希望**保持横屏**的时候它**自动切换竖屏模式**了（而且**没有开自动旋转**）」。
这条澄清直接排除了「手动退出又被自动拽回大屏」那一种：`shouldAutoEnterBigScreen` 要求
`autoRotate == true`，关着时**不可能**自动进大屏。

**真机取证（S6，未改任何系统设置）**：`ncrust_settings.xml` 里 `auto_rotate=false`，
logcat 有 `big screen: relaxed to SENSOR (device landscape 298°)` —— 应用**确实**把方向放宽成了 SENSOR。

**根因**：`SENSOR` 跟随传感器（不理会系统旋转锁），于是手机稍微一歪（躺床上 / 放支架 / 手腕转动）
就翻回竖屏 → 配置变竖屏 → `shouldExitOnConfiguration` 退出大屏 → auto-rotate 关又把它锁成 PORTRAIT
⇒ **把手机转回横向也回不来**。

**修法（`orientationFor` 第 1 档）**：方向策略按用户意图的来源分流 ——

| 自动旋转 | 语义 | 大屏里的方向 | 退出路径 |
|---|---|---|---|
| 开 | 「跟随手机方向」 | `SENSOR`（双向，行为不变） | 转回竖屏 / ⤢ / 返回键 |
| 关 | 「我要横屏播放器」 | **`SENSOR_LANDSCAPE`（保持横屏）** | ⤢ / 返回键 |

两处配套（缺一即失效）：

1. `enterBigScreenMode` 在 auto-rotate 关时**不启动**物理朝向门控 —— 那道门控会在设备横向时
   直接把 `requestedOrientation` 写成 `SENSOR`（绕过 `applyOrientationPolicy`），正好抵消本条修复；
2. `LaunchedEffect(autoRotate)` 里**对称收敛**门控：大屏内「关 → 开」补启门控
   （否则 `bigScreenOrientationRelaxed` 永远停在 false、方向被钉死在强制横屏）、
   「开 → 关」停门控。

> **偏离记录**：v1.8.0 的契约是「绝不长期锁横屏」（理由：否则退出只剩按钮和返回键）。
> 本版按真机反馈对 auto-rotate 关的用户推翻它 —— 那个按钮的语义就是「我要横屏」，
> 而关掉自动旋转本身就是在表达「不要跟着手机转」。两条退出路径仍然齐备。

### T1-B · 歌词加载中时控制栏「有动画但无动作」：**未复现，未能定位**（如实）

用户报告「歌词处于加载状态时，点上一首/播放/下一首，按钮有动画但无动作；歌词加载完成后恢复」，
且**目前无法稳定复现**。按任务书授权，本条以代码审查为主，**不做猜测性改动**。

| 候选根因 | 结论 | 证据 |
|---|---|---|
| 加载态挂载了 `fillMaxSize` 遮罩 / loading 层 | 不成立 | `LyricsView.kt:93-100`：`lyrics.isEmpty()` 直接返回空（`isLoading` 为真时连「暂无歌词」都不画），无 Box / 无 `pointerInput` |
| 加载层吃掉命中区（触摸陷阱 1/2/5） | 不成立 | 同上：该区域一个节点都没有；控制栏在歌词面板之外，层叠顺序不变 |
| 三个按钮被 `enabled` 门控 | 不成立 | `FullPlayerControls.kt:239/354` 的 `previousEnabled` 只等于 `playMode != INFINITY`（`PlayerCard.kt:718`）；播放键无门控 |
| 切歌被歌词闸门阻塞 | 不成立 | `LyricRequestGate` 是 `AtomicLong` 单操作无锁闸门，**不挂起不加锁** |
| 歌词与取链共用单线程调度器 | 不成立 | `fetchLyrics` 走 `viewModelScope`（Main），取链走 `Dispatchers.IO`，无 `limitedParallelism` |
| `playNext` / `playSong` 在加载期 early-return | 不成立 | `playNext` 只在队列空时返回；`playSong` 非 suspend、无状态门控 |

**仍存的两个可能**（都需复现现场才能定论）：主线程瞬时卡顿（`LyricsCache.get` 在 Main 上做 prefs 读 +
Gson 反序列化）、或点击落在 `graphicsLayer` 过渡期的命中期（触摸陷阱 3）。

### T1-C · 乱序播放：`playMode` 静默丢失 + 轮末重播 + 游标失步（**不是洗牌算法的问题**）

用户报告「容易同一语种扎堆，刚刚随机到十首日语歌」，并确认自动播完也扎堆、曲库以日语为主。

- **洗牌算法无缺陷**：Kotlin `shuffled()` 落到 `java.util.Collections.shuffle`，标准无偏 Durstenfeld
  Fisher-Yates（javap 反汇编 `kotlin-stdlib-1.9.24` 核对）；无 `swap(i, random(0,n))` 式有偏写法、
  无固定/秒级种子、无按语种/专辑分池。
- **统计事实**：均匀随机排列下同类占比 k/n 时期望相邻同类对 = k(k-1)/n；n=50、日语 70% 时
  P(≥10 连) ≈ **27.8%** ⇒ 在日语为主的库里是**正常统计现象**，算法层面无法"修正"分布。
  **本 fork 不做「按语种均衡」**（语言是伪概念，ncm 没有语言字段）。
- **但有三处真缺陷**（它们才会造成"真·连播"）：

| # | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| 1 | `playMode` 只在 `remember` 里，Activity 重建即重置为 CYCLE | 静默退回顺序播放（网易歌单原始顺序常按语种/地区成块） | `PlaybackStateManager` 落盘 `play_mode`（非法值回落 0） |
| 2 | 轮末分裂：手动路径重洗整池后播 `fresh[0]`；无缝路径把当前曲钉 0 位后播下标 0 | 轮末**重播刚播完的那首**（无缝路径必现） | 统一 `ShuffleRound.newRound` 并跳过下标 0；无缝路径用 `pendingRound` 让**预载与过渡读同一份序列** |
| 3 | 队列面板点歌只改 `currentQueueIndex`，不同步 `shuffledPosition` | 之后的"下一首"按旧游标推进 ⇒ 已播歌再播 | `playFromQueue` 同步游标 |

- **附带（产品向）**：`player/ShuffleRound.kt` 的 `disperse` 用**主艺人 id** 做「相邻两首不同艺人」约束，
  最多剩余优先 + 随机打破平局，**下标 0 保持不动**（`shuffledIndices[0]` 恒等于正在播的那首是本应用的事实契约）；
  池子太小 / 整张专辑同一艺人时**优雅退化**，绝不丢歌、绝不死循环。

### T2 · 播放时禁止熄屏

| 项 | 决策 |
|---|---|
| 触发 | **仅 `isPlaying == true`**（暂停 / 缓冲恢复系统策略） |
| 范围 | **仅播放器界面**（竖屏全屏播放器与大屏共用同一个展开进度；mini bar 不算） |
| 后台 | **不禁止**：`ON_PAUSE` 立刻摘 flag |
| 默认 | 开（`ncrust_settings` 的 `keep_screen_on`），设置 → 播放可关 |
| 实现 | `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`，**不用 WakeLock**（零权限、零依赖、不存在忘记释放） |

- `ON_RESUME` 幂等重放（部分 ROM 从最近任务回来会重置窗口标志，与 v1.8.0 沉浸式同一套处理）、
  `onDispose` 再清一次；
- 媒体通知栏的暂停/继续天然更新 flag（同一个 `isPlaying` StateFlow）；
- 展开态另存一份 `State` 供判定，**刻意不新增第二个 `snapshotFlow`**，也不在组合期读 `progress`
  （那会让 `MainScreen` 跟着展开动画逐帧重组）；
- 可观测性：`Log.i("NcrustBigScreen", "keep-screen-on: flag added/cleared")`，
  交叉验证可用 `dumpsys window | grep mHoldScreenWindow`。

### T4 · 动态字号（实验性，默认关）· **`SweepTrack.kt` 一行未改**

按每句**估算折行数**给这一句一个离散倍率：1 行且填充率 ≤ 0.82 → ×1.15；≥3 行 → ×0.85；其余 ×1.0。
倍率乘在用户 A-/A+ 的基准字号**之上**（不替换），并被 **48sp 绝对上限**夹住（A+ 的 1.5 档 ⇒ 放大档自动退回 1.0）；
译文 / 音译同乘同一倍率 ⇒ 三层比例恒定。

**红线复核（调研结论）**：`SweepGeometry` 只有「行号 + 横向 x」，**没有任何纵向 API** ——
`build` 只消费 `charStart/charEnd/lineStart/lineEnd/lineForChar` + `fadePx`，`sample` 只返回
`(lineIndex, x, rtl)`；行高与上下沿全部由渲染层直接问 `TextLayoutResult`。字号变化影响的是
**折行结果**（= geo 的内容），那本来就是渲染层每次 layout 变化时重算的输入 ⇒ 只改
`fontSize`/`lineHeight`，渐变 / 逐字 / 跨行插值全走既有路径。

**为什么估算而不是测量**：真值要两遍排版（每行已有两层 `BasicText` → 四遍），S6（API 24 / 3GB）
上是可感知开销，且首次滚到的行会有一次「基准 → 终值」跳变。估算 O(字符数)、无排版、无首帧跳变；
判据保守到可在模型内证明「放大后仍占 1 行」（`fill ≤ 0.82` 且 ×1.15 ≤ 1）⇒ **不会振荡**。
代价：拉丁比例字体只能近似（CJK 1em / ASCII 0.55~0.66em），判错一档只是字号差一档，
不影响逐字几何（几何来自真实 layout）。

**「关掉 = 与 v1.9.3 逐字节一致」如何保证**：`NcrustLyricLine.fontScale` 为 1f 时面板里**原样使用**
`fontSize`/`lineHeight`（刻意不写成 `fontSize * 1f`），diff 可逐行审计。

## v2.0.0 · T3 离线缓存 Phase 2（本 fork：离线曲目管理 UI + 容量控制）

> **范围声明（合规前提）**：Phase 2 **不做显式下载**。没有 media3 的 `DownloadManager` /
> `DownloadRequest` / `DownloadService`，没有下载按钮 / 下载队列 / 进度百分比，也没有新增任何权限、
> 依赖或 applicationId 改动。本 fork 的契约仍是 v1.6.0 · D1 那一句：**缓存的是「用户已经点播、
> 已经播放过的音频流」**（等价于 ExoPlayer 的 CacheDataSource）。UI 文案一律用「已缓存 / 缓存」，
> 并常驻声明「只缓存播放过的音频片段，不保证整曲完整」—— SimpleCache 本来就只保证有片段，
> UI 不能替它承诺整曲。

### 做了什么（6 个逻辑单元 7 个 commit）

| 层 | 交付 |
|---|---|
| 数据层 | `cache/OfflineLibrary.kt`：离线曲目索引（songId / name / artist / albumPicUrl / durationMs / level / cacheKey / approxBytes / completedAt），SharedPreferences + Gson（`ncrust_offline` 的 `tracks`），有界 300 条 LRU、坏 JSON 容错；纯逻辑 `OfflineLibraryIndex` + 17 条 JVM 单测 |
| 缓存 API | `OfflineAudioCache` 新增 `keys()` / `songKeys()` / `bytesForSong()` / `removeSong()` / `reconcileLibrary()` / `maxMb()` / `setMaxMb()`；`clear()` 连索引一起清；`OfflineUrlStore` 抽出 `mutate()` 并新增联动删除 |
| UI | 设置页「存储与缓存」新增入口 → 全屏 Dialog：总量 / 上限 / 剩余、上限选择器（64..8192 MB）、曲目列表（封面色块 + 标题 + 歌手 + 档位 + 该曲占用）、单曲删除（二次确认；当前播放曲目禁用并说明原因） |
| 统计口径 | 修掉图片缓存双计，拆成音频 / 图片 / 其他三项（`ui/screen/CacheUsage.kt`，纯逻辑 + 6 条 JVM 单测） |
| 离线优先 | `PlayerViewModel.fetchUrlOfflineFirst`：明确离线时先离线兜底，`preloadSong` 同一条路径；另接上 `LyricsCache.getTtmlStale`（v1.9.0 就写好、一直没有调用者）做离线 TTML 兜底 |
| i18n / 文档 | 17 条文案 × 8 语言；AGENTS.md / CLAUDE.md 同步（prefs 表补 `offline_cache_mb` / `ncrust_offline`） |

### 写入点：索引里的歌 = 这台设备**真的播过**的歌

挂在 `PlaybackService.updatePlaybackState()`（2Hz 心跳，且 `player.isPlaying` 为真）。为什么不是 `playUrl`：
gapless 自动接续与车机点播都不经过 `playUrl`，只有心跳这一处覆盖全部起播路径；「心跳跑到了」本身也约等于
「这歌真的开始出声了」，比在 `playUrl` 里乐观写入更贴近「已缓存」的语义。只认带 `ncrustkey` 的 URL
（那是唯一会走 `OfflineAudioCache` 的路径）。每首歌只在首次起播与时长首次可知时各落一次盘（内存去重），
连播十遍也只有一条、`completedAt` 仍是第一次那个值。

### 口径（统计 == 可清理，这一版把它修对了）

「缓存占用」旧口径 = Coil 磁盘缓存 size + `folderSize(cacheDir)` + 离线音频 size，而 Coil 的磁盘缓存目录
就是 **`cacheDir/image_cache`**（`NcrustApplication.newImageLoader`），`folderSize(cacheDir)` 已经**递归**含它
⇒ **图片缓存被算了两遍**（20MB 图片缓存让设置页多报 20MB，且清完还在 —— 那是 journal 残留）。
修法是换口径而不是减一项：**先量三项、再相加**，目录树只走一遍，图片从里面**切出来**
（`other = folderSize(cacheDir) − imageBytes`），三个来源互不重叠：

| 项 | 目录 | 谁清 |
|---|---|---|
| 音频 | `filesDir/offline/audio`（不在 cacheDir，系统清缓存不会误删） | `OfflineAudioCache.clear()`（v2.0.0 起连 URL 清单 + 曲目索引一起清） |
| 图片 | `cacheDir/image_cache` | Coil 的 `diskCache.clear()`（要走它的 API：绕过 journal 直接删目录会让内存状态与磁盘不一致） |
| 其他 | cacheDir 其余子项（WebView / http / …） | 逐项 `deleteRecursively()`（旧实现只删 WebView 与 http，别的子目录算了占用却清不掉） |

管理页里每一行都必须**真的能离线播**：打开时 `reconcileLibrary()` 用 `SimpleCache` 的真相（`keys()`）对账索引，
丢掉「缓存里已经没有任何片段」的条目（典型成因是音频 LRU 淘汰了 span）。宁可少列也不谎报 ——
这是 Phase 1「离线兜底必须 `contains()` 才起播」那条契约在 UI 侧的延续。

### 上限为什么是「下次启动生效」

media3 的淘汰器 `LeastRecentlyUsedCacheEvictor` 在 `SimpleCache` **构造时固化**
（`OfflineAudioCache.get` 是进程内单例），改上限只能重建实例；而播放中的 ExoPlayer 正握着这个 cache，
重建 = 中断播放。所以设置页只写 `ncrust_settings:offline_cache_mb` 并**如实提示「下次启动生效」**，
不为了「看起来即时」去动正在播的缓存。上限调小后本次进程内仍是旧上限 ⇒ 管理页的「剩余」按 0 兜底，
不显示负数。

### 相对 Phase 1 的偏离（逐条）

1. **同一个 prefs 文件里多一个 key**：Phase 1 只有 `urls`（URL 清单）；本版在 `ncrust_offline` 里加了
   `tracks`（曲目索引）。**没有新 prefs 文件**，`clear` 仍是清同一个文件的几个 key。
2. **`clear()` 的语义扩大了**：Phase 1 只清音频 span + URL 清单；现在连曲目索引一起清 ——
   这是「统计口径 == 可清理范围」不变量的直接后果，不是顺手改的。
3. **缓存的清理范围内多了一类**：`cacheDir` 下的**全部**子目录（image_cache 交给 Coil）。
   Phase 1 只删 WebView / http，但统计一直把整个 cacheDir 算进占用 ⇒ 显示 300MB 只能清掉一部分。
4. **索引的 `approxBytes` 字段留空（null）**：不存快照，UI 每次从 `SimpleCache` 现场量
   （`bytesForSong` 按 CacheSpan 求和）—— 存下来的数字会在用户删片段 / LRU 淘汰之后变成谎话。
   量不到就显示「已缓存片段」（i18n 的 `offlineCachePartial`）。
5. **离线兜底的触发时机前移**：Phase 1 是「取链失败后回落缓存」；本版在**明确离线**时先走缓存。
   注意 `NetworkAvailability` 判断失败按「在线」处理，且离线 + 缓存未命中时**仍然照旧试网络** ——
   不因为一次网络判断把歌静默跳过（在线路径逐字未变）。
6. **URL 清单的写入时机未变**（仍在 `PlaybackService.playUrl`），但曲目索引的写入点另挂在心跳上，
   两者都只在真正播过之后才有内容。

### v2.0.0 · T3 的测试（JVM 单测 330 个全绿）

| 项 | 结果 |
|---|---|
| JVM 单测 | **330 个全绿**（本 session 基线 307；本任务 +23：`OfflineLibraryIndexTest` 17 + `CacheUsageTest` 6） |
| 新增覆盖 | 有界 LRU / 重播不刷新 completedAt / upsert 幂等 / 坏 JSON / 字段缺失迁移 / 删除后 URL 清单同步（含孤儿条目）/ list 稳定排序 / retainSongIds / JSON 往返；图片缓存双计回归（临时目录复现旧口径的差值）/ cacheDir 缺失与负值边界 |
| 编译 | `./gradlew :app:testDebugUnitTest` 全绿；`compileDebugKotlin` 无新增 warning |

### v2.0.0 · T3 的未验证项（如实）

- **整个过程没有真机验证**：本任务只跑 JVM 单测与编译，**没有装到 S6 / PCL110 上跑过**。未实测：
  - 全屏 Dialog 的视觉效果、返回键 / 返回手势（依赖 Dialog 默认的 dismissOnBackPress）；
  - 曲目列表的封面加载与色块观感、删除按钮的 48dp 命中盒与 TalkBack 语义（只做了静态声明）；
  - 上限选择器写盘后「下次启动生效」的真实表现（淘汰器确实在重启后才换上限）；
  - 心跳写入索引的真实落盘（`ncrust_offline.xml` 的 `tracks`）与 `durationMs` 的补写时机；
  - 离线优先短路的真实收益（断网首播是否真的不再等降级链）—— 需要真的断网环境；
  - 过期 TTML 兜底的真实效果（要构造「TTML 已过期 + 断网」）；
  - `bytesForSong` 的数值与 `sizeBytes` 的量级是否一致（按 span 求和的假设未经真机核对）；
  - `cacheDir` 全清是否影响某些 ROM 上正在使用的缓存目录。
- **`reconcileLibrary` 只在打开管理页时跑**：不做后台周期清理（有意的，不新增后台任务），
  代价是索引在别处（如日志）可能偏旧，直到用户打开管理页。
- 大屏 / 横屏未验证（管理页是全屏 Dialog，理论上自适应，但没有截图）。

### v2.0.0 · T3 的已知问题

- **在线但镜像全挂时不会用过期 TTML**：本版只在「明确离线」时接 `getTtmlStale`，
  在线失败仍走 `AmllTtmlClient.load` 的既有契约（「要不要用过期数据是歌词源链的策略」），没有改它。
- **跨档位的 URL 清单命中仍可能落空**：`OfflineUrlStore.recall` 退化成「这首歌的任意档位」后，
  可能返回一个**没有缓存**的档位条目（同曲另一档有缓存），随后 `contains()` 失败 ⇒ 这次离线兜底放弃。
  Phase 1 的既有行为，本版未改。
- **删除与正在写入的 span 并发**：`removeSong` 之后 CacheDataSink 可能又写回一段，极端情况下删除不彻底。
  未实测。
- **`durationMs` 依赖播放心跳**：歌没播到 `player.duration` 可知就切走时为 null（当前 UI 不显示时长）。

### 与任务书的偏离（逐条）

1. **i18n 单独成一个 commit**（任务书把它与文档合在最后一个 commit）：Strings.kt 是单 data class、
   除一个字段外都无默认值，加文案就必须同时改 8 个语言文件；文案与 UI 是两次独立可回滚的改动，
   拆开更符合「一个逻辑单元一个 commit」，且两次提交都能单独编译。文档 commit 因此只含 AGENTS.md / CLAUDE.md。
2. **离线短路与过期 TTML 兜底拆成两个 commit**（`perf(player):` 与 `fix(lyrics):`）：同一文件、同一主题
   （离线优先），但一个是性能、一个是数据可用性，可以独立回滚。
3. **管理页用全屏 Dialog，没有接导航图**（任务书给了二选一）—— 理由见 `OfflineCacheOverlay.kt` 的 KDoc：
   Dialog 是独立窗口，天然在播放器卡片之上（不会踩触摸陷阱第 2 条的命中区问题），返回键交给系统。
4. **「该曲缓存占用」用 CacheSpan 求和**，而不是 `Cache.getCachedBytes(key, 0, MAX)`：后者要求从 position 起
   连续缓存，而缓存里只有播过的片段（seek 后留洞），从 0 起经常不连续、会量成 0。
5. **删除入口用「行尾按钮 + 二次确认」，当前播放曲目禁用并显示原因**（任务书允许在「提示」与「禁用」中二选一）。
6. **统计分项直接显示在设置页**（总量行下面三行小字），管理页顶部只显示音频缓存自己的总量 / 上限 / 剩余 ——
   两处数字都来自 `CacheUsage` / `OfflineAudioCache`，不是各算一份。

---

## v2.0.1 hotfix（本 fork · 大屏歌首「歌词被拉到最顶端、第一句看不见」）

**版本**：`2.0.1-gpl` / versionCode **28**（冷启动跑 `tools/next-version.sh`，三源一致 = 27 ⇒ 28）。
**范围**：只修这一个 bug —— 不动 `SweepTrack.kt`（v1.5.2 冻结，本次一个字节未改）、不动数据层、
不动 P1 大屏布局、不动渐隐高度公式。

### 用户原话与关键确认

> 有些逐字歌词在没有进入第一句话的时候，大屏模式下会把歌词自动拉到最顶端导致看不见第一句歌词

| 问题 | 探针结论（PCL110 真机） |
|---|---|
| 只有大屏出现？ | **是**。同一状态、同一首歌，只差窗口方向：竖屏第一句完整可见，大屏第一句被压到面板底边 |
| 只有逐字歌词出现？ | **不是**。判据是「第一句时间戳 > 0」，与 yrc 无关。缓存 73 首里 8 首命中（yrc 4 / 纯 LRC 4） |
| 只有歌首出现？ | **是**（前奏期间一直保持）；歌中会在下一次跨行时被自动纠正 |
| 第一句是「看不见但可滚动到」还是「真没了」？ | **可滚动到** —— 数据/行/时间戳都在，纯粹是定位错 |

### 根因（三条叠加，缺一不成 bug）

`ui/player/NcrustLyricsPanel.kt` 的 LazyColumn 结构是 `top_spacer(固定 200dp)` + 歌词行 + `bottom_spacer(200dp)`：

1. **前奏期间「没有当前行」，所有纠正路径集体失效**。`currentLineIndex()` 在
   `position < timestamps[0]` 时返回 **-1**，于是：`LaunchedEffect(isVisible)`（只在 `isVisible` 翻转时跑）、
   `LaunchedEffect(forcedScrollTrigger)`（只在播放器展开进度跨 0.9 时跑）**都不跑** ——
   而进大屏（窗口旋转）既不改 `isVisible` 也不改 `progress`；跨行自动滚动那条又写着
   `if (currentIndex < 0) return`。**唯一还在跑的是换歌词表时的 `LaunchedEffect(lines)`，
   而它做的是 `listState.scrollToItem(0)`（回顶）。**
2. **「回顶」在大屏下 == 「第一句被推出可视区」**。顶部留白是**固定 200dp**（v1.5.0 从 Kanesumi 面板
   搬入时按"竖屏面板约 700dp 高"定的），而大屏右栏歌词面板**实测只有 259dp 高**
   （PCL110：面板视口 908px ÷ 3.5）。200dp 占 72% ⇒ 列表在位置 0 时第一句从 200dp 才开始画，
   只剩 ~59dp 露在面板内，且整条落在底部渐隐带（`min(100dp, 30%×视口)` = 77.7dp）里 ⇒ **不可读**。
   竖屏面板视口实测 315dp（1105px），同样 200dp 只占 63%，定位后又会被"当前行落在 36%"的
   `scrollToItem(1, -397)` 拉回 113dp ⇒ 第一句完整可见 ⇒ **竖屏看不出问题**。
3. **谁触发 `LaunchedEffect(lines)`（回顶）**：它的 key 是**歌词表身份**（`LyricsView` 的 `panelLines`），
   不是歌曲 id。切歌、歌词源 phase1(YRC)→phase2(TTML) 升级、翻译/音译开关、
   **动态字号开着时面板宽度变化导致 `lineScales` 重算**（进大屏 = 面板宽度从 363dp 变 448dp）都会触发。

### 探针证据（PCL110 / v2.0.0-gpl 原样复现）

复现歌 `27198266`《Go West》——**首句 `[00:33.66]`，前奏 33.7 秒**，这是"有些"的来源：
首行是 0 秒信息行（`[00:00.000] 作词 : …`）的歌 `currentIndex` 一上来就是 0，永远不进入 -1 窗口。

| 场景 | 证据 | 结果 |
|---|---|---|
| 竖屏 + 位置 0 | `tools/evidence/v2.0.1/01-portrait-OK-first-line-visible.png` | 第一句完整可见 |
| 大屏 + 同一状态 | `02-bigscreen-BUG-first-line-clipped.png` | 面板上方整块空白，第一句被裁在底边、糊在渐隐带里 |
| 大屏里手动上滑一次 | `03-bigscreen-after-manual-scroll-reachable.png` | 第一句立刻完整可见 ⇒ 定位错，不是数据丢 |

日志打点（临时探针构建，发布前已删除）拿到确定性复现路径 —— **大屏里点 A± / 换歌词表**：

```
I/LyricsScroll: ev=lines-reset IN  lines=66 currentIndex=-1 vh=908
I/LyricsScroll: ev=lines-reset OUT first=0 off=0      ← 回到最顶端，此后没有任何路径纠正
```

同一份探针还测到：竖屏面板视口 **1105px（315dp）**、大屏 **908px（259dp）**，
以及进大屏时面板被**重新挂载**（`isVisible` 会先 false 再 true）——
于是"`lines` 重置回顶"与"`isVisible` 定位"之间存在**竞态**：谁最后跑决定结果，
这解释了为什么进大屏时这个 bug 时灵时不灵，而"切歌 / 点 A±"是 100% 必现。

### 修法（两条，加一个纯函数 + 单测）

1. **顶部留白按视口夹取**：`topSpacer = min(200dp, 36% × 视口)`（`LyricsPanelScroll.topSpacerHeightPx`）。
   面板根节点因此从 `Box` 换成 `BoxWithConstraints` —— 用 `maxHeight`（约束）而不是
   `listState.layoutInfo`（要等第一次排版）才能保证进大屏那一帧就是对的。
   - 竖屏 315dp：留白 200dp → 113dp，**定位后的位置不变**（`scrollToItem(1, -36%)` 仍然把第一句放在 113dp）；
   - 大屏 259dp：留白 200dp → 93dp，第一句落在 36% 处、且恒定在顶部渐隐带下方（`0.30 < 0.36`）；
   - **不变量**：留白 ≤ 36% 视口 ⇒ 「回顶」与「首句落在 36%」在数学上是同一个位置，
     两条路径不再互相打架，第一句开唱时也不会再跳一下。
2. **「没有当前行」也要定位**：`-1`（前奏 / 拖回开头）按**第 0 行**处理
   （`LyricsPanelScroll.targetItemIndex`），跨行自动滚动那条不再 `currentIndex < 0` 直接 return；
   换歌词表时也从"无条件回顶"改成同一条定位规则（切歌时位置本来就是 0，行为等价；
   而"进大屏 / 点 A± / phase1→phase2"不再丢掉当前行）。
   `lastAutoScrolledIndex` 仍记**原始** `currentIndex`，所以启动时（-1 == -1）不会多滚一次。

**可以复用的判据**（下次遇到「歌词定位不对」先看这条）：
`LazyColumn` 的 `top_spacer` 高度与「当前行定位槽」必须成对定义 —— 只要留白能大于定位槽，
就必然存在一条路径（回顶）把内容推出短视口；**顶部留白 ≤ 定位槽**是这个组件的不变量，
不是可有可无的样式参数。同理，凡「前置内容不是固定高度」的 `LazyColumn`，
它的"回顶/复位"路径都要按**视口**核对一次，不能只在设计稿的那块尺寸里看。

### v2.0.1 的测试与实测

| 项 | 结果 |
|---|---|
| JVM 单测 | `LyricsPanelScrollTest` 11 个用例（含「留白 ≤ 36% 视口」「回顶 == 首句落在 36%」两条不变量、竖屏 555/556dp 边界、-1 → 第 0 行、越界钳制）；全仓库 **30 类 / 341 用例全绿** |
| 大屏 + 逐字 + 歌首（前奏期间） | ✅ 真实播放到 0:31（首句 33.66s 未到），第一句 + 译文完整可见 |
| 大屏 + 逐字 + 首句开唱 | ✅ 0:47 当前句落在 36%，前句/后句分别弱化，无跳变 |
| 大屏 + 逐字 + 歌中 | ✅ 拖到 2:02，当前句 = `index 19`（`t=119.11s`）落在 36% |
| 大屏 + 逐字 + 拖回开头 | ✅ 拖回 0:16（< 首句）后面板自动回到第一句并置于 36% |
| 大屏 + 歌词表重建（预修时的**确定性复现步骤**：点 A+ 两次） | ✅ 第一句仍在 36%，不再回顶 |
| 竖屏对照 | ✅ 同一状态第一句可见；播放中进出大屏不中断 |
| 整行 LRC 对照（`27946894`，`picked=YRC lines=1 words=false`） | ✅ 大屏下唯一一行（t=5s）在位置 0 时可见 |
| 逐字渐变 | ✅ 帧间差分：同一条正在唱的歌词行两次截图差 7568 像素（>18 灰度）⇒ 光标在推进 |
| 音译 / TTML | ✅ `1959528822`：phase2 `roman=MIXED/29`，罗马音渲染在原文下方 |
| 动态字号（实验性，PCL110 开着） | ✅ A+ 0.85→1.2 触发重算，布局重排正常、不再丢当前行 |
| 控制栏 / 音质切换 / 大屏进出 | ✅ 就地切到「更好」（exhigh）后按新档续播；⤢ 进出多次无异常 |
| 离线缓存 | ✅ 设置 → 存储与缓存（636.2 MB，音频 529.5 / 图片 100.1 / 其他 6.6）→ 离线缓存管理（5 首、上限 4096 MB、正在播放的行禁用删除） |
| 覆盖安装 / 签名 | ✅ PCL110 release 27→28、S6 debug 27→28 均覆盖成功；证书 `e75af3ff…5511` 未变 |
| API 24（S6 / Android 7.0） | ✅ 启动、歌词面板与定位正常（`BoxWithConstraints` 路径在 API 24 上无异常） |
| 崩溃 | ✅ 全程 `FATAL|AndroidRuntime` 零命中 |

### v2.0.1 的未验证项（如实）

1. **歌词网络重取在本轮测试网络下不可用**：`POST /api/song/lyric` 在 PCL110 上 60s+ 不返回
   （老缓存缺 `romalrc` 字段触发的重取路径）。探针因此改用**缓存直命中**：把目标歌条目的
   `romalrc` 补成空串（等价于 app 自己重取成功后的产物），测试后已还原。
   ⇒ **"新歌首次联网取词"这条路径本轮没有覆盖**，但本 bug 与取词无关（面板只消费已经到手的行）。
2. **真人手持旋转没有重做**：本轮用 `auto_rotate=false` + 播放器 ⤢ 按钮控制进出大屏
   （设备平放，传感器姿态不可控），验证的是「窗口变横屏 ⇒ 大屏布局」这条路径。
3. **`SweepTrack` 逐字渐变的"视觉验收"仍以帧间差分为准**（7568 像素在变），没有逐帧人工核对软边形状 ——
   本版没有动过渲染层，这是回归确认而非新功能验收。
4. 面板高度实测值（竖屏 315dp / 大屏 259dp）来自探针日志，与 `LyricsView` 里旧注释写的
   「竖屏约 700dp」不符 —— **那行注释是错的**（修复时未改动它，避免把无关内容混进 hotfix diff）。

### 与任务书的偏离（逐条）

1. **任务书说"只有逐字歌词出现"，实测与逐字无关**（纯 LRC 同样命中，判据是第一句时间戳 > 0）。
   修法因此放在面板的滚动定位上，而不是逐字渲染路径上。
2. **额外收了两个同源表现**：进大屏 / 点 A± 导致歌词表重建时"丢掉当前行、甩回顶端"
   （与报告同一条机制、同一个 `scrollToItem(0)`），一并按同一条定位规则修掉；
   除此之外没有做任何无关改动（`SweepTrack.kt`、数据层、大屏布局、渐隐公式全部未动）。
3. **探针用了日志打点构建**，证据采集后已把探针代码从最终 diff 中移除（最终 diff 只有
   `NcrustLyricsPanel.kt` 的定位/留白改动 + 新增 `LyricsPanelScroll.kt` 与它的单测）。


## v2.0.2 新增（本 fork · 媒体通知专项：华为/荣耀「双通知栏」+「歌词只有暂停才刷新」）

> 两个 bug 都是**应用层**缺陷，各一个独立 commit。`SweepTrack.kt`（v1.5.2 冻结）**一个字节未改**，
> 歌词渲染链路、`MediaDisplayLines` 的两行排版契约、`applicationId`、签名、权限（11→11 逐条 diff 为空）、
> 依赖全部未动。全部 diff = `PlaybackService.kt` + 新增纯逻辑 `LyricNotifyGate.kt` + 它的 17 个 JVM 单测
> + `build.gradle.kts` 版本号，共 4 个文件（+523 / −44）。
> 完整调研报告：仓库外 `../PHASE0-REPORT-v2.0.2.md`，原始证据 `../evidence-v2.0.2/`。

### 真机信息（**实测，不按任务书假设**）

| | 华为平板 | 荣耀手机 |
|---|---|---|
| serial | `WVQ6R22124000968` | `APFQUT2C15004471` |
| model / brand | `WGR-W09` / HUAWEI | `AGT-AN00` / HONOR |
| Android | **12（API 31）** | **15（API 35）** |
| 厂商版本 | `EmotionUI_14.2.0` + `hw_sc.build.platform.version=4.2.0` ⇒ **HarmonyOS 4.2.0**，`devicetype=tablet` | **`MagicOS_9.0.0`** |
| 其余 | 2560×1600；设备上另有一条 `wm size` override `1600x2560`（**环境既有，不是本次改动**） | 1080×2400 |

⚠️ **任务书里「HarmonyOS 5.0+」的假设不成立** —— 实测是 HarmonyOS **4.2.0**（底层 Android 12）。
所以本版**与 AVSession / 鸿蒙原生歌词 API 无关**，调研里也没按 5.0+ 的方向走。

### B1 · 双通知栏 = media3 自己又发了一条（id=1001）

`PlaybackService` 是 media3 的 `MediaLibraryService` 且建了 `MediaLibrarySession`。
media3 `MediaSessionService` 的**默认实现**会在播放进行时自己 post 一条媒体通知，
用的是 `DefaultMediaNotificationProvider` 的 **id=1001 / channel=`default_channel_id`（渠道名 "Now playing"）
/ groupKey=`media3_group_key`**；本类同时又发自己的 **id=1 / channel=`ncrust_playback`**。
两个 id 不同 ⇒ 谁也覆盖不了谁。（media3-session-1.5.0 字节码核实；修复前全仓库
`onUpdateNotification` / `setMediaNotificationProvider` 命中数为 0。）

**那条多出来的通知注定没有歌词**：media3 通知正文取自 media3 会话 metadata ← 当前 `MediaItem` 的
`MediaMetadata`。`playUrl()` 建的是裸 `MediaItem.fromUri(url)`（一个字段都没有 ⇒ `title=null/text=null`），
无缝预载项虽然有 title/artist，但**歌词只写进 `MediaSessionCompat`**，从来没进过 media3 会话。

**三个平台三种表现（同一根因）**：

| | 华为 API 31 | 荣耀 API 35 | AOSP 模拟器 API 34 |
|---|---|---|---|
| 修复前通知条数 | **2**（都存活） | **2**（都存活） | **1**（只剩 media3 那条空的） |
| 前台通知归属 | 两条并存 | 两条并存 | `foregroundId=1001` ⇒ 应用那条被取消 |
| 用户看到 | 两张一样的卡片 | 1 张卡片 = media3 会话的旧歌名（无歌词） | 1 张空卡片 |

**修法**：覆盖**双参** `onUpdateNotification(session, startInForegroundRequired)` 且**不调 super**
⇒ media3 的 `defaultMethodCalled` 不置位 ⇒ 彻底不 post（官方留的口子；**只覆盖单参版挡不住**）。
配套 `cancelStaleMedia3Notification()`：通知不随进程死亡回收，升级后要显式撤掉旧版那条 id=1001。
刻意**不**删 `default_channel_id` 渠道（删渠道会让仍往该渠道 post 的路径静默丢通知）。
已核实 `MediaSessionService.onTaskRemoved` / `onDestroy` / `pauseAllPlayersAndStopSelf` 字节码
**都不碰通知管理器**，覆盖后无副作用；`startForeground`/`stopForeground` 本来就在本类里。

### B2 · 歌词不刷新 = 唯一那条跨行重 post 被 API 版本闸门关掉

**先澄清**：歌词行**不是**绑在状态回调上 —— `PlayerViewModel` 用
`combine(lyrics, currentPosition, lyricsInMediaSession)` 从 2Hz 位置流算当前行，一直是通的。
华为实测 47 秒内会话 metadata 推进 **14 个不同歌词行**，而通知正文 47 秒零变化。

断点在通知正文这一环：正文只在 6 个事件点重发（播放态/切歌/封面/取色/启动/预载），
**没有一处是「跨行」**；唯一那条跨行重发被 v1.8.0 · T5 的
`if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return` 关掉（两台设备 31/35 ⇒ **恒为 0 次**）。

**那个闸门的前提是错的**：v1.8.0 以为「API 28+ 的 SystemUI 会自己从会话 metadata 重建媒体通知」。
实际 SystemUI **只在通知被 post 的那一刻读一次**会话 metadata，之后不轮询
（AOSP `MediaDataManager.loadMediaDataInBg()` 就是在 `onNotificationPosted` 里 `create(token)` 再读一次）；
media3 自己那条通知的刷新白名单也只有 playbackState / playWhenReady / metadata / timeline 四类事件，
**歌词行变化不在其中**。⇒ **不重发通知 = 系统不重读 = 不刷新**。

**修法**：
1. 删掉版本闸门，**所有 API 版本**都在跨行时重 post（不再按版本猜 ROM 行为）；
2. 判定收敛成纯逻辑 **[LyricNotifyGate](app/src/main/java/com/takahashirinta/ncrust/player/LyricNotifyGate.kt)**
   + 17 个 JVM 单测：没进前台不发 / 与通知里当前那一行相同不重发 /
   距上次 post 不足 `MIN_INTERVAL_MS=250` 时**延后重试而不是丢弃**
   （旧实现直接丢弃 ⇒ 说唱等密集段落里通知会永远停在被丢掉的那一行）；
3. `lastPostedLyricLine` 只在 `updateNotify()` 真的把通知发出去之后才记账；
   哨兵用 `NEVER_POSTED` 而不是 `null` —— `null` 是有意义的行取值（当前没有歌词行），
   混用会让第一首无歌词的歌永远不刷新。

### v2.0.2 的测试与实测（2026-09-25）

**JVM 单测 358 个全绿**（新增 `LyricNotifyGateTest` 17 例，含「密集段落最后一行一定会到达」的模型级验证）。

| 验证项 | 华为 WGR-W09 | 荣耀 AGT-AN00 | AOSP API 34 | AOSP API 24 |
|---|---|---|---|---|
| 媒体通知条数 | **1** | **1** | **1** | **1** |
| 前台通知归属 | id=1 | id=1 | `foregroundId=1` / `ncrust_playback` / `color=0xff1db954` | id=1 |
| 歌词随行刷新 | ✅ 45s 内 3 次行级跟随 | ✅ 40s 内 4 次行级跟随 | —（无登录态，只验通知合并） | — |
| 下拉通知栏 | **1 张卡片 + 实时歌词**（相隔 1 分钟两张截图，歌词已推进） | **1 张卡片 + 实时歌词**（修复前是 media3 会话的旧歌名 `下山`） | — | — |
| 暂停 / 恢复 / 切歌 | ✅ 通知保留当前行 → 切歌立即换歌名 + 新歌词行 | — | — | — |
| 拖动进度条 | ✅ 42.5s → 190s → 72.7s，歌词三次同步 | — | — | — |
| 重 post 是否打扰 | `flags=0x6a`（含 `ONLY_ALERT_ONCE`，不响不震） | 同 | 同 | 同 |
| 崩溃 | 无 | 无 | 无 | 无 |

真机探针脚本（仓库外，`.sh` 已 gitignore）：`tools/wgr.sh`（UI dump / 按文本点击 / 切设备）、
`tools/wgr-lyric-probe.sh <秒> <间隔>`（**同时采样通知 extras 与 MediaSession metadata**，逐秒对齐）。

### v2.0.2 的未验证项（如实）

1. **PCL110（ColorOS / Android 16）与 S6（Android 7 真机）本轮不在场**，回归改用
   AOSP **API 34 + API 24** 两个模拟器（通知条数 / 前台通知归属 / 播放切歌 / API 24 无异常）；
   **没有真人手持的 PCL110 / S6 实测**。
2. 修复后只跑了**两台**真机（华为 WGR-W09、荣耀 AGT-AN00）；其他华为/荣耀机型与其他 OEM ROM 未验证。
3. **`lyrics_in_media_session` 默认仍是「关」**（本版没改这个默认值）—— 不开该开关时通知两行退回
   「歌名 / 艺人」，这是 v1.5.1 · D 的设计，不是本版退化。
4. 锁屏歌词 / 车机（Android Auto）歌词 / 蓝牙 AVRCP 歌词本轮**未逐项复测**。
5. **EMUI / MagicOS 的通知节流阈值未找到可靠来源**；实测每歌词行一次（约 2–4 秒一行）的重 post
   全部生效、未被限流。**不编造数值**。

### v2.0.2 的已知问题 / 系统限制（应用层无法解决）

1. **华为「播控中心」不会出现本应用卡片、也不显示歌词**：它是独立的系统卡片（华为官方称
   「系统设计的实况窗」），且有**官方支持应用清单**（音乐类只列了华为音乐/ncm/QQ/酷狗/酷我/
   咪咕/波点/Apple Music/Spotify/TIDAL 等，不含第三方小众客户端），需要厂商白名单/商业合作，
   **没有 API 可申请**。
2. **无法阻止 ROM 再画一张系统卡片**（系统侧行为）。本版能做的是**不再自己多发一条**。
3. **无 root 无法实现系统级常驻状态栏歌词**（第三方方案走 LSPosed 系统级 Hook），
   与本项目「仅 ADB、不 root、不装系统级模块」红线冲突，**不做**。
4. **荣耀「通知栏播放器样式」一类的系统开关**属系统设置项，应用层无法强制或改写；
   本版**不做任何引导用户改系统设置的提示**。
5. **本版未修但已记录的相邻缺陷**（保持改动面最小）：
   ① 自建通知没有 `setDeleteIntent`（media3 默认 provider 有）⇒ 划掉通知后应用侧状态不同步；
   ② 歌词只写进 `MediaSessionCompat`、media3 会话 metadata 未同步 ⇒ 车机 / Android Auto 拿到的
   仍是 media3 会话（通知栏那条路已被「只保留一条通知」绕过，车机那条没修）；
   ③ `default_channel_id` 渠道本版后不再使用但保留。

### 与任务书的偏离（逐条）

1. 任务书列了四个方向（A 调通知策略 / B 修刷新 / C 引导用户改系统设置 / D 探索鸿蒙 AVSession）。
   实际做 **A + B**；**C 不做**（与「不要求用户改系统设置」的补充约束冲突）、
   **D 不做**（实测设备是 HarmonyOS 4.2.0，与 5.0+ 无关，且鸿蒙原生歌词 API 属 ArkTS 侧，
   普通 Android APK 不可用）。
2. 任务书说「参考 v1.8.0 时期修复三星 S6 歌词不刷新的方案」—— 本版**推翻了那个方案的版本闸门部分**
   （真机证据见上），保留其核心思路（跨行重 post + 限流 + `setOnlyAlertOnce`），
   并把限流从「丢弃」改成「延后重试」。
3. 任务书说 versionCode 由 `tools/next-version.sh` 决定 —— 一致，三源最大值 28 ⇒ **29**。

## v2.1.0 新增（本 fork · qm 音源 + 双账号体系）

> **版本**：`2.1.0-gpl` / versionCode **30**（`tools/next-version.sh` 三源交叉验证：
> 最近 5 个 tag / dist 44 个 APK 的 aapt2 badging / 仓库当前 build.gradle 最大值都是 29）。
> **渲染层**：`SweepTrack.kt`（扫词算法，v1.5.2 冻结）**一个字节未改**；
> `applicationId`、签名（`e75af3ff…5511`）、权限（11 条逐条 diff 为空）、依赖全部未动。
> **调研报告**：仓库外 `../PHASE0-ARCH-v2.1.0.md`（架构耦合点，1130 行）、
> `../PHASE0-QQMUSIC-API.md`（QQ API 实测，2546 行，含 36 项实测 / 19 项未实测 / 7 项风险三张表）。

### 一句话架构

「音源」这个概念只在**一处**定义（`source/MusicSource.kt`），只在**一处**分叉
（`source/SourceRouter.kt`），两个音源各有一个 `MusicSourceProvider` 实现。
调用方（`PlayerViewModel` / `PlaybackService`）只把 `SongItem` 递进来。

### 决策 1：id 命名空间用**高位标志位**隔离，而不是给五个文件改 key

架构调研给的方案是「`SongRef` 先落地，然后在 `OfflineKeys` / `LyricsCache` /
`PlaybackStateManager` / `OfflineLibrary` / `LibraryManager` 五个文件里各做一次
「key 带音源 + 老 key 兼容读」。**本版采用了更小的替代方案并在此记录偏离**：

qm 的数字 id 一律写成 `(1L shl 62) or rawId`（`SourceIds.qqId`）。
ncm 的 id 是十进制百万~十亿量级，永远触不到 2^62，于是：

- 撞号从「每个调用点都要记得带音源」变成**结构上不可能**；
- **既有结构一个字节都不用改**，不需要任何数据迁移；
- 对照方案的出错面大得多 —— 本仓库 v1.9.3 的教训正是「加字段 = 加迁移逻辑 = 加单测」。

`qqRawId` 用掩码无损反解（真实 songid 是 9~10 位十进制数，不可能占到 bit 62；
真占了就退回 FNV-1a 62 位散列兜底）。它对**非 QQ id 返回 null 而不是原样返回**：
调用方拿到 null 说明「这不是 qm 的 id」，静默通过只会让它跑到取链那一步才炸。

### 决策 2：**QQ 用的不是标准 3DES**（本版最反直觉的一条）

QRC 歌词是 `hex → TripleDES → zlib → UTF-8 XML`。按常识该用 `javax.crypto` 的
`DESede/ECB/NoPadding`，**实测走不通**：同一把固定密钥解真实密文，得到的第一块不是
zlib 流头。两边独立证据：

1. `DESede` 解真实密文失败；
2. 参考实现（Python）的 DES **同样通不过标准测试向量**（key=`0123456789ABCDEF`、
   pt=`4E6F772069732074` 应得 `3FA40E8A984D4815`，实得 `FE6782F11080C6E6`）。

差异在**密钥扩展的 PC-2**：压缩表与标准相同，但取 D 半部时写成 `31 - (pc2[j] - 27)`
（标准是 `- 28`），D 的每一位朝低位偏一位，且 PC-2 里 D 的最后一个下标（27）
溢出成恒 0 的填充位。这是**错位而不是旋转**（末尾补 0、首位移出），
所以**无法**用「换一把标准密钥」等价模拟 —— 只能把变体实现出来（`qq/QqDes.kt`）。

移植时踩到两个会静默算错的点，都留在注释里：
① Kotlin 的 `shl/ushr/and/or` 是**同级左结合的中缀函数**，把 Python 表达式按符号
平替会得到 `((a shl 7) or b) shl 6` —— 生成代码必须**全括号化**（表由脚本从参考实现导出）；
② 参考实现在 `f()` 里把 S 盒结果**重新赋值给 `state`**，换新变量名时必须把 return 里的
`state` 全换掉，否则置换的还是旧 state（曾因此错了 4 个字节）。

**验证方式不是「看起来对」**：`QrcDecryptorGoldenTest` 用调研期独立抓取的真实《晴天》
QRC（9808 hex）与**另一份独立实现**解出的黄金 XML（9821 字节）比对，逐字节一致。
将来有人把 `- 27` 改回「更标准」的 `- 28`，测试立刻红 —— 那条用例就是解释为什么不能改的文档。

### 决策 3：QQ 取链必须**一次批量**、文件名只能用 `file.media_mid`

两个实测踩出来的坑：

1. **服务端不会自动降级音质**：请求 `M800` 无权限就返回空 purl + `result=104003`，
   不会顺手给 128k。所以降级必须客户端做，且要把多个档位**一次性放进同一个
   `filename[]`**（一次往返），再按**请求时的优先级**挑第一个可用的
   （不按响应顺序挑 —— 那是服务端的实现细节，依赖它等于把「选无损却拿到 128k」
   变成随机事件）。
2. **文件名只能用 `file.media_mid`**（实测《晴天》`mid=0039MnYb0qxYhV` /
   `media_mid=003Qui1q2u1Zho`）。**用错时服务端照样返回 purl 与 vkey、不报任何错**，
   直到 CDN 下载才 `404 file not exist`。正因如此代码里**没有**「media_mid 失败就用 mid
   再试一次」的兜底 —— 那个兜底拿到的是坏链接，只会把一次干净的失败换成一个诡异的播放错误。

### 决策 4：搜索走旧版 GET，不走 `musicu.fcg`

`DoSearchForQQMusicMobile` 实测**连续 6 次只成功 1 次**（其余 `code:2001`，疑似按出口 IP
限流），而 `c.y.qq.com/soso/fcgi-bin/client_search_cp` **3/3 稳定**。实测确认加
`new_json=1` 后两者字段名**完全一致**，所以共用一套映射。
`musicu` 那条留作兜底，且必须按实测配方「信封 key 用 **module 名**、**不带 `comm`**」——
带了 Web comm 会被判通道不匹配、返回 0 条。

### 决策 5：`qrc=0` 的歌 `lyric` 照样加密，但解出来是**行级 LRC**

只按 QRC 解析会让这些歌显示「暂无歌词」。现在 QRC 解析为空时自动回落 LRC 解析
（`QqApi.fetchLyric`）。同理，`trans` 轨里没有翻译的行服务端写成 `//`，已过滤。

### 决策 6：QQ 歌词**不经过** `LyricsCache`，也不走 TTML 那一相

- **不走 TTML**：TTML DB 是按 ncm id 索引的，拿 QQ 的 id 去查只会 404，
  极小概率还会命中一首**完全无关**的歌的 TTML。
- **不进 `LyricsCache`**：那张表存的是 ncm 的字段形状（lrc/tlyric/yrc/romalrc/ttml），
  塞 QQ 数据要么加字段 + 迁移逻辑、要么污染字段语义。取舍是每次播放现取一次，
  **代价是断网时 QQ 曲目没有歌词** —— 已进 release notes 的已知问题。

### 决策 7 续（hotfix 4）：自绘二维码，以及为什么最后一步仍走 WebView

任务要求「换成自绘二维码」。调研把两条 QR 路线都探明了，结论决定了实现形态：

| 路线 | 最后一跳 | 采纳 |
|---|---|---|
| qm 客户端扫码（`CreateQRCode` + `GetQRCodeStatus`） | **只能是 MQTT**（扫码方确认后由服务端推送；HTTP 侧穷举 48 个候选方法后确认没有「把登录态交给发起方」的接口） | ❌ 要自己实现 MQTT 5.0 over WSS，成本远超范围 |
| **QQ 互联扫码**（`ptqrshow` + `ptqrlogin`） | 纯 HTTP 长轮询，官方 Web 端现役；**手机 QQ** 扫 | ✅ 本版实现 |

**实现**：`QqQrLogin`（纯逻辑：`hash33` token、`ptuiCB` 解析、状态映射）+ `QqQrClient`（HTTP：
独立 OkHttp + 内存 CookieJar，`qrsig` 必须与当前这张二维码配对）+ `QqQrLoginDialog`（自绘）。

**最后一步刻意不自己用 HTTP 做完**：换音乐票据（`QQConnectLogin.LoginServer.QQLogin{code}`）
需要扫码方的 OAuth code，本仓库**没有账号可实测**，参数形状属于「有依据的推测」。
而 `check_sig` 那个跳转地址交给 WebView 打开后，**站点自己的脚本**就能完成换票 ——
这条路**已被真机验证可登录**。与其赌一个没验证过的请求形状，不如把最后一步交给已经证明能用的机制：
用户体验一样（扫完自动完成），但不引入未验证的假设。扫码后我们还会把 OkHttp jar 里的 cookie
注入 WebView 的 cookie store（两边是独立的存储，不注入的话 WebView 是匿名状态）。

**验证边界（如实）**：`ptqrshow` 实测可用（HTTP 200 + 真实 PNG + `qrsig`）；
**`ptqrlogin` 在本机出口 IP 被 WAF 恒定 403，因此轮询这一步没有实测过**；
`hash33` 是官方 JS 的标准算法，但调研期抓到的那对 `qrsig`/`ptqrtoken` 经核验**不是同一次请求**，
所以没有真实向量可钉。界面上因此**常驻「改用网页登录」按钮** —— 这条路不通时用户不会卡死。

### 决策 7 前情（hotfix 1）：QQ 登录浮层的三条硬要求

第一版按「WebView 打开 `y.qq.com`」实现，**真机反馈直接失败**：
「网页版会自动从 pc 跳到手机，没有对应登录方式」。根因与修法：

| 现象 | 根因 | 修法 |
|---|---|---|
| 打开就是移动版 H5，**没有登录入口** | WebView 默认发 Android UA，站点按 UA 分流 | **覆写桌面 Chrome UA**（桌面版首页右上角有「登录」） |
| （潜在）点了「登录」毫无反应 | QQ 互联登录是 `window.open` 弹窗，WebView 默认不支持多窗口，请求被丢弃 | `setSupportMultipleWindows(true)` + `WebChromeClient.onCreateWindow` 把弹窗接管到同一个 WebView |
| 登录完了应用不知道 | 登录在多个 `qq.com` 子域间跳转，「最后一个加载完的页面」不确定 | **按 1.5 s 轮询 `CookieManager.getCookie("https://y.qq.com/")`**，登录成功的唯一权威事实是 cookie；另在检测到「互联已登录但缺音乐票据」时主动回一次 `y.qq.com` 让音乐侧完成换票 |
| 兜底提示条挡住了入口 | 提示条放在顶部，正好盖住站点 header（「登录」按钮就在那里） | 移到底部（**这是真机截图复核才发现的**：为了兜底而加的 UI 反而挡住了主路径） |

登录浮层因此独立成 `ui/components/QqLoginOverlay.kt`（原来内联在 `MainActivity` 里，
这些结论需要写在离代码最近的地方）。

### 决策 7 原文：为什么是 WebView，**不做**自绘二维码（偏离任务书建议）

任务书写「推荐二维码登录」。本版用 WebView（`y.qq.com`），理由：

- QQ 互联的扫码轮询端点在本机出口 IP 上被 WAF **恒定 403**（调研期 8 种以上参数 /
  Header / TLS 指纹变体全部失败）；
- qm 客户端自己的扫码链路（`CreateQRCode` + MQTT over WSS）实测可达，
  但要自己实现 MQTT 5.0 握手与订阅，协议栈成本远超本版范围；
- WebView 与 ncm 那条已经用了很久的登录路径**同一套机制**，覆盖 QQ / 微信 / 手机号
  三种方式，且**全程不采集密码**（用户在腾讯自己的页面输入）。

### 决策 8：会员状态**只用于显示**，不参与播放决策

`VipLogin.VipLoginInter / vip_login_base` 匿名也能调（实测 `code=0`），返回
`identity.vip/svip/overdate` 与音质权益。但**登录态下的取值没有实测过**
（本仓库没有 qm 账号），所以它只驱动设置页的一个角标；
「这首歌能不能放」永远由服务端返回的 purl 决定（见 `QqApi.fetchPlayUrl`）。
判断错了最坏是界面上的一个角标不对，不会凭空给或夺走播放权限。

### 合规：档位表**不含 O801**

调研发现 VIP 专享曲的 `O801`（AI 伴奏轨）匿名也能拿到可下载 purl。
把它放进降级链等于让无会员用户听到会员内容 —— `QqQualityTest` 有一条用例
钉住「档位表不得引入 O801」。本实现同样不含任何 DRM 解密、解灰、30 秒试听拼接。

### 决策 9：聚合搜索做在 `SearchViewModel`，且**不做跨平台去重**

`searchByType` 是「一次搜索」的唯一入口，聚合放这里 ⇒ UI 层零改动。
不做跨平台去重：同一首歌在两个平台上**不是同一份资产**（码率、版权、能否播放都可能不同），
合并成一条会让用户点到一个他其实没权限播放的来源。
一侧失败**不再清空另一侧**已经拿到的结果（原先的 `catch` 会 `clearResults()`）。

### 双账号

| | ncm | qm |
|---|---|---|
| cookie | `ncrust_prefs`/`user_cookie` | **`ncrust_qq_prefs`/`qq_cookie`** |
| 登出 | 只清 ncm | **只清 qm** |

登录判据 = **uin 与票据同时存在**（只看票据会在票据过期但仍留在 cookie 里时误判成已登录，
表现是「设置页显示已登录、一播放就跳歌」，比干脆显示未登录更难排查）。
登录流程拿到的是**增量字段集**，所以写 cookie 走 merge 而不是 replace。

### 验证到什么程度（如实）

**已验证**：
- JVM 单测 483 个通过（本版新增 81 个）；
- **打真实 QQ 服务端的探针**（`QqLiveProbeTest`）：旧版搜索返回并被本仓库映射器正确解析、
  批量取链的三数组形状被受理且服务端**逐条回显的 filename 与我们请求的完全一致**、
  匿名权限行为与实测一致、**匿名取歌词 → 本仓库自己的 QRC 解密 + 解析端到端走通**、
  会员接口匿名可调；
- **跨实现黄金样本**：真实《晴天》QRC 的解密结果与另一份独立实现逐字节一致；
- 真机（PCL110 / Android 16 / API 36）：v2.0.2 → v2.1.0 **覆盖安装成功**（签名兼容）、
  启动无 FATAL、UI 正常渲染、ncm 歌词链（缓存命中 → YRC 逐字 59 行）正常；
- 权限 11 条与 v2.0.2 **逐条 diff 为空**；签名指纹 `e75af3ff…5511`。

**未验证（写进 release notes）**：登录态的 QQ 取链/歌词/会员取值；
QQ 登录链路端到端（无账号）；QQ 曲目断网无歌词；音源角标对 QQ 曲目不细分档位；
QQ 曲目不进 ncm 歌单。

**真机 UI 自动化的限制**（沿用 v1.7.0 的记录）：本轮 adb 注入文本到 Compose 输入框
**完全不生效**（设备无可用 IME，`input text` 与 `input keyevent` 均无效），
所以「输入关键词看聚合搜索结果」这条只能等真人手测。这**不是**本版引入的问题。

### 测试基础设施的一条经验

「请求形状」这类**不报错的错误**（数组不等长、信封带错字段、用错 mid）本地怎么测都是绿的，
线上表现只是「搜不到 / 取不到链」。所以把请求构造抽成纯函数（`QqRequests`）之后，
**让同一份构造既被形状单测覆盖、又被真实服务端探针使用** —— 一份代码、两处验证。
本轮唯一一个「只有真实数据才会暴露」的 bug（带尾空格的词被裁剪后与字符区间不一致，
渲染层会串位）就是这么抓到的。

## v2.1.1 新增（本 fork · QQ 扫码登录的真根因 + 手机号登录 + 播放页音源角标）

> 这一节最重要的是第一条：**v2.1.0 把 QQ 扫码登录的根因判错了，而且判错的方式会重演。**
> 完整的探针证据在仓库外 `PHASE0-QQ-QR-TOKEN-v2.1.1.md`。

### 1. 「所有变体都失败」时要先怀疑共同的不变量，不是环境

用户报告：QQ 扫码登录一直显示「网络不稳定，正在重试…」，只有网页登录能用。

v2.1.0 的判定是「本机出口 IP 被 TAPISIX WAF 拦截，属 **IP 级风控**」，
依据是「8 种以上参数/Header/TLS 变体全部 403」。**这个判定是错的。**

真根因：`ptqrtoken = hash33(qrsig)` 的初值用了 **5381**，官方是 **0**。

```js
// 腾讯生产 JS  ptlogin/js/c_login_2.js（2026-09 抓取）
"hash33": function (t) {
    for (var e = 0, n = 0, o = t.length; n < o; ++n) e += (e << 5) + t.charCodeAt(n);
    return 2147483647 & e
}
i.ptqrtoken = q["default"].str.hash33(q["default"].cookie.get("qrsig"))   // 无 seed
```

同一个 `hash33` 在 `g_tk` 那边**才是**带 seed 调的（`hash33(p_skey, 5381)`）——
v2.1.0 把 g_tk 的 5381 搬到了 `ptqrtoken` 上。对照实验（同一 `qrsig`、同一时刻、
同一出口 IP、同一套 Header，唯一变量是初值），**3/3 复现**：

| 初值 | HTTP | 响应体 |
|---|---|---|
| `0`（官方） | **200** | `ptuiCB('66','0','','0','二维码未失效。', '')` |
| `5381`（v2.1.0） | **403** | 空 |

**403 是自己发出去的畸形请求触发的，与出口 IP 无关。**

**为什么会判错（这条比 bug 本身更值得记）**：v2.1.0 的代码注释里其实写着
「抓到的那对 `qrsig`/`ptqrtoken` 经核验不是同一次请求，所以**没有真实向量可钉**」——
即 5381 从来没被验证过。但 Phase 0 报告把它写成了「实测」，于是后面所有变体实验
（Header / TLS 指纹 / 参数 / 先访问 `xlogin` 建会话）**全都在错的 token 上做**，
「全部 403」就被合理地归因成了风控。

> **规则**：当一组变体**全部失败**时，先找出它们在**共同保持不变**的那个东西
> （这里就是 token），单独验证它；不要先假设环境有问题。
> 一条「无法用证据钉住」的常量，必须在代码里标注成假设，不能进报告当结论。

### 2. 扫码状态码 65/67 是反的（只修 token 不够）

同一份 vendor JS 的 `ptuiCB` 分支：

```js
case "65": → set_qrlogin_invalid()     // 二维码失效
case "66": → 什么都不做                 // 继续轮询
case "67": → go_qrlogin_step(2)        // 手机端扫码成功，等待确认
case "22005": case "68": → 拒绝
```

v2.1.0 把 65 当「已扫描」、67 当「已过期」。后果是**用户一扫，界面立刻说
「二维码已过期」并停止轮询** —— 即使 token 修对了也永远走不到确认成功。

### 3. 单测不能拿实现的常量当协议

v2.1.0 的三个用例**把实现的假设钉成了「已知性质」**：断言初值 5381、
用 `'65'` 断言「已扫描」、用 `'67'` 断言「已过期」。它们**全绿，而功能全坏**。

> **规则**：码表与算法要拿**服务端响应**或 **vendor 代码**来钉。拿自己实现里抄来的
> 常量写断言，只是在给 bug 发合格证。本版的做法是两份证据都用上：
> ① vendor JS 的 `case` 分支；② 服务端**实际接受过**的 `qrsig → ptqrtoken` 向量
> （`QqQrLoginTest` 里钉了 3 组，即「服务端返回过 200」的那三个值）。

### 4. 错误分类：风控 ≠ 网络异常（这条对所有轮询/重试逻辑都适用）

v2.1.0 把 HTTP 403、空 body、解析失败、`IOException` **四类失败归成同一个 `null`**，
界面只能一律显示「网络不稳定，仍在重试…」，而且**永远不会停**（终止条件只有
14 分钟超时与明确状态码，403 不在其中）。

现在 `QqQrClient.poll` 返回三态（`QqQrLogin.PollResult`）：

| 结局 | 含义 | 策略 |
|---|---|---|
| `Status` | 拿到可解析的 `ptuiCB` | 按状态走 |
| `Unavailable` | 服务端**明确回绝**（非 2xx，或 200 却回了解析不出的内容） | 重试没有意义：累计 **3 次**停止轮询 → 说明原因 → **自动切网页登录**（留 2 秒让用户看见原因） |
| `NetworkError` | 超时/连接被断 | 这才是抖动，继续重试；连错 3 次才提示「网络不稳定」 |

归类抽成纯函数 `QqQrLogin.classifyPollResponse`（JVM 单测覆盖 403 / 各种非 2xx /
200+垃圾 body / 200+空 body / 200+正常 ptuiCB）。

### 5. 微信用户为什么必须走手机号登录

用户反馈微信登录的用户在网页版登不进去。三条路只有一条通：

| 路径 | 可行性 |
|---|---|
| 官方 App 那种「一键微信登录」（微信开放平台**移动应用 SDK**） | ❌ 要求 `appid` 与**签名**在腾讯侧配对注册；fork 的签名必然不同。**与代码质量无关** |
| 网页版微信登录（`open.weixin.qq.com/connect/qrconnect`） | ❌ 只能被**另一台设备**的微信扫：同一台手机扫不了自己的屏幕，**微信也不认相册里的登录码**（用户实测确认） |
| **手机号 + 短信验证码**（`music.login.LoginServer`） | ✅ 纯 HTTP，单机完成 |

### 6. 手机号验证码登录的实测形状（三个写错了服务端不报错的坑）

探针：`tools/probe-qq-phone-login.py`（**只用明显非法的号码**，脚本内置白名单硬校验，
白名单以外直接自杀退出 —— 绝不能向真实号码发短信）。

| 坑 | 实测 |
|---|---|
| `param.areaCode` **必须是 JSON 字符串** | 传数字 `86` → `req.code=10006`（**请求畸形，无 `data`**）。Kotlin 里 `.put("areaCode", 86)` 编译完全没问题 —— 这是最容易写错的一处 |
| `comm.tmeLoginMethod` **只在 `Login` 上是硬必需**（且必须 = 3） | `Login` 删掉它或值≠3 → `104400`；**`SendPhoneAuthCode` 删掉它毫无影响**。两个端点在这点上不一样，别顺手统一 |
| 假验证码 → `req.code=1000` + **完整 35 字段凭证骨架**（值全空） | 所以 `1000` =「凭证校验失败」而非参数错误，**绝不能靠「有没有 `data`」判成功** |

另外两条：`Login` **不校验号码格式**（`phoneNo="1234"` → 1000，不是 104400）；
`req.data.phoneNo` 是**请求参数回显 + 掩码**（`100****0000`），不是账号真实信息，别拿它当标识。

**成功路径没有实测**（要给真实号码发短信）—— 所以 `cookieFromCredential` 对
`musickey` / `musicid` 都做非空校验，缺任一就返回 null 并按失败处理：
宁可让登录明确失败，也不要落一份缺票据的 cookie（那会表现为「设置页显示已登录、
一取链就说没权限」，比直接失败难排查得多）。

**合规**：不读短信（验证码手输，**本版没有新增任何权限**）、不存手机号（只在内存里活着）、
不存密码（本就没有）。界面底部常驻合规说明。

### 7. 新增文案必须往分组里放，不能加外层 `Strings` 参数

外层 `Strings` 构造函数当时是 **244 个参数 = 245 个 dex 寄存器**（含 `this`），
上限 255，**余量只剩 10**（v2.0.0 · HF1 就是往它上面直接加字段而崩的）。
本版新增 15 条文案**全部加在 `SourceStrings` 分组里**（15 → 30 参数），
外层一个都没加。

> **规则**：加文案前先数一下外层构造参数。用这个片段数：
> 解析 `data class Strings(` 后深度为 1 的 `val x:` 行。接近 255 就往分组里塞。

### 10. 跨功能的文案不要复用（v2.1.3，用户当场报出来的）

手机号登录失败的三个分支都抓了扫码那条链路的 `sourceQrFailed`（「扫码登录失败」），
于是短信登录失败时界面显示「**扫码登录失败**」。用户的原话是
「我短信验证码登陆为什么会显示扫码登录失败?」—— 这句话在任何情况下都是错的。

**规则**：
1. **兜底分支（`else -> …`）最容易随手抓一个现成的字符串** —— 那正是最不该复用的地方，
   因为兜底意味着「我们不知道具体原因」，此时说任何**带前提**的话都是猜。
   兜底文案要**中性**（「登录失败，请稍后重试」），不要借别的功能的说法。
2. 一个功能有自己的失败文案。字面上都是「登录失败」，但扫码失败与短信登录失败是两回事。
3. 顺带一条同源的教训：`classify*` 不要「一律 else」。能按码表分桶就分（哪怕码表来自参考实现、
   未复现 —— 注释里如实标注即可），但**认不出来时必须中性作答**。

### 9. 图形验证码（`20276`）：风控该过就过，但不能把用户关进循环（v2.1.2）

真机反馈：手机号登录点「发送验证码」直接回 `20276`，而 v2.1.1 的界面只说「这条路走不通」。
**`20276` 不是错误，是风控在正常工作** —— 官方客户端遇到它也是弹 WebView 让用户滑一下。

**做法（不依赖验证页的 DOM）**：

| 步 | 做什么 |
|---|---|
| 1 | `data.securityURL` 取验证页地址（**这个 key 在实测的 `104400` 响应里就在，只是空串** —— 字段位置是钉死的，不是猜的） |
| 2 | 开在 WebView 里 |
| 3 | 完成时**把 WebView 的 cookie 读出来回传** |
| 4 | 重发时用 `QqClient.musicuLogin(extraCookie = …)` 带上 |

**第 3/4 步是命门**：验证是**会话级**的、结果落在 cookie 上，而 OkHttp 与 WebView
有各自独立的 cookie store。少了这两步，用户看到的是「验证完了还要再验证」——
**那是我们自己没接上，不是腾讯刁难**。凡是「WebView 里做了一件事、结果要给 HTTP 客户端用」
的流程都是这个形状。

**两个坑（都写进代码注释了）**：

1. **cookie 基线必须在首次 `onPageFinished` 之后才取**。验证页一进来就会写自己的会话 cookie；
   拿进入时的空快照当基线，第一次加载完就会被误判成「验证完成」，于是拿一份没验证过的
   cookie 去重试 → 被再次要求验证 → **死循环**。
2. **要有轮次上限**（现在是 2 次）。若「结果落在 cookie 上」这个前提不成立，
   继续弹验证页只是把用户关进循环，应当在阈值处停下来引导他去网页登录。

**明确不做**：不伪造/不获取 QIMEI 去降低风控等级。`QqIdentity.qimei36` 是**合成**的
（随机 hex + 补零），它很可能就是触发验证的诱因之一 —— 但「不模拟设备指纹、不绕过风控」
是本项目的红线。风控要验证就验证。

**日志纪律**：`20276` 的现场只记验证 URL 的**主机与长度**。URL 可能带一次性 token，
不把整条写进 logcat —— 与「不把凭证写进日志」同一条。

### 8. 播放页音源角标（v2.1.1）

播放页是用户唯一能确认「现在放的是哪家」的地方，所以**两个音源都标**
（`歌手 · ncm` / `歌手 · qm`），与列表页「只标非 ncm」的降噪策略不同。
落点五处共用 `PlayerCard.ArtistLineWithSource`。三个取舍：角标与歌手**同一行**
（窄屏顶栏是固定 56dp 的 Box，多一行会被裁）；`weight(1f, fill = false)` 只给歌手，
**角标永远完整可见**；`alignByBaseline()` 让 12sp 与 14/16sp 基线对齐。
**播放器的动画与手势代码一行未动。**
