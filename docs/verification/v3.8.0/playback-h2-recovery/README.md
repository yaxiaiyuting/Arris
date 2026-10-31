# 3.8.x 网易云播放“网络异常”修复

日期：2026-10-30（北京时间）。用户确认故障在正常播放网易云歌曲，不能与识曲私有接口的字符串解析错误混为一谈。

## 诊断证据

基线4b2b298（继承3.7.13构建源码2e64aed的播放链路）。仅读取PLC110已有logcat，看到/eapi/song/enhance/player/url/v1反复出现HTTP/2 REFUSED_STREAM/CANCEL，原有同协议重试耗尽后被归为NETWORK；另一次在8001ms结束，未观察到请求响应头。抽录与后续原始筛选行见observed-log-excerpts.md和existing-device-log-redacted.txt。较早日志仅留在聊天工具输出，未伪造完整原始日志或请求总数。

这确认传输层直接失败点，不能据此区分服务端限流、网络中间设备或连接复用各自作用。未实测HTTP/1.1能否在用户实际网络成功，仍需后续验收。

## 修复

RetrofitClient只在/eapi/song/enhance/player/url/v1这条只读POST使用PlaybackReadTransport：

- 首先保持现有客户端路径。
- 存活协程遇到REFUSED_STREAM/CANCEL流重置或明确InterruptedIOException(timeout)，只追加一次独立HTTP/1.1连接池取链，保持同一请求与Cookie会话快照。
- 用户切歌/暂停引发的协程取消原样传播；显式Call.cancel的普通Canceled失败不触发回退。
- 第二次失败直接交回原次数门，不递归。两次各自8秒上限，仍受原取链整体15秒及当前播放代次约束。
- 其他读取、鉴权、收藏/歌单写入等不进入此重放路径；不改变HTTP/2全局配置。

## 验证范围

本地interceptor模拟流重置、成功、普通网络异常、回退失败、真实OkHttp callTimeout取消标记和用户主动取消；不发送真实网易云请求，不使用真实账号。验证调用生产异步响应体读取与恢复辅助函数。接入路径和独立HTTP/1.1配置为源码核对，不冒充端到端设备通过。

没有启动设备播放、安装APK或操作模拟器；设备只用于读取已有日志。本轮不升级版本、不上传/发布，3.8.x保持minSdk29/code120；不改动3.7.x LTS。当前工作区包含其他未提交识曲/一起听改动，编译包含该WIP；本轮提交只包含播放传输修复与证据，不把他人文件纳入提交。

识曲的原始字符串响应错误属于独立待办，当前优先按用户更正处理正常播放故障，未把它标为已修。

## 最终检查结果

定向JVM：23项、3类，0失败/0错误/0跳过；Debug编译成功；Lint零错误、76警告、7信息，完整日志targeted.txt，XML见jvm-results.zip与lint-results-debug.xml。首轮不含超时新增分支的成功日志保留为targeted-before-timeout.txt，不累加测试数。未跑完整JVM/Release打包，不据此宣称全项目验证。
