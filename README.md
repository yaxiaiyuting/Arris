<div align="center">

# Arris

## ncm + qm + B站，三块曲库装进同一块直角玻璃

**三音源各自独立登录 · Kanesumi Design · GPU 零重组动画 · 逐字歌词（yrc / QRC / TTML）· B站大会员 Hi-Res · 无缝播放 · 8 级音质 · 大屏模式 · 车机适配 · 8 语言 · 统一动效强度**

纯 Kotlin / Jetpack Compose · Media3 播放引擎 · 三音源直连（eapi 加密 / QQ 客户端协议 / B站 Wbi）· **无中间服务器、无账号代管**

**Arris 是 [GuitaristRin/Ncrust](https://github.com/GuitaristRin/Ncrust) 的 GPLv3 fork（v3.4.8 起改名），由 [yaxiaiyuting](https://github.com/yaxiaiyuting) 维护。上游原始代码以 MIT 许可发布，本 fork 的修改与新增部分以 GPLv3 分发。**

[![Version](https://img.shields.io/badge/version-3.4.8--gpl-brightgreen?style=flat-square)](https://github.com/yaxiaiyuting/Arris/releases)
[![APK](https://img.shields.io/badge/APK-9.83%20MB-blue?style=flat-square)](https://github.com/yaxiaiyuting/Arris/releases)
[![API](https://img.shields.io/badge/API-24%2B-green?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose-BOM%202024.12-blue?style=flat-square&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/license-GPLv3-blue?style=flat-square)](LICENSE)
[![Original](https://img.shields.io/badge/original%20code-MIT-yellow?style=flat-square)](LICENSE-MIT)

[**下载安装**](https://github.com/yaxiaiyuting/Arris/releases) · [**完整更新日志**](CHANGELOG.md) · [**问题反馈**](https://github.com/yaxiaiyuting/Arris/issues) · [**Kanesumi 设计库**](https://github.com/GuitaristRin/Kanesumi-sec-a)

</div>

> **⚠️ 重要声明**
>
> 本项目仅供学习交流使用，请勿用于任何违法违规用途。
>
> 使用本工具产生的一切后果由用户自行承担。
>
> 请尊重版权，支持正版音乐。

---

## 为什么是 Arris

> 官方客户端很全，但它不是为「直角、克制、信息优先」设计的；
> 而同时用几家曲库的人，长期被迫在几个 App 之间来回切。
> Arris 是一次从零开始的重写：**没有圆角，没有弹簧回弹，没有多余装饰**，
> 并且**把几个平台的歌放进同一个队列**。

**名字取自建筑术语 arris —— 两个面相交的那条锐棱。** 它一头连着上游 Ncrust 的「硬壳」意象，
一头接着这个 App 的设计原则（直角切割、无曲线过渡）。图标就是它的字面写法：
一个正圆唱片、一个内嵌的直角三角、一个圆心孔。

| | |
|---|---|
| 🟩 **直角美学** | 全应用遵循 Kanesumi Design：直角切割、纯色细线、封面贴屏边、浮层返回箭头 |
| ⚡ **GPU 零重组** | 播放器动画由单个 `progress` 经 `graphicsLayer` 驱动，展开 / 收起时**不重组** |
| 🎵 **双音源并存** | ncm + qm。**各自独立登录、独立会员状态、互不影响**；队列里的歌按所属音源自动路由取链与歌词，搜索结果聚合两个平台并标注来源 |
| 🔐 **登录无忧** | ncm：浏览器 / 二维码 / 手机扫码授权平板；qm：**自绘二维码**（手机 QQ 扫）或网页登录。全程不采集密码、cookie 只存本机、不上传任何服务器 |
| 🎧 **真·无损** | 两套音质档位映射到统一的 8 级阶梯，逐级降级兜底；弱机自动跳过无解档位（FLAC 解码门控） |
| 🎤 **逐字歌词** | ncm `yrc` + AMLL TTML + **qm QRC** 三源，TTML → YRC → LRC 三级回退，逐字渐变三档质量。QRC 那套是 QQ 自研的非标准 3DES，本仓库自实现并与独立实现逐字节比对验证 |
| 🔀 **5 种播放模式** | 顺序循环 / 单曲循环 / 乱序 / 顺序线性 / 相似无限（私人 FM 电台） |
| 🚗 **车机就绪** | Android Auto / Automotive 媒体源与浏览树，车机系统栏 inset 专项适配 |
| 🪶 **9.4 MB** | R8 全量混淆 + 资源 shrink；体积主要来自 FFmpeg 解码扩展的 4 个 ABI（换来 API 24–26 的真无损）。冷启动预热，进程被杀也能恢复队列 |
| 🌐 **8 语言** | 运行时切换，不依赖系统 locale |

> **合规边界**：本项目**不破解 DRM、不做「解灰」、不绕过平台鉴权**。
> 能否播放某首歌完全取决于你自己账号的权限 —— 服务端说不行就是不行，播放器跳歌。
> 已知的一处权限异常（VIP 专享的 AI 伴奏轨匿名可取）被**明确排除在降级链之外**，并有单测钉住。

---

## 🎵 两个音源，一个队列

| | ncm | qm |
|---|---|---|
| 登录 | 浏览器 / 二维码 / 手机扫码授权平板 | **自绘二维码**（手机 QQ 扫）或网页登录 |
| cookie 存储 | `ncrust_prefs` | `ncrust_qq_prefs`（**两份完全独立**，登出一家不影响另一家） |
| 取链 | eapi 加密直连，8 档降级阶梯 | 客户端协议（**免签**），一次请求批量问所有档位后按优先级挑 |
| 逐字歌词 | `yrc` + AMLL TTML | `QRC`（自研非标准 3DES 解密，已与独立实现逐字节比对） |
| 免费曲目 | 需登录才能取链 | **不需要登录**即可完整播放（实测下载真实音频验证） |
| 搜索 | 主源 | 聚合追加，列表顶部标注「ncm N 首 · qm M 首」 |
| 会员 | 独立显示 | 独立显示（**只用于展示，不参与播放决策**） |

**队列怎么混**：队列里每首歌都带 `source` 字段，播放时按它路由到对应音源取链与歌词。
两个平台的数字 id 用**高位标志位隔离**（QQ 的 id 抬到 `1 shl 62` 以上），
所以离线缓存、歌词缓存、续播进度、队列判重这些既有结构一个都没改，也没有任何数据迁移 ——
「撞号串歌」在结构上不可能发生。

**未做的三件事**（写清楚，免得误解）：QQ 曲目**不进 ncm 歌单/收藏**（需要「本地歌单」这个尚不存在的概念）；
QQ 曲目的歌词**不落盘缓存**（断网时没有歌词）；QQ 曲目**不参与播放上报**（QQ 侧没有等价机制，也不该伪造）。

---

## ✨ 功能一览

### 🏠 发现

- **每日推荐**、**推荐歌单**、**新歌速递**，懒加载分页，缓存命中秒开
- **私人 FM**：首页电台入口，持续拉取私人 FM 流无限续播
- **剪贴板识别**：复制 `music.163.com` / `163cn.tv` 分享链接回到 App 自动打开（单曲只载入不自动播放）
- **音乐人推荐卡片**（v1.4.0）：首页推荐流按「收藏艺人 ∩ 风格锚点」**纯本地判定**插入一张艺人卡，点进艺人详情页；配置默认全空 —— 不配置就不显示、也不发任何请求

### 🔍 搜索

- 单曲 / 专辑 / 艺人三标签，500 ms 防抖，三态 Crossfade 过渡
- 本地搜索历史（每类 10 条、14 天过期）
- 专辑 / 艺人长按可批量「播放全部 / 下一首 / 添加到队尾」

### 📚 曲库

- **收藏单曲**：云端同步「我喜欢的音乐」，列表懒加载分页
- **收藏专辑**：云端订阅专辑网格
- **收藏歌单**：当前账号的用户歌单
- 单曲可收藏 / 插播 / 加队列，本地乐观更新后异步同步云端
- **歌单管理**（v1.3.0）：创建 / 改名 / 改简介 / 改隐私 / 删除 / 增删曲；入口在收藏页「＋」、播放器队列区 ⊕、任意歌曲长按菜单与歌单详情页顶部 ⋮；写操作串行 + 最小间隔 2 s，不自动重试

### 🎵 播放

- **8 级音质**：压缩 → 较好 → 更好 → 无损 → 高解析 → 高清环绕声 → 超清母带 → 杜比全景声
- **分网络偏好**：Wi-Fi 与移动数据独立设置，默认无损 / 较好
- **无缝播放（gapless）**：进入最后 60 秒预加载下一首，切歌即播
- **自动降档**：解码失败或音频输出故障时同曲降一档重试，避免「进度在走但没声音」
- **三区队列**：已播 / 当前 / 待播，触摸即拖重排、边缘自动滚动、清空队列
- **播放上报**：复刻官方 `webLog`，本地收听计入推荐与听歌指数
- **底部控制栏可收起**（v1.4.0）：窄屏全屏播放器下向上拖控制栏 → 控制栏滑出、歌词 / 队列面板长高到全屏，右下角悬浮播放键向下拖恢复；`Animatable` + `graphicsLayer` 平移，动画帧零重组。v1.4.2 起常驻把手（上拖收起 / 下拖恢复 / 点按切换），v1.5.0 起触摸区与无障碍语义达 48×24 dp
- **音质就地切换**（v1.8.0）：竖屏控制栏 / 宽屏控制条 / 横屏大屏共用同一个音质 chip，点开就是 8 档二级菜单，不再跳设置页；档位真变了才重新取链续播
- **离线缓存**（v1.6.0 Phase 1 → **v2.0.0 Phase 2**）：**已经播放过**的音频流按 media3 `SimpleCache` / `CacheDataSource` 语义自动落盘 `filesDir/offline/audio`（LRU，上限 64 MB–8 GB 可调，默认 512 MiB），断网时可回放；未播放过的歌不产生任何本地文件。**Phase 2 起可在设置 →「离线缓存」里看到缓存了哪些歌（标题 / 歌手 / 档位 / 占用）、单曲删除、调整上限并看到分项占用**。⚠️ 仍然**没有显式「下载」入口** —— 离线可播范围严格等于「这台设备上真正播过的歌」（合规定位见下方「离线缓存的范围」）

### 🎨 界面

- **全屏播放器**：三层图层架构，拖拽 25% 阈值吸附，展开 / 收起只做位移缩放
- **歌词**：LRC 行级 + yrc 逐字 + `tlyric` 行级翻译双语同屏；黄金分割定位、手动滚动 5 秒后恢复、点击行跳转；A-/A+ 字号五档（0.7x–1.5x）
- **逐字渐变**：`SweepTrack` 连续光标逐帧推进 + 窄离屏软边；渐变质量三档（自动 / 高级 / 兼容）+ 低内存自动降级；逐字动画三模式（渐变扫过 / 硬切 / 关闭），关掉即退回与 v1.4.1 一致的整行渲染
- **AMLL TTML 歌词源**（v1.9.0）：在 yrc 之外接入 [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db) 逐字数据，组成 **TTML → YRC → LRC 三级回退链**（总开关默认开、默认 TTML 优先，可切 YRC 优先）；取不到或解析失败一律静默回退，渲染层零改动 —— 实测覆盖率见下方「歌词数据来源」
- **歌词音译显示**（v1.9.3）：粤语拼音 / 日文罗马音 / 韩文罗马音等音译轨显示在**原文下方**（有译文时排在译文下面），设置项「显示音译」**默认关**；没有音译数据的歌不产生空行。数据来自 v1.9.2 就绪的音译轨（TTML `x-roman` + ncm `romalrc`），渲染层按时间戳逐行配对
- **播放时禁止熄屏**（v2.0.0）：**只**在「正在播放 + 停在播放器界面」时挂 `FLAG_KEEP_SCREEN_ON`（零权限、零依赖，不用 WakeLock）；暂停、切到其他页面、退到后台都立刻恢复系统熄屏策略。设置 → 播放可关
- **动态字号**（v2.0.0，实验性、**默认关**）：按每句**估算折行数**给这一句一个离散字号倍率（短句 ×1.15 / 三行以上 ×0.85），目标是让每句的视觉体量更均衡；倍率乘在 A-/A+ 的基准字号之上并被 48sp 上限夹住，译文 / 音译同乘。`SweepTrack` 渐变算法一个字节未改
- **大屏模式**（v1.7.0）：横屏桌面播放器 —— 左栏大封面 + 歌名 / 作者 + 就地音质选择器，右栏复用同一套逐字歌词面板；⤢ 按钮 / 系统返回键 / 转回竖屏三条退出路径
- **音频可视化**（v1.8.0 / v1.8.1）：大屏模式封面下方 28 根幅度柱，走 media3 `TeeAudioProcessor` 在应用自己的音频链上取数、**零新增权限**；v1.8.1 把采样速率提到 30 次/秒、重绘上限提到 60fps，并给柱高加时间常数平滑（起音 22 ms / 回落 130 ms）
- **波形效果分级**（v2.8.0）：大屏可视化分**简洁 / 精致 / 炫技**三档 —— 简洁 = 圆角柱 + 峰值保持 + 缓动衰减（低内存 / 3 GB 级 / Android 7.x 机型默认），精致 = 简洁 + 渐变流动 + 柱顶光点 + 呼吸（其余设备默认），炫技 = 精致 + 冲击波 + 粒子 + 3D 透视（另有「炫技效果」总开关与细分开关，默认关）。同一页如实说明：**亮度表示的是「时间新旧」，不是频谱**（当时没有频域数据，不画任何会被读成频谱的东西）。⚠️ v2.8.0 的「帧时间超标自动下调一档」已在 **v3.0.0 整个删除**（理由见下）**设置界面同时改成一级 7 张分组卡片 + 二级详情页**（账号与登录 / 通用 / 外观与动效 / 播放与音质 / 歌词 / 存储与缓存 / 关于），每个设置项的 key、读写入口与默认值都与迁移前逐条一致（单测做**双向 key 等值**断言）
- **统一动效强度**（v2.9.0）：一个档位同时决定波形与界面动效 —— 简洁 / 精致（默认）/ 炫技。A 档（默认开，另有独立总开关）：封面背景模糊、背景随音量呼吸、封面浮起阴影与切歌淡入；B 档：全屏背景级波形、当前歌词行律动、控制条节拍脉冲、拖拽视差；C 档：封面 3D 旋转。⚠️ 背景粒子与冲击波/光晕在 **v3.0.0** 改成由**真实音频特征**驱动、并**下放到精致档**（各自还有独立开关），见下一条。背景模糊走**封面降采样到 32px + 三遍盒式模糊 + 按封面 URL 缓存**（每首歌只算一次，且不对原图做高斯模糊），模糊失败自动回退纯色背景。横屏的动效也铺满全屏，不再局限左侧。
- **音频特征驱动的动效**（v3.0.0）：动效跟着**真实的音乐内容**走，不是按固定节奏播放的装饰。从播放链路上旁路的一份 PCM 里**一次遍历**算出全带响度、低 / 中 / 高频带能量、频谱质心近似与**瞬态**（鼓点等一击）—— 全程**没有 FFT**、没有分帧、没有额外缓冲，音频线程上零分配（真机实测 162 倍实时余量）。绑定关系：**冲击波 / 光晕 ↔ 瞬态**（力度越大扩散越远，「炫技」档一次扩两圈，**出生点随机、不再固定在屏幕正中**）；**粒子 ↔ 中高频能量**（人声 / 弦乐 / 镲片，能量越强生成越快，安静段落不生成）；**波形 ↔ 多频段**（每一根柱子按它那一刻低 / 中 / 高频谁占主导着色，「炫技」档另加三条频带能量条；**横轴仍然是时间，不是频谱**）；**背景呼吸 ↔ 整体响度**。**静音就是静止**：所有随时间变化的量在静音下会收敛到精确的 0，之后不再重绘。五个动效（冲击波 / 光晕 / 粒子 / 波形频带响应 / 背景呼吸）**各有独立开关**，默认全开，关掉后画面立刻跟上
- **不再自动降级**（v3.0.0）：v2.8.0 起那套「帧时间超标就自动降一档」的机制**整个删除** —— 真机上它的实际效果是**画面在用户不知情时变化**，而且会**改写用户选的档位**（设置页显示「炫技」、画面上却不是）。现在渲染只由三样东西决定：**你选的档位**、界面动效总开关、五个独立开关；低端设备的初始档位仍按设备能力解析，但**一旦你自己选过就永远以你的选择为准**。想省电可以逐项关掉上面那些开关
- **应用内自动旋转**（v1.8.0）：开关开（默认）= 跟随传感器，且在播放器界面转横屏自动进大屏；关 = 手机锁竖屏，只能用 ⤢ 手动进大屏。只决定应用自己的方向，**既不读取也不修改**系统旋转设置
- **主题**：6 种主题色 × 3 种模式（跟随系统 / 深色 / 浅色），运行时切换
- **响应式**：窄屏限宽居中，宽屏（≥ 600dp）左侧 200dp 常驻 Sidebar
- **进程恢复**：被杀后恢复进度、歌曲信息、封面、歌词与完整队列

### 🔔 系统集成

- **媒体控制**：MediaLibraryService + MediaStyle 通知，锁屏 / 控制中心 / 蓝牙按键，封面主色调着色
- **媒体面板歌词**（v1.5.1 / v1.6.1 / v1.8.0）：控制中心与通知栏第一行显示当前歌词行、第二行显示「歌名 · 艺人」；API 24 上跨行时补一次通知重播，歌词随行刷新
- **车机**：Android Auto / Android Automotive（AAOS）媒体源与浏览树
- **后台播放**：`WAKE_LOCK` + 电池优化白名单引导，熄屏不被 ROM 清理
- **音频焦点**：ExoPlayer 自动处理

---

## 🎼 支持音质

UI 上是一套 8 级档位，两个音源各自映射到自己平台的文件档位：

| 档位（UI） | ncm `level` | qm 文件前缀 | 容器 | 要求 |
|---|---|---|---|:--:|
| 压缩 | `standard` | `M500` | mp3 128k | 普通账号 |
| 较好 | `higher` | `M800` | mp3 320k | 普通账号 |
| 更好 | `exhigh` | `C400` → `M800` | m4a AAC | 普通账号 |
| 无损 | `lossless` | `F000` | flac | ncm 黑胶 / QQ 绿钻 |
| 高解析 | `hires` | `RS01` → `F000` | flac | 会员 |
| 高清环绕声 | `jyeffect` | `Q000` → `RS01` → `F000` | flac | 会员 |
| 超清母带 | `jymaster` | `AI00` → `RS01` → `F000` | flac | SVIP / 臻品 |
| 杜比全景声 | `dolby` | `Q001` → `Q000` → `AI00` | flac | 会员 |

> **每一条 QQ 链的最后都收敛到 `M500`**：QQ 服务端**不会**自动降级（请求 320k 无权限就返回空链接，
> 不会顺手给你 128k），所以降级由客户端做，且**一次请求把所有档位放进同一个 `filename[]`** 后按优先级挑 ——
> 逐个档位发请求的话，一首会员曲最坏要 8 次往返才轮到能放的那一档。
>
> 设备没有 MediaCodec FLAC 解码器（API < 27 或精简 ROM）时会自动跳过无损档位，避免无声；本 fork 已集成 FFmpeg 解码扩展，API 24–26 也能真无损。

---

## 📦 安装

1. 打开 [Releases](https://github.com/yaxiaiyuting/Arris/releases)，下载最新的 release APK（约 **9.83 MB**）
2. 允许「未知来源」安装
3. 打开 App，在用户页登录（见下）

> 体积说明（最新）：v1.9.3 release APK **实测 9,794,320 字节 ≈ 9.79 MB**，debug 30,057,644 字节。
> 体积说明：v1.8.1 release APK **实测 9,761,344 字节 ≈ 9.76 MB**，主要来自 FFmpeg 解码扩展的 4 个 ABI（上游 v1.0.4 时代不含 FFmpeg，只有 3.9 MB）。v1.9.0 构建后按实际产物更新。

### 🔐 登录

App 不提供手动粘贴 Cookie，登录方式：

- **浏览器登录（手机默认）** — 用户页 → 头像 → 应用内 WebView 打开 ncm 登录页，登录后自动提取凭证
- **扫码登录（平板 / 宽屏）** — 生成二维码，用手机 ncm App 扫码
- **手机扫码授权平板** — 平板显示二维码后，用已登录 Arris 的手机扫描，经局域网把凭证加密传给平板

登录成功后自动同步云端收藏。

---

## 🛠️ 从源码构建

### 环境要求

Android Studio Hedgehog+ · JDK 11 · Kotlin 1.9.24 · Gradle 9.3.1 · Android SDK 36（minSdk 24）

### ⚠️ 必须先克隆 Kanesumi

`settings.gradle.kts` 通过 `includeBuild("../Kanesumi-sec-a")` 直接依赖同级的 [Kanesumi](https://github.com/GuitaristRin/Kanesumi-sec-a) 源码仓库。**单独克隆 Arris 无法构建**，目录结构必须是：

```
projects/
├── Arris/
└── Kanesumi-sec-a/
```

### 构建命令

```bash
git clone https://github.com/yaxiaiyuting/Arris.git
git clone https://github.com/GuitaristRin/Kanesumi-sec-a.git

cd Arris
./gradlew assembleDebug            # Debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease          # Release APK（R8 minify，需 keystore.properties）

benchmark/run_benchmark.sh all     # Macrobenchmark：冷启动 / 滚动 / 播放器展开
```

---

## 🧭 技术架构

| 主题 | 说明 |
|---|---|
| **Kanesumi Design** | 直角、无圆角、无弹簧；共享组件与动画词汇（`Metro*` / `sokuou`）位于外部 Kanesumi 库，App 内**已无 `material3` 依赖** |
| **GPU 零重组** | 播放器动画由单个 `progress: Animatable<Float>` 驱动，视觉属性只在 `graphicsLayer {}` 内读取；逐字高亮同样只在 draw 阶段推进 |
| **三层图层** | 主页面 / 播放卡 / 导航栏为独立 composable 层，手势转场互不干扰 |
| **歌词引擎** | `LrcParser`（行级）/ `YrcParser` + `YrcAligner`（ncm 逐字，LCS 保序对齐）/ `SweepTrack`（连续光标）/ `TtmlParser` + `TtmlScanner`（自研纯 Kotlin 扫描器，零依赖、JVM 可单测）/ `AmllTtmlClient`（AMLL 镜像拉取）/ `LyricSourceChain`（三级回退）/ `LyricRequestGate`（防快速切歌竞态）/ `LyricsCache`（200 条持久化） |
| **网络直连** | 自有 `EapiCrypto`（AES-128-ECB + MD5 签名）与 `WeapiCrypto`（双 AES-CBC + 原始 RSA），直连 ncm，无中间服务器 |
| **ContentCache** | 内存网络快照 + Crossfade，消除「空屏 → spinner → 跳变」；LRU-32 详情缓存 |
| **离线缓存** | `cache/OfflineAudioCache`（media3 `SimpleCache` + `CacheDataSource`，`filesDir/offline/audio`，512 MiB LRU；自定义 key `ncrustkey=song:<id>:<level>`，否则 URL 每次轮换永远不命中）+ `OfflineUrlStore`（离线回放 URL 清单，300 条 LRU） |
| **持久化** | 全部 SharedPreferences + Gson，无 Room |
| **性能** | R8 全量混淆 + 资源 shrink（release 9.76 MB，v1.8.1 实测）、`AppWarmup` 冷启动预热、状态订阅下推至叶子组件、播放器折叠态子树 gating、大屏可视化 60fps 增量重绘 |
| **动效强度（v3.0.0）** | `ui/player/motion/`：`MotionEffects`（统一档位 / 能力位矩阵 / 五个独立开关，**纯逻辑且不含任何降级水位**——自动降级已删除，有反射断言守着）、`MotionPrefs`（10 个键 + 迁移水位 4）、`MotionBindings`（**特征 → 动效参数**的唯一映射层）、`MotionEnvelope` + `MotionBackdropState`（响度包络 / 瞬态脉冲 / 冲击波 / 光晕 / 粒子的定长 SoA 池）、`CoverBlur`（32px 降采样 + 三遍盒式模糊 + 按封面 URL 缓存的纯 Kotlin 实现）、`MotionClock`（全仓库唯一的帧时钟）、`MotionBackdrop`（全屏背景层：模糊 / 呼吸 / 视差 / 冲击波 / 光晕 / 粒子） |
| **音频特征（v3.0.0）** | `player/AudioFeatureExtractor.kt`：两个一阶低通（150 Hz / 2 kHz）在**同一次逐样本遍历**里算出全带 RMS + 低 / 中 / 高频带 + 质心近似 + 瞬态（**每声道一份滤波状态** + **10 ms 子帧结算**）；`player/TransparentWaveformSink.kt` 负责两个开关（画不画波形 / 界面动效要不要特征）与「失败回落 RMS-only」；`ui/player/WaveformStore` 用 volatile 标量 + **单调瞬态计数**发布到 UI 线程。**全程无 FFT**，音频线程零分配 |

```
app/src/main/java/com/takahashirinta/ncrust/
├── MainActivity.kt        # 入口 + MainScreen 编排（导航 / 队列 / 播放模式）
├── auth/                  # Cookie + 局域网扫码配对
├── cache/                 # ContentCache（内存快照）+ OfflineAudioCache（media3 SimpleCache）
├── library/               # 云端收藏 + 搜索历史
├── lyric/                 # LRC / yrc / TTML 解析 + 逐字对齐 + 歌词源回退链 + 歌词缓存
├── network/               # Retrofit / eapi / weapi / 加密
├── player/                # PlaybackService / SongUrlFetcher / PlayReporter / PreloadSlot
├── reco/                  # 音乐人推荐锚点推导（ArtistReco）
├── warmup/  power/        # 冷启动预热、电池白名单
└── ui/                    # screen / player（含 AudioVisualizer）/ components / theme / i18n / navigation
```

> 更完整的架构说明见 [AGENTS.md](AGENTS.md) 与 [Wiki](https://github.com/GuitaristRin/Ncrust/wiki)。

---

## 📋 版本历史

> 完整的逐版本详解（症状 / 根因 / 修法 / 实测数据）见 **[CHANGELOG.md](CHANGELOG.md)**。
> 下表只列一句话亮点；**日期取自各版本 git tag 的创建时间**。

### 本 fork 版本线（`-gpl`）

本 fork 从上游 `v1.3.1` 分叉，版本号**从 `v1.0.4-gpl` 起独立递增**，
所以这一栏的日期从 2026-09 开始、与下面「上游版本线」的日期**不连续** —— 两条并行的线，不是排错了。

| 版本 | 日期 | 一句话亮点 |
|---|---|---|
| v1.0.4-gpl | 2026-09-21 | fork 首个 GPLv3 版：修音质切换与红心歌单分页，许可证 MIT→GPLv3 |
| v1.1.0-gpl | 2026-09-21 | Android 7.0/7.1 真无损（FFmpeg 软解）+ 3GB 机型低配优化 + 进度记忆断点续播 |
| v1.1.1-gpl | 2026-09-21 | 修展开态关闭歌词后底部播放控制栏与导航栏一起失效 |
| v1.2.0-gpl | 2026-09-21 | 取链身份对齐（修 Hi-Res/母带被封顶）、新增超清母带档位（7→8 档 + 索引迁移）、音质角标改按实际文件判定、主题色来源三选一、首页榜单卡片 |
| v1.3.0-gpl | 2026-09-22 | 修播放器「死带」（折叠态隐藏子树 + 收窄命中区）+ 歌单管理全量（创建/编辑/删除/增删曲） |
| v1.4.0-gpl | 2026-09-22 | 底部控制栏可收起（动画帧零重组）、暂停态拖进度条立即生效、首页「播放全部」先播后补、音乐人推荐卡片 |
| v1.4.1-gpl | 2026-09-22 | 暂停态 seek 后歌词立刻同步（此前要按一下播放 / 暂停才跟上） |
| v1.4.2-gpl | 2026-09-22 | 控制栏加常驻拖拽把手，收起后能划回来；恢复手势不再被整卡拖拽抢走 |
| v1.5.0-gpl | 2026-09-22 | yrc 逐字歌词与行级翻译双语、音乐人推荐锚点收敛、离线下载调研选型（实现留后续版本） |
| v1.5.1-gpl | 2026-09-22 | 歌词字号五档调节、媒体控制中心显示当前歌词行、逐字动画三模式、控制栏把手排除系统手势区 |
| v1.5.2-gpl | 2026-09-22 | 逐字渐变重做（SweepTrack 连续光标）、渐变质量三档、待播槽位不变量修串台、冷启动不再等网络 |
| v1.6.0-gpl | 2026-09-22 | 逐字覆盖率提升（37→42/100）、离线缓存 Phase 1、Android 16 实时更新（未验证） |
| v1.6.1-gpl | 2026-09-23 | 媒体面板 / 通知栏 / 实时更新三处统一为「第一行当前歌词、第二行歌名 · 艺人」 |
| v1.7.0-gpl | 2026-09-23 | 修「收起播放器只能拖下去不能拖上来」、横屏大屏模式、M3 理念美化（色槽 / TalkBack / 48dp 目标） |
| v1.8.0-gpl | 2026-09-23 | 应用内自动旋转开关、大屏沉浸式、竖屏就地音质选择器、大屏音频可视化（零新增权限） |
| v1.8.1-gpl | 2026-09-23 | 大屏可视化提帧：20→30 柱/秒、20→60fps、柱高平滑；PCL110 实测 61fps、Janky 0.00% |
| v1.9.0-gpl | 2026-09-23 | AMLL TTML 逐字歌词源 + TTML→YRC→LRC 三级回退；覆盖 10.2%，逐字 40.8%→45.9% |
| v1.9.1-gpl | 2026-09-23 | 歌词镜像回退修正（jsdelivr 404/403 不再判成「没有 TTML」）+「歌词源 picked=」日志 |
| v1.9.2-gpl | 2026-09-23 | 译文 / 音译轨分轨合并（按文本 / 行序对齐）；修 22704409 丢翻译、1959528822 丢音译 |
| v1.9.3-gpl | 2026-09-24 | 歌词音译显示（罗马音 / 粤拼，默认关）；默认关时与 v1.9.2 逐字节一致；S6 实测帧时间无劣化 |
| v2.0.0-gpl | 2026-09-24 | 离线缓存 Phase 2（管理页 / 容量上限 / 离线优先取链）；修大屏保持横屏、乱序播放静默退回、播放时禁止熄屏 |
| v2.0.1-gpl | 2026-09-24 | 修大屏逐字歌词「还没进入第一句就被拉到最顶端」：顶部留白按视口夹取 + 无当前行按第一句定位 |
| v2.0.2-gpl | 2026-09-25 | 华为 / 荣耀媒体通知 hotfix：修双通知栏、通知栏歌词只有暂停才刷新；真机验证通知 2 → 1 条 |
| v2.1.0-gpl | 2026-09-25 | ncm + qm 双音源并存、各自独立登录；QRC 逐字歌词（非标准 3DES 变体）；单测 483 全绿 |
| v2.1.1-gpl | 2026-09-25 | QQ 扫码登录「网络不稳定」真根因（hash33 初值 5381→0、65/67 反了）+ 手机号登录 + 音源角标 |
| v2.1.2-gpl | 2026-09-25 | 手机号登录图形验证码 20276 走通（回传 WebView cookie）；v2.1.1 tag 未发布，内容全在本版 |
| v2.1.3-gpl | 2026-09-25 | 修短信验证码登录失败时显示「扫码登录失败」；登录失败码分类；单测 530 全绿 |
| v2.1.4-gpl | 2026-09-25 | 音质角标不再把请求档位当实际档位；聚合搜索按会员状态排序 |
| v2.1.5-gpl | 2026-09-25 | 修跨源自动切歌歌词串台：取词问错平台；「当前播放的是谁」收敛成单一写入点 |
| v2.1.6-gpl | 2026-09-25 | 两条 MediaSession 合并为一条；顺手修掉暂停时 speed 谎报 1.0；华为控制中心卡片未修好 |
| v2.2.0-gpl | 2026-09-25 | qm 歌单与用户信息只读同步（148 次请求实测）；修 R8 丢泛型签名导致的 release 崩溃等三个问题 |
| v2.2.1-gpl | 2026-09-25 | P0 事故复盘：可视化单声道 tee 打挂 AudioSink 引发循环降档 / 自动切歌；三道有界熔断 |
| v2.3.0-gpl | 2026-09-25 | 库界面重构、本地歌单（只加不减 + tombstone）、音源与版权标注、横屏歌词自动居中 |
| v2.4.0-gpl | 2026-09-25 | 艺人 / 专辑 / 单曲双源聚合 + 版权可用性优先；跨源匹配置信度分级（≥ MEDIUM 才合并） |
| v2.5.0-gpl | 2026-09-25 | UI 视觉规范（AppShapes / AppMotion 单一落点）、封面边框分档、添加到下一首的四条队列边界 |
| v2.5.1-gpl | 2026-09-25 | 页面切换动效改为用户可配（默认开 + 可持久化开关）；歌曲菜单高度修复 |
| v2.5.2-gpl | 2026-09-25 | 歌词「整条居中」几何修正：按整条中点定位（真机实测中心落在 67.6%）；竖屏比例未改 |
| v2.5.3-gpl | 2026-09-26 | Strings 上帝类 245→128 拆分并定预算 150；队列身份改用 TrackKey（含音源） |
| v2.5.4-gpl | 2026-09-26 | 搜索历史被 R8 混淆字段名修复、QQ 兜底统计、平板横屏波浪条、竖屏托盘两行 |
| v2.5.5-gpl | 2026-09-26 | R8 字段名 7 个实例系统性修复 + 机器守卫；跨源上报闸门；托盘上一首与 80dp 三层布局 |
| v2.5.6-gpl | 2026-09-26 | baseline profile 生成管线；搜索首帧解阻塞（真机实测被 ncm 硬阻塞 30 s）；平板控制条命中区修复 |
| v2.6.0-gpl | 2026-09-26 | 修 QQ 曲目入库后刷新消失（收藏库补「只加不减 + tombstone」）；歌手全部播放、布局切换、歌单折叠 |
| v2.6.1-gpl | 2026-09-26 | P0 hotfix：QQ 曲目「转到歌手」跳到错误艺人（4558→马洪波）；身份必须是 (source, id) |
| v2.6.2-gpl | 2026-09-27 | P0 hotfix：QQ 曲目「转到专辑」跳到错误专辑；值域判据收敛到 SourceIdDomain 单一实现 |
| v2.8.0-gpl | 2026-09-27 | 波形效果三档分级（简洁 / 精致 / 炫技）+ 帧时间自动降一级；设置界面改为 7 张卡片 + 二级页 |
| v2.9.0-gpl | 2026-09-27 | 统一「动效强度」一个档位驱动波形与界面动效；背景模糊先降采样再模糊；帧循环收敛到 MotionFrameClock |
| v3.0.0-gpl | 2026-09-28 | 音频特征驱动动效（无 FFT）；删除自动降级；S6 帧时间 P99 150→53 ms（P50 21→26 ms） |
| v3.1.0-gpl | 2026-09-28 | 并行预加载（切歌更快）+ B站音源（默认关、Wbi 签名、匿名 320K）；B站播放未验证 |
| v3.2.0-gpl | 2026-09-28 | 修 qm VIP 误判「无版权」、B站搜索卡死、迷你封面错位、简洁档仍抖；新增界面律动开关与 B站扫码登录 |
| v3.2.1-gpl | 2026-09-28 | 播放器卡片 9092 指令拆到 1972 修闪退；修宽度算出 0 / NaN 崩溃与 B站二维码「打开即失效」 |
| v3.2.2-gpl | 2026-09-28 | 波形改为左低 / 中中 / 右高三条频带泳道，PCHIP 零过冲，三级防闪 |
| v3.2.3-gpl | 2026-09-29 | 频带着色与档位解耦（默认简洁档也有）+ 亚格插值、去掉老系统 30fps 上限（1227→1784 帧） |
| v3.2.4-gpl | 2026-09-29 | 修 B站歌曲无法播放（缺合格 User-Agent 被 403）+ 高刷设备音频条抖动 |
| v3.3.0-gpl | 2026-10-03 | 10 条用户反馈：离线兜底与歌词搜索；新增桌面小组件、歌词复制 / 分享 / 出图、播放统计页 |
| v3.3.1-gpl | 2026-10-03 | 修 B站视频轨歌词从未发出请求（cid 未像取流那样补问）；区分可重试失败与稳定空态 |
| v3.4.0-gpl | 2026-10-04 | 全仓库品牌改名（网易云→ncm、QQ 音乐→qm）+ 专辑页音源角标与筛选；修连接预热与静默失败分支 |
| v3.4.1-gpl | 2026-10-04 | 修波形尖端「变圆再变尖」：相位从插值取值改成几何平移（峰尖度波动 90%→0%） |
| v3.4.2-gpl | 2026-10-04 | 紧急修 v3.4.1 引入的播放闪退（peaks 数组越界）；延伸点容量补成 pointCount + 1 |
| v3.4.3-gpl | 2026-10-04 | 修三泳道绕过着色弹道导致的「没有弹起感」（新柱达 90% 由 1 帧变 8 帧） |
| v3.4.4-gpl | 2026-10-04 | 小球改牛顿落体 + 弹性碰撞（g=32、e=0.45）；短横与圆点错开 2r+cap |
| v3.4.5-gpl | 2026-10-05 | 修 B站曲目收藏 / 上报被发给网易云（判据改为正向「是 ncm」）；红心歌单读端三处修复 |
| v3.4.6-gpl | 2026-10-06 | 小球脱离地面 + 解析碰撞 + 重标定（g=10、e=0.7）；短横改真峰值保持；音质旁显示实际码率 |
| v3.4.7-gpl | 2026-10-06 | 修波形横向抽搐：相位改用两个真实时间戳相减、删掉累加帧时钟（抖动率降到 1/3） |
| v3.4.8-gpl |  | **改名 Arris + 新图标**（正圆唱片 + 直角播放三角 + 圆心孔；三层自适应含 Android 13 主题图标，并补上 **API 24/25 的密度桶回落**）；**B站大会员 Hi-Res 无损**（读 `dash.flac.audio` —— 此前从没被读，所以永远是普通音质）、字幕语言可选（此前写死中文优先）、音质上限与优先 FLAC 两个用户参数 |

### 上游版本线（[GuitaristRin/Ncrust](https://github.com/GuitaristRin/Ncrust)）

本 fork 的基线是上游 `v1.3.1`。下表是**上游**的版本历史，保留在这里是为了交代来处；
其中的功能**全部已包含在本 fork 里**（`v1.3.1` 之后的功能见上表）。

| 版本 | 日期 | 一句话亮点 |
|---|---|---|
| v0.1.0-beta | 2026-04-26 | 初始 MVP，核心播放流程跑通 |
| v1.0.0 | 2026-04-29 | 首个正式版：多屏幕适配、状态持久化、队列管理 |
| v1.0.1 ~ v1.0.3 | 2026-05-04 | 零重组修复、主题色系统、多语言框架、歌单页闪退与 WebView 登录修复 |
| v1.1.0 | 2026-05-05 | 收藏 / 队列 / 音质完善 |
| v1.1.4 | 2026-05-17 | 无缝播放元数据同步、URL 缓存去重、多语言扩充 |
| v1.2.0 | 2026-07-28 | Sokuou 动画系统、ContentCache + Crossfade、专辑收藏、批量入队 |
| v1.2.1 | 2026-07-29 | Kanesumi 化收官、冷启动预热、播放器重组归零、R8（25 MB → 4.1 MB） |
| v1.2.2 | 2026-08-04 | 全页面 Kanesumi 统一、底部导航与迷你条衔接、登录统一为浏览器方式 |
| v1.3.0 | 2026-09-10 | 平板 / 大屏 Sidebar 与宽屏两栏、AudioSink 降档兜底、歌词定位修复、队列拖拽重排、私人 FM 续播 |
| v1.3.1 | 2026-09-11 | 车机媒体源与浏览树、浅色模式与主题切换、扫码登录重构、冷启动与歌词性能优化 |

> **读表须知**
>
> - `v1.4.1` / `v1.4.2` 是 `v1.4.0` 的补丁；**`v2.7.x` 从未存在**（`v2.6.2` 之后直接是 `v2.8.0`）。
> - ⚠️ v3.4.8 重写本节时做了四件事，记在这里以免下次被当成「顺手改历史」：
>   ① **修格式损坏** —— 原表在 `v2.0.0` 与 `v2.0.1` 之间混进一个空行，表格被劈成两半，
>   后半段（`v2.0.1` 起 40 余个版本）在 GitHub 上渲染成了一大段带 `|` 的正文；
>   ② **修乱序**（`v3.2.0` 原本排在 `v3.1.0` 前面）；
>   ③ **拆成两张表** —— 两条时间轴按版本号交错排列时日期看起来在乱跳
>   （fork 的 `v1.0.4-gpl` 打在 2026-09，而上游的 `v1.1.0` 在 2026-05）；
>   ④ **补全 24 个缺失版本** —— `v1.1.0-gpl` / `v1.2.0-gpl` / `v1.3.0-gpl`（fork 自己早期的三个，
>   原表从未有过）、`v1.0.4` / `v1.1.1` / `v1.4.1` / `v1.4.2`、`v2.1.4`~`v2.1.6`、`v3.2.1`~`v3.4.7`。
> - 补全内容全部取自仓库内的一手资料（`docs/release-notes-*.md`、`docs/verification/*/RELEASE-NOTES.md`、
>   `AGENTS.md` 逐版本小节、git tag 注释），逐条标注在 [CHANGELOG.md](CHANGELOG.md) 里。
>   其中 `v1.0.4` / `v1.1.1` / `v1.4.1` / `v1.4.2` **没有独立发布说明**，详解是重建的，正文已注明来源。
> - 有 3 行的日期与原表差一天（`v1.0.0`、`v3.0.0`、`v3.2.0`），本次按「日期取自 tag」的口径对齐到 tag。

## 🎙️ 歌词数据来源

歌词来自两个互相独立的公开数据源，**都不需要登录**，也都只读取公开数据：

| 来源 | 内容 | 说明 |
|---|---|---|
| ncm 官方歌词接口 | 行级 `lrc` + 行级翻译 `tlyric` + 逐字 `yrc` | `POST /api/song/lyric`（`lv` / `tv` / `yv` 开关）；匿名与登录态返回逐字节相同 |
| [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db) | 逐字 TTML（`ncm-lyrics/<songId>.ttml`） | **CC0-1.0**；按 ncm 歌曲 ID 直接拉取公开静态文件 |

- **不爬虫、不模拟登录、不绕过任何保护机制**：AMLL TTML DB 是社区维护的公开静态文件仓库，客户端只按歌曲 ID 发起普通 HTTP GET；4 个镜像实测内容逐字节一致、可互为备份，取不到就静默回退，不重试、不探测。
- **覆盖率如实说明**（v1.9.0 Phase 0 实测，样本 = 98 首真实收藏）：TTML 覆盖率 **10.2%**（10/98），yrc 覆盖率 40.8%（40/98），两者并集 **45.9%**（45/98）—— TTML 的净增量是 **+5.1 个百分点**，不是「大幅提升」。样本来自单一账号（偏华语流行 + 部分欧美摇滚 / 电子），ACG / vocaloid / 日系用户的实际 TTML 覆盖率会显著更高。
- **不做按歌名 / 歌手模糊匹配的兜底**：不同版本、不同音源的歌词时间轴不可混用，宁可没有逐字，也不给错位高亮。
- 第三方依赖许可证与 GPLv3 兼容性分析见 [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md)；AMLL TTML DB 为 CC0-1.0，本项目**不引入任何新的第三方运行时依赖**。

### TTML 的定位：**英文歌的逐字 + 翻译源**，不是覆盖率主力

TTML 在本项目里的角色是「给 ncm 没有逐字 / 没有译文的那部分歌补一份」，**不是**把逐字覆盖率拉高：

| 语种 | ncm YRC（逐字） | 逐字并集（YRC ∪ TTML） | ncm tlyric（译文） |
|---|---|---|---|
| 中文 | 16/30 = **53.3%** | 66.7% | 池内 232 首只有 4 首（2%） |
| 英文 | 24/30 = **80.0%** | 83.3% | 池内 274 首 248 首（91%） |
| 日文 | 1/20 = **5.0%** | 5.0% | 90% |
| 韩文 | **0/10 = 0%** | 0% | 100% |

- **日韩没有逐字**（TTML 在日韩基本 0 命中、ncm YRC 也只有 0–5%），但译文与音译接近 100% ——
  对日韩用户，译文质量的天花板在 ncm。**这一点只写在文档里，不做 UI 提示、不加弹窗、不加设置项。**
- **不做按语言分流**：实测数据不支持（语言维度相关的是覆盖率，不是「哪个源更好」）；也**不做语言检测**、
  **不做同名异版的模糊匹配**（会让逐字错位）。
- 中文歌几乎恒无 `tlyric`（池内 232 首里只有 4 首），所以「TTML 优先会丢译文」在中文歌上根本不会触发，
  风险集中在非中文语种。

### 分轨合并（v1.9.2）：主歌词 / 译文 / 音译**各自独立回退**

- 主歌词沿用 v1.9.0 的 `TTML → YRC → LRC` 回退链（谁的逐字好用用谁），本版一行未改；
- 第二相 TTML 胜出时译文轨与音译轨**不再被整体替换**：TTML 那一轨里能落到主轨时间轴上的行原样用，
  **缺的行**按**文本 / 行序**（归一化去空白 + LCS 保序）回退到 ncm 的 `tlyric` / `romalrc`，
  对不上的**逐行丢弃** —— 宁可这一行没有译文，也不按时间戳硬配（两源行时间中位差 +169 ms，
  单曲 −551 ms ~ +561 ms，不可混用）。
- **不新增依赖、不新增权限、不新增网络请求**：ncm 的 `romalrc` 本来就在同一个 `/api/song/lyric`
  响应里（实测 `rv` 取值与它无关），本版只是把此前没解析的字段接上。
  > **v1.9.3 起音译轨已经从数据层走到 UI**：设置 → 播放 → 「显示音译」（键 `lyrics_romanization`，默认关）。
- **音译轨（`romalrc` / TTML `x-roman`）本版只到数据层**：v1.9.0 / v1.9.1 从未渲染过音译，
  要显示它必须给 `LyricsView` / `NcrustLyricsPanel` 加一个副文本槽，而本版硬约束是
  「渲染层 diff 为空」—— 渲染接线留给解禁渲染层的版本。
---

## 🔗 相关项目

| 项目 | 说明 |
|---|---|
| [Kanesumi-sec-a](https://github.com/GuitaristRin/Kanesumi-sec-a) | Kanesumi Design 组件与动画库（Apache-2.0），本项目 UI 依赖 |
| [163CMAnalyser](https://github.com/GuitaristRin/163CMAnalyser) | Rust CLI 无损下载工具 |
| [Netease_url](https://github.com/Suxiaoqinx/Netease_url) | Python 原版 ncm 解析（MIT） |

---

## 📄 许可证

**本 Fork 整体以 GPLv3 分发，并完整保留上游原始代码的 MIT 许可。**

**上游 Ncrust** 的原始代码 Copyright (c) 2026 Takahashi_Rinta，遵循 MIT 许可。本 Fork 的修改与新增部分，Copyright (c) 2026 yaxiaiyuting，遵循 GPLv3 许可。整体以 GPLv3 分发。

- 上游项目：[GuitaristRin/Ncrust](https://github.com/GuitaristRin/Ncrust)
- 原始 MIT 许可证全文（未作任何修改、原版权声明完整保留）：[LICENSE-MIT](LICENSE-MIT)
- 本 Fork 整体适用的 GPLv3 全文：[LICENSE](LICENSE)
- 第三方依赖许可证与 GPLv3 兼容性分析：[THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md)
- UI 依赖 Kanesumi（kanesumi-core / anim / controls / structure）为 Apache-2.0，与 GPLv3 兼容。

---

<div align="center">

## ⭐ Star History

<a href="https://www.star-history.com/?repos=yaxiaiyuting%2FArris&type=date&legend=top-left">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=yaxiaiyuting/Arris&type=date&theme=dark&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=yaxiaiyuting/Arris&type=date&theme=light&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
    <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=yaxiaiyuting/Arris&type=date&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
  </picture>
</a>

**如果 Arris 让你重新爱上听歌，请赐一颗 Star ⭐**

[问题反馈](https://github.com/yaxiaiyuting/Arris/issues) · [Wiki](https://github.com/GuitaristRin/Ncrust/wiki) · [Releases](https://github.com/yaxiaiyuting/Ncrust/releases)

</div>
