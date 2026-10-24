# Arris v3.7.3-gpl 验收记录

- 版本：`versionName 3.7.3-gpl` / `versionCode 89`（上一版 88）
- 分支：`master`；本轮**未做真机测试**，也**未向真实账号发送**续期、收藏或播放上报请求。
- 构建：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`，`BUILD SUCCESSFUL`。

## 1. 单元测试

```
3058 tests, 0 failures, 0 skipped   (249 个测试类)
```

本轮新增 4 个纯逻辑测试类，共 20 例：

| 测试类 | 覆盖的判据 |
|---|---|
| `auth/NeteaseSessionRenewalTest` | 迟到响应不能跨账号提交；同字节重登仍推进代次；续期只换代次不换登录代次；快照 `toString` 不泄露凭证；冷却按会话绑定且边界精确；成功后 10 分钟内抑制刷新风暴；续期成功只关闭被替换的那份响应并在新凭证上重试一次；并发续期只重试一次；网络失败保留原响应与票据；换号期间不得重放旧账号读取；取消时关闭原响应且不重试 |
| `cache/OfflineContentRecoveryTest` | 首选档位不可用不遮挡其它档位；失败档位不阻止回退；不会串到别的歌曲；旧字符串清单迁移为 UNKNOWN 而非 FULL；试听属性冷启动往返后仍可判定 `Trial(45)`；full/trial 用不同文件 key 且保留档位语法；同文件的 remember 不擦掉已持久化的归属；FULL 优先于旧记录与试听；损坏条目不影响其它记录；**FULL 保持历史 key，既有下载仍能命中** |
| `player/PreloadGenerationTest` | 删除待播曲后，已入队的 Intent 与在飞结果同时失效；重复失效不会复活旧 Intent；移动/追加同样推进代次 |
| `qq/QqPlaylistAuthTest` | 断网 → `OFFLINE/Network` 而不是 NEED_LOGIN；未知业务码 → `OFFLINE/Other(code)`；只有显式鉴权拒绝才 → `NEED_LOGIN/NeedLogin`；成功不产生降级 |

## 2. Lint

```
0 errors, 60 warnings (17 errors, 60 warnings filtered by baseline lint-baseline.xml)
```

60 条警告均为既有类型（弃用 API、未用变量、`Unnecessary safe call` 等），无新增 error。

## 3. 制品

| 文件 | 大小 | 说明 |
|---|---|---|
| `Ncrust-v3.7.3-gpl-release.apk` | 12 MB | `versionCode=89`，V2 签名证书 SHA-256 `e62eca39…bbec82`（与 v3.7.2 同一把），`zipalign -c -P 16 4` 通过 |
| `Ncrust-v3.7.3-gpl-debug.apk` | 33 MB | 同上版本号，debug 签名 |
| `mapping-v3.7.3-gpl.txt.gz` | 5.6 MB | R8 混淆映射 |
| `SHA256SUMS` | — | 上述三个文件的校验和 |

覆盖安装：证书与 v3.7.2 一致，`versionCode` 89 > 88，可原地升级。

## 4. 源码修复清单

详见 `docs/reviews/2026-10-25-api-enhanced-comparison.md`（会话隔离 / QQ 分类）与
`docs/reviews/2026-10-24-playback-adjacent.md`（预载 / 离线档位 / 试听属性）末尾的“修复状态”一节。

## 5. 远端草稿 Release

- 发布：draft **true**，tag `v3.7.3-gpl`，id `422929677`，源码提交 `786f89c`。
- 五个附件远端 `size` 与 `digest` 全部与本地一致：**True**；另行下载 release APK 复核 SHA-256：**True**。

| 附件 | 字节 | 远端 digest |
|---|---|---|
| `mapping-v3.7.3-gpl.txt.gz` | 5776242 | `sha256:67945654ca5000ab56ffb94b3e9eb9e7a9c164d33089a16945f0ebade42d3fc3` |
| `Ncrust-v3.7.3-gpl-debug.apk` | 34197075 | `sha256:89740161284eaa60fc301de65816cfaba6194ee4eb7ff95a510ded3d23b2e7b1` |
| `Ncrust-v3.7.3-gpl-release.apk` | 12039884 | `sha256:935d16d71344f5ed458174b3ec118aa1f2bc9ef2923b61dd15aeb785921e2a64` |
| `RELEASE_NOTES.md` | 2161 | `sha256:082d4de653ad824134c2ff0fe0b4992d5eac8b31bbecbf97e5597bdb8c6877d7` |
| `SHA256SUMS` | 365 | `sha256:e652e380c1d89712bb12ec253a908697a68e067be6bd9db9ff803f09bcca0b1e` |

链接：https://github.com/yaxiaiyuting/Arris/releases/tag/untagged-683e0fd363644862f0bc
