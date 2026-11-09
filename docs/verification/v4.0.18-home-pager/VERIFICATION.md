# Arris 4.0.18-gpl 跟手导航与首页验收

2026-11-09。本地签名 Release，versionCode **221**，minSdk **29**。next-version 核到最高220后取221。

底栏胶囊读取 `currentPage + currentPageOffsetFraction`，连续绘制位置。标签图标及无障碍选中状态使用最近页，首页和库的加载以及艺人推荐刷新使用停稳页。宽屏侧栏从最近页开始更新。

首页用每日推荐主卡、紧凑单曲预览、两条一组的榜单和原有歌单封面形成不同层次。私人 FM 在宽度达到1000dp时与每日推荐并排。空态仍有Tux页头和重试，只有榜单缓存时能显示榜单。

## 签名包

- 源码 `7931691cc7dcf5821e4797d387940156be03da17`，app tree `6026a74d3ad8572dc8d8a6ca6949f58237ec4f7d`。
- APK `Arris-v4.0.18-gpl-release.apk`，10,589,160字节，SHA-256 `1013ca22096005cabec5d98f4a43dc457145038693608ce0b341f2bbe0d841a7`。
- APK v2签名通过，证书指纹 `e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82` 与 4.0.17-gpl 一致。后台模拟器上安装的 APK 哈希与交付文件相同。
- 产物和校验值位于 `/home/duanjb666/deepseek/arris/dist/v4.0.18-home-pager`。

## 检查

`JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --offline --max-workers=2 -Dorg.gradle.jvmargs='-Xmx6g -Dfile.encoding=UTF-8' :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease` 通过。JVM 3536项 / 339类，3532通过、4跳过、0失败。Lint 0错误、100条警告、11条信息。

在后台 Android 14/API34 模拟器上把库页拖向首页，在手指保持按下时截图取底栏下划线中心。单位为该模拟器截图像素。

| 版本 | 库页静止 | 短距离拖动 | 拖过半屏 | 首页停稳 |
| --- | ---: | ---: | ---: | ---: |
| 4.0.17-gpl | 331.5 | 331.5 | 331.5 | 124.5 |
| 4.0.18-gpl | 332.5 | 291.5 | 205.5 | 124.5 |

新版取消短拖时从 291.5 回到 332.5，与库页静止位置一致。连续左划两次从库到用户，再右划两次回库。搜索、统计与首页的点按切换通过，滑动每日歌曲预览时主标签仍是首页。

已检查普通手机明暗、320dp宽/130%字号、1920×1200横屏明暗、滚动、只有榜单缓存、断网无缓存及重试。截图使用同一份虚构歌曲、封面和本地缓存进行前后对照，数据只写入后台模拟器，APK不含测试数据。真实账号、在线请求、有声播放和用户真机性能不在此次验证范围。
