# v3.6.0-gpl 构建与验证

## 源码与版本

- 构建提交：`2414a7c9ec00c2685b0bef8666c9840485e7ab5f`；`app/` 树：`5424c34bf6325fa206c8f5f9a5bbc04e0bdeb584`。
- 版本：`3.6.0-gpl` / versionCode **76**；包名继续为 `com.takahashirinta.ncrust`，minSdk 24、targetSdk 36。
- 定号前运行 next-version.sh（带 fetch）；tag 与工作区最大值 75。脚本仅命中旧缓存 APK，所以另对上一份 v3.5.0 签名 APK 执行 badging，实测 versionCode 75。原始输出随本目录归档。
- Kanesumi 复合构建：`../Kanesumi-sec-a`，HEAD `acddf3e53d6a90f6b7d19d6a0445631457c3bda5`，工作区干净。
- 用户原有的 `docs/verification/v3.2.2/probe/curve-smoothing-measurements.md` 修改没有纳入本次提交，未编辑或还原该文件。构建时 app/ 与 HEAD 无差异；该用户文档不参与编译。

## 构建与静态验证

JDK 21；Gradle wrapper 9.3.1；SDK / build-tools 36.0.0。在以上版本提交运行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease \
  --offline --console=plain --max-workers=4 \
  '-Dorg.gradle.jvmargs=-Xmx6144m -XX:MaxMetaspaceSize=1536m -Dfile.encoding=UTF-8' \
  -Pkotlin.daemon.jvmargs=-Xmx3072m
```

- **BUILD SUCCESSFUL，2m29s**。完整日志位于仓库外 `dist/v3.6.0-gpl/build-final.txt`。
- JVM：**2950 tests / 0 failures / 0 errors / 0 skipped / 230 classes**，由 JUnit XML 汇总。
- lint：**0 errors / 61 warnings / 5 information**。未放宽 lint 基线；彩蛋目录没有 lint 警告。
- 新彩蛋包 DEX 方法大小：468 个带代码的方法，**最大 942 code units，0 个超过 2000，0 个超过预警线 1200**。范围仅 celebration/，不把此数字表述为全仓库统计。R8 的边界保留规则限定该包，避免 Compose 与原生绘制被合并成巨型方法。
- Strings 根构造参数保持 148；新文案挂在 AboutStrings.celebration，八语、设置注册与构造预算回归通过。

## 签名与产物

- release 证书 SHA-256：`e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`，与 v3.5.0 一致。apksigner 验证成功。
- APK / mapping 哈希见 `apk-sha256.txt`；完整 badging 与签名结果随本目录归档。
- GitHub 资产继续使用 `Ncrust-v3.6.0-gpl-release.apk` 名称，供旧版 ForkRelease 解析器识别；软件显示名为 Arris。
- 每轮 R8 后、下一轮构建前均另存候选包 mapping；最终 `mapping-v3.6.0.txt.gz` 与 sha256 在本目录，原文在仓库外 `dist/mapping/mapping-v3.6.0.txt`。

## 运行验证与真实边界

- 初始候选 2（相同版本号的私下测试构建）成功覆盖用户 PLC110 的 v3.5.0，无卸载/清数据。用户实测反馈比例不佳与吹气不灵敏，本版据此修正。
- 用户随后明确要求「不用实机测试」，此后没有再操作或安装到用户手机。**当前最终 APK 没有做真机验收，不能声称吹气识别率或耳机路由已验证。**
- API33 / x86_64 模拟器、1080×1920：候选 3 通过生日预览 → 等待 → 点按吹灭 → 黑场 → 烟花升空/爆炸/金色垂落 → 祝福收尾；关闭返回。版本信息行快速点按 7 次打开听众席。截图在 screenshots/。这些截图来自候选 3；最终包额外修正宽屏画布原点，一般竖屏绘制计算保持相同。
- 最终签名包再次在同一 API33 模拟器覆盖安装并冷启成功。
- 早期模拟器 SwiftShader 后端曾发生宿主机 SIGSEGV，改为 Lavapipe 后完成以上链路；不将该宿主崩溃计为 Android App 崩溃，也不据此声称所有 GPU 已兼容。
- 真正吹气、周围噪声误触率、后台麦克风系统指示、蓝牙输出、真实低端/高刷设备帧率仍未验证。麦克风单元测试只覆盖检测逻辑，模拟器使用 -no-audio。
- 生日闰年、按年去重、连点时限/重置、较轻低频气流、校准时吹气、短间断、孤立噪声、粒子池上限与 15/60/90/120Hz 收尾等由纯逻辑测试覆盖；不能把这些测试写成实机准确率。

## 其他功能审查

见 [SOURCE_REVIEW.md](SOURCE_REVIEW.md)：记录 3 项待修复问题，分别涉及 QQ 在飞续期结果、上报时长与播放位置混用、墙上时钟回拨后的写操作延迟。
