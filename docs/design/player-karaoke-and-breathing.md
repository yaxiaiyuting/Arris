# Arris 播放页 · 卡拉 OK 与背景呼吸可行性

2026-11-05。对照 NeriPlayer（音理音理）提交 `8397055`，以及 Arris 主线的歌词、背景与音频可视化代码。以下是两项功能的实现方案和验证门槛。

## 卡拉 OK · 高可行性

本报告将「卡拉 OK」定义为全屏跟唱视图。当前句以大字显示，逐字高亮，下一句提前入场；译文或音译使用小字。麦克风录音、人声分离、评分、实时耳返分别属于另一条音频工程，所需的伴奏来源、权限与延迟预算各自需要评估。

音理将原文、翻译和音译归一为跟唱模型。它将播放采样与帧内插值分开，并交给 Accompanist Lyrics 的渲染器绘制，提供焦点行定位、弱化前后行、点击跳转和低功耗刷新档。可借鉴的是同步结构与焦点层级。 [高级歌词接入](https://github.com/cwuom/NeriPlayer/blob/8397055124a91f1db0adfcf88b71506b1534b4f4/app/src/main/java/moe/ouom/neriplayer/ui/component/lyrics/AdvancedLyricsView.kt#L124) · [播放位置插值](https://github.com/cwuom/NeriPlayer/blob/8397055124a91f1db0adfcf88b71506b1534b4f4/app/src/main/java/moe/ouom/neriplayer/ui/component/lyrics/LyricPlaybackPosition.kt#L18) · [Accompanist 子模块](https://github.com/cwuom/NeriPlayer/blob/8397055124a91f1db0adfcf88b71506b1534b4f4/.gitmodules)

Arris 已有 YRC、TTML、LRC 解析，`LyricsView` 用播放位置和当前曲目身份建立时间锚，缓冲或首条样本前冻结外推。`NcrustLyricsPanel` 根据已测量的文本绘制活动句逐词扫光。首版可在现有歌词页增加跟唱入口和独立布局样式，继续复用解析、缓存、双语选项、拖动进度与点击歌词跳转。 [Arris 歌词时钟](../../app/src/main/java/com/takahashirinta/ncrust/ui/player/LyricsView.kt#L276) · [Arris 扫光绘制](../../app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt#L865)

| 场景 | 首版表现 |
|---|---|
| 有逐字时间戳 | 当前句渐进点亮，下一句淡入，有明确行尾的长间奏显示入唱提示 |
| 只有 LRC 行时间戳 | 整行亮起并自动滚动，时间轴精度由源文件决定 |
| 原文加译文或音译 | 同一个时间轴驱动两行，优先把逐字遮罩用在有真实逐字时间戳的那行 |
| 无歌词或暂未加载 | 保留歌曲信息、状态提示和退出入口 |

验收覆盖暂停、缓冲、跨句 seek、倍速、切歌后迟到译文、换行、中文／英文／日文和 RTL。逐字起点与 Media3 时间对齐，固定样本下的目标是误差不超过一个渲染节拍再加播放采样误差。触摸操作与系统双语歌词沿用现有链路。首版估算 **2–4 个开发日**，含一轮 Android 真机与布局复核。

## 背景呼吸 · 渐变版高可行性，流体版依赖设备性能

音理的动态背景用封面调色板驱动 shader，把 RMS 音量和节拍作为独立输入，采用快起慢落平滑，并在页面恢复可见时按固定帧槽更新。封面取色异步执行并核对请求身份，切歌时旧结果不会覆写新封面。音理使用的 `RuntimeShader` 从 Android 13（API 33）开始提供。 [音理背景着色与生命周期](https://github.com/cwuom/NeriPlayer/blob/8397055124a91f1db0adfcf88b71506b1534b4f4/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L239) · [音理音频包络](https://github.com/cwuom/NeriPlayer/blob/8397055124a91f1db0adfcf88b71506b1534b4f4/modules/playback/logic/src/main/java/moe/ouom/neriplayer/core/player/audio/reactive/AudioReactive.kt#L116) · [Android RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader)

Arris 当前已有 `MotionBackdrop` 的封面低分辨率模糊、RMS 驱动的亮度与缩放，以及共享的 `MotionFrameClock`。可利用同一张封面与同一条音频包络，把效果改成两到三团缓慢流动的色光。低频主要改变光团范围，中高频只轻微改变色光的强度，文本区用独立遮罩保持稳定对比度。 [Arris 背景层](../../app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionBackdrop.kt#L84) · [Arris 帧调度](../../app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionClock.kt#L213)

| 档位 | 实现 | 停止条件与回退 |
|---|---|---|
| 基础 | Android 10+ 使用缓存径向渐变和低频位移。按曲目身份异步取色，切歌用交叉淡化 | 暂停、缓冲、后台或折叠后收敛到静态。减少动态效果时直接静态 |
| 流体 | Android 13+ 可选 AGSL。沿用已有节拍源和色板，以 30 fps 为初始上限 | GPU 初始化失败、掉帧或省电时切回基础档。画面持续不可见时停止刷新 |
| 静态 | 使用本轮播放页的固定环境光和遮罩 | 封面缺失、音频 PCM 不可用时仍有完整画面 |

效果的幅度以歌词可读性为上限，避免整屏明暗快速变化。音频处理复用现有数据，不增加麦克风、下载或 DSP。建议在中低端 60 Hz 和高刷设备上对同一曲目各跑三次，比较帧耗时 P95、卡顿比例、暂停后的绘制次数和耗电趋势，再决定流体档的默认策略。基础档估算 **1–2 个开发日**，流体档另需 **2–4 日**用于原型、降级路径和实测。

首版已在 4.0.5 测试包接入全屏跟唱、渐变背景呼吸和可选流体背景，并保留整页背景上的无底板控制区。参考源码用于设计对照，Arris 继续使用自己的渲染与状态组件。真机上的逐字误差、设备帧耗时和电池影响留待测试。
