# v2.8.0-gpl 发布状态（打 tag 之后回填）

> 本文件在 tag 之后提交，**不移动任何 tag**（铁律 7）。

| 项 | 值 |
|---|---|
| tag | `v2.8.0-gpl`（附注 tag） |
| tag 对象 | `5fa01ce80254b33f7ea941f2991a655e4eaf204c` |
| 指向提交 | `06782e28c7ab280302a36893dffc1e53b19f8807`（= 当前 master HEAD） |
| 产物源码自证 | `git show v2.8.0-gpl:app/build.gradle.kts` → `versionCode = 51` / `versionName = "2.8.0-gpl"` |
| draft release | https://github.com/yaxiaiyuting/Ncrust/releases/tag/untagged-5077e032a93f5dae9ec3 （`isDraft: true`，**未发布**，等用户 smoke test 后手动发布） |
| release 标题 | `v2.8.0-gpl（versionCode 51）· 波形效果分级 + 设置界面二级菜单` |
| 资产 | `Ncrust-v2.8.0-gpl-release.apk`（10,108,360 B） |
| 本地 sha256 | `6f9e61993296ac6fedc5963dfb97e19003bc1f1716c882798b42f5a28c1fe305` |
| GitHub 侧 digest | `sha256:6f9e61993296ac6fedc5963dfb97e19003bc1f1716c882798b42f5a28c1fe305`（**逐字相同**，服务端独立计算） |
| 签名 | 新密钥 `ncrust-release-v2.jks`，cert SHA-256 `e62eca39…bbec82`（`apksigner verify --print-certs` 实测） |

## 三源 versionCode 复核（发布后）

| 源 | 值 |
|---|---|
| tag `v2.8.0-gpl` 的 `app/build.gradle.kts` | 51 |
| 已发布 APK 的 `aapt2 dump badging` | 51 |
| draft release 资产 digest | 与本地逐字相同（见上） |

## 已发布 tag 未被移动（逐条复核）

| tag | 对象（现在） | 与接手时对比 |
|---|---|---|
| `v2.6.2-gpl` | `e7c153f72e42d6d30bc0ba4d706f00b96dd03bdc` → commit `744ff0e` | 与接手时 `git ls-remote` 记录**逐字相同**，未移动 |
| `v2.8.0-gpl` | `5fa01ce…` → commit `06782e2` | 本次新增 |

推送方式：`git push origin master`（`4b0a124..06782e2`）+ `git push origin v2.8.0-gpl`；未使用 `--force`，未删除任何远端 ref。

## 未做／待用户决定

- **release 仍是 draft**：按仓库惯例由用户 smoke test 后 `gh release edit v2.8.0-gpl --draft=false` 手动发布。
- 本版**签名更换**是不可逆的用户可见后果（详见 `EVIDENCE.md` §1 与 release note 顶部警示）。
