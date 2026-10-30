# 自有域名接入一起听中继（v3.8.1）

## 一、配置结果（已完成，服务端侧全绿）

| 项 | 值 |
|---|---|
| 域名 | `arris.ccwu.cc`（免费子域，NS 委派给 Cloudflare） |
| Zone | `ac1c9d0bfcff220b90e69a7171138302`（status: **active**） |
| Nameservers | `lilith.ns.cloudflare.com` / `seth.ns.cloudflare.com` |
| Worker Custom Domain | `arris.ccwu.cc` → `arris-listen`（cert 已签发） |
| 首页 | `https://arris.ccwu.cc/` → 200（项目介绍页，内联在 Worker 里） |
| 接口 | `/health` → `ok`；`/rooms` → `{"rooms":[]}` |

验证方式：手机 Chrome **不经任何代理**打开 `https://arris.ccwu.cc/health` 得到 `ok`，
`/rooms` 得到 JSON —— 说明域名、证书、Worker 绑定、路由全部正确。

## 二、但是：TCP 到该域名被间歇性 RST

同一台手机、同一时刻的对照：

| 客户端 | 结果 |
|---|---|
| Chrome（有 QUIC/Happy Eyeballs、自己的一套指纹） | 有时成功（拿到 `ok` / JSON） |
| 本应用 OkHttp | 稳定 `SocketException: Connection reset` |

已排除的变量（都做过实验）：

- **IP 选择**：钉到 Chrome 用过的同一个 IP（`104.21.69.86`）依旧 RST
- **请求头**：换成完整浏览器 UA + Accept/Accept-Language 依旧 RST
- **协议**：强制 HTTP/1.1 + 兼容 TLS 套件依旧 RST
- **端口**：`443` 与 `8443` 都 RST
- **IPv6**：先试 IPv6 会 8 秒超时（已改成 IPv4 优先/仅用）
- 对照 `https://www.cloudflare.com/` 同网络可正常打开 ⇒ 不是"Cloudflare 被封"

结论：**该 SNI 在 TCP 上被 RST 注入**（免费域名后缀常被整段标记），
而 Chrome 偶尔侥幸（QUIC / 多次重试）。这类封锁不是客户端参数能绕开的。

## 三、当前可用的跨网方案（不依赖该域名）

1. **局域网 / 热点直连**（已实现，真机验证过）
2. **P2P 打洞**（已实现；家用宽带锥形 NAT 有机会，移动数据 CGNAT 不行）
3. **Tailscale 虚拟内网** + 局域网加入（免费，跨网可用）
4. 换一个**未被标记的域名**（自有域名或付费域名）后，Worker 侧一行都不用改

## 四、客户端改动（保留的部分）

- 默认中继指向 `https://arris.ccwu.cc`；老版本 prefs 里的 `*.workers.dev` 一次性迁移到新域名
- 固定 IP 只作为「DNS 被污染」场景的兜底，且**只在域名含 workers.dev 时生效**
  （换成自有域名后钉 IP 反而会被 IP 定向阻断打死）
- `AddressPreference`：**有 IPv4 就只用 IPv4**，纯 IPv6 网络原样返回
