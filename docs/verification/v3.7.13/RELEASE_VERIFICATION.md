# Arris 3.7.13-gpl 弱网与播放操作修复验证

完成：2026-10-29 23:21:30 CST。本地测试交付，无GitHub推送、标签或Release；没有恢复已结束的核心审查定时任务。

## 源码与版本

- 构建源码提交：`2e64aed602df7caf9932fc853e4ea9eedfcdfcff`；分支feat/bilingual-media-lyrics。
- app源码树：`1a2d65c886ec8e66cea77eedfe80fa705d95b8c3`。后续文档收尾不改变此源码树。
- 显示版本3.7.13-gpl，versionCode 100。动版本前重新运行带fetch的next-version.sh，三源最大99，取100；原始输出见next-version.json。
- 本轮继续包含3.7.11正式整合与双语媒体歌词更新。旧3.7.11/99与3.7.12/98 APK逐个校验，指纹与既有档案一致，没有覆盖；原Arris-playback-fixes的31处他人修改和未跟踪docs/research保留。

## 用户场景与改动

1. 用户反馈：弱网/断网、下一首未缓存后，界面留在上一首，播放键与切歌无效；从库点缓存歌曲才能恢复。源码中明确断网且无缓存仍会进入远程阶梯、新歌元数据等待URL成功才提交、失败后播放键直接恢复旧媒体项、默认路由切换没有恢复协同，分别留下等待与旧状态。
2. 当前改动：先显示所选歌曲并停止旧项；明确离线无缓存进入可重试等待；新的选择撤销旧请求/重试/预载；播放键重新解析未就绪/失败项。JSON取消覆盖响应体读取，远程取链15s有界，超时后仍尝试完整缓存；音质能力补查1.2s有界。
3. 默认网络变化合并500ms，只恢复最后未暂停且仍在取链/等待/缓冲的请求。INTERNET为在线判据，VALIDATED仅变化信号；健康播放不因切网重启。持续缓冲12s按次数门重新取链，暂停/停止/新选择撤销，正常暂停后续播重新绑定当前代次。
4. 连点上/下一首每次推进游标，停手220ms只提交最后目标，避免逐个取链与封面过渡积压。
5. U01已获追加授权：竖屏、横屏封面采用可用边长88%，标题未测量时先留位，并响应密度/字体变化。沿用单一封面动画节点。

完整说明见RELEASE_NOTES.md。这里记录源码诊断与修复，不声称已复现或验证用户手机上的实际网络切换。

## 执行检查

| 检查 | 最终结果 | 证据 |
|---|---|---|
| 完整JVM | 3386项 / 311类，0失败、0错误、0跳过 | jvm-results.zip / verification.json |
| Lint Debug | 0错误，61警告，7信息 | lint-results-debug.xml / .txt |
| Debug与签名Release | 构建成功 | full-build.txt |
| APK版本 | 3.7.13-gpl / 100 | apk-badging.txt |
| 签名 | 验证通过，证书与既有正式包相同 | apk-signature.txt |
| 源码对应 | 构建时app无未提交修改，树为上述指纹 | 本文 / verification.json |

命令：

```text
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --offline --no-daemon '-Dorg.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
```

第一次全量3382项有3个失败：异步OkHttp执行线程不再是特定Dispatcher线程，更新线程断言以继续禁止Main；拦截器抛出的取消被OkHttp包装成IOException，传输层补充取消解包，并增加回归。原始日志、XML和统计保留为full-build-initial.txt / initial-jvm.zip / initial-summary.json。新增测试一度缺少toResponseBody扩展导入，编译失败日志为full-build-import-error.txt，补齐后重新成功。后续分别补齐暂停续播绑定、取链超时后的缓存回退，之前成功日志另存before-resume-fix、before-cache-timeout-fix；最终结果只计上表这次完整执行，不累加历史轮次。早期40项定向结果明确标为最终计时器/后续边界修正之前，不冒充最终结果。

## 证据层级与源码核对

- 本地隔离执行：BufferedHttpCallTest调用实际传输辅助函数，拦截响应头后阻塞响应体，撤销旧协程，确认Call已取消且新请求不等旧响应体；所有响应由本地interceptor返回，无socket、账号或云端写入。
- 实际纯逻辑执行：resolveOfflineFirst、PlaybackRecoveryGate、LatestPlaybackAction与UrlRetryGate的生产实现。覆盖离线有/无缓存、请求中断/超时回退、主动取消不提交旧缓存、最后选曲恢复、暂停撤销、续播重新接管、同网络句柄的能力变化、健康播放不重启、重试计时归零及多次点击/换向/显式取消。
- 几何执行：CoverGeometryTest、PlayerLayoutTest核对缩小后的尺寸、边界与已有极端输入；这是数学布局约束验证。
- Android系统网络回调、PlayerViewModel与Service整体生命周期、Compose动画/布局和MediaSession的真实系统执行，属于源码调用链核对，未在设备执行。不能把上述JVM当作真实界面复现。

| 范围 | 定位（构建SHA） | 层级 |
|---|---|---|
| 取链、重试与播放键 | `app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt:295` | 源码调用链核对 |
| 默认路由监听 | `app/src/main/java/com/takahashirinta/ncrust/network/DefaultNetworkObserver.kt:15` | 源码调用链核对 |
| 完整响应体取消 | `app/src/main/java/com/takahashirinta/ncrust/network/BufferedHttpCall.kt:12` | 源码调用链核对 |
| 失败/取链等待命令 | `app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt:937` | 源码调用链核对 |
| 媒体会话执行入口 | `app/src/main/java/com/takahashirinta/ncrust/player/QueueSessionPlayer.kt:18` | 源码调用链核对 |
| 连续点击合并 | `app/src/main/java/com/takahashirinta/ncrust/player/LatestPlaybackAction.kt:7` | 源码调用链核对 |
| 离线及超时回退 | `app/src/main/java/com/takahashirinta/ncrust/player/OfflineFirstResolution.kt:8` | 源码调用链核对 |
| 队列游标/延迟提交 | `app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt:1556` | 源码调用链核对 |
| 竖屏封面 | `app/src/main/java/com/takahashirinta/ncrust/ui/player/CoverGeometry.kt:29` | 源码调用链核对 |
| 横屏封面 | `app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerLayout.kt:290` | 源码调用链核对 |

本轮仅复核变更和相关调用方，不重新申报核心源码通读覆盖。

## 产物

目录：`/home/duanjb666/deepseek/dist/v3.7.13-gpl-code100`。

| 产物 | 文件 | Bytes | SHA-256 |
|---|---|---:|---|
| release | Arris-v3.7.13-gpl-release.apk | 12,121,852 | `6c0b3424302dde55d648e408362258d897f89de8441905664a7d2cc5c590f0be` |
| debug | Arris-v3.7.13-gpl-debug.apk | 34,756,213 | `037a03a19eadaf528878773f3c8007f46ac19f296031bf6ea33ab813cdb2047f` |
| mapping | Arris-v3.7.13-gpl-mapping.txt | 80,484,200 | `b0941189a40de7820c09edb38ff1397daf67d0250e7eeef5a15e6b14853bca49` |

发布证书SHA-256：`e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`。mapping原文在产物目录，压缩副本mapping.txt.gz随证据提交。完整JVM产生的旧曲线测量写入已移存curve-smoothing-measurements-current-run.md，历史原文件恢复为HEAD原始字节。

**未做真机/模拟器、可见浏览器、自动播放、真实账号、真实网络切换或云端写入验证。** 没有推送或上传GitHub。真实网络切换时的运营商/ROM差异、通知控件显示与封面动态观感，保留为测试包设备验收项。
