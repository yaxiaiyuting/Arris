# v3.2.1-gpl 发布状态

> 本文件按 tag 自证，不写死 SHA（v3.2.0 起的约定）。

## 结论：**已发布**

| 项 | 值 |
|---|---|
| tag | `v3.2.1-gpl`（annotated，指向产出这批 APK 的那个提交） |
| release | https://github.com/yaxiaiyuting/Ncrust/releases/tag/v3.2.1-gpl · **已发布**（非 draft） |
| 发布时间 | 2026-09-28T13:15:24Z |
| versionCode | **56** |
| versionName | **3.2.1-gpl** |

## 三源交叉校验（versionCode 56）

| 来源 | 值 | 证据 |
|---|---|---|
| ① `app/build.gradle.kts`（工作区） | 56 | `grep -E '^\s*versionCode = ' app/build.gradle.kts` |
| ② release APK（`aapt2 dump badging`） | **56** | `dist/Ncrust-v3.2.1-gpl-release.apk`（红线 19：只看二进制，不看 release 标题） |
| ③ debug APK（`aapt2 dump badging`） | **56** | `dist/Ncrust-v3.2.1-gpl-debug.apk` |
| ④ tag 指向的 `app/build.gradle.kts` | 56 | `git show v3.2.1-gpl:app/build.gradle.kts \| grep version`（打 tag 前自证过一次） |

定号出处：`tools/next-version.sh`（**带 fetch**）三源交叉验证 ⇒ max 55 ⇒ 本版取 **56**
（原始报告：[next-version.txt](next-version.txt)）。

## HEAD == 产物源码

| 检查 | 结果 |
|---|---|
| 打包时工作区 | **clean**（`git status --short` 为空） |
| `dist/*.apk` 与 `app/build/outputs/apk/**` 逐字节一致 | ✅ `cmp` 通过（release + debug） |
| tag ↔ HEAD 同一提交 | ✅（`git rev-parse v3.2.1-gpl^{commit}` == `git rev-parse HEAD`） |

## 产物 digest（自证：与 release 上的 asset digest 相同）

```
sha256(bace51dc1dc20b7f2c2905ca7ddb219e1aa8e283d9eb48ebd9eb1af21c56a36a)  Ncrust-v3.2.1-gpl-release.apk
```

- 本地：`dist/Ncrust-v3.2.1-gpl-release.apk`（10,206,696 字节）
- 远端 asset digest（`gh release view --json assets`）：`sha256:bace51dc…a36a` —— **逐字节一致**

## 本次发布**没有**移动任何历史 tag

- `v3.2.0-gpl`（55）及其之前的 tag 全部原样保留；
- 本版是**新打的** tag，`git push origin v3.2.1-gpl` 的输出是 `* [new tag]`。

## 发布后仍有的事（不阻塞发布，如实记录）

1. **B站「扫码 → 登录成功」需要真实账号扫码**（本轮只验证到二维码正常显示 + 轮询启动）；
2. **JIT 归因未证实**（12 次尝试 0 复现）——「不再闪退」的证据强度是 10 次冷启动全绿；
3. 方法大小报告里仍有 **7 个 app 内方法超 2000**（本版范围外）；
4. 华为 / 荣耀控制中心卡片本版**一个字都没碰**。
