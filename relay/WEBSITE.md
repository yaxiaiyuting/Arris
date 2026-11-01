# 官网前端维护

官网源文件为 `public/index.html`，补充内容样式和交互在 `public/assets/discover.css`、`discover.js`。使用普通 HTML / CSS / JavaScript，无框架构建步骤和远程字体依赖。

修改首页后必须运行 `node tools/sync-homepage.mjs`，因为 `/` 由 Worker 内嵌的 HTML 响应；只改静态 HTML 会导致首页不同步。脚本只重建首页常量，不改前面的中继/API/下载代码。

原生截图来自 `docs/verification/v3.8.3/screenshots/normal/`，按 630px 宽转换为 WebP。介绍片来源为本地 `arris/media/arris-video-vs-ncrust-20261101` 的完整 fork 对比影片，网页压缩版为 720p；源内容截至 3.8.9，页面已注明。视频不得设置 autoplay；保留 preload=none、poster、controls、playsinline。VTT 是同一旁白的可选字幕，成片本身已烧录中文。

更新版本时同步下载卡片、更新摘要和 APK 清单；不要把未公开的 GitHub 草稿 tag 链接当作公开可访问链接。主线与 LTS 别名由下载清单维护。图表只是功能示意，保留示意标签。

本次浏览器和媒体验证见 `docs/verification/website-20261101/VERIFICATION.md`。部署沿用 `wrangler deploy`，需先确保当前 APK 与清单匹配，不能用缺少 gitignored APK 的新 worktree 直接部署，以免覆盖现有下载文件。
