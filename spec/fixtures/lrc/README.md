# fixtures/lrc —— 歌词解析与双语合并

LRC 时间戳解析与 `tlyric` 合并的固定答案（见 `spec/README.md`）。

## schema

`lrc.json` 是用例数组，每条带 `kind`：

| kind | input | expect |
|---|---|---|
| `parse` | `{ "lrc": "<LRC 文本>" }` | `{ "lines": [ { "timeMs": <long>, "text": "<文本>" } ] }` |
| `merge` | `{ "lrc": "<原词>", "tlyric": "<翻译>" }` | `{ "lines": [ { "timeMs": <long>, "text": "<原句>", "translation": "<译文或空串>" } ] }` |

## 规则（与 Android `LrcParser.kt` / `LyricsView.kt` 一致）

- 时间戳格式 `[MM:SS.mm]` 或 `[MM:SS.mmm]`；**两位**毫秒按十毫秒解释（`03` → 30ms）。
- 每行只取**第一个**时间戳；同一行后面若还有 `[..]`，会原样进入歌词文本（Android
  `Regex.find` 只匹配一次的行为，夹具把它钉住，避免各端实现不一致）。
- 文本取匹配后的剩余部分并 `trim()`；空文本的行丢弃。
- 无时间戳的行忽略；输出按 `timeMs` 升序（稳定排序）。
- 合并：译文按 `timeMs` **精确**对齐原句（网易 tlyric 与原 lrc 时间戳一致）；
  原句没有对应时间戳的译文时，`translation` 为空串；译文里多出的孤立行忽略。

## Arris 适配说明（本 Fork）

1. **一行多时间戳：本 Fork 与上游相反 —— 每个时间戳都产出一条。**
   v3.3.0 实测（B 站音频区 au39《成都》）22 行里 13 行是多戳、共 44 个时间点，
   旧实现（`Regex.find` 只匹配一次）只产出 22 条、**丢掉 21 个时间点**，
   而且每条多戳行的文本里还留着 `[02:44.32]` 字面量 —— 屏上出现方括号、副歌整段消失。
   现在 `LrcParser.parse` 用 `LEADING_TIME_STAMPS` 展开行首全部时间戳、文本不含时间戳。
   因此夹具的 `lrc.parse.first-timestamp-only` 与本实现**冲突**（本实现给两条：
   `(1000, "word")` 与 `(2000, "word")`），已在 `LrcSpecFixtureTest` 用 `@Ignore` 显式标注。
2. **合并**：歌词面板按 `timeMs` **精确**配对（`LyricsView.kt`：
   `translatedLyrics.associateBy { it.timeMs }` → `tMap[line.timeMs]?.text ?: ""`），
   与夹具的 `exact-time-alignment` 一致。注意系统媒体通知那条路径
   （`MediaLyricTrack.from(..., toleranceMs = 1500)`）是「最近邻 + 容差」语义，**不是**面板语义；
   单测用 `toleranceMs = 0` 走同一精确判定，另加一条源码守卫钉住面板那句配对表达式。
3. 其余规则一致：两位毫秒按十毫秒解释（`03` → 30ms）、空文本行丢弃、无时间戳行忽略、
   输出按 `timeMs` 稳定升序（多戳行展开后天然乱序，排序是必须的）。
