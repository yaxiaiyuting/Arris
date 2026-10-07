# v3.4.8 验证记录 —— B 站音源：Hi-Res 无损 / 多语言字幕 / 可调参数

> 本目录只放**这一次修复的可复现证据**。结论先行，每条都标出「这是实测还是推断」。

## 用户报的三个问题

| # | 原话 | 根因（实测确认） | 落点 |
|---|---|---|---|
| 1 | 「b站音源有大会员支持的 hires 音源，但是我们的 b 站音源无论如何都播放的是 mp3 的普通版本」 | `BiliParse.parseDashAudio` **只读 `dash.audio[]`**，而大会员 Hi-Res 无损在 **`dash.flac.audio`**（一个独立字段）；而且视频轨**完全不看请求档位** | `fix(bili)` |
| 2 | 「b站有些歌曲具有多种语言歌词，建议可以增加抓取的自选项，在所有音源都支持选择自己想要开启的字幕语言」 | `parseFirstSubtitleUrl` 写死「**优先含 `zh` 的那条**」，60 条语言轨里用户只能拿中文，且没有开关 | `feat(bili)` |
| 3 | 「开放一些参数供用户自行调整」 | 音质上限、是否优先 FLAC **在代码里写死**，用户改不了 | `feat(settings)` |

---

## 一、问题 1：大会员 Hi-Res 从来没被读过

### 1.1 服务端确实在给 FLAC（实测，2026-10-07，登录态 + 年度大会员）

```
GET /x/player/playurl?bvid=BV1EC4y1R7ax&cid=25931154534&fnval=4048&fnver=0&fourk=1
```

| 出自 | `id` | `bandwidth` | `codecs` |
|---|---|---|---|
| **`dash.flac.audio`** | **30251** | **2247494** | **`fLaC`** |
| `dash.audio[]` | 30280 | 213610 | `mp4a.40.2` |
| `dash.audio[]` | 30232 | 109633 | `mp4a.40.2` |
| `dash.audio[]` | 30216 | 53781 | `mp4a.40.5` |

下载那条 FLAC 的前 2 MB 用 ffprobe 解码（**这是「真的 Hi-Res」的定义性证据**）：

```
codec_name=flac  sample_rate=96000  channels=2  bit_rate=2029320
```

另一首 `BV1BZbSzZEGT`（周杰伦《晴天》）：`flac.bandwidth = 3154514`，
ffprobe 实测 `flac / 96000 Hz / 2425659 bps`。

### 1.2 同一请求匿名发出去时 `flac` 恒为 `null`

```
匿名：flac.audio: NULL      （同一 bvid、同一 cid）
登录 + 大会员：flac.audio: id=30251 bw=2247494 codecs=fLaC
```

⇒ 读到空**不是缺陷**，是「这个身份拿不到」。所以新代码在 `flac` 缺失时
静默退到 AAC 并如实标「已降级」，而不是报错。

### 1.3 `fnval` 不需要改（这一条推翻了调研期的推断）

调研文档（`docs/verification/v3.1.0/bili-research`）建议「`fnval=16` → `4048`
以多拿 `dash.flac.audio`」。**实测两者都返回 flac**：

| `fnval` | 有 DASH 位？ | `dash.flac.audio` |
|---|---|---|
| `16` | ✅ | ✅ id=30251 |
| `272` (16+256) | ✅ | ✅ id=30251 |
| `4048` | ✅ | ✅ id=30251 |
| `64` / `128` / `256` / `512` / `1024` / `2048`（无 DASH 位） | ❌ | 无 `dash` 对象 |

⇒ 修复前拿不到 Hi-Res **纯粹是因为没读那个字段**，与 `fnval` 无关。
`fnval=4048` 保持不变（少动一个已验证过的常量）。

### 1.4 「修复前会选什么」与「修复后选什么」（同一份响应）

| 请求档位 | 修复前 | 修复后 |
|---|---|---|
| `standard` / `higher` / `exhigh` | 192K AAC（30280） | 192K AAC（30280）—— **有损档位不去碰 FLAC** |
| `lossless` / `hires` / `jyeffect` / `jymaster` | **192K AAC** ← 缺陷 | **Hi-Res FLAC（30251）** |
| `dolby` | 192K AAC | 杜比轨（缺失时退 AAC） |

复现：`env -u https_proxy -u http_proxy python3 probe-v348.py`（完整输出见 `probe-output.txt`）。

### 1.5 顺带修掉的第二条：音频区「界面写无损、耳朵听 320K」

音频区（`au:`）这条路**服务端会静默降级**：请求 `qn=3`（FLAC）时，
`qualities[]` 里根本没有 `type:3`，返回的是 `-320k.m4a`：

```
## au39  请求 qn=3（FLAC）
   服务端返回文件：ef0083e1cd13e73dcd20bfe3e672c21a-320k.m4a
   qualities[] 里有 type:3 吗：没有
   ★ 修复前回显：lossless（按请求档位，界面写「无损」）
   ★ 修复后回显：exhigh（按文件名反推，界面如实显示降级）
```

这是 v2.1.4 修掉的「标签写高」在 B 站这一侧的**同一缺陷形状** ——
代码没读服务端写进文件名的那一档。修复后 `actualLevel` 由文件名反推，
`QualityAssessment` 会如实给出「已降级」。

### 1.6 证据链的两半（如实说明各自证明了什么）

| 证明 | 手段 | 能证明什么 | **不能**证明什么 |
|---|---|---|---|
| 服务端会给这些字段 | `probe-v348.py`（真实 curl/urllib + ffprobe） | 字段存在、带宽、真实编码 | 代码会不会读它 |
| 代码按这些字段决策 | `BiliHiResTest`（JVM，离线，**内嵌同一份响应样本**） | 解析 / 选流 / 档位反推 / 端到端取链 | 真机播放出声 |

两侧共用同一份 JSON（字段级逐字），所以合起来才是
「用户点播放会拿到 Hi-Res」的完整证据。
**真机播放（ExoPlayer 解 96 kHz FLAC）本次未验证** —— 测试机在施工期间掉线（见 §四）。

---

## 二、问题 2：多语言字幕

### 2.1 同一个视频最多 60 条语言轨（实测）

```
## BV1GJ411x7h7 共 12 条语言轨
   zh-CN / zh-Hans / zh-Hant / zh-HK / en-US / ja / ko / de-DE / ru / iw / ca / ase
## BV1uT4y1P7CX 共 60 条语言轨
   zh-CN / zh-Hans / zh-Hant / zh-HK / zh-TW / zh-SG / en / en-US / en-CA / en-IE
   / en-GB / ja / ko / es / pt / pt-BR / fr / fr-BE / … / ase / ie
```

而修复前 `parseFirstSubtitleUrl` 只做一件事：**找 `lan` 里含 `zh` 的那条**。

### 2.2 修复后的选择结果（同一份真实响应）

| 用户选择 | 应用语言 | 选中的轨 |
|---|---|---|
| 自动 | zh-CN | `zh-CN` 中文（中国） |
| 自动 | zh-TW | `zh-Hant` 中文（繁體） |
| 自动 | en-US | `en-US` English(US) |
| 简体中文 | — | `zh-CN` |
| 繁体中文 | — | `zh-Hant` |
| 英语 | — | `en-US` |
| 日语 | — | `ja` 日本語 |
| 韩语 | — | `ko` 한국어 |
| 不抓取字幕 | — | **零请求**（见下） |

### 2.3 「不抓取」是真的一发不发

判据落在 `BiliSourceProvider.fetchLyric` 的第一行：`BiliPrefs.subtitleLang().fetches`
为假时**在取 `cid` 之前**返回 `null`。

⚠️ 这一条**不能用本探针证明**（探针只重放了偏好表与匹配逻辑，
没有重放「提前返回」）。它由 JVM 单测钉住：

```
BiliSubtitleLyricFetchTest > 选中不抓取字幕时一个请求都不发 —— 连 cid 都不补问
    assertEquals("关闭抓取后必须零请求：$requested", 0, count.get())
```

返回值是 `null`（= 没有数据源）而不是空串（= 这首歌确实没有歌词）——
关掉之后视频轨**确实没有任何歌词数据源**；用空串会让上层记成「稳定空态」，
将来用户打开开关也不会重取。

### 2.4 「所有音源都能选字幕语言」这句承诺的边界（如实写）

ncm / qm 的服务端**每条歌词只有一轨译文**（`tlyric` / QQ 的 `trans`），
**没有语言元数据** —— 那两个音源的「语言选择」在事实上就是「要不要这一轨」。
所以：

- 它们的开关（`lyrics_translation` / `lyrics_romanization`）**与 B 站字幕语言下拉放在同一个分组**（「歌词」页），
  并由新增的 `lyricsTranslationHint` 写明「ncm / qm：显示服务端返回的译文轨」；
- 不给它们造一个「只有一个选项」的空设置 —— 那才是骗用户。

---

## 三、问题 3：开放的两个参数

| 设置项 | 取值 | 默认 | 语义 |
|---|---|---|---|
| `bilibili_quality_cap` | `auto` / `hires` / `exhigh` / `higher` | `auto` | 音质上限。`auto` = 跟随全局档位（**对既有行为零影响**）；`hires` = **抬升**到无损档（移动网络下全局默认 192K，不抬升就永远拿不到无损）；其余两档是**夹取** |
| `bilibili_prefer_flac` | 布尔 | `true` | FLAC 与 AAC 都有时选哪个。默认**开**：一个默认关的「要不要 Hi-Res」开关会让这次修复在默认配置下零可见变化 |

两条都受 `bilibili_enabled` **软门控**（源关着时可见但置灰，见 `GatingReason.BILI_SOURCE_DISABLED`）。

`applyCap` 的机械性质（`BiliHiResTest` 逐条断言）：

- **幂等**：取链重试与预载会重入；
- **`auto` 是恒等**：默认值不改变任何既有行为；
- **方向互斥**：只有 `hires` 抬升、且**永不降低**；其余三档只夹取、**永不升高**；
- **未知档位服从显式选择**（`sky` + `exhigh` → `exhigh`），而不是静默绕过上限。

### 3.1 顺带发现并修掉的第四条：服务被系统单独拉起时镜像没播种

`BiliPrefs` / `BiliAuthStore` 是「拿不到 `Context` 的调用点读的进程内镜像」，
而播种点原先**只有 `MainActivity.onCreate`**。`PlaybackService` 是除 Activity 之外
**唯一的进程入口**（Android Auto / 车机绑定、媒体按钮、通知栏恢复）：

| 镜像 | 未播种时的表现 |
|---|---|
| `BiliPrefs.isEnabled` | 恒 false ⇒ B 站曲目直接返回 null、整首跳过 |
| `BiliPrefs.qualityCap` | 恒 auto ⇒ 用户设的「仅 192K 省流」失效 |
| `BiliPrefs.preferFlac` | 恒 true ⇒ 省流用户拿到 2~3 Mbps 的 FLAC |
| `BiliAuthStore` | 恒匿名 ⇒ **大会员也拿不到 Hi-Res** |

最后一行与本版的核心修复直接相关：车机路径恒匿名 ⇒ 恒拿不到 `dash.flac.audio`
⇒ **修复在那个路径上完全不生效**，而表现恰好就是用户报的那句
「无论如何都播放的是普通版本」。修法是在 `PlaybackService.onCreate` 里补两行
（与既有的 `VisualizerSetting.read(this)` 同一模式），
守卫是 `BiliMirrorSeedingTest` 的源码形状扫描（含「必须在 `onCreate` 体内」那一条）。

---

## 四、未验证项（不要把它们读成已解决）

| # | 未验证的事 | 为什么 | 怎么补 |
|---|---|---|---|
| 1 | **真机播放 Hi-Res FLAC 出声** | 测试机（SM-G920F / Lineage，`adb` 设备号 `0715f763f54c023a`）在施工期间从 USB 掉线，`adb devices` 已看不到它；Waydroid 实例同时 `offline` | 设备插回后：`./gradlew installDebug` → 选一首带 Hi-Res 的视频 → 看 logcat 的 `BiliSourceProvider: video stream picked: … kind=FLAC` 与播放器角标是否显示「高解析」 |
| 2 | **真机上的入口码率标签** | 同上 | 播放该曲时看全屏播放器档位旁的 `kbps`（2247494 bps ⇒ 显示 `2247kbps`） |
| 3 | **多语言字幕在真机上的端到端** | 同上 | 设置页把字幕语言切成「英语」→ 播放 `BV1GJ411x7h7` → 歌词应变成英文轨 |
| 4 | **`dash.flac.audio` 在别的账号上是否稳定** | 只测了本机这一个年度大会员账号 | 抽样更多账号 / 更多视频；代码已按「读 `qualities`/字段存在性而不是硬编码」写，拿不到就退 AAC 并如实标降级 |
| 5 | 「30 秒试听」（`type = -1`）与付费墙 | 本次 2 首样本都是完整曲 | 沿用 v3.1.0 的防御分支（`BiliQn` 降级阶梯） |
| 6 | **Android Auto / 车机上的 B 站播放** | 同上（无设备，也没有车机） | `BiliMirrorSeedingTest` 只守住了「那两行写在 `onCreate` 体内」这个**形状**，证明不了它真的被执行到 —— 需要真机 + 车机模拟器（`desktop-head-unit`）|

---

## 五、复现方式

```bash
# 1) 线上探针（会落到 probe-output.txt）
cd docs/verification/v3.4.8
env -u https_proxy -u http_proxy -u all_proxy \
  python3 probe-v348.py --cookie-file <SESSDATA 文本文件> | tee probe-output.txt

#   不带 --cookie-file 时等价于匿名 —— 你会看到 flac 恒为 null，
#   那正好是 §1.2 那条对照实验。

# 2) 代码侧（离线、不需要网络与设备）
cd ../../..
./gradlew testDebugUnitTest --tests '*Bili*'
```

⚠️ 探针里的 `selectStream` / `levelOf` / `applyCap` / `pickTrack` 是 Kotlin 实现的
**Python 镜像**（同一份规则重写一遍），不是直接跑 Kotlin。它的作用是证明
「服务端事实」；「代码按这些事实决策」由 JVM 单测在同一样本上证明。
两侧的样本是同一份（字段级逐字）—— 这是这条证据链成立的前提，
**改任何一侧的样本都必须同步另一侧**。
