# v3.5.0-gpl 发布验证证据

本文件记录 **Arris v3.5.0-gpl / versionCode 75** 的打包、测试、签名与归档事实。
构建在仓库内的隔离 worktree 中进行，**原 checkout 的用户未提交文档未被触碰**。

## 1. 源码基线

| 项 | 值 |
|---|---|
| 分支 | `sync/ncrust-142e908` |
| 版本提交（构建用） | `0c92f89`（`build: 升级至 v3.5.0-gpl（versionCode 75）`） |
| 构建时 `app/` 树 | `e6517c231a4b8a0be3213b91de8b3491839f61af` |
| 构建时 `HEAD:app` 与工作区 app 差异 | 无 |
| Kanesumi 复合构建源 | `/home/duanjb666/deepseek/Kanesumi-sec-a`，HEAD `acddf3e53d6a90f6b7d19d6a0445631457c3bda5`，工作区干净 |
| 复合构建镜像 | `.release-v3.5.0/Kanesumi-sec-a`（同源文件逐文件 `diff -rq` 一致；因沙箱只读 sibling 的 `.gradle`，仅排除 `.git/.gradle/build` 后复制） |

`app/build.gradle.kts` 中只有两处实际版本赋值：

```
versionCode = 75
versionName = "3.5.0-gpl"
```

## 2. 版本号三源交叉校验（原始输出）

在**升级前**提交 `5d3ce9b` 上运行仓库外脚本（带 fetch）：

```
ARRIS_REPO=<prebump worktree at 5d3ce9b> \
GHX=/home/duanjb666/deepseek/ghx.sh \
  bash /home/duanjb666/deepseek/tools/next-version.sh
```

原始输出见 `next-version-prebump-raw.txt`，要点：

- ① 最近 5 个 tag：`win-v1.0.0=6`, `v3.4.12-gpl=74`, `v3.4.11-gpl=73`, `v3.4.10-gpl=72`, `v3.4.9-gpl=71`
- ③ 升级前工作区：`versionCode=74 versionName=3.4.12-gpl`
- 三源最大值 **74 ⇒ 下一个可用 75**

脚本的 ② 只命中了本地缓存里的旧 APK；为补足「这个号是否已发布」这一来源，
另外下载了线上已发布 `v3.4.12-gpl` 的 release 资产并实测：

```
package: name='com.takahashirinta.ncrust' versionCode='74' versionName='3.4.12-gpl'
```

见 `published-v3.4.12-badging.txt`。三源（tag / 已发布 APK / 工作区）一致为 **74**，
因此本版取 **75**。`v3.5.0-gpl` tag 与 release 在创建前均不存在。

## 3. 构建命令与环境

实际命令（`clean` + 四个验收任务）：

```bash
cd /home/duanjb666/deepseek/Arris/.release-v3.5.0/Arris
export GRADLE_USER_HOME=$PWD/../gradle-home
export GRADLE_RO_DEP_CACHE=/home/duanjb666/.gradle/caches
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_USER_HOME=$PWD/../android-home
export XDG_DATA_HOME=$PWD/../xdg-data
export XDG_CACHE_HOME=$PWD/../xdg-cache
./gradlew clean :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleRelease \
  --console=plain --stacktrace --max-workers=4 \
  -Dorg.gradle.jvmargs="-Xmx6144m -XX:MaxMetaspaceSize=1536m -Dfile.encoding=UTF-8" \
  -Pkotlin.daemon.jvmargs="-Xmx3072m"
```

- 结果：**BUILD SUCCESSFUL in 5m 15s**（394 actionable tasks；106 executed / 288 up-to-date）
- SDK：`/home/duanjb666/Android/sdk`；build-tools **36.0.0**（aapt2 / apksigner）
- JDK：**OpenJDK 21.0.12.1**；Gradle wrapper **9.3.1**
- 完整日志：`.release-v3.5.0/logs/build-4.log`（发布时另存到 dist 发布目录）

> 环境说明：外层沙箱把 `~/.gradle`、`~/.android`、`~/.local/share/kotlin` 设为只读，
> 并把 `/tmp` 每次命令重置。因此使用工作区内可写的 Gradle/Android/Kotlin home，
> 并把 sibling 源码镜像到隔离目录。**没有修改任何 app 源码、构建脚本或签名配置**。
> Git 工作区仅用 `.git/info/exclude` 排除了 `.release-v3.5.0/`，未改 `.gitignore`。

## 4. 测试与 lint

| 项 | 结果 |
|---|---|
| `testDebugUnitTest` | **2934 tests / 0 failures / 0 errors / 3 skipped / 229 test classes** |
| `lintDebug` | **0 errors / 65 warnings**（既有基线与警告未改动、未放宽） |
| `assembleDebug` | 成功 |
| `assembleRelease` | 成功（R8 完成） |

测试统计来自 `app/build/test-results/testDebugUnitTest/*.xml` 的真实汇总，
见 `test-summary.txt`。

## 5. APK 验收

### 5.1 包名 / 版本（`aapt2 dump badging`，build-tools 36.0.0）

两个 APK 均为：

```
package: name='com.takahashirinta.ncrust' versionCode='75' versionName='3.5.0-gpl'
launchable-activity: name='com.takahashirinta.ncrust.MainActivity'
```

原始输出见 `apk-badging.txt`。

### 5.2 签名（`apksigner verify --print-certs`）

| APK | 证书 DN | 证书 SHA-256 |
|---|---|---|
| `Ncrust-v3.5.0-gpl-release.apk` | `CN=Ncrust, OU=Dev, O=Ncrust, L=changji, ST=xinjiang, C=CN` | `e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82` |
| `Ncrust-v3.5.0-gpl-debug.apk` | `C=US, O=Android, CN=Android Debug` | `ae74dadb83dab08ac5a673879558886b15d89075bb9fdb2b9c0002f2ec3d27f4` |

release 证书与线上已发布 v3.4.12 APK 的证书 **逐字节相同**（同一 SHA-256），
可覆盖安装；debug 是 Android 默认调试证书，与 release 不同属于预期。
原始输出见 `apk-signature.txt`。

### 5.3 产物哈希与大小

| 资产 | 字节 | SHA-256 |
|---|---:|---|
| `Ncrust-v3.5.0-gpl-release.apk` | 10,469,728 | `97bbf12db015ba07360508e26122c6d8097323a0484caae52c4ad2bf59100997` |
| `Ncrust-v3.5.0-gpl-debug.apk` | 31,817,714 | `050ba77e82de994692377d99e37f1d52c8788bf10b2ae89efcdf12a81a229d3e` |
| `mapping-v3.5.0.txt.gz` | 5,514,886 | `864c1e226d1a6b85134de125fcef1b3ce80c3466fcfbe4c947a89cbb0b3542b8` |

## 6. mapping 归档（按 AGENTS 铁律 27）

- release 构建完成后**立即**复制：`app/build/outputs/mapping/release/mapping.txt`
  → 仓库外原始件 `/home/duanjb666/deepseek/dist/mapping/mapping-v3.5.0.txt`
- 仓库内归档：`docs/verification/v3.5.0/mapping-v3.5.0.txt.gz`（gzip -9）
  + 同名 `.sha256`（内容为 `864c1e22...  mapping-v3.5.0.txt.gz`）
- 原始 mapping SHA-256：`69e6faf987cb35d1edc9945fdc9e94e9667b5d31ec5ee4535d4cfbd97dedf968`
- **没有覆盖**旧的 `mapping-upstream-142e908.txt.gz` 或 `dist/mapping/` 中任何历史文件。

## 7. 最终提交复核（在证据提交后执行）

判据（必须全部成立）：

1. `git rev-parse HEAD:app` == `e6517c231a4b8a0be3213b91de8b3491839f61af`；
2. `git diff --stat 0c92f89..HEAD -- app` 为空；
3. 在最终 `HEAD` 上再次执行 `:app:assembleRelease`，任务为 `UP-TO-DATE`
   （或产物 SHA-256 与上表相同），证明 tag 指向的就是产出这批 APK 的 app 源码树；
4. `git status --porcelain` 中只有用户原有的
   `docs/verification/v3.2.2/probe/curve-smoothing-measurements.md` 修改。

实际复核结果记录在 `/tmp/arris-release-final.json` 与
`/tmp/arris-release-report.md`；tag 只在该复核通过后创建。

## 8. 用户文档保护

`docs/verification/v3.2.2/probe/curve-smoothing-measurements.md` 在任务开始与结束时
SHA-256 均为：

```
5683659e6620297ed0260af600d3b520055eadd359166c35e06d34577f2b4152
```

该文件**从未暂存、从未提交、从未 stash/checkout 改写**。

## 9. 验证边界（不夸大）

- 本证据只覆盖构建、JVM 单测、lint、APK 元数据与签名；**没有真机测试**。
- **没有真实 QQ 一周续期 / 收藏隔夜 / 云端入账 / 真机 offload 的长期验证**；
  这些由用户在真机上验证，release notes 已如实列出。
- 本版创建的是 **GitHub 草稿 Release**，没有 publish、没有设置 latest。
- 线上已发布 v3.4.12 的 APK 仅用于 versionCode/证书交叉核对，未安装、未修改。
