# v3.6.8-gpl 验证

versionName 3.6.8-gpl / versionCode 84。构建提交 c0dc8d299087bf5c37d7b92eadbc3d8bf1c9a0e0；app 树 4eb54145e0c891be7a91b9811e94c9f215b68503。

## 来源与接入

本地 birthday-motion/out/reel/birthday.mp4 是 2026-10-23 21:15 的新成片：H.264 High、1600×900、60fps、2880 帧、48.000s、9,061,018 B，无音轨。ffmpeg 完整解码检查无错误。源码与成片未修改、未重渲染，源哈希见 birthday-source-manifest.json。

直接导入新版 cues.js、motion.js、lighting.js；main.js 导入固定机位与手动质量档，保留应用内禁用图层 will-change 和画布分辨率上限；base.css 导入移除 SVG filter、环境混合与重复 bloom，同时保留已有首帧淡入。Arris 的 arris.js/css、纵向 VFX 画布、repaint 以及禁用额外 Finish 合成层继续保留。

新版取消动态推拉和多层动态视差，仅保留按深度设置的静态缩放；保留角色表演、暖光、黑场、烟花与祝福。不能把早期 README 的动态视差描述或成片 60fps 当成本版手机运行性能保证。

运行时仍是打包的离线互动网页，26.2s 等待原生吹气/点按；保持本地化祝福、祝福下方结束按钮、竖屏重排与横屏构图。没有新增麦克风入口、网络请求、音频焦点或路由操作；静态原生回退、迟到生日和七击彩蛋不变。

## 验证

- 全量 JVM：3001 tests / 239 suites / 0 failures / 0 errors / 0 skipped。
- Lint：0 errors、55 warnings、5 information；debug/release 构建成功。
- 相关 160 个 R8 方法均 <= 2000 code units，最大 1231，未放宽阈值。mapping 在最终构建后立即归档。
- 正式证书 SHA-256 e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82，与既有版本一致。
- 两个 APK 中所有生日资产与提交源码逐文件一致；正式包签名、版本和哈希见同目录报告。
- verify-layout.cjs：8 种横竖屏尺寸，结束按钮在屏幕内、最小触控高度、原生关闭桥接、重播再次经过吹气等待，全部通过。
- verify-motion.cjs：390×844、360×640、844×390、1600×900，各 9 个时刻，共 36 张关键画面；检查主体边界、48s 时长、静态相机、SVG filter 禁用、无动态 brightness、吹气等待、重复吹气拦截、释放后拒绝命令与无外部网络，全部通过，页面错误为 0。
- 独立 API33 模拟器 / WebView109 / debug84：394×755 CSS px，5 个关键时刻画面、原生点按吹灭与 DONE 状态通过。结束按钮 y=646.87、bottom=694.86、height≈48，底部仍有约60 CSS px 空间。截图在两次 rAF 后再等待300ms，避免抓到 WebView 上一次提交的画面。

- 同一 GPU 模拟器从 0 秒实际播放至 DONE，经过原生手动吹灭，约 49.15 秒完成，所有状态样本均无页面错误。此检查窗口日志未出现 tile memory 或 FATAL EXCEPTION；仍不推广为真机结论。见 native-playback.json。

## 验证边界

没有实体手机安装或测试。模拟器 SwiftShader 两次发生宿主 qemu RenderThread 段错误，改用 NVIDIA host GPU 才完成网页检查；这是测试环境故障，不用作 Arris 真机稳定性结论。首次切换模拟器密度时，GMS 字体 provider 重启导致依赖进程被系统回收，重新打开后复核。

本版验证不提供相同设备、相同条件下的前后性能对比，不声称提升多少 fps 或所有机型均无闪烁。真实吹气阈值和权限流程保持原实现，此次模拟器检查使用原生手动吹灭按钮，不代替麦克风真机验证。原始截图、录屏及完整日志保存在仓库外 dist/v3.6.8-investigation/。
