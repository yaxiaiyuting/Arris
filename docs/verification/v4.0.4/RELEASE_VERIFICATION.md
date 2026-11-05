# Arris 4.0.4 与 LTS 3.7.27 发布验证

## 构建与签名

- 主线 4.0.4-gpl，versionCode 204，minSdk 29，10401136 字节。3513 个 JVM 用例，336 个测试类，零失败，跳过 4 个。
- LTS 3.7.27-gpl，versionCode 114，minSdk 24，12,270,321 字节。3,414 个 JVM 用例，317 个测试类，零失败，跳过 4 个。源码提交 `6e3284c`。
- 两条线通过 Release 关键 Lint 与 R8 构建，APK 的包名均为 `com.takahashirinta.ncrust`，签名证书与各自上一公开版一致。Kanesumi 构建依赖 `acddf3e`。
- 主线 SHA-256 `c0fcdbe94319dda2c8fda86cf833bfcf2f457844c4bcc5b4a48b3268eeaa87dd`
- LTS SHA-256 `98b16a30a284c542d1bef01feb4ae8698bb70dc7d31a5779f7045429652da932`

## 验证范围

- 清单 I01 和 I07 已通过 4.0.3 的原问题验收。I06 是已撤回的历史实验。本轮处理剩余 15 项；LTS 回移共用播放与更新逻辑，房间与识曲适用于主线，官网 Range 修复同时服务两条线。
- I03/I04：本地响应拦截，运行生产分段下载器与提取的 `UpdateService` 下载分支。旧探测取消后无法发布，替换任务完整交付；错误区间、缺少或改变强 ETag、过长分段均拒绝。证据见 `segmented-transfer.sh` 与 `segmented-transfer.log`。
- I05/I11/I12：进程内运行生产 Worker 路由与 Room/Directory，验证 APK/MP3 的条件请求、房主凭据、成员伪造消息拒绝、无房主拒入与目录清理。复跑 `node relay/test/release-boundaries.mjs`。
- I09/I10/I14/I15/I16：生产选择器拒绝损坏 feed 与跨线附件；P2P 使用环回 UDP，STUN 使用本地替身；HTTP 使用隔离拦截器，验证识曲取消和非成功响应分流。证据见 `isolated-boundaries.sh` 与 `isolated-boundaries.log`。
- I02/I08/I13/I17/I18：覆盖生产状态提交、档位拒绝、房间跟随和播放服务入口的源码检查及 JVM 测试。跨设备同步、Android 运行时生命周期与真实帐号识曲留待真机验证。

## 兼容性

新版公网与 P2P 房间需要双方升级至 4.0.4 并使用新房间码。Android 前台服务接到过期房间的启动指令时会先完成平台要求的前台启动，再清理空闲实例。 [Android 前台服务排错](https://developer.android.com/develop/background-work/services/fgs/troubleshooting#foreground-service-exceptions)
