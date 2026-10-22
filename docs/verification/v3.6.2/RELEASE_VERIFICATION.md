# v3.6.2-gpl 构建与验证

## 版本与源码

- 版本 `3.6.2-gpl`，versionCode **78**，包名 `com.takahashirinta.ncrust`，minSdk 24、targetSdk 36。
- 构建提交 `60951ca6c26e1405bd6a304710f622c273fb344a`，`app/` 树 `7fcc1d5de9df063460f57943cdfc9a50b4296294`。tag 指向实际构建提交，后续验证文档提交不改变应用源码。
- 定号前运行 next-version.sh（带 fetch），三源最大值 77；脚本只发现旧缓存，因此另以 aapt2 检查上一版签名 APK，确认 v3.6.1 为 77，输出随目录归档。
- 相邻复合构建 Kanesumi 为 `acddf3e53d6a90f6b7d19d6a0445631457c3bda5`，工作区干净。
- 在独立工作树 `Arris-review-fixes` 构建；主工作区 `Arris` 原有的测量文档改动未动。测试产生的旧曲线报告改动只在本独立工作树还原。
- AGP 未识别普通 Git worktree 的 `.git` 指针，APK 内 VCS 信息为 `NO_VALID_GIT_FOUND`；源码身份由构建提交、app 树、tag、外置 JSON 和固定 APK 哈希共同记录，不声称 APK 自带有效提交号。

## 两项产品修复

1. 搜索翻页候选页只在结果成功发布后提交；失败、取消保留同页重试，查询重置会使在途旧页无效。初始搜索与加载更多互斥，聚合请求全失败不伪装成已到末页。新增 3 项生产游标策略回归。
2. 本地歌单元数据、曲目及容量淘汰在同一个 SharedPreferences.Editor 中提交；检查落盘结果，失败向同步仓库报告 Failed。由于 SharedPreferences 即使磁盘失败也先更新内存，补偿还原旧内存快照，防止失败同步被 TTL 当作新鲜内容。新增 3 项事务回归，覆盖单次提交、模拟磁盘失败和容量裁剪；未做实际断电或真实文件系统故障实验。

对应原始审查见上一版 [SOURCE_REVIEW.md](../v3.6.1/SOURCE_REVIEW.md)。本轮验证限于这些修复和生日集成，不构成全仓库审计结论。

## 生日动画与迟到祝福

来源为维护者完成的 `birthday-motion` 页面；导入时原文件哈希见 [birthday-source-manifest.json](birthday-source-manifest.json)。原项目保持不变。应用打包 HTML、SVG、CSS、JavaScript、GSAP 和纹理资源，离线加载，未内嵌固定 MP4。原时间线长 48 秒，26.2 秒等待用户吹气或点按，因此实际观看总时长包含等待时间。竖屏调整前景和祝福布局，烟花画布按屏幕比例扩展；横屏保留原 16:9 构图。

WebView 只拦截专用本地 HTTPS origin 下的打包资产，禁用网络、文件和内容访问，原生桥仅处理有限状态事件。READY 和页面加载回调双向配置握手；页面未就绪时不会误启动。后台或关闭销毁页面及录音，失败/关闭动效/系统减少动画回退原生界面。七击版本号彩蛋继续使用原生层。

生日后第 **1～7 天（含第 7 天）**，若本年度生日当天未记录打开、且当年尚未展示祝福，先说明实际迟到天数；可点“收下祝福”，也会停留 4.2 秒后自动进入生日动画。超过 7 天不补播。生日当天仍直接庆祝。跨年按生日所属年去重；2 月 29 日在非闰年按 2 月 28 日处理。

打开记录只从本版开始保存在本机，保留有界的 32 年集合；旧年度“已展示”标记继续有效，无法追溯旧版本从未记录的历史打开行为。前台打开会记录生日当天，即使首页动画因其他界面暂缓。迟到说明被后台打断且动画未可见时不提前消耗年度标记；明确关闭尊重用户选择，不再当年自动弹出。设置中的预览不消耗年度标记。

新增 9 项日期/文案回归：当天、逐日 1..7、窗口外、跨年、已展示去重、生日当天打开抑制迟到、闰日、非法/禁用、八语言天数插值。

## 构建与回归

JDK 21，Gradle wrapper 9.3.1，SDK/build-tools 36.0.0；最终执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease \
  --offline --console=plain --max-workers=4 \
  '-Dorg.gradle.jvmargs=-Xmx6144m -XX:MaxMetaspaceSize=1536m -Dfile.encoding=UTF-8' \
  -Pkotlin.daemon.jvmargs=-Xmx3072m
```

- **BUILD SUCCESSFUL，2m14s**；最终日志位于仓库外 `dist/v3.6.2-gpl/build-release-final.txt`。
- **236 suites / 2983 tests / 0 failures / 0 errors / 0 skipped**，比上一版增加 15 项回归。
- Lint **0 errors / 61 warnings / 5 information**；没有降低门槛或新增 suppress。
- DEX 监控覆盖本轮 celebration、SearchPageCursor、LocalPlaylistStore、SearchViewModel 共 **788** 个带代码方法，全部低于 2000 code units。搜索协程方法为 **1642**，超过 1200 预警线；生日集成范围最大 1064。完整报告随目录归档；不是全量 DEX 无超限声明。

## 浏览器视觉与交互检查

Chromium 检查 6 个视口：390×844、360×640、430×932、844×390、1600×900、320×568；每个抽取 9.8、14.85、19.9、24.5、27.7、31.9、34.9、44.5 秒共 **48 个关键画面**，涵盖走位、端盘、点灯、许愿、吹气/黑场、烟花、抬头和祝福。检查主体边界、祝福与原生底栏预留区域、全屏烟花比例；另检查 14 组本地化竖屏布局。示例帧、几何测量和原测试脚本见 [browser/](browser/)。

六种视口均无控制台/页面错误；资源请求全部本地；验证 READY 前过早配置、26.2 秒停留、键盘不能绕过等待、首次点按、重复点按、DONE 停止、重播和销毁。原脚本保留实际执行时的本机路径；复跑时需将 Playwright、资源根目录、输出目录与 locales.json 路径改为自己的位置。检查使用确定时间截图与真实播放跨越停点相结合，没有声称逐帧验证全部 1440 帧。

## Android 运行检查

仅使用 API 33 的 `emulator-5554`（Android System WebView 109.0.5414.123）。最终签名 Release APK 覆盖安装成功并冷启动；通过设置页打开生日预览，确认原生等待控件可见，点按吹灭后继续烟花与祝福，结束出现“继续听歌 / 再看一次”，点“继续听歌”成功返回。由此核对 R8 后的 JavaScript 桥及有限状态事件可用。示例截图见 [emulator/](emulator/)。没有使用麦克风；迟到日期分支由 JVM 确定日期测试覆盖，未通过改系统时间或私有偏好伪造运行结果。调试期间的端口转发已移除，正式包未开启 WebView 调试。

按用户要求未安装或操作任何实体手机；未测真实吹气识别率、真实麦克风/耳机路由，也未声称模拟器代表真机性能。麦克风沿用明确授权后短时前台 AudioRecord，不保存、不上传、不请求音频焦点或改路由；点按始终可用。最低 API 24 WebView 与不同厂商设备仍需用户真机反馈。

## 签名与归档

Release APK 11,945,532 字节；SHA-256 `79163686ab33ba8bf5cdab0f6f9bf91c67dd602d99b941b8473ed2fdc4b05785`。Debug APK 33,841,734 字节。签名证书 SHA-256 `e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`，与上一版一致。签名检查通过；Ncrust 资产名前缀保留供旧更新器识别，显示名仍为 Arris。日常覆盖安装使用 Release 包。

成功构建后立即保留最终 R8 mapping 原文到仓库外 `dist/mapping/mapping-v3.6.2-gpl-60951ca.txt`，压缩件、校验文件和 APK 哈希随版本归档。此前候选 mapping 也已在后续 R8 之前单独保留。生成验证文档后不再重打包。GitHub 仅创建 **draft Release**，由维护者实机测试后决定发布。
