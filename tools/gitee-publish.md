# 用 Gitee 做下载镜像（一次性操作）

Gitee 上没有 `.git` 历史也能发 Release —— 只要仓库存在，`tools/gitee` 会直接调 OpenAPI 建仓、
发 Release、传附件，然后打印附件的**直链**（不是 tag 拼出来的那种）。

## 现状（2026-11-05 完成）

- 仓库：**https://gitee.com/yaxiaiyuting/arris**（公开，已镜像 17 分支 + 148 tag）
- 令牌已存在 `~/.config/gitee/token`
- 已发两个发行版：`v4.0.9-gpl`、`v4.0.10-gpl`（附件直链可匿名下载）
- **实测：Gitee 附件忽略 `Range`（回 200 全量）⇒ 只做单流回退，不参与分段轮转**
- **自动同步已打通**：Gitee「仓库镜像管理」配了 Pull（GitHub → Gitee），GitHub 侧 webhook 打在
  `gitee.com/yaxiaiyuting/arris/sync_tasks/callback`，实测 `f931412 → fafe6ee → dd70833 → 3df0a38` 全部自动跟到
- 手动兜底仍保留：`tools/gitee-sync`（零配置，一条命令推 master + tag）

## 同步节奏（来自 Gitee 官方文档）

镜像页写明了三条硬限制，别再按"push 完立刻一致"来预期：

- **触发最短间隔 5 分钟** —— 5 分钟内多次 push 只会合并成一次同步
- 单次同步**超过 30 分钟算超时**
- **连续 5 次失败**会被强制停止，之后最快 5 分钟才能再发起；错误信息在镜像列表里
- 镜像页的「更新」按钮可以手动发起（同样受 5 分钟间隔限制）

**实测（2026-11-05）**：一次普通 push 后 **32 秒** Gitee 就跟上了（`3f0a660 → efcc35c`）。
之前观察到"3 分钟没动"，是因为在 5 分钟窗口内连推了三笔、中间还夹了一次强制推送，
队列被合并/推迟了 —— 不是镜像坏了。

所以：正常发版让它自己跟（通常 30 秒 ~ 5 分钟）；需要**立刻**一致就 `tools/gitee-sync`。

## 1. 拿一个私人令牌（只有你能做）

Gitee → 头像 → **设置 → 私人令牌 → 生成新令牌**，勾上：

- `projects`（建仓、发 Release）
- `pull_requests`（不需要可不勾）

生成后只显示一次，复制。

## 2. 存进本机

```bash
/home/duanjb666/deepseek/tools/gitee login <粘贴令牌>
# → 已保存令牌，账号: <你的 Gitee 用户名>
```

令牌存在 `~/.config/gitee/token`（0600），和 `gh` 的 `~/.config/gh` 一个思路。

## 3. 建仓 + 发首个 Release（我来跑，或你手动跑）

```bash
tools/gitee repo create arris
tools/gitee release create <用户名>/arris v4.0.9-gpl \
  dist/v4.0.9-test/Arris-v4.0.9-gpl.apk \
  --name "Arris 4.0.9-gpl" --body release-notes.md
```

它会打印类似：

```
附件已上传: Arris-v4.0.9-gpl.apk
https://gitee.com/<owner>/arris/attach_files/<id>/download/Arris-v4.0.9-gpl.apk
```

## 4. 回填到客户端

把上面这条直链填进 `UpdateMirrors.GITEE_ASSETS`：

```kotlin
val GITEE_ASSETS: Map<String, String> = mapOf(
    "Arris-v4.0.9-gpl.apk" to "https://gitee.com/<owner>/arris/attach_files/<id>/download/Arris-v4.0.9-gpl.apk",
)
```

之后下载的段会轮转用到 Gitee 这条线（排在本站之后、ghproxy 之前）。

> 为什么不用 tag 拼前缀：Gitee 的 Release 附件 URL 是 `attach_files/<id>/download/<name>`，
> `<id>` 每次上传都不同，拼不出来 —— 只能上传后回填。
