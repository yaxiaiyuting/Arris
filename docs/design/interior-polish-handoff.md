# 内页质感打磨与签名 Release 续办

更新于 2026-11-08，用户已授权完成内页打磨并交付可用签名 Release。工作区为 `Arris-ui-refresh`、分支 `feat/4.0.0`，起点 `8432f22`（已发布 4.0.15 / 218）。不覆盖已发布产物，不在本轮擅自新增远端发布。

## 方向与范围

- 延续已获用户真机认可的哑光风格，炭灰/暖白底色、松绿强调色、安静层次和精细间距。通透感来自轻表面与边缘，避免发光塑料、重复装饰和昂贵背景模糊。
- 本轮覆盖首页、库、搜索、用户设置/二级设置、关于、识曲、一起听、统计及共用导航/分类控件。
- 保持播放与歌词时序、网络与登录、录音权限、队列、缓存业务和原生返回/触摸语义。
- 参考本机 `/home/duanjb666/下载/impeccable-cli-v4.8.1/.agents/skills/impeccable/SKILL.md` 及 polish、craft-floor、android 参考。它是审查与打磨工作流，不是需要编入 APK 的运行时；原项目的 Kanesumi/Compose 约束与用户既定风格优先。

## 已完成与证据

- 15 个业务 UI/主题源文件已有未提交修改，共用 `arrisSurface`、`ArrisSegmentedTabs`、`ArrisChip` 与短促选中/按压反馈。
- 初轮 `assembleRelease`、全量 `testReleaseUnitTest`、`lintRelease` 通过。日志在 `/home/duanjb666/deepseek/arris/dist/interior-polish-preview/verification.log`。这仍是内部 QA 构建、版本 218，不能冒充最终发布产物。
- 旧版截图、编译日志、Impeccable context 输出均在同一预览目录。
- 后台模拟器使用 `emulator-5586`、Android 14、只读 AVD、无窗口/音频，飞行模式。只操作该串号，不操作连入的用户真机。

## 当前下一步

1. 对实际应用截图做审查，清除不必要眉题、统一主题色对比与窄屏/130% 字号表现。
2. 深浅色、手机/平板、空态、页面切换、设置下拉、键盘避让与底部区域均需检查；记录实际验证范围，不把模拟器截图当作真机性能证据。
3. 复核 diff，补跑受新改动影响的检查，保留全量 JVM 与 Lint 结果。
4. 用项目 `next-version.sh` 带 fetch 重新核号，选择未占用版本与大于既有包的 versionCode，提交源码后构建签名 Release，记录 APK/mapping/SHA256/源码提交一致性。
5. 交付本地签名包与验证文档，删除 `arris-lts` 续办任务并核对状态。

## 自动续办

`arris-lts` 已按用户要求从 LTS 审阅改为「Arris 内页重构与 Release 续办」，每 5 小时在本聊天续接。开始、阶段性和收尾读取实际五小时/周额度，任一剩余约 5% 时停止扩展，先保存源码、证据和此断点。若额度尚未恢复，仅状态核对。不要消耗重置卡或制造并行修改。
