# fixtures/quality —— 音质阶梯

7 级音质的降级顺序与 FLAC 门控规则，各端共用（见 `spec/README.md`）。

## schema

`quality.json` 是用例数组。公共字段 `id` / `description` / `source` / `input` / `expect`。
每条带 `kind`：

| kind | input | expect |
|---|---|---|
| `ladder` | `{ "level": "<请求档位>", "supportsFlac": true\|false }` | `{ "levels": ["<按顺序尝试的档位>"] }` |
| `retry` | `{ "level": "<当前实际档位>" }` | `{ "nextLevel": "<降一档>" \| null }` |
| `preference` | `{ "index": <偏好索引> }` | `{ "level": "<档位>" }` |

## 规则（与 Android `SongUrlFetcher` / `PlayerViewModel` 一致）

- 档位全集（偏好索引顺序）：`standard`(0) `higher`(1) `exhigh`(2) `lossless`(3)
  `hires`(4) `jyeffect`(5) `dolby`(6)。默认 Wi-Fi = 3，移动 = 1。
- 降级序列（请求档位 → 依次尝试）：
  - `dolby` → dolby, hires, lossless, exhigh, higher, standard
  - `jyeffect` → jyeffect, lossless, exhigh, higher, standard
  - `hires` → hires, lossless, exhigh, higher, standard
  - `lossless` → lossless, exhigh, higher, standard
  - `exhigh` → exhigh, higher, standard
  - `higher` → higher, standard
  - `standard` → standard
  - 未知档位（如服务端返回 `sky`）→ 该档位, lossless, exhigh, higher, standard
- **FLAC 门控**：设备解不了 FLAC 时，从序列里剔除 `lossless` / `hires` / `jyeffect`
  三档。`dolby` 用 mp4（EAC3）容器输出，不在剔除之列。
- **出错降一档**：播放解码失败时沿固定顺序 `dolby, jyeffect, hires, lossless, exhigh,
  higher, standard` 降一级；不在阶梯内的档位从 `lossless` 起降；已是 `standard` 返回 null
  （由上层跳歌）。注意这条顺序与上面的降级序列**不同**（这里 `jyeffect` 排在 `hires` 前）。

## Arris 适配说明（本 Fork）

本 Fork 的档位表是 **8 档**（`player/QualityLadder.LEVELS`），比上游多一个 `jymaster`（超清母带），
插在 `jyeffect` 与 `dolby` 之间：

| 索引 | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|---|
| 档位 | standard | higher | exhigh | lossless | hires | jyeffect | **jymaster** | dolby |

`standard…dolby` 这 7 档的**相对顺序不变**，只是 dolby 的位置从索引 6 挪到 7，
因此本夹具里所有涉及前 7 档与阶梯外标签 `sky` 的用例在两边都成立。差异只有三处：

1. **取链降级序列多一条**：`jymaster → jymaster, hires, lossless, exhigh, higher, standard`
   （`SongUrlFetcher.fallbackByLevel`）。
2. **FLAC 门控多一档**：`SongUrlFetcher.FLAC_TIERS` = `lossless, hires, jyeffect, jymaster`。
   夹具里没有 jymaster 用例，所以 `dolby-no-flac` 等用例的期望值不变。
3. **出错降档的固定顺序含 jymaster**：本 Fork 的 `qualityRetryLadder` 是
   `jymaster, dolby, jyeffect, hires, lossless, exhigh, higher, standard`（jymaster 在最前），
   `dolby` 的下一档仍是 `jyeffect`，所以 `retry.from-*` 六个用例一致。
   但**阶梯外档位**（如 `sky`）走 `QualityRetryGuard.decide` 时 `floorIdx = -1`，
   从阶梯最高档起降 ⇒ 得到 `jymaster`，而上游夹具写的是 `lossless`。
   这条差异已在 `QualitySpecFixtureTest` 用 `@Ignore` 显式标注（上游期望值不被改写）。

单测落点：`app/src/test/java/com/takahashirinta/ncrust/player/QualitySpecFixtureTest.kt`。
取链序列表由反射读生产字段（`fallbackByLevel`），FLAC 门控用生产的 `isFlacTier`，
重试阶梯从 `PlayerViewModel.kt` 的声明处解析 —— 三处都不是在测试里重抄一份表。
