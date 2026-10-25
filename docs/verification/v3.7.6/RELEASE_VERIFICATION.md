# Arris v3.7.6-gpl 验收记录（h2 误判纠正 + 登录页 404 兜底）

- 版本：`3.7.6-gpl` / `versionCode 92`
- 两件事：① 撤销 v3.7.5 的 HTTP/1.1 回退（**h2 本身可用，之前是误判**）；
  ② 登录页在部分运营商出口 404 时自动换入口；③ 补 `saveCookie` 的半截会话闸门。

## 1. h2 对照实验（真机 PLC110，debug 探针 `network/ProtocolProbeActivity`）

探针在**同一台设备、同一时刻**对同一接口发 8 组请求，每组独立连接池、记录协商协议与耗时：

| 用例 | 协议 | 结果 |
|---|---|---|
| A h2 + 完整头 + 真实 eapi 参数 | h2 | **200 / 343 ms / 1067 B** |
| B h2 + 完整头 + 小 body | h2 | 200 / 278 ms |
| C h2 + 最小头 + 小 body | h2 | 200 / 269 ms |
| D h2 + 不带 Cookie + 真实参数 | h2 | 200 / 305 ms |
| E h1 + 完整头 + 真实参数（对照） | http/1.1 | 200 / 336 ms / 1067 B |
| F h2 + 另一路径 `song/detail` | h2 | 200 / 309 ms |
| G h2 + `interface` 主机 `account/get` | h2 | 200 / 303 ms |
| H h2 + 主机根路径 `/` | h2 | **404**（CDN 对该出口的根路径行为，与协议无关） |
| I h2 + **应用共享连接池** | h2 | 200 / 332 ms |
| J h2 + **HttpTimingListener** | h2 | 200 / 317 ms |
| K h2 + **perHost=12 dispatcher** | h2 | 200 / 326 ms |
| L h2 + **`plainClient` 同配置**（即修复前的客户端形状） | h2 | 200 / 303 ms |

结论：**服务端不拒绝 h2**，包括应用真实的客户端配置。v3.7.5 里观察到的 `REFUSED_STREAM`
是**请求风暴限流**：一次点击触发 6 档降级阶梯、`catch (Exception)` 把取消一起吞掉 → 旧阶梯继续跑、
切歌又叠加，失败后还被误判成「结构性不可播」去自动跳歌，53 秒内累计 2064 次请求。
风暴当天做的「h2 vs h1」对照实验仍处在限流窗口内，因此得出了错误结论。

## 2. 真实取链路径压测（h2，连续 10 首，`SongUrlFetcher.fetch`）

```
S total  1895 ms（≈190 ms/首）
8 首 granted=jymaster（3.87–5.32 Mbps FLAC）
2 首 granted=exhigh(320k) / higher(192k) —— 歌曲自身档位限制，非失败
transportFail=false，无 StreamReset
```

## 3. 登录页 404

同设备实测（运营商出口）：`https://music.163.com/` = **404**；`https://music.163.com/login`
→ 302 → `https://y.music.163.com/m/login` = **200**。换 UA 无效，说明是出口/CDN 节点行为。
修法：WebView 主框架命中 404/403 时**只换一次**到 `/login`。

## 4. 会话被顶掉

`CookieManager.saveCookie`（整体覆盖写）此前没有「半截会话不得覆盖完整会话」的闸门，
登录页只写入 `JSESSIONID` 时可以把 `MUSIC_U` 顶掉。已补 `NeteaseCookie.shouldReplace` 判定。

## 5. 自动检查

- 3074 项 JVM 单测通过（250 类 / 0 失败）；Lint **0 error**（60 条既有 warning）。
- 正式包 `versionCode 92`，签名与 3.7.2–3.7.5 相同；`zipalign -P 16` 通过。
- 探针只存在于 `src/debug`，正式包 dex 中不含 `ProtocolProbeActivity`（已核对）。

## 6. 边界

- 只在 PLC110（Android 16，移动网络）上验证；未覆盖其它运营商/CDN 节点。
- h2 结论为行为级证据（8 组对照 + 应用真实配置），未做报文级抓包。
