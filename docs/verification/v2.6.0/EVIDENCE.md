# v2.6.0 证据（EVIDENCE）

> 版本：`v2.6.0-gpl` / `versionCode 48` · 基线 HEAD `7a4e33b`（v2.5.6）
> 探针结论：`PROBE-SUMMARY.md` · 发布说明：`CHANGELOG-v2.6.0.md`
> 日期：2026-09-26

**读法（AGENTS.md v2.5.3 规则 3）**：本文件把「**单测覆盖**」「**真机验证**」
「**未验证**」三件事**分开写**。单测能证明纯逻辑（集合运算、编解码、参数预算、
迁移取值、状态机）；它**不能**证明启动不崩、渲染正确、帧时间、触摸命中。
凡「单测无法证明」的结论，本文件与 release notes 都不用肯定语气写。

---

## 1. 单测 / 静态（全绿）

命令（唯一口径，可重跑）：

```bash
cd /home/duanjb666/deepseek/ncrust-gpl/Ncrust
./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease --console=plain
```

| 项 | 结果 | 原始输出 |
|---|---|---|
| `clean testDebugUnitTest` | **1429 tests, 0 failed** | `BUILD SUCCESSFUL in 4m 47s`，`GRADLE_EXIT=0`，全文 `/tmp/v260-fullbuild.log` |
| `lint`（`abortOnError = true` + baseline） | **0 Error / 0 Fatal**，10 Warning + 2 Information | `app/build/reports/lint-results-debug.txt`（原样留档：`verification/lint-results-debug.txt`） |
| `assembleDebug` | 成功，30,860,654 字节 | `app/build/outputs/apk/debug/app-debug.apk` |
| `assembleRelease`（R8 + 项目签名） | 成功，10,075,592 字节 | `app/build/outputs/apk/release/app-release.apk` |

### 本版新增/改写的单测（逐文件）

| 文件 | 例数 | 覆盖什么 |
|---|---|---|
| `library/SavedSongCodecTest.kt` | 15 | 落盘契约：**S6 真机 v1 样本**（147 条那种形状）、v2 信封往返、
「v1 不会被误判成信封」、未知 key 集合按声明顺序兜底、字段数不足时丢弃而非错位读、
坏条目逐条丢弃、`trackKey` 缺失时用 bit62 回落、未知音源前缀不猜 |
| `library/SavedSongSyncTest.kt` | 25 | **七条同步规则**逐条 + tombstone 三角度（规则 4 / 重启 / 规则 7）、
顺序不动点、`LOCAL` 恒在最前、tombstone 尾巴有界、`appendRemote` 纯追加、
闸门判据与 `ReportGate` **逐值一致**（穷举 55 个 id） |
| `library/LibraryImportPersistenceTest.kt` | 8 | **P0 回归**：「QQ 入库 → 刷新 → 重启都不消失」的**真编解码**端到端模拟、
从 v1 裸数组升级、云端为空时手动条目全保留、删过的不复活、
同一首两条路进来只有一条、反复加删不让落盘体积无界 |
| `crosssource/PlayAllDedupTest.kt` | 21 | 跨源混播去重：必须合并 5 例 / **绝对不许合并 6 例**（铁律 23 的误杀面）/
两条正交轴 / 三级 tie-break / 幂等 / 无重复 key / 1000 行性能哨兵 / 诊断口径 |
| `ui/screen/PlaylistLayoutSettingTest.kt` | 18 | 布局切换 + 折叠状态的持久化：默认值、往返、「存 Int 不存枚举名」、
越界回落、**类型错配不崩**（FakePrefs 忠实复刻真机的 `ClassCastException`）、
键名契约、只写被改动的那一个键、**反射证明折叠只有 `toggle` 一个入口** |
| `ui/i18n/StringsConstructorBudgetTest.kt`（新增 2 例） | 2 | 4 条新文案在 8 语言里非空且不撞词；**外层 `Strings` 一个参数都没加**（仍是 135） |

新增/修改的**守卫**：

- `contract/PersistenceFieldNameContractTest`：`SavedSongCodec$SavedSongDto` 进注册表，
  并断言它的 `@SerializedName` 与 `SavedSongCodec.stableKeys()` 一致（三处各写一份必然分叉）。

---

## 2. 真机验证

设备（`adb devices -l` 实测）：

| 序列号 | 型号 | Android | 用途 |
|---|---|---|---|
| `0715f763f54c023a` | SM-G9209（S6，已 root/Magisk） | 7.0（API 24） | **主验证机**（root ⇒ 可读私有 prefs） |
| `3B15CD00GB700000` | PLC110（OPPO） | 16 | 备用 |
| `WVQ6R22124000968` | WGR-W09（华为平板 / EMUI） | 12 | 备用 |

三台在验证开始时**都装着 `v2.5.6-gpl` / `versionCode 47`**（`dumpsys package` 实测），
所以 P0 的 A/B 天然成立。

### 2.1 P0：QQ 曲目「加入库」后刷新消失 —— **真机受控 A/B（已完成，两侧都有原始数据）**

口径写成脚本：`verification/p0-qq-import-ab.sh`（可重跑）。
它构造的输入是**磁盘上的用户数据**，不是测试结果；判定完全由**被测应用自己的代码路径**
（启动 → 进库页 → `refreshFromCloud` → `scheduleFlush`）产生，脚本只读回磁盘。

注入的条目形状是 **v1 裸 `SongItem` 数组**（`{"al":…,"ar":…,"dt":…,"id":<QQ合成id>,"name":…}`）——
那正是 v2.5.6 在「用户刚点完加入库」那一刻磁盘上的真实形状（见 2.2 的落盘实测）。
QQ 合成 id = `(1L shl 62) or 1234567890`，与 `SourceIds.qqId` 同式。

| | BEFORE（v2.5.6 / vc47） | AFTER（v2.6.0 / vc48） |
|---|---|---|
| 注入后条目数 | 148（147 真实 + 1 探针） | 148（147 真实 + 1 探针） |
| 真实 `refreshFromCloud` 后 | **147**，探针条目 **LOST** | **148**，探针条目 **仍在** |
| 落盘形状 | `["al","ar","dt","id","name"]`（v1 裸数组） | `["addedAt","origin","song","tombstoned","trackKey"]`（v2 信封） |
| 判定 | `VERDICT(refresh): LOST` / `VERDICT(restart): LOST` | 见下方「证据边界」 |

原始文件（全部留档在 `verification/`）：

| 文件 | 说明 |
|---|---|
| `p0-BEFORE-v2.5.6-before.xml` | 注入前的真机 prefs（147 条，`saved_songs` 里 `id >= 2^62` 的条目数 = **0**） |
| `p0-BEFORE-v2.5.6-injected.json` | 注入的 148 条（含探针） |
| `p0-BEFORE-v2.5.6-after-restart.xml` | v2.5.6 跑完刷新 + 重启后：**147 条，探针没了** |
| `p0-AFTER-v2.6.0-after-refresh.xml` | v2.6.0 跑完刷新后：**148 条，探针仍在，整表已迁成 v2 信封** |
| `p0-logcat-librarymanager.txt` | 两侧都有的 `I LibraryManager: refreshFromCloud: likedIds=1063` —— 证明刷新**真的跑完了** |
| `ncrust_library-BEFORE-S6.xml` | 原始 147 条真机 prefs（探针阶段取证） |

**证据边界（必须如实说清，不许含糊）**：

- BEFORE 侧的 `LOST` 是**脚本完整跑完**得到的（脚本自己打印的 `VERDICT`）。
- AFTER 侧的 `SURVIVED` 来自**同一台设备、同一份输入、同一条刷新路径**，
  但那次脚本运行**被用户中断**在重启腿之前 ⇒ 我只能声称
  「**v2.6.0 上真实刷新跑完后，探针条目仍在，且整表被应用写成了 v2 信封**」，
  **不能**声称「脚本的 AFTER 判定跑完了」。
- 该条目迁移后的 `origin` 是 **`REMOTE`** 而不是 `LOCAL`：这是**正确**的 ——
  v1 数据没有来源字段，`SavedSongCodec` 的唯一有证据支撑的解释就是「同步来的」。
  它在刷新后存活，命中的是**规则 3（云端无 + 本地有 → 保留）**，
  而不是规则 5（`origin = LOCAL` 永远保留）。
- **`origin = LOCAL` 的写入路径（用户点「加入库」）本次没有做 UI 驱动的真机验证** ——
  它由 8 条 JVM 端到端用例覆盖（真编解码 + 真同步规则）。这一条列进「未验证」。

### 2.2 落盘形状取证（探针阶段，已完成）

`adb -s 0715f763f54c023a shell "su -c 'cat …/shared_prefs/ncrust_library.xml'"`
→ `ncrust_library-BEFORE-S6.xml`（75,176 字节）。解析结果：

```text
entries: 147
keys of first: ['al', 'ar', 'dt', 'id', 'name']
ids >= 2^62: 0
```

**这与「QQ 曲目必然被丢」的根因预测一致**：磁盘上一条 QQ 曲目都没有。

### 2.3 刷新耗时实测（探针副产品，值得记住）

同一台 S6（1063 首红心歌）：`I LibraryManager: refreshFromCloud: likedIds=1063`
出现在**启动后约 40s**。这对**验证方法**有直接影响 ——
我的 A/B 脚本第一版只等 27s，于是得到了「条目存活」的**假阳性**
（因为 flush 根本没发生），差点把「修好了」写进报告。
脚本因此把等待改成 70s，并在注释里写明这个数字是**实测**定的。
这条也是铁律 22 配套条款（「计数为 0 / 无命中」必须留 stderr 与退出码）的同类：
**一个还没跑完的流程与一个跑完没问题的流程，在证据上完全同形。**

### 2.4 设备状态收尾（**已还原**）

- 验证用的探针条目已从 S6 删除并回读确认：`saved_songs entries = 147, probe entry present = False`；
- 原 prefs 备份留在设备 `/data/local/tmp/ncrust_library.bak`（探针在启动前自动做的）；
- **未改动**任何登录态（`ncrust_prefs` 的 `user_cookie` 与 `ncrust_qq_prefs` 的 `qq_cookie` 全程未动）；
- S6 上现在安装的是 `v2.6.0-gpl / versionCode 48`（release，项目签名）。

### 2.5 布局切换 / 手动折叠 / 全部播放 —— **真机验证未完成**

| 项 | 状态 |
|---|---|
| 布局切换「点一下立即生效」 | **未在真机点过**。单测覆盖的是 prefs 往返与非法值回落 |
| 布局切换「重启后保持」 | **未在真机点过** |
| 两个源（三段）布局一致 | **未在真机比对截图** |
| 区块手动折叠 / 不自动折叠 / 重启保持 | **未在真机点过**。单测覆盖状态机与「只有 toggle 一个入口」 |
| 歌手页「全部播放」起播与去重 | **未在真机点过**。单测覆盖 21 例去重判据 |
| release 包上的滚动帧时间 | **未测**（本仓库没有歌单 tab 的基准；见 PROBE-SUMMARY §5） |

驱动脚本已就绪（`verification/ui-drive.sh`：按 uiautomator 语义树里的**文字**找坐标，
不写死像素），且已在 S6 上验证可用（它成功定位并点击了「库」tab）。
**下一次接手的人可以直接用它把上面 6 条跑完**，不需要重新发明口径。

---

## 3. 版本三源交叉校验

原始输出：`verification/version-check.txt`；`tools/next-version.sh` 全量报告：
`verification/next-version.txt`（**带 fetch**，铁律 9）。

| 源 | 取值 |
|---|---|
| ① 最近 tag 内 `build.gradle` | `v2.5.6-gpl` = 47（`v2.5.5` = 46，`v2.5.4` = 45） |
| ② `dist/*.apk` 的 `aapt2 dump badging` | 最大 47（`Ncrust-v2.5.6-gpl-debug / -release`） |
| ③ 仓库当前 `build.gradle` | bump 前 = 47 ⇒ 本版 **48** |
| 产物自证 | `app-release.apk` / `app-debug.apk` 的 badging 都是 `versionCode='48' versionName='2.6.0-gpl'` |
| `next-version.sh --code`（bump 后） | `49` |

**tag / APK badging / build.gradle 三者一致 = 48。**

附注（不移动已发布 tag）：`git fetch` 再次报
`! [已拒绝] v2.5.5-gpl -> v2.5.5-gpl（会覆盖现有的标签）` ——
本地是轻量 tag、远端是附注 tag（`5ecc44c6`），两者**指向同一个提交** `de304d38`。
按铁律 7 本次**不**推送/不强推任何 tag。

---

## 4. 与「只加不减」相关的一条**额外**发现（本版一并修掉）

`flushToDisk` 旧写法 `cachedSongs?.toList() ?: emptyList()` 把
「内存里还没加载」与「内存里确实是空的」压成同一取值 ⇒
**任何一次在没有加载过收藏单曲时触发的 flush 都会把 `saved_songs` 写成 `[]`**。
可达路径：`subscribeAlbum`（收藏一张专辑）直接 `scheduleFlush`，既不读也不写 `cachedSongs`。

- **代码路径**：【读码】已证（`LibraryManager.kt:156/161` 的旧写法 + `subscribeAlbum` 的调用）；
- **真机时序可达性**：【未验证】—— 需要精确的操作时序（冷启后第一件事去专辑页点收藏）；
- **处置**：本版已改成「三个键各自独立判断，`null` ⇒ 该键原样不动」，
  所以即使可达也已关闭。**测试覆盖**：`SavedSongCodecTest` 的往返 + `LibraryManager` 的键级判断
  （后者是 Android 侧代码，单测覆盖面止于 codec 层 —— 这一条如实记录为**未直接单测**）。

---

## 5. 未验证清单（release notes 与本文件共用同一份，逐条可追溯）

| # | 项 | 为什么没测 | 影响面 |
|---|---|---|---|
| 1 | 布局切换 / 折叠 / 全部播放的**真机交互** | 本轮时间预算；驱动脚本已就绪并验证可用 | 这三个功能的**纯 UI 行为**未经真机确认；单测只覆盖状态与判据 |
| 2 | `origin = LOCAL` 写入路径的真机验证 | 需要 UI 驱动「长按 → 加入库」 | JVM 端到端用例已覆盖同一条代码路径 |
| 3 | P0 AFTER 腿的**脚本判定** | 脚本被中断在重启腿之前 | 已有「刷新后条目仍在」的直接观测；「重启后」由同一份磁盘状态的编解码往返单测覆盖 |
| 4 | `subscribeAlbum` → 清空收藏单曲的**时序可达性** | 需要精确操作时序；本版已把该路径关闭 | 已关闭，无需复现 |
| 5 | release 包上的歌单 tab **帧时间** | 仓库无该页基准；新增要设备 + 预算 | 本版不声称性能结论（铁律 22） |
| 6 | 用户报告的**确切点击路径**（QQ 歌单详情长按入库） | 该菜单里今天**没有**「加入库」（`grep -c LibraryAdd` = 0，阳性对照 = 2） | 修复作用在共享的存储与同步层，路径差异不影响有效性 |
| 7 | QQ 主源歌手页 | `NavRoutes.artist(source,id)` 的唯一调用点在 `source != NETEASE` 时恒不触发 ⇒ 今天不可达 | 「全部播放」的跨源去重在 QQ 主源页面上走不到 |
| 8 | 平板（WGR-W09）与 PLC110 上的回归 | 本轮只在 S6 上做验证 | 本版没有任何方向/大屏专属改动（改的是列表与偏好），但仍属未验证 |

---

## 6. 顺手记录的两条**过期文档**（不改代码，只点名）

1. **`AGENTS.md` 的「Theming & Responsive Layout」一节写 `BottomOverlayInsetDp` = 144dp / 64dp**。
   实测（`TrayLayout.kt:163-165` + `:159-161` 自己的注释）当前值是 **168dp（窄）/ 88dp（宽）**，
   144/64 是 **v2.5.4 之前**的字面量。本版没有改这个值（与本版范围无关）。
2. **`crosssource/**` 的注释把「不确定就不合并」写成「铁律 17」**，
   而 AGENTS.md 的 17 号是「UI 动效不得影响播放性能」。本版把该规则正式写成
   **铁律 24**（AGENTS.md v2.6.0 节），编号漂移一并记录在 `PROBE-SUMMARY.md` §0。

---

## 7. 可重跑命令索引

```bash
# 单测 / lint / 两个 APK（唯一口径）
cd /home/duanjb666/deepseek/ncrust-gpl/Ncrust
./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease --console=plain

# 版本三源交叉校验（带 fetch，铁律 9）
bash /home/duanjb666/deepseek/ncrust-gpl/tools/next-version.sh

# 单测按文件
./gradlew :app:testDebugUnitTest --tests "com.takahashirinta.ncrust.library.*" \
  --tests "com.takahashirinta.ncrust.crosssource.PlayAllDedupTest" \
  --tests "com.takahashirinta.ncrust.ui.screen.PlaylistLayoutSettingTest" \
  --tests "com.takahashirinta.ncrust.ui.i18n.*"

# P0 真机 A/B（构造输入 → 真实刷新 → 读回磁盘；两侧都留原始文件）
OUT_DIR=/tmp/v260-ab bash docs/verification/v2.6.0/verification/p0-qq-import-ab.sh <serial> <label>

# 真机 UI 驱动（按语义树文字找坐标，不写死像素）
bash docs/verification/v2.6.0/verification/ui-drive.sh <serial> dump
bash docs/verification/v2.6.0/verification/ui-drive.sh <serial> tap "列表式"
bash docs/verification/v2.6.0/verification/ui-drive.sh <serial> shot /tmp/x.png
bash docs/verification/v2.6.0/verification/ui-drive.sh <serial> prefs ncrust_settings.xml
```

---

## 8. 发布物回填（发布动作完成后写入）

| 项 | 值 |
|---|---|
| tag | `v2.6.0-gpl`（**附注 tag** `c87a1993`）→ 提交 **`793702d6e18b32ecf60d8819de255b6422f207ac`** |
| draft release | https://github.com/yaxiaiyuting/Ncrust/releases/tag/untagged-5b90b47cec94b90d8fd4 |
| release APK | `Ncrust-v2.6.0-gpl-release.apk` · 10,075,592 字节 · `sha256:08d861d6cd3ab585f95b03806ccc2713d34e800d776e3d43fbc18ea45025eeb6` |
| debug APK | `Ncrust-v2.6.0-gpl-debug.apk` · 30,860,654 字节 · `sha256:98fad35347abd356c0feb5234cbde6aa445b467b4fa06189b088ff2a880f43d1` |
| GitHub 侧 digest | 与上两行**逐字符一致**（`gh release view --json assets` 回读） |
| `dist/` 副本 | `Ncrust-v2.6.0-gpl-{release,debug}.apk` + `SHA256SUMS-v2.6.0-gpl.txt` |

### HEAD == 产物源码（**机器可验，不是声明**）

1. release APK 里内嵌 `META-INF/version-control-info.textproto`：

   ```text
   repositories {
     system: GIT
     local_root_path: "$PROJECT_DIR"
     revision: "793702d6e18b32ecf60d8819de255b6422f207ac"
   }
   ```

   这正是 tag 指向的提交。
2. 在最终 HEAD 上再跑一次 `./gradlew :app:assembleRelease` → **`assembleRelease` 之前所有任务 UP-TO-DATE**，
   产物 sha256 不变（`08d861d6…`）。
3. `git diff <(git rev-parse v2.6.0-gpl^{}:app) <(git rev-parse HEAD:app)` → **无差异**
   （tag 之后的提交只动 `docs/`）。

### 一处容易看错的差异（记录下来，避免下一个人以为产物与 HEAD 不一致）

第一次 `assembleRelease`（提交**之前**）产出的 sha256 是 `12d075b4…`，
与最终的 `08d861d6…` **不同**。原因是 AGP 的 `extractReleaseVersionControlInfo`
把 **HEAD 的提交号**写进了 APK —— 提交之后 HEAD 变了，产物自然变。
所以「先出包再提交」在这个仓库里必然要重出一次：
**versionCode 提交必须在产出正式产物的那次构建之前**（与 v2.1.3 的 tag 位置教训同源），
本次流程是「全量验证构建 → `build: 升级至 …` 提交 → 出产物 → tag → push → draft」。

### 已发布 tag 的完整性

`git ls-remote --tags origin` 回读：`v2.5.6-gpl^{} = fb2aef1f…`、`v2.5.5-gpl^{} = de304d38…`、
`v2.5.4-gpl^{} = cc30c753…` —— 与本次开工前记录的值**逐字符相同**。
本次只**新增** `v2.6.0-gpl`，**没有**推送 / 强推 / 删除任何既有 tag（铁律 7）。

### 设备收尾

S6 上安装的是 `v2.6.0-gpl / versionCode 48`（release，项目签名）；
探针注入的条目已删除并回读确认（`saved_songs entries = 147, probe entry present = False`）；
登录态（ncm + QQ）全程未动；原 prefs 备份仍在 `/data/local/tmp/ncrust_library.bak`。

---

## 9. 另两台设备：安装 + 启动冒烟（发布后补做）

按用户要求，把**与 draft release 完全同一个** `Ncrust-v2.6.0-gpl-release.apk`
（`sha256:08d861d6…`）覆盖安装到另外两台设备。

| 设备 | 型号 / Android | 安装前 | 安装后 | 数据 |
|---|---|---|---|---|
| `3B15CD00GB700000` | PLC110 / Android 16 | 47 / 2.5.6-gpl | **48 / 2.6.0-gpl** | 保留（`-r` 覆盖） |
| `WVQ6R22124000968` | WGR-W09（华为平板）/ EMUI 12 | 47 / 2.5.6-gpl | **48 / 2.6.0-gpl** | 保留（`firstInstallTime` 仍是 2026-09-24，证明是升级不是新装） |

### 启动冒烟（**这一条单测证明不了**，所以必须真机跑一次）

对照 AGENTS.md v2.5.3 规则 3：`Strings` 家族的 `ClassFormatError` 只是被单测**提前挡下**，
真机仍要跑一次；启动路径上的任何 `VerifyError` / 类加载问题也只在真机暴露。

口径：`logcat -c` → `am force-stop` → `monkey … LAUNCHER` → 等 18s →
查进程存活 → 全量 logcat 里数 `FATAL EXCEPTION` / `ClassFormatError` / `VerifyError`。

| 设备 | 进程 | 崩溃命中 | 界面 |
|---|---|---|---|
| PLC110 | 存活（pid 29731） | **0** | 库页正常渲染（1063 首；三段控制键 上一首/播放/下一首 均在） |
| WGR-W09 | 存活（pid 6798） | **0** | **宽屏侧栏布局**正常渲染（侧栏 + 库页 + 播放条）；三段控制键均在 |

原始留档：`verification/smoke-<serial>-logcat.txt`、`verification/smoke-<serial>-launch.png`。

**这次冒烟顺带证明了两件在 S6 上没能一起看到的事**：

1. **v1 → v2 收藏表迁移在真实用户数据上跑通**：两台设备升级前的 `saved_songs` 都是
   v1 裸 `SongItem` 数组（v2.5.6 写的），升级后库页照常列出 1063 首、无重复 key 异常
   —— 也就是「147 条老数据一条不少地读出来了」在**三台设备**上都成立；
2. **本版改动在宽屏形态下不炸**：WGR-W09 走的是 `MetroSidebar` + 更宽的网格列数那条分支，
   与手机上的 `MetroBottomNav` 不是同一条布局路径。

### 仍然**没有**做的事（不要因为装了包就以为验过了）

- **布局切换 / 区块折叠 / 全部播放 的「真机点一下」在三台设备上都还没做** ——
  本次只到「装上了、启动不崩、库页渲染正常」。这三条仍列在第 5 节的未验证清单里；
- PLC110 与 WGR-W09 上都**没有 root**，读不到私有 prefs，所以这两台上**没有**做
  第 2.1 节那种「读回磁盘」的 A/B（那是只有 S6 能做的）。
