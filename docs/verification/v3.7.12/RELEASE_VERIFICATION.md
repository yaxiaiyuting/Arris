# v3.7.12-gpl 双语媒体歌词测试包验证

- 版本：3.7.12-gpl / versionCode 98；应用 ID：com.takahashirinta.ncrust。
- 产物源码提交：`774db289cce95e69de451f808ab0ffc27549bfa3`；app tree：`806386dc85af15e2c7c4cab34b72179d9928ccf2`。
- 独立分支：`feat/bilingual-media-lyrics`；当前工作区既有 3.7.11 变更先保存为 `0fd05bf`（31 个文件）后再接入本功能。原工作树 HEAD 5462b53 与未提交补丁均未改动。
- Kanesumi composite 依赖：`acddf3e53d6a90f6b7d19d6a0445631457c3bda5`，构建时工作树干净。
- 定号证据：next-version.json，带 fetch 的三源核查 max 97 → next 98。

## 行为

原文与译文组成同一句的不可变帧，系统媒体面板、媒体通知和 Live Updates 共用两行排布。默认原文在上、译文在下；可切换译文为主行。没有译文、译文为空或重复原文时仅显示原文；没有歌词则显示歌名和歌手。原有总开关保留已有偏好；双语开关默认打开。切换主行与译文迟到纳入通知去重标识；暂停时也刷新会话 metadata。设置路径见 TESTING.md。

## 自动化验证

- 完整 JVM：3332 项 / 294 类，0 失败、0 错误、0 跳过。分类计数见 unit-test-summary.json；本轮新增 MediaBilingualLyricsTest 的 15 项 XML 同目录归档。
- 定向验证：93 项 / 6 类全部通过；涵盖双语、旧排布兼容、会话合并、通知限流、设置注册/渲染契约。
- Lint Debug：0 Error/Fatal，61 Warning，7 Information。保留项目现有告警，不声称无告警。
- Debug 与 R8 Release 均构建成功；首次合并构建在 JVM 测试完成后因默认 2 GiB Gradle 堆不足而停止，使用命令行 6 GiB 堆参数重跑 Lint/打包后成功，未修改 app 源码或仓库 Gradle 内存配置。
- APK badging：versionName 3.7.12-gpl / versionCode 98；apksigner 验证通过，release 签名与已有 3.7.11 测试 release 一致（证书 SHA-256 `e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`）。
- 构建后 app 源码与产物源码提交一致；测试自动生成的历史计时文档已恢复到原内容。

## 产物

目录：`/home/duanjb666/deepseek/dist/v3.7.12-gpl`。

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| Arris-v3.7.12-gpl-release.apk | 12105420 | `ce9ca0abe3ec8afcd7d5d97a443091b998b9c41ad6e8191d75a54241c4990077` |
| Arris-v3.7.12-gpl-debug.apk | 34706721 | `408011b7e3a443090e97e095c765d40b8ebe8e6bc174c3166b9059f89545c152` |
| mapping.txt | 80079934 | `498db92580a6ec8a16cdb1b8a018c9a4be57972e4d773f25ae666a0c6ae3e6c2` |

完整 XML 压缩包、Lint XML/HTML、签名核验、badging 和构建日志存放于上述产物目录。推荐覆盖安装 release APK；debug 仅作为调试备用，签名与 release 不同。

## 本轮边界

未进行真机或模拟器测试，未打开可见浏览器/自动播放，未推送或创建 GitHub Release。系统模板控制主副行字号、截断和收起胶囊行数；收起态可能仅显示主行，不能用纯 JVM 测试保证具体 ROM 的两行布局。复用已有译文轨，不增加在线翻译请求。既有 3.7.11 歌词分享/资料等变更包含在基线中，不将其全部功能声称为本轮新增或完成真机验收。源码审查自动任务仍暂停。
