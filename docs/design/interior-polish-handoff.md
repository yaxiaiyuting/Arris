# 内页质感打磨与签名 Release 续办

更新于 2026-11-08，工作区 Arris-ui-refresh / feat/4.0.0。

## 当前基线

- dsh 的 ea292ac 已整合此前全部内页改动，产出 4.0.16/219；37d3cf5 增加 QQ 扫码会话生命周期修复。两项完整保留。
- 本轮 eed6bd2 补完分段控件真实淡入淡出、主题小字/图标可读性、具备48dp触控和名称语义的主题色板；首页标题去掉装饰卡片，保留Tux。DESIGN.md记录设计方向。
- 9826b7f 升级至4.0.17-gpl / 220，next-version带fetch核号max219。0ff5549修正首页缩进并通过下一次Lint验证。
- 没有更改QQ业务、播放器、歌词、网络、缓存代码。已有4.0.15正式包与4.0.16本地包不覆盖。没有新推送或发布。

## 正在进行

最终全量构建在 /home/duanjb666/deepseek/arris/dist/interior-final-qa/final-build.log，命令包含 assembleRelease、testReleaseUnitTest、lintRelease。前一次3536 tests（4 skipped）没有失败，但Lint因首页缩进报错；已修正，必须以最终日志为准。

## 完成前必做

1. 最终构建成功后检查APK版本220/4.0.17、minSdk29及签名；只安装后台 emulator-5586，不操作真机、不播放媒体、不连账号。
2. 执行 /home/duanjb666/deepseek/arris/dist/interior-final-qa/qa_phone.py（依赖同目录ui_capture.py）。捕获暗色各主页面、识曲和一起听、浅色素白主题、320dp/130%字号、关于长文滚动。脚本若因重建后当前页变化失败，读取UI再从断点继续。截图必须实际查看，不能只根据脚本结束判定。
3. 补平板横屏与底部让位，所有改变仅限模拟器。没有在线账号/加载内容/真机性能验证时明确披露。
4. 最终APK/mapping/SHA256/验证报告保存到新的 v4.0.17-interior 目录，源码SHA与产物保持一致，本地提交文档，交付包与截图。
5. 交付完成后删除 arris-lts 续办任务并核验状态。

## 参考与续办纪律

用户提供Impeccable 4.8.1、stylekit-dev.zip、awesome-design-md-main.zip，已阅读相关说明及StyleKit作者指南/Linear设计分析。仅取原生内页适用的语义令牌、层次、状态反馈、内容优先方法，不复制网页运行时或品牌样式。

五小时/周额度任一剩约5%时停止扩展并保存断点。每5小时续办直至本地Release交付，未恢复只读留档。不要消耗重置卡、不新增远端发布、不回到LTS审阅。
