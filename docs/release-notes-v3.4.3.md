# Ncrust v3.4.3-gpl

**versionCode 65** ｜ patch 版，只修一条波形回归 ｜ 全量单测 **2564 用例 / 0 失败 / 194 个测试类**

---

## 修的是什么：小球和色带顶边「没有弹起感」

你这次给了 **146fps 的横屏录像**（2378×1080），我逐帧量了它。定位到一条**我在三泳道改造里造成的回归**。

### 根因：三泳道分支**从未走过**起音 / 回落

v3.2.2 把三泳道的几何取值接到了 `lowTargets/midTargets/highTargets` —— 那是**原始**窗口，
逐格等于音频缓冲值。而 v1.8.1 就有的 **22ms 起音 / 130ms 回落只作用在 `bars[]` 上**，
并且只有**单条曲线**分支读它（`sqrt(bars[i])`）⇒ **三泳道把整条弹道绕过去了**。

后果：每格的**高度是瞬时赋值**。涨是硬跳、落也是硬跳，而柱间隔是 82.6ms ⇒ **12.1Hz 的阶梯**。
画在色带顶边上的圆点（你说的「小球」）在它整个可见寿命里**高度恒定**，过一格换一个值 ——
所以「没有弹起来的感觉」。而「一口一口拼上去 / 吃回去」，正是这种瞬时涨落。

### 修法

新增三条**画面值窗口**（`lowBars/midBars/highBars`）：

- 它们与原始窗口**逐格同步平移**；
- 在平移后的坐标系里做**同一套** 22ms 起音 / 130ms 回落；
- 输出改为**画面值**（原始窗口保留，所以「改前」仍然可测）。

平移与平滑**刻意分开**：这样 v3.4.1 修掉的「时域模糊」（把相邻两格的值混合）
不会回来 —— **形状仍是刚性平移**，不会重新引入尖端变形。

### 改前 / 改后（同一把尺子 = ring 自己输出的画面值窗口；工况 146.2fps / 82.6ms 柱）

| 量 | 改前 | 改后 |
|---|---|---|
| 新柱落点达到新值 90% 所需帧数 | **1 帧**（硬跳） | **8 帧** |
| 进柱后第一帧的格内变化（整格落差 0.9） | **0.9（100%）** | **0.240（26.7%）** |
| 回落到 1/e | 1 帧 | ≥80ms |
| 60Hz 与 146.2Hz 同一条曲线最大差 | — | **0.0028**（与刷新率无关） |

---

## 同时**推翻了两个我自己的假设**（如实记录）

### 1. 「帧率不够高」**不是掉帧**

146.2fps 采集下，1421 个帧间里只有 **17 个（1.2%）**像素几乎不变。
如果应用只跑 60Hz，146fps 采集应该出现约 **59%** 的重复帧；120Hz 约 18%。
实测 1.2% ⇒ **应用几乎每个采集帧都在出图**。

所以我此前用 30fps 那份录像量到的「只走一半」是**采样混叠伪影**，不是真实缺陷 ——
这一点我先前判断错了，特此更正。

### 2. 「往回拖动」**我没能证实，也没能修**

- 模型的到达帧位移 = `名义 + (L_next − L_prev)/T`，**恒 ≥ 0**；录像里倒退帧也是 **0**。
- 两个独立估计器在到达帧上**结论不一致**（一个说「每 82.6ms 恰好一帧位移≈0」，
  另一个说「内容变了、已顶到搜索边界」），**幅度与符号我判不了**。
- 最后几秒（你指出的地方）：色带塌成 **~2px 平线**、录音 RMS 掉到 **−54dB** ——
  「真的冻住」与「平线本来就没东西可跟」**我分不开**。

**这三条我都如实记为未解决**，没有为了好看而放宽阈值。

---

## 我那个未发布的 v3.4.3「排帧门」实验：**建议不要用**

我原本准备发的一个改动（把排帧判据从「最新那根柱够不够响」改成「窗口里还有没有内容」），
经独立评估后**否掉**，理由成立：

- 前提不成立：`consumePending()` 每根柱都会置 `changed=true`，安静段退化成 12.1Hz 阶跃，
  **不可能无限冻住**；
- 录像显示**没有掉帧**，所以安静段多排帧**不可能**提高交付帧率，只会多烧 GPU；
- 而本次修复已经覆盖了它的动机：画面值现在有回落尾巴，安静段本来就会一直排帧到窗口收敛。

---

## ⚠️ 未验证（请重点看这一节）

1. **没有视觉验证。** 我无法在本环境渲染这个 Composable，所以「弹起感是否真的回来了」
   **只有数字，没有眼睛**。数值方向是对的（1 帧硬跳 → 8 帧渐进），但**观感必须由你确认**。
2. 到达帧的**周期位移不匀**未修。
3. 「最后几秒冻住」**未证实**。
4. 一处代码疑点未定位：`frameClockMs += dtForPhase.toLong()` 看着该用四舍五入
   （`dtMs` 是 6.84ms，向下取整会让帧时钟比 `uptimeMillis()` 慢 4–12%）。
   **改成四舍五入后，项目自己的两条判据同时变红**（最大步 5.88× 名义；相位停在顶棚 478/1080 帧）
   ⇒ 说明**向下取整现在在替某处系统性偏差打掩护**。偏差定位前不要动这一行，已在原位留注释。
5. 小球在**单条曲线降级路径**下的行为未量（本次只覆盖三泳道 = 生产路径）。

## 复现材料

`.scratch/wf-measure/FINDINGS.md` + 同目录脚本：`zncc.py`(无混叠位移)、`sub.py`(亚像素顶边)、
`unamb.py`(低通 LK)、`beads.py`(小球检测)。

---

## English summary

**v3.4.3** (`versionCode 65`) fixes a regression I introduced during the three-lane migration:
the three-lane geometry reads the **raw** band windows, so the 22 ms attack / 130 ms release
ballistics — which only ever ran on `bars[]` and are only read by the single-curve branch —
were **bypassed entirely**. Every cell height was therefore an instantaneous assignment:
a 12.1 Hz staircase with no rise-and-fall. That is the missing "bounce" in the beads and the
top edge, and the "eating/stitching back" look.

Fix: three per-band **display** windows that shift in lockstep with the raw windows and then
get the same 22/130 ms ballistics (zero allocation). Shift and smoothing are kept separate so
the sub-cell sharpness fix from v3.4.1 is untouched.

Measured on one ruler (the ring's own displayed window, at 146.2 fps / 82.6 ms bars):
frames for a new cell to reach 90% of a new value **1 → 8**; first-frame delivery of a 0.9 step
**0.9 (100%) → 0.240 (26.7%)**; 60 Hz vs 146.2 Hz max trajectory difference **0.0028**.

**Two of my own hypotheses were disproved:** the "low frame rate" is **not** dropped frames
(only 1.2% near-identical frames at 146 fps capture; a 60 Hz app would show ~59%), and
"backward drag" could **not** be confirmed (the model's arrival-frame displacement is never
negative, and the recording shows zero backward frames). The final seconds collapse to a ~2 px
flat line at −54 dB, so "frozen" and "nothing to track" are indistinguishable.

**Not verified:** no visual confirmation is possible in this environment — the bounce must be
confirmed by eye on a device.
