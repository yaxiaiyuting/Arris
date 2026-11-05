# Arris 4.0.5-gpl 主线测试包
- APK：10,474,468 字节，Android 10 起，versionCode 205。与 4.0.4 稳定 APK 的签名证书一致。
- SHA-256：`d22f195b1dc7b960be630fbf328d11e5da2cf0853519af1371f694953dd7edb4`。
- 签名构建、完整 Release Lint、全量 JVM 已通过；338 个 JVM 测试类、3521 项、4 跳过、0 失败。
- 完整 Lint 保持启用。两处已有诊断分别在录音器权限竞态和 Compose 车机配置读取路径上修复。
- 隔离 API 33 跟唱与着色器检查见 [EMULATOR.md](EMULATOR.md)，设备温度、帧耗时和功耗未做真机核验。
- GitHub 使用草稿，官网使用独立版本 URL。稳定和 LTS 入口继续提供各自正式版。
