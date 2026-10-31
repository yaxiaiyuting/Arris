# 一起听 · 中继服务（Cloudflare Workers + Durable Objects）

一个房间 = 一个 Durable Object 实例；服务端只做「把这行消息转给同房间其他人」。
账号、播放裁决、音频都不经过这里。

## 一、部署（约 3 分钟）

```bash
# 1. 安装 wrangler（Cloudflare 官方 CLI）
npm i -g wrangler

# 2. 登录（浏览器点一下授权即可，免费注册账号就行）
wrangler login

# 3. 在 relay/ 目录部署
cd relay
wrangler deploy
```

部署成功会打印地址，形如：

```
https://arris-listen.<你的子域>.workers.dev
```

自检：

```bash
curl https://arris-listen.<你的子域>.workers.dev/health     # → ok
```

## 二、客户端怎么连

```
wss://arris-listen.<你的子域>.workers.dev/room/<房间码>?role=host&name=昵称
wss://arris-listen.<你的子域>.workers.dev/room/<房间码>?role=member&name=昵称
```

- `房间码`：4–32 位字母数字（客户端生成 6 位即可）
- `role=host` 只是标记，服务端不校验（房主权威由客户端的 `RoomEngine` 保证：
  seq 单调、非房主操作被 `Reject(NotHost)` 拒掉）
- 协议就是现有 `RoomWire` 的行 JSON：`hello / snapshot / op / ping / pong`

## 三、免费额度能撑多少人（官方数字，2026-02）

| 维度 | Workers Free |
|---|---|
| 请求数 | **100,000 / 天** |
| Durable Object 时长 | 13,000 GB·s / 天 |
| SQLite 行读 | 5,000,000 / 天 |
| 行写 | 100,000 / 天 |

计费口径（关键）：

- 建一条 WebSocket = **1 次请求**
- **发出去的消息不计费**；只有**进来的**消息计费，且按 **20:1** 折算（100 条 → 5 次请求）
- 用 Hibernation API 后，房间没人说话时**不产生时长费用**

### 换算成人数

单个成员每小时成本 ≈ `建立连接 1 次 + 心跳条数 ÷ 20`。

| 心跳间隔 | 每人每小时请求 | 100k/天 能撑（按每天 4 小时活跃） |
|---|---|---|
| 10 s | ≈ 19 | ≈ **1,300 人次/天** |
| 20 s | ≈ 10 | ≈ **2,500 人次/天** |
| 30 s | ≈ 7 | ≈ **3,500 人次/天** |

也就是说：**同时在线几十到几百人毫无压力**，瓶颈是「每天总时长」而不是「同时人数」。
每个房间一个 DO，单房间上限在代码里写死 `MAX_MEMBERS = 16`（可改）。

超过免费额度时：升级 Workers Paid（$5/月起）后额度变成 100 万请求/月 + 40 万 GB·s，通常够一年。

## 四、常见问题

- **部署报 `new_sqlite_classes` 不识别**：wrangler 版本太老，`npm i -g wrangler@latest`
- **连不上 wss**：确认用的是 `wss://` 不是 `https://`，并且 URL 里带 `/room/<id>`
- **房间满**：改 `MAX_MEMBERS` 后重新 `wrangler deploy`
- **想换房间码规则**：改 `src/index.mjs` 里的正则 `[A-Za-z0-9_-]{4,32}`
