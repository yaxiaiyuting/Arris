# UI 自动化尝试（**未成功**，如实保留）

## 为什么保留这份失败记录

v3.1.0 的验证计划里本来有一条「用 `uiautomator` + `input tap` 驱动 release 包做端到端搜索/播放」。
它在 API 24 模拟器上**没有成功**：`uiautomator dump` 拿到的节点树里找不到底部导航的「搜索」入口
（首启的「后台运行」权限弹窗会挡住整屏，点掉之后仍然定位不到），于是脚本点在了一个空坐标上。

**没有把它包装成"通过"**：那份 run.log 里 `aggregate` 的记录全部来自**冷启动时上一次
输入残留触发的搜索**，而不是脚本真的操作了搜索页。

## 替代方案（真的跑通了的那条）

改用**仪器化探针**（`app/src/androidTest/.../probe/BiliV310ProbeTest.kt`）直接调生产代码：
真实网络、真实 `BiliApi` / `BiliSourceProvider` / `OfflineAudioCache.dataSourceFactory`，
在 S6 与 API 24 模拟器上各跑一遍。证据见 `../EVIDENCE-bili-probe.md`。

release 包这一侧改用**冒烟**（安装 → 冷启 → 无崩溃 → 首页请求正常 → 截图）：
`../release-smoke/{api24,api33,s6}-smoke.txt` 与三张 `*-launch.png`。

## 这份记录能证明什么

`summary.txt` 里那四轮日志（`00`~`04`）显示的是：**B 站关闭时**两轮搜索都是
`bili=0 biliAllowed=false`，而 ncm 与 QQ 各 30 条 —— 即「关掉 B 站不影响另外两个源」。
这条结论后来由仪器化探针的 `PROBE-BILI-ROUTER` 与 `PROBE-BILI-TOGGLE` 更严格地复现。
