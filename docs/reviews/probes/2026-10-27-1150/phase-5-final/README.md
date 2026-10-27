# 11:50 轮最终累计缺陷证据

业务基线 15237f8（v3.7.8-gpl / 94）。23 条定向 JVM 缺陷断言，14 类，0 failure/error/skipped；并非全应用测试通过，也不表示缺陷已经修复。源文件和 XML 同名对应，SHA256SUMS.json 记录本目录原始源/日志/XML。

运行时临时将 Noon*AuditProbe.kt 放在 app/src/test/java/com/takahashirinta/ncrust/audit，使用 Java21 执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest --tests 'com.takahashirinta.ncrust.audit.Noon*AuditProbe' --console=plain
```

运行后从常规测试目录移走本轮源文件。HTTP 在应用拦截器/替身内返回，没有 proceed、真实账号或云端写操作。列表动画使用无窗口真实 Compose Runtime 帧钟，手势相关用例限真实纯逻辑/滚动连接，不能据此宣称真机验收。

独立 Go 服务的 3 条错误断言已保留在 ../phase-4，不混入安卓计数；当前 APK 不使用该后端。详细触发条件、影响、行号、证据等级和建议见 ../../../2026-10-27-1150-scheduled-review.md。
