# 06:43 审查证据

应用源码基线 `15237f8c06ba160632c9096101386f821b85a723`，v3.7.8-gpl / versionCode 94。结论见 [本轮报告](../../2026-10-27-0643-scheduled-review.md)。

`phase-1/` 为首批 16 项，`phase-2/` 为累计 22 项，`phase-3/` 为累计 28 项，`phase-4-final/` 为最终累计 **30 项**。不是四批用例数量相加。第一批摘要叫 `probe-summary.json`，后续叫 `summary.json`；各阶段均保留源码、JUnit XML、构建日志与校验摘要，首批另存导航依赖字节码。

探针断言的是错误存在，**通过不表示已修复**。网络使用应用拦截器直接返回夹具，不调用 chain.proceed；WebSocket 使用本地回调/通道夹具；账号凭据均为虚构。未打开真实账号、云端写操作、设备、模拟器、可见浏览器或自动播放。

如需再次复现，先在隔离 checkout 复制对应阶段的 Kotlin 到临时 `app/src/test/java/com/takahashirinta/ncrust/audit/`，使用本机 Java 21 与既有 composite dependency，运行：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.takahashirinta.ncrust.audit.Scheduled*AuditProbe' --console=plain
```

最终常规测试目录不保留这些“期望错误行为”的临时源码。修复后应把相应断言改成正确行为，再作为正式回归测试；不要把这里 30 项通过当作修复验收结果。

`coverage.json` 是阅读范围，不是测试覆盖率。`not-recorded-this-round.json` 列出本轮没有逐文件阅读记录的文件，也不表示历史上从未审过。
