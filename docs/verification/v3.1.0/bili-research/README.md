# B站音源调研（Ncrust v3.1.0）

> 纯调研 + 实测验证产物。**未改动 `app/src` 下任何代码。**

## 读哪一篇

| 想了解 | 看这里 |
|---|---|
| 音频区有哪些接口、哪些还活着、返回什么字段 | [`bili-audio-api.md`](bili-audio-api.md) |
| Wbi 签名怎么实现、怎么验证、有哪些坑 | [`wbi-signature.md`](wbi-signature.md) |
| 要不要登录、匿名能用什么 | [`bili-auth.md`](bili-auth.md) |
| 别人怎么做的、**许可证能不能抄** | [`community-implementations.md`](community-implementations.md) |
| **接入方案、优先级、与现有架构怎么接** | [`RECOMMENDATIONS.md`](RECOMMENDATIONS.md) |
| 原始 curl 输出与时间戳 | [`EVIDENCE.md`](EVIDENCE.md) + `evidence/` |

## 一键复现

```bash
bash collect_evidence.sh            # 65 条 curl + 3 份脚本产物 → ./evidence/
bash collect_evidence.sh songinfo   # 只跑名字含 songinfo 的

# Wbi 两条硬性验收（互相独立）
env -u https_proxy -u http_proxy python3 wbi_golden.py --live   # 离线官方向量自检（4 项 MATCH）
env -u https_proxy -u http_proxy python3 wbi_ab.py              # 服务端 A/B（-352 → code:0）
```

⚠️ B站请求**必须绕过本机代理**（环境变量默认含 `http_proxy=http://127.0.0.1:10808`）；
`collect_evidence.sh` 已内置 `unset`。

## 五条最关键的实测结论

1. **Wbi 签名已实测成功**：官方向量 4 项逐字节 MATCH（`mixin_key` / 排序 query / `w_rid` / 中文空格编码），
   服务端 A/B 打在 `/x/web-interface/wbi/view/detail` 上 —— 无签名 `-352 风控校验失败`，有效签名 `code:0 OK`，
   伪造 `w_rid` 又回到 `-352`（证明服务端真在算 md5）。
   ⚠️ 但**不是所有 wbi 端点都验签**（`search/type` 在本机出口完全不验）；**HTTP 412 与签名无关**（是路径级封禁）。
2. **音频区没有搜索接口**（穷举 10 个候选全 404/空壳），**通用搜索也没有音频分区**；
   `search_type=music`/`audio` 与故意乱填的 `foobar` 返回同一个 `-1200` ⇒ **取值非法**。
3. **匿名能拿到 320K** —— 但只在 APP 端点 `/audio/music-service-c/url?songid=` 上；
   web 端点 `/web/url` 只给 192K 且完全忽略 `quality` 参数。FLAC 匿名不可用（`qualities[]` 里没有 `type:3`）。
4. **URL 有效期实测是 2 小时**：响应 `timeout` 恒为 10800（名义 3h），
   而 URL 查询串里的 `deadline` 恒为 `now+7200`（真实 2h）。**以 `deadline` 为准**。
   并且 **CDN 强校验 Referer，不带就 403**（三种流一致）。
5. **推荐接入模式**：音频区 `au` 作播放主体（匿名 320K + LRC 歌词 + 零风控）+ 视频搜索作发现入口
   （音频区搜不到东西）。**默认关闭**、**本版不登录**。

## ⚠️ 两条合规提醒

- **`SocialSisterYi/bilibili-API-collect` 已永久关停**（2026-01-28 收到 B站委托律所的律师函，
  `docs/` 与 `LICENSE` 全部删除，上游实测 HTTP 404）。其许可是 **CC BY-NC 4.0**，
  **与 GPLv3 不兼容，文档内容不得复制进本仓库**。本次调研的全部结论建立在自行 curl 实测之上。
- 五个社区参考项目里：**2 个无 LICENSE（默认保留全部权利）、1 个 PolyForm Noncommercial（非开源且限非商业）、
  1 个 CC BY-NC（已关停）**，只有 2 个是 MIT。许可证明细见 `community-implementations.md` §1。
