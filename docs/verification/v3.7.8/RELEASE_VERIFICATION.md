# Arris v3.7.8-gpl 验收记录

- 版本：`3.7.8-gpl` / `versionCode 94`（三源交叉校验：tag `v3.7.7-gpl`=93 为最大值 ⇒ 94）
- 构建日期：2026-10-27（Asia/Shanghai）
- 基线与修复清单：[`docs/reviews/2026-10-27-BUG_SUMMARY.md`](../../reviews/2026-10-27-BUG_SUMMARY.md)、[`BUG_FIX_2026-10-27.md`](BUG_FIX_2026-10-27.md)

## 构建与测试

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **3109 通过 / 0 失败** |
| `:app:lintDebug` | **0 error / 60 warning**（17 条在基线内） |
| `:app:assembleRelease` | 成功，`12,089,036` 字节 |
| `:app:assembleDebug` | 成功，`33,936,861` 字节 |
| aapt2 badging | `versionCode='94' versionName='3.7.8-gpl'` |
| apksigner（V2 签名） | 证书 SHA-256 `e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82` |
| `zipalign -c -P 16 4` | 通过（16 KB 页对齐） |
| debug 探针泄漏检查 | release APK 内 `ProtocolProbe` 条目数 **0** |

## 产物

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| `Ncrust-v3.7.8-gpl-release.apk` | 12,089,036 | `86c2048e2fdaf16253fe94eae81a2646a9e99a6a2db8e65754c26df7ae715304` |
| `Ncrust-v3.7.8-gpl-debug.apk` | 33,936,861 | `4145affe406c0c1cb4e0c1c3add519daa9c53f565ad0b83d3cc1db09debc98ce` |
| `mapping-v3.7.8-gpl.txt.gz` | 5,837,762 | `d72bc69769ae8aadbd71de34abce5096370c6079a7a875b16ac7b75b4c3a8911` |
| `SHA256SUMS` / `RELEASE_NOTES.md` | — | 见远端核验 JSON |

## 证据

- 探针转回归的四个用例：`LocalSyncRevisionAuditProbe`（S2）、`PlaylistMutationAuditProbe`（S18/S22）、
  `EditionMatchAuditProbe`（C1）。它们原先断言"错误行为存在"，现在断言修复后的行为。
- `UpdateDownloaderTest` 新增 `does not splice when the URL identity has no validator`（S11），
  并把四个续传用例的夹具补上验证器（S11 策略变更后的正确前提）。
- 跨源匹配策略变更后同步更新了锁定旧行为的两个测试
  （`CrossSourceMatcherTest` 的原曲/伴奏、`PlayAllDedupTest` 的原版/Remix），
  并把 `MatchCacheCodec.ALGORITHM_VERSION` 推进到 2。

## 未验证项

- 未做真机验证：本版改动集中在数据层、协议层与策略层，界面后果由调用方源码推导并在单测中固定。
- 局域网配对 v2（S19）没有与旧版平板/手机做兼容性实测；二维码里没有 `arris` 参数时按设计**拒绝回传**。
- 车机路径（S20 / F1 / F2）未连接车机实测，只验证了载荷回查与单位换算。
