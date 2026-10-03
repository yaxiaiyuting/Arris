# P1 结论（**已修正**）：华为控制中心媒体面板 —— 是应用侧的缺陷，不是 ROM 能力边界

> ## ⚠️ 本文件推翻了本目录先前版本（以及 v2.1.5 release notes 初稿）的结论
>
> 先前写的是「应用已经发布、ROM 已经消费，平台上不存在专门的歌词 key，屏幕侧无法判定，
> 因此是能力边界」。**那个结论是错的**，错在拿「`dumpsys` 里有数据」当成了「面板会显示」。
>
> 用户反馈「华为设备还是不行」后，我按他说的**真的去截了屏**，并用同一台设备做了 A/B，
> 结论被推翻。错误与纠正过程保留在 §5 —— 它与 `../dex-check.txt` §4 是同一类教训：
> **代理指标（dumpsys 里有数据）不能替代端到端观测（屏幕上有没有）。**

---

## 1. 决定性证据：同一张卡，别人行、Ncrust 不行

设备：WGR-W09（华为平板，HarmonyOS 4.2 / EMUI 14.2.0 / Android 12 / API 31）。
方法：`uiautomator dump` 读控制中心那张媒体卡的**文本节点**（不靠肉眼猜模糊截图）。

| 正在播放的应用 | 卡片的 `content-desc` | 判定 |
|---|---|---|
| **官方 ncm**（第三方！） | `The Final Countdown Europe` | ✅ 正常显示歌名 + 艺人 |
| **华为音乐**（系统应用） | `喜欢你 BEYOND` | ✅ 正常显示 |
| **Ncrust**（v2.1.5，确实在播 `state=3`，歌词行在更新） | `未在播放` | ❌ 空卡片 |

⇒ 「华为控制中心不认第三方应用」**不成立**。官方 ncm 就是第三方，它正常。
所以这是**应用侧的问题**。

原始证据：`EVIDENCE.md`、`ui-dump-*.xml`、`01-ncrust-playing-card-says-not-playing.png`。

## 2. 应用侧的两个确凿缺陷

### 2.1 两条 MediaSession 在互相顶替（实测日志）

Ncrust 同时暴露两条会话：

```
NcrustSession                com.takahashirinta.ncrust/NcrustSession
androidx.media3.session.id.  com.takahashirinta.ncrust/androidx.media3.session.id.
```

而 ROM 的 media button session 在两者之间**反复跳**：

```
I MediaSessionService: Media button session is changed to …/androidx.media3.session.id.
I MediaSessionService: Media button session is changed to …/NcrustSession
```

对照：官方 ncm `com.netease.cloudmusic/MediaSession` **只有一条**；华为音乐
`com.android.mediacenter.mediasession` **也只有一条**。Ncrust 是唯一一个让系统在两个
「当前播放器」之间摇摆的。

### 2.2 应用没有向系统声明自己是媒体应用

华为 ROM 里两处判定都在明确拒绝：

```
V HwMediaSessionServiceInner: this app is not in media white list, pkgName: com.takahashirinta.ncrust
V MediaControlUtils:          this app is not in media white list, pkgName: com.takahashirinta.ncrust
```

清单层面的差集（`aapt2 dump xmltree` + `dumpsys package` 实测）：

| 应用 | 是否声明 `android.intent.action.MEDIA_BUTTON` 接收器 | 控制中心卡片 |
|---|---|---|
| 官方 ncm | 是（`MediaButtonEventReceiver`，`mPriority=2147483647`） | ✅ |
| 华为音乐 | 是（`MediaButtonIntentReceiver`） | ✅ |
| **Ncrust（v2.1.4/2.1.5 修复前）** | **否 —— 清单里 `<receiver>` 数量为 0** | ❌ |

即：用户说的「**感觉可能是没被当成音乐软件**」是准确的。

## 3. 本版已改的两处（其中一处已验证、一处未验证）

| 改动 | 状态 |
|---|---|
| `PlaybackService.buildCurrentMediaItem()`：当前播放项带上 `title/artist/artworkUri/mediaId`（旧代码是 `MediaItem.fromUri(url)`，**没有任何 metadata**） | ✅ **已验证**：media3 会话从 `size=3, description=null` 变成 `size=10, 修炼爱情, 林俊杰`。两条会话终于对系统讲同一件事 |
| 清单补 `androidx.media3.session.MediaButtonReceiver`（`MEDIA_BUTTON`） | ⚠️ **未验证有效**：已进包（`aapt2 xmltree` 可见）、已安装、已重启设备，但 **ROM 仍然打 `not in media white list`，卡片仍是「未在播放」** |

**结论：卡片问题尚未解决。** v2.1.5 不能声称修好了它。

## 4. 下一步（v2.1.6）

按证据排序：

1. **把两条会话合成一条**（最可能的真因）。media3 的 `MediaSession` 提供
   `sessionCompatToken`，可以让 MediaStyle 通知引用 media3 那一条，从而不再需要独立的
   legacy `MediaSessionCompat`。这样系统只会看到一个「当前播放器」。
   **风险**：通知/媒体按键路径是 v2.0.2 刚稳定下来的承重结构，必须成组回归
   （通知正文随歌词行刷新、锁屏、蓝牙 AVRCP、车机、耳机按键）。
2. **查清华为 `media white list` 的准入判据。** 已知它在 system_server 的
   `HwMediaSessionServiceInner` / `MediaControlUtils` 里，但列表来源未定位
   （ROM 内可能是硬编码数组或签名白名单）。若判据是「华为应用市场签名」，
   那对这一侧的缺口就是**真正的能力边界** —— 但那是**第二个**问题，不能拿来解释第一个，
   因为官方 ncm 证明这张卡对普通第三方应用是可用的。
3. 就「歌词行显示在卡片上」这件事：先让卡片**能用**（第 1 条），再谈把歌词推上去。

## 5. 我错在哪（保留记录）

- **错在把代理指标当成了结论。** `dumpsys activity service SystemUIService` 里
  `MediaDataManager` 有一条 `active=true, song=<歌词行>` 的 Ncrust 记录 —— 我据此写成
  「ROM 确实消费了」。但**那条记录与用户看到的那张卡不是同一个东西**：
  华为音乐在卡片上正常显示时，`MediaDataManager` 里**根本没有**它的条目。
  两个组件、两套数据源，我拿 A 的观测去证明了 B 的结论。
- **错在没有做端到端观测就写「无法判定」。** 我当时写「adb 无法判定屏幕侧」，
  但 `uiautomator dump` 就能把卡片上的文字**读出来**，而且同设备换个应用就能做 A/B。
  工具一直在手里，缺的是「先验证观测手段本身是否够用」。
- **用户的直觉两次都比我的推断准**：第一次是「没被当成音乐软件」（命中
  `media white list` 与缺 `MEDIA_BUTTON` 声明），第二次是「qq音乐和 ncm 都是正常的」
  （直接证伪了我的「ROM 不认第三方」假设）。

**固化成规则**：只要结论是「ROM/平台不支持」，必须先给出**同一设备上另一个应用正常**的
反例搜索记录；没有做这个搜索，就不许写「能力边界」。
