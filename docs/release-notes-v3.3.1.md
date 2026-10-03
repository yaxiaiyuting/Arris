# Ncrust v3.3.1-gpl

**versionCode 61** ｜ patch 版，只修一个 P0 ｜ 全量单测 **2485 用例 / 0 失败**

---

## 修的是什么

你反馈「B站歌词在这个版本还是拉取不上啊，我已经登陆账号了啊」——
**确认是我在 v3.3.0 引入的缺陷，而且那条链路一次请求都没发过。**

### 根因：cid 没有像取流那条路一样补问

视频搜索接口**不返回 cid**（它只给 bvid/aid），所以曲目的 `sourceId` 是
`bv:<bvid>:**0**`，解析回来时 `cid` 就是 `null` —— **正常播放路径下它恒为 null**。

取流那条路早就知道这件事，它写的是
`payload.cid ?: 去问一次 view`；
而我在取词那条路上只写了「没有 cid 就直接返回」⇒ 静默返回「无词」⇒
界面显示「暂无歌词」，与「这首歌确实没有字幕」**完全同形**，你无法分辨。

v3.3.0 源码逐行可核（tag `v3.3.0-gpl`）：
```kotlin
232:        // 视频轨：字幕 → LRC。
233:        val bvid = payload.bvid ?: return null
234:        val cid = payload.cid ?: return null        ← 必然在这里返回
```
同一版的 `parseSearchTracks` 构造曲目时确实没有传 `cid`。

**这也是我的流程失误**：我给字幕的解析与判据写了 20 条单测，但**没有一条守着
「请求到底发出去了没有」**，所以全绿而功能完全不通。

### 顺带修正一处语义（不是回归，是 v3.3.0 设计得不够好）

「请求失败/风控」（可重试）与「**这个视频没有字幕**」（稳定属性，重试不会变）
原本被折叠成同一个「无词」。折叠之后，「视频本来就没字幕」的用户会看到一个
永远点不出结果的按钮 —— 那正是 v3.3.0 从另一头修掉的缺陷形状。
现在两者分开：传输失败 ⇒ 可重试（按钮保持可点）；列表确认为空 ⇒ 稳定空态。

---

## 新增的回归防线（8 条，端到端、完全离线）

判据**不是**「解析对不对」（那已经有别的用例），而是上一版唯一没人守着的那一环 ——
**请求到底发出去了没有、cid 有没有被补问出来**：

- 搜索路径的 `sourceId`（cid 位为 0）也必须真的去取字幕 ← **核心回归判据**
- 补问之后 `player` 请求里带的是**真的 cid**（不只是「发了请求」——
  cid 传 0 服务端会回空列表，与「没有字幕」同形，所以判据落到参数值上）
- cid 已在载荷里时不再多问一次 `view`
- 返回的必须是歌词，不是 MV 开场的对白（6 条对白要被滤掉）
- 未登录 → 空串（确实没有），**网络失败 → null（取不到）** —— 这两条必须分开，
  否则重试按钮会永久置灰
- 开关关闭 → 零请求；`sourceId` 不可解析 → 不请求也不崩

---

## 我验证到什么程度

**已实测复核**（真实登录态，curl，2026-10）：
- 同一个视频**匿名 → `subtitles=0`，登录 → `subtitles=1 lan=ai-zh`**
  —— 这也解释了你最初的观察「B站的歌大多没有字幕」：其实**多数是登录后才有的**。
- 用**应用自己的 URL 形状 + 真实登录态**打真实接口 → `code:0`、`subtitles=1`、
  `subtitle_url=//aisubtitle.hdslb.com/…` ⇒ HTTP 层没有阻塞。

**已验**：v3.3.1 在 API 33 模拟器上安装、冷启、无崩溃；全量 2485 用例 / 0 失败；
lint 零 error。

**⚠️ 仍未验证（请你实测确认）**：模拟器上没有 B 站登录态，所以
**「点开某个 MV 看到歌词上屏」这一步我没有端到端跑过**。
我修的是一个**确定性**缺陷（代码必然在那一行提前返回），但只有你的真机实测才算闭环。

如果这次仍然没有歌词，请抓这段日志给我（它会直接区分「没发请求 / 没字幕 / 判定为非歌词」）：

```bash
adb logcat -s BiliSource
# 期望看到其中之一：
#   subtitle -> lyric: BV…/… cues=52 kept=46          ← 成功拿到并判定为歌词
#   subtitle is not lyrics: BV…/… cues=71             ← 有字幕但判定不是歌词（如 MV 合集对白）
#   subtitle none: BV…/…（列表为空 ⇒ 稳定空态）        ← 服务端没给字幕
#   subtitle list failed: BV…/…                       ← 请求失败（网络/风控）
#   subtitle skipped: no cid for bvid=…               ← cid 仍然取不到（说明 view 也失败了）
```

---

## 安装

下载下面的 APK 直接覆盖安装即可（`applicationId` 与签名均未变，`versionCode` 递增）。
本版**无新功能、无新文案、不改持久化结构**，所以安装后不需要任何额外设置。

完整功能清单与验证边界见 [v3.3.0 的发布说明](https://github.com/yaxiaiyuting/Ncrust/releases/tag/v3.3.0-gpl)。

---

## English summary

Patch release fixing a single P0 I introduced in v3.3.0: Bilibili **video-track lyrics
were never even requested**. The video search API does not return `cid`, so `sourceId`
is `bv:<bvid>:0` and `payload.cid` is always null on the normal playback path. The stream
path already resolved this by querying `view`, but the lyrics path I added simply returned
early — silently reporting "no lyrics", indistinguishable from "this video has no subtitles".
The lyrics path now resolves `cid` the same way the stream path does, and the
"transport failure" vs "no subtitles at all" distinction is preserved so a video without
subtitles does not leave a permanently greyed-out retry button.

Verified by curl with a real logged-in session (anonymous → 0 subtitles, logged in → 1
`ai-zh` subtitle for the same video) and with the app's exact request shape. 8 new
end-to-end offline tests assert what was previously unguarded: **that the requests are
actually issued and carry the resolved cid**. 2485 unit tests pass, lint is clean, and the
APK installs and cold-starts on an API 33 emulator.

**Not verified:** the final "open a music video and see lyrics" step on a real device with
a Bilibili login — the test emulator has no Bilibili session. The fix addresses a
deterministic early return, but only real-device confirmation closes the loop.
