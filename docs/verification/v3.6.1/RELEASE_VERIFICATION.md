# v3.6.1-gpl 构建与验证

## 版本与源码

- 版本：`3.6.1-gpl` / versionCode **77**；包名 `com.takahashirinta.ncrust`，minSdk 24、targetSdk 36。
- 实际构建提交：`aa24843eb1fbcff075931a36ba6212538cd5932e`，`app/` 树：`4651b668a9432888757750db55fbff8dde3b69de`。发布 tag 指向这一构建提交；后续验证文档提交不改变 `app/`。
- 定号前执行 next-version.sh（带 fetch），最大值为 76；脚本只找到旧 APK 缓存，因此另对上一版 v3.6.0 签名 APK 执行 badging，确认 versionCode 76。两份原始输出已归档。
- Kanesumi 复合构建：相邻 `../Kanesumi-sec-a`，HEAD `acddf3e53d6a90f6b7d19d6a0445631457c3bda5`，工作区干净。
- 使用独立工作树 `Arris-review-fixes`，未修改生日动画。主工作区开始前已有的曲线测量文档修改予以保留。
- AGP 在这个普通 Git worktree 中未识别 `.git` 指针，APK 内 `META-INF/version-control-info.textproto` 为 `generate_error_reason: NO_VALID_GIT_FOUND`，因此不声称 APK 自带提交号。源码身份由构建时 HEAD、无差异的 `app/` 树、对应 tag 和外置 `build-verification.json` 记录。

## 构建和回归

JDK 21，Gradle wrapper 9.3.1，SDK/build-tools 36.0.0。最终源码提交执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease \
  --offline --console=plain --max-workers=4 \
  '-Dorg.gradle.jvmargs=-Xmx6144m -XX:MaxMetaspaceSize=1536m -Dfile.encoding=UTF-8' \
  -Pkotlin.daemon.jvmargs=-Xmx3072m
```

- **BUILD SUCCESSFUL，4m30s**。最终日志在仓库外 `dist/v3.6.1-gpl/build-final-boundary.txt`。
- JUnit XML 汇总：**233 suites / 2968 tests / 0 failures / 0 errors / 0 skipped**。
- 新增回归 18 项：QQ 并发/取消/登出/有界重试 8，实际收听与重播回调 6，单调限流与失败间隔 4。测试通过生产使用的策略类固定并发顺序和时间输入，没有依靠真实等待一周。
- Lint：**0 errors / 61 warnings / 5 information**，没有降低门槛或增加 suppress。
- 老的 JVM 曲线探针会改写已跟踪测量文档；最终测试后仅还原独立工作树中这份自动生成的改动。该文件在本工作树创建时干净，主工作区的原有修改未动。

## DEX 方法监控

对 QQ 续期/取链、歌单门控、收听上报与 PlayerViewModel 范围执行 method_size.py，覆盖 376 个带代码的方法。

- 新策略类关联的 R8 方法 17 个，最大 **319 code units**，没有超过预警线 1200 或上限 2000；R8 合并/内联会使显示名归到其他原始类，数字按工具的过滤结果统计。
- 扩大检查包含一个既有超限方法：R8 监控名为 `PlayerViewModel.lyricsNoContentSongId (+13 inline/overload)`，**2050 code units / 38 registers**。对上一版签名 APK 和当版 mapping 复测，完全相同；本轮没有新增超限方法。完整范围的检查进程因此返回 1，不能将其写成“全量 DEX 通过”。
- 当前报告与 v3.6.0 基线均随本目录归档；该既有结构问题仍需后续单独拆分。

## 签名、归档和校验

- apksigner 验证 **Verifies**；证书 SHA-256 为 `e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`，与 v3.6.0 相同。
- Release APK：`Ncrust-v3.6.1-gpl-release.apk`，10,527,585 字节；SHA-256 `02fe3c1f7cf8efcc19a8c12d844a06339c1dd30a9fe1a13da7c1e969ef5f6596`。
- Debug APK：`Ncrust-v3.6.1-gpl-debug.apk`，32,331,973 字节；SHA-256 `5aab2d83c9555f07ffbce27d2d25aa093fac7f07e6f2a993f46ebb538d44d0d5`。日常覆盖安装使用 Release 包。
- 保留 Ncrust 资产名前缀供旧更新器识别，软件显示名仍是 Arris。
- 最终 R8 mapping 原文存于仓库外 `dist/mapping/mapping-v3.6.1.txt`；SHA-256 `b8c46bd551b131549a2928e0cfc8a42fa1b888f78c1badfc0caff5956e7f87c7`。压缩件与校验文件已归档。本轮较早候选的 mapping 也在下一次 R8 之前留存到 dist/mapping，未覆盖。
- `SHA256SUMS` 与 `build-verification.json` 对应以上固定安装包。生成验证文档后不再重打包，避免将文档提交与另一份 APK 混淆。

## 运行边界和后续审查

按用户要求，本轮没有操作或安装到用户手机，也没有做模拟器运行验收。QQ 长期续期、真实服务端拒绝码、网易收听榜入账结果不能由 JVM 测试保证。缺少可续期票据的旧网页登录方式仍可能需要重新登录，本修复不创造服务端没有提供的长期凭证。

后续检查新增两项待修复产品问题：搜索翻页失败跳页、本地歌单元数据与曲目非原子提交。见 [SOURCE_REVIEW.md](SOURCE_REVIEW.md)。它们没有混入本轮三个既定修复；本报告是有范围的源码检查，不是全仓库安全审计。
