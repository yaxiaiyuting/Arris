# 生日快乐 · A Birthday in the Night Green

## Arris 接入版（2026-10-23）

已合入本地 birthday-motion 当晚修订的 cues / motion / lighting / main / base。
新版为固定机位：保留按景深设置的静态缩放、角色表演、烛光、黑场、七发烟花和祝福，
去掉动态推拉、环境细线的逐帧位移、逐帧 brightness 滤镜与运行中自动切档。
下文的旧版多层动态视差说明属于制作历史，不代表当前运行效果。

原成片为 1600×900、60fps、48 秒。应用使用同套离线 HTML 源码，继续在 26.2 秒
等待原生吹气或点按，不把视频直接自动播完。保留 Arris 的竖屏前景重排、等比烟花、
本地化文案、祝福下方结束按钮、首帧淡入、合成层与画布分辨率限制、resize 只重绘不重放。
没有新增网络请求或麦克风访问入口。横屏保留新版固定机位构图。

可复跑检查：`tools/celebration/verify-layout.cjs`（八种尺寸、结束按钮）与
`tools/celebration/verify-motion.cjs`（九个时刻、静态相机、SVG 滤镜、吹气门控）。

一支约 48 秒的生日动画短片，纯 HTML / SVG / CSS / JavaScript + GSAP 3.13，离线可跑。
叙事：夜森林温室苏醒 → Tux 端来蛋糕 → 放下 → 逐支点灯 → 许愿 → 吹灭 → 黑场 → 七发升空烟花 → 祝福浮现 → 余韵。

## 成片

- 视频：[out/reel/birthday.mp4](out/reel/birthday.mp4)（48s / 1600×900 / 30fps）
- 接触印样：[out/reel/contact.png](out/reel/contact.png)
- 定版海报：[19.9s 点灯](out/reel/poster-19_9.png)｜[34.9s 烟花](out/reel/poster-34_9.png)｜[44.5s 祝福](out/reel/poster-44_5.png)

## 打开方式

| 方式 | 命令 |
|---|---|
| 直接看 | 双击 `index.html`（file:// 即可，无构建、无服务器） |
| 定位到某一秒 | `index.html?t=27.5&clean=1` |
| 局部复看 | 空格暂停/继续，`←/→` ±1 秒，底部时间轴可拖 |
| 静帧采集 | `node tools/shoot.cjs --times 0,12,33.8 --out out/frames` |
| 自动检片 | `node tools/qa.cjs` |
| 导出视频 | `node tools/reel.cjs` → `out/reel/birthday.mp4` |

`prefers-reduced-motion` 下默认呈现静态终帧；点播放仍可完整观看。
直接双击打开会自动播放；`?clean=1` 隐藏控制条，`?still=1&t=34.9` 定格某一秒。

## 这一版做对了什么

- **多层视差**：9 个 `data-depth` 图层，相机是纯函数插值（`CUES.cam` 9 段"起幅—过程—落幅—停滞"），
  每层再叠两组不可公约频率的漂移，所以没有整块画面同步平移。
- **自然缓动**：自建 `arrive / settle / weight / drift / backOut` 曲线；没有一段 `linear` 长位移。
- **错峰与重叠动作**：走位 13 步、点头灯三拍 1.30/2.00s、火焰三支相位差、帽/耳机 60–120ms 延迟。
- **光**：烛光主光（L\*45+ 环带 ≈400px）、月绿辅光、烟花事件光（rim 0.49–0.63，全屏增量 ≤3.1%）。
- **时间可寻址**：任意一帧可复现（`BDAY.seek(t)` + VFX 确定性重放），所以逐帧审片、自动检片、录屏都建立在同一套状态上。

## 第二轮美术总攻（终审）

对 v1 做了六线精修（场景纵深 / 角色材质 / 灯光形状 / 合成调色 / 版式 / 动效），
再按 `docs/ART-BAR.md` 六条硬线逐帧判。**盲评 v1 3.31 → v2 3.63**，三处退步帧已逐条修掉。

- 终审判词：[docs/ART-VERDICT.md](docs/ART-VERDICT.md)
- 判据：[docs/ART-BAR.md](docs/ART-BAR.md)｜独立审片：[docs/ART-REVIEW-ROUND2.md](docs/ART-REVIEW-ROUND2.md)｜盲评：[docs/ART-REVIEW-BLIND.md](docs/ART-REVIEW-BLIND.md)
- 前后帧：`out/round2/before/` 与 `out/round2/after/`

## 2026-10-22 修的两个问题

**① 头/耳机脱开、头"突然不见"**
`#tux-head` 与 `#tux-hat` / `#tux-headphones` 原本是兄弟节点，motion 分别驱动它们：
头一扭头（吹气时 5.4°）或一鼓腮（`scaleX 1.045`），帽子和耳罩不跟，头部就会从耳罩里"滑出去"、
和身体糊成一团，看起来像头消失了。
修法：**boot 时把帽子与耳机挂进 `#tux-head`**（此时头还没有 transform，坐标不变），
帽子/耳罩从此跟着头走，各自的独立抖动变成"局部延迟"（正好是要的二次动作）；
同时把鼓腮收到 1.5%、甩头收到 3.2°、喙的鼓气收到 1.42×。

**② 视频帧数低**
之前的 mp4 是"实时录屏"导出的——录的是机器实际渲染的那几帧，再补成 30fps，所以一顿一顿。
修法：新增 `node tools/render.cjs --exact`，**逐帧 seek + 截图**（1440 帧 / 48.000s），
每一帧都是唯一帧（校验：1440 帧里 0 对连续重复），再交给 ffmpeg 编码；颗粒改在编码阶段用 `noise` 滤镜加。
页面侧也顺手做了三件事：整屏颗粒层默认关闭（`?grain=1` 才开）、特效画布降到 0.6× 背板、
视差位移量化到 0.25px 才写 DOM。

## 本版已知限制

见 [docs/FRAME-REVIEW.md](docs/FRAME-REVIEW.md) §4：帧率数字不作数（无头软件光栅 + 多 agent 抢 CPU）、
火柴只有拟态、全局呼吸未叠、无音频、字幕按 §1 例外贴角。

## 技术结构

```
index.html            舞台骨架（SVG 坐标系 1600×900，9 层视差）
css/base.css          排版、调色、颗粒、控制条
js/cues.js            节拍表（唯一时间源：幕、点火、吹灭、烟花、机位、字幕）
js/assets.env.js      场景七层：sky / aurora / far / mid / ground / near / fg
js/assets.tux.js      角色：Tux（15 个可独立驱动的部件）
js/assets.cake.js     道具：蛋糕、三支蜡烛、火焰锚点、暖光池
js/lighting.js        光：烛光主光 / 月绿辅光 / 烟花事件光 / 黑场 / 呼吸光晕
js/vfx.js             火、烟、气流、七发烟花、浮尘（确定性时间寻址的粒子系统）
js/motion.js          GSAP 主时间线：入场、放置、点灯、许愿、吹灭、抬头、祝福
js/main.js            集成层：资产注入、相机 rig、逐层视差、时间寻址、UI
tools/                逐帧采集、资产预览、自动检片、动图导出
docs/                 契约、分镜、美术圣经、角色/场景/灯光/动效/特效/检片报告
```

### 多层视差怎么做的

`#camera` 里每层带 `data-depth`（0.02 → 2.2）。`main.js` 的 `applyCamera(t)` 是**纯函数**：
机位由 `CUES.cam` 的关键帧插值，每层按 `-机位 × depth` 反向位移、按 `1 + z × depth × 0.34` 微缩放，
再叠加两组**不可公约频率**的正弦漂移（每层振幅/相位不同）。因此没有"整块画面同步平移"，
远景几乎不动、近景走得更快，镜头推近时层与层之间会持续产生视差。

### 时间可寻址

`BDAY.seek(t)` 会把主时间线从 0 重放到 t，并对 VFX 做同样的确定性重放（固定 1/60 步长 + 固定随机种子）。
所以任意一帧都可以精确复现——静帧采集、自动检片、视频导出都建立在这一点上。

## 制作团队（8 人，对应现实美术小组建制）

| # | 角色 | 现实岗位对应 | 职责 | 产物 |
|---|---|---|---|---|
| 1 | 美术指导 | Art Director | 定视觉基调、光的逻辑、构图网格；终审每一张海报帧 | `docs/ART-BIBLE.md`、`docs/CRITIQUE.md` |
| 2 | 分镜 / 剪辑 | Storyboard & Editorial | 镜头表、节奏呼吸、转场；维护节拍表 | `docs/TIMELINE.md`、`js/cues.js` |
| 3 | 角色原画 | Character Artist | Tux 与蛋糕的矢量造型、部件拆分、可动性 | `js/assets.tux.js`、`js/assets.cake.js` |
| 4 | 场景美术 | Environment Artist | 七层视差场景、温室空间、地面与光影承载面 | `js/assets.env.js` |
| 5 | 灯光合成 | Lighting / Comp TD | 主光辅光事件光、黑场、最终影调 | `js/lighting.js` |
| 6 | 动效设计 | Motion Designer | 主动作表演、缓动与错峰、重叠动作 | `js/motion.js` |
| 7 | 技术美术 | VFX TD | 火焰、轻烟、气流、烟花、浮尘、性能预算 | `js/vfx.js` |
| 8 | 动检审片 | Motion QA | 逐帧采集、重叠/越界/抖动/空帧检测、动图导出 | `tools/qa.cjs`、`tools/reel.cjs`、`docs/QA-REPORT.md` |

协作契约见 `docs/CONTRACT.md`：坐标系、图层深度、强制 id、模块接口、动效准则、验收方式。
