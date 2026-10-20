# fixtures/update —— Arris 应用内更新检查（yaxiaiyuting/Arris）

本夹具锁住 **Arris Fork** 的发布契约与「挑更新」规则。上游 Ncrust 的同名夹具
（`ncrust-android-universal-<version>.apk` + `CrateStack-*` 班次）不能直接复用：
本 Fork 的仓库、tag、资产名、签名通道都不同。

## 契约

| 项 | 值 |
|---|---|
| GitHub 仓库 | `yaxiaiyuting/Arris`（唯一源，不加镜像） |
| Release tag | `v<version>-gpl`，例如 `v3.4.13-gpl` |
| release 资产 | `Ncrust-v<version>-gpl-release.apk` |
| debug 资产 | `Ncrust-v<version>-gpl-debug.apk` |
| 包名 | `com.takahashirinta.ncrust`（不变） |
| 安装校验 | 包名一致 → `versionCode` 严格更高 → 签名证书摘要一致 |

release 与 debug 是**不同签名**：应用内检查按当前 build type 只挑匹配通道的资产；
另一通道即使版本更高也必须忽略。签名不一致时更新页如实提示，不调起安装器。

## schema

`releases.json` 是用例数组：

```json
{
  "id": "update.fork.higher-version",
  "description": "这条用例在防什么",
  "source": "依据",
  "input": {
    "currentVersion": "3.4.12-gpl",
    "variant": "release",
    "releases": [ /* GitHub Releases API 响应数组，只保留用得着的字段 */ ]
  },
  "expect": {
    "version": "3.4.13",
    "assetName": "Ncrust-v3.4.13-gpl-release.apk",
    "assetUrl": "https://…",
    "assetSize": 10000000,
    "releaseTag": "v3.4.13-gpl",
    "releasePageUrl": "https://…",
    "publishedAt": "2026-10-21T09:30:00Z",
    "body": "notes"
  }
}
```

`expect` 为 `null` 表示无更新。`version` 是显示式三段（`AppVersion.toString()`）；
四段版本在盘上用 `storageString()` 保存，避免「稍后」的版本比较丢第四段。

## 规则

1. 只认 `Ncrust-v<version>-gpl-<release|debug>.apk`；`.sha256` 等校验文件、上游
   `ncrust-android-universal-*` 命名、`app-release.apk` 等旧资产一律忽略。
2. 版本段最多 4 段、缺段按 0 补；非法段（`3.4.x`）忽略，不猜。
3. `draft` 与 `prerelease` 忽略；不依赖 GitHub 的 Latest 徽章，也不依赖数组顺序。
4. 跨所有可见 release 取**严格高于当前版本**的最高者；同版本不算更新。
5. 版本比较用资产名里的版本；GitHub API 不暴露 APK 内的 `versionCode`，
   下载后用 `ApkVerification` 再做一次「包名 / versionCode / 签名」门控。
6. 每个 release 内按当前 `variant` 过滤后再取最高版本；debug 资产不得被 release
   构建选中，反之亦然。
7. 任何一页响应解析失败都按失败处理（`evaluateReleases` 返回 null），
   绝不当成「已最新」去清掉上次真实发现的结果。
8. 同名同版本资产原地替换不会被发现；要让已安装用户收到修复，versionName、
   versionCode 与资产名版本必须一起递增。

## 与上游夹具的关系

本夹具是**替换**而不是追加：上游的 Windows / 班次用例不适用于 Arris 的
`vX-gpl` 标签与 `Ncrust-v…` 资产命名。上游仍保留自己的 `spec/fixtures/update`。
