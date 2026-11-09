# Arris 4.0.17-gpl 内页整合验收

2026-11-09，本地签名 Release，versionCode **220**，minSdk **29**。未推送、未创建远端标签、未发布到 GitHub/官网；旧包与标签保留。

## 源码与产物

- 构建源码：`bf099b2525e22639629e2da495b5892938471a10`；app tree：`7f91f501b0450e2eb133bc1742fee3e3906f3a26`。
- Kanesumi：`acddf3e53d6a90f6b7d19d6a0445631457c3bda5`，工作树干净，本轮没有修改。
- APK：`Arris-v4.0.17-gpl-release.apk`，10,589,160 字节。
- SHA-256：`97304a874ad7800695c0cb5fbcf55f8a6cc1b07821bc52d4147c20d3a0e22cde`。
- APK v2 签名有效，证书 SHA-256：`e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`，与 4.0.15 正式包一致。模拟器已安装 APK 的哈希与交付文件一致。
- 升号前 next-version 带 fetch 核查最大219、取220；交付前复核最大220，无新冲突。
- 完整产物目录：`/home/duanjb666/deepseek/arris/dist/v4.0.17-interior`，含 APK、mapping、SHA256SUMS、XML、构建日志、截图与检查脚本。

## 整合内容

沿用已经认可的哑光播放器，内页统一炭灰/暖白、细边缘、分组表面与克制的强调色。首页去除装饰标题卡，保留 Tux；库、搜索与设置统一分类选择器和选中淡入淡出；设置行合为分组列表；关于长地址/许可及统计长时长允许完整换行；识曲、一起听使用一致排版和表面。

补齐主题色板的48dp触控、颜色名称和选择语义，以及浅色素白/琥珀下的小字与图标对比度。最后一轮实图修正了设置/关于顶部的黑色遮罩，并让关于页没有副值的操作名称占满一行，解决320dp、130%字号下“实验室（诊断）”被空副栏挤断的问题。

DSH 的 `ea292ac` 整合和 `37d3cf5` QQ 扫码会话修复保留。相对 `37d3cf5`，QQ业务、QqScanLoginDialog、MainActivity、player、lyric、network、cache 目录逐项 diff 为零；SettingsAccountSection 仅增加纯色页面导航样式参数。没有改变外部 Kanesumi 库、歌词时钟和播放业务。

Impeccable polish/craft-floor/android、StyleKit 和 awesome-design-md 提供层次、语义令牌、可读性与完整状态的检查方法；没有引入网页运行时。Impeccable网页扫描器未用于声称完成 Kotlin 自动扫描。

## 构建检查

命令：`JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --offline --max-workers=2 -Dorg.gradle.jvmargs='-Xmx6g -Dfile.encoding=UTF-8' :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease`。

最终 `build.log`：BUILD SUCCESSFUL，8m05s。JVM 3536项 / 339类：3532通过、4跳过、0失败、0错误。Lint 0错误、100 Warning、11 Information；不将“构建通过”描述为“没有警告”。原始XML已归档。

## 实图检查

使用后台 `emulator-5586`，Android 14/API34，no-window/no-audio；没有操作用户真机或打开可见浏览器。

| 范围 | 实际证据 |
| --- | --- |
| 常规手机明暗 | 1080×2400 / 420dpi / 100%字号；最终首页缓存、主题、关于、库。此前0ff5549的暗色用户/搜索/统计/识曲/一起听截图单独放在 earlier-0ff5549，不冒充最终SHA的新截图；这些页面此后未变更。 |
| 窄屏大字号 | 840×1680 / 420dpi，即320dp / 130%；最终关于全页、末尾致谢、库、识曲、一起听与首页；检查换行、触控入口、滚动和底部系统栏让位。 |
| 平板横屏 | 1920×1200 / 240dpi / 100%；明暗设置、库、一起听、首页；侧栏没有覆盖内容，主要按钮未压到底部任务栏。 |
| 选择切换动效 | 最终签名包录制库页单曲→歌单，截取6个过渡帧；观察到选中背景渐隐/渐显，控件几何位置连续。仅作为视觉证据，不是帧率基准。 |

首页内容通过现有 HomeSnapshot 格式写入虚构离线样例，封面使用应用自身Tux资源；数据不进入APK。模拟器飞行模式、Wi-Fi与移动数据关闭。没有点击识曲录音、创建/加入房间或播放音乐，没有使用真实账号或线上写入。

## 异常记录与边界

主机另一个系统构建期间内存耗尽并大量换页，模拟器先后发生 Pixel Launcher 与 Arris 输入超时；Arris ANR主线程栈停在 `ClipboardManager.getPrimaryClip` 的同步 Binder 调用。原始记录保留于 `interior-final-qa/emulator-anr-125209.txt`。不能据此声称应用在所有负载下没有ANR，也不能将其直接归因为本轮UI改动。主机内存缓解、后台模拟器重启后，同一包完成小屏、平板与选择切换检查。

改变显示尺寸会重建Activity并关闭临时关于覆盖层，因此第一轮误停在关于设置分组的截图已按真实页面重新命名；实际关于页重新进入后采集。失败/中断记录保留，不计入通过证据。

本轮不包含真机性能、真实QQ登录、在线数据业务、录音识曲、一起听房间、音乐播放和有曲目时的mini播放器实测。相关业务代码保持基线，不以布局检查代替这些验证。
