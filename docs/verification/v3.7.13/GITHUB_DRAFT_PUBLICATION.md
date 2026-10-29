# 3.7.13 GitHub草稿上传核验

用户2026-10-29在本聊天追加授权推送3.7.13代码及上传草稿，真机测试由用户进行。本记录是原本地构建验证之后的交付事件；原RELEASE_VERIFICATION.md和verification.json的“未推送/发布”字段保持其当时状态，不覆盖历史。

- 远端维护分支：`lts/3.7`。
- 标签：`v3.7.13-gpl`，指向`d5e3256c301b8292650a75a44656f1781d3507a7`；该提交仅在已验证代码上追加维护策略文档。
- 标签app树：`1a2d65c886ec8e66cea77eedfe80fa705d95b8c3`，与构建源码2e64aed完全一致，没有重打包或改动APK。
- GitHub Release ID：428079315，tag_name=v3.7.13-gpl，target_commitish=lts/3.7。
- 草稿地址：https://github.com/yaxiaiyuting/Arris/releases/tag/untagged-cc96022f01c8e420b226。
- 远端状态draft=true、prerelease=true、published_at=null，尚未公开发布。
- Release APK、Debug APK、原始mapping、SHA256SUMS、构建验证报告共5个附件，均state=uploaded；逐项核对远端size和GitHub SHA-256 digest与本地文件完全一致。原始元数据见github-draft-verification.json。
- master未修改；未推送3.8.x开发工作区及其未提交更改。

本地3.8.x核对：Arris-playback-fixes的8b106d7为3.7.13构建源码2e64aed的后代，已合入这些修复；3.8.0合并APK实测code120、minSdk29。当前3.7.13 APK仍为code100、minSdk24，不能常规覆盖降级已安装的code120包。

本次未新增测试或重跑构建，因为变化仅为维护策略/上传证据文档；保留原3386项JVM全过、Lint零错误、签名验证结论和无设备验证范围。后续真机测试优先使用Release APK。
