# arris.de5.net 的 Cloudflare 配置（操作手册）

> 最后更新：2026-11-06。所有条目都用 API 落地过并逐条验证，不是"照着面板猜的"。
> **本文不含任何令牌**；令牌只存在 `~/.config/cloudflare/`（600）。

## 1. 区域结构

`arris.de5.net` 是一个**独立的 Cloudflare 区域**（父域 `de5.net` 里委派下来的子域）。
这带来两个后果，踩过一次就会记住：

1. 这里的记录写在 **`arris.de5.net` 区域**里（zone id 前缀 `90f24a55`），不是 `de5.net`；
2. `arris.ccwu.cc` 是**另一个**区域（`ac1c9d0b`），同样指向 `arris-listen` 这个 Worker ——
   所以两个域名内容一致，可互为备用入口。

## 2. DNS 现状

| 类型 | 名称 | 内容 | 代理 | 说明 |
|---|---|---|---|---|
| AAAA | `arris.de5.net` | `100::` | ✅ 已代理 | 占位地址，实际由 Worker 的 Custom Domain 接管 |
| AAAA | `www.arris.de5.net` | `100::` | ✅ 已代理 | 命中 Worker 路由后 301 回主域 |
| AAAA | `mirror.arris.de5.net` | S6 的 v6 地址 | ❌ 仅 DNS | 国内镜像；**必须关代理**，否则又绕回 CF 边缘 |
| TXT | `arris.de5.net` | `v=spf1 -all` | ❌ | 声明"本域不发邮件" |
| TXT | `_dmarc.arris.de5.net` | `v=DMARC1; p=reject` | ❌ | 伪造发信直接拒收 |

**`mirror` 的 TTL 是 120 秒**：ISP 前缀约 2 小时变一次，`arris-ddns.timer` 每 5 分钟比对并更新，
120 秒既能及时切换又不至于把 DNS 查询打爆。改 TTL 记得同步 DDNS 周期。

## 3. Worker 与路由

| 对象 | 值 | 备注 |
|---|---|---|
| Worker 名 | `arris-listen` | `wrangler.toml` 里的 `name` |
| Custom Domain | `arris.de5.net`、`arris.ccwu.cc` | 面板/Dashboard 创建，**账户级**对象 |
| Worker 路由 | `www.arris.de5.net/*` → `arris-listen` | 区域级对象，API 可建 |

**为什么 www 走"路由"而不是"Custom Domain"**：Custom Domain 属于账户级 API
（`/accounts/{id}/workers/domains`），实测 `POST` 会被拒（`10405 Method not allowed for this
authentication scheme`）；区域级路由 `POST /zones/{zone}/workers/routes` 一次就过。
代价是要自己补一条代理着的 DNS 记录（`100::` 占位即可）。

跳转写在 Worker 里（`www.` 开头 → 301 主域），不用面板的 Redirect Rule —— 面板规则不在仓库里，
换账号或子域就丢；代码在 `relay/src/index.mjs`，跟着版本走、可回滚。

## 4. Zone 设置（2026-11-06 收紧）

| 设置 | 值 | 为什么 |
|---|---|---|
| `always_use_https` | **on** | 之前是 off：`http://` 明文直通，这是本站唯一的安全隐患 |
| `min_tls_version` | **1.2** | 之前是 1.0；客户端 minSdk 24 起都支持 1.2，收紧无副作用 |
| `ipv6` | on | 国内移动网络 v6 普及率高，`mirror` 也靠 v6 直连 |
| `brotli` | on | 文本资源压缩 |
| `tls_1_3` | on | — |

改法（任意一把有 **区域设置:编辑** 的令牌）：

```bash
curl -X PATCH -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  "https://api.cloudflare.com/client/v4/zones/$ZONE/settings/min_tls_version" -d '{"value":"1.2"}'
```

## 5. 给 agent 的机器可读入口（2026-11-06 上线）

| 路径 | 内容 |
|---|---|
| `/robots.txt` | Allow / `Disallow: /api/feedback` / `Content-Signal` / 8 个 AI 爬虫显式放行 / Sitemap 引用 |
| `/sitemap.xml` | 单页站的 6 个锚点 |
| `/llms.txt` | 项目摘要 + 下载与源码位置（llmstxt.org 约定） |
| `/openapi.json` | 手写的 5 个端点（更新检查 / 下载 / 健康 / 房间） |
| `/api-doc.md` | 人读的接口表 |
| `/.well-known/api-catalog` | RFC 9727 linkset |
| 首页 `Link` 头 | `api-catalog` / `service-desc` / `service-doc` / `describedby` |

`isitagentready.com` 扫描从 **0 项通过 → 6 项通过**。刻意没做的项及理由：
`markdownNegotiation`（要给首页再维护一份 markdown 镜像）、`DNS-AID`、OAuth/OIDC、
`auth.md`、MCP Server Card / WebMCP、A2A Agent Card、`agentSkills`、ARD、
Personal Agent Protocol、以及全部 `commerce.*` —— 本站不卖东西、没有 agent 注册、没有 OAuth。

**AI 政策**取全允许（`ai-train=yes`）：GPLv3 开源项目，被检索与训练对传播有利。
要改成禁止，只动 `src/index.mjs` 里的 `CONTENT_SIGNAL` 一处。

## 6. 国内访问：302 到 Gitee

`/get/*` 对 `cf-ipcountry = CN` 的请求 **302 到 Gitee**，非 CN 或 `?GITEE_REDIRECT=0` 时行为不变。
依据是 S6 探针实测（见 `docs/ops/s6-probe.md`）：Gitee 整包 1.1 MB/s、TTFB 1.4s；
本站经 CF 直出 40~850 KB/s 波动、TTFB 1.6~4.1s（`cf-ray` 落在 SEA）。

- 版本化文件名（`Arris-vX.Y.Z-gpl.apk` / `Arris-vX.Y.Z-lts-gpl.apk`）→ 直接拼 Gitee 地址
- 别名（`latest.apk` / `lts-latest.apk`）→ 查 **`src/alias-tags.mjs`**

> ⚠️ **发布清单新增一步**：`alias-tags.mjs` 由 `node tools/sync-dl-manifest.mjs` 在**部署时生成**。
> 用工作区里过期的 `relay/public/dl/` 直接 deploy，会出现"版本化直链 404 + 别名指向旧版"，
> 踩过一次。正确顺序：`cp` 新包进 `public/dl/` → `node tools/sync-dl-manifest.mjs` → `wrangler deploy`。

## 7. 令牌清单（只记用途，不记内容）

| 文件 | 权限 | 用于 |
|---|---|---|
| `~/.config/cloudflare/dns-token` | 区域 DNS + 区域设置 | 加记录、改 zone 开关 |
| `~/.config/cloudflare/workers-token` | 账户/区域 Workers 模板 | 改路由、部署 worker |
| S6 上的 `/etc/cloudflare/dns-token` | 区域 DNS | `arris-ddns.timer` 更新 mirror 的 AAAA |

- R2 的 S3 密钥：**R2 服务未开通**（`code: 10042`），当前无用；且在聊天里出现过，**建议删除**
- 令牌建议 90 天过期；本仓库与文档**永不**保存令牌内容

## 8. 验证命令

```bash
# 首页与 api
curl -s -o /dev/null -w '%{http_code}\n' https://arris.de5.net/health
curl -s https://arris.de5.net/api/latest | head -c 200

# www 与 http→https
curl -s -o /dev/null -D - https://www.arris.de5.net/health | grep -i '^location'
curl -s -o /dev/null -D - http://arris.de5.net/health | grep -i '^location'

# 国内跳转（CF 的 cf-ipcountry 外部伪造不了，用查询参数测）
curl -s -o /dev/null -D - 'https://arris.de5.net/get/latest.apk?geo=CN' | grep -i '^location'

# agent 入口
for p in /robots.txt /sitemap.xml /llms.txt /openapi.json /.well-known/api-catalog; do
  curl -s -o /dev/null -w "$p %{http_code}\n" "https://arris.de5.net$p"
done
```

## 9. 仍然做不到 / 待办

- **`Rulesets`（Redirect Rule、Cache Rule）**：当前令牌 403；这些走不到代码里，暂不需要
- **Custom Domain 的增删**：账户级 API + 令牌认证方式限制，只能面板操作
- **R2**：未开通（10GB 免费 + 零出口费，但开通需绑卡）；开通后可把 `public/dl/` 的 APK
  移出仓库、由 R2 直接流式返回
