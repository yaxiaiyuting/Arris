/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 新增：可随列表上滑收起的表头（库页使用）。
 * 用户原话：「库界面上面听歌识曲、一起听、搜索框，还有那个『单曲 歌单 专辑 离线』的 bar
 * 能不能随着拖动滑动？不然歌单根本滑动不了，歌曲占得地方太小了。」
 *
 * 为什么单独成文件：这是**布局机制**（自定义 layout + 嵌套滚动），与库页的业务无关；
 * 放在 LibraryScreen.kt 里会让那个本来就 880 行的文件再多出 120 行机械代码，
 * 而且其它页面（艺人页顶部操作条）以后大概率复用同一套。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

/**
 * 可收起表头的状态（库页顶部那一整块：页头 / 快捷入口 / 会话横幅 / 分类条）。
 *
 * 只有两个量，都是 snapshot state：
 *
 * - [headerHeightPx]：表头的**自然高度**（px）。由**布局阶段**写入，由**嵌套滚动回调**读取；
 * - [collapsePx]：0 = 完全展开；负值 = 已被收起的像素，合法区间 `-headerHeightPx..0`。
 *
 * ## 零重组的全部秘密：composition 一次都不读这两个量
 *
 * 读它们的地方只有两处 —— `Modifier.collapsingHeader` 的 `layout { }` lambda（布局阶段）
 * 与 [CollapsingHeaderConnection] 的回调（手势阶段）。于是拖动时发生的是
 * 「写状态 ⇒ 只重排表头这一个节点」，**不触发任何重组**（与 `PlayerCard.collapsibleHeight`
 * 读 `Animatable` 是同一个模式，见 AGENTS.md 的 GPU 零重组原则）。
 *
 * ⚠️ **谁在 composable 里写 `if (state.collapsePx < 0f)`，就等于把整页拖回每帧重组**。
 * 需要「收起了就改变某个东西」的话，只能做在 layout / graphicsLayer lambda 里。
 *
 * ## 为什么不用 `animateFloatAsState` 做吸附
 *
 * 本仓库踩过「动画在重挂载时重播」的坑（v2.3.0 的下拉刷新指示器就是这么闪的）。
 * 收起量**连续跟手**已经是一等公民观感，额外的时间轴动画只会带来两个新的失败模式：
 * 手势中途被打断时动画与手指互相拽、以及重挂载时从 0 重播一次。所以这里没有任何动画。
 */
class CollapsingHeaderState {

    var headerHeightPx by mutableIntStateOf(0)
        private set

    var collapsePx by mutableFloatStateOf(0f)
        private set

    /**
     * 布局阶段回写**测量结果**（高度 + 由它决定的合法区间）。每次布局末尾调一次。
     *
     * 这里做三件事，顺序不能换：
     *
     * 1. **「已经收到底」⇒ 继续贴着新高度**。库页的 `SessionExpiredBanner` 是条件挂载的：
     *    用户已经收到底（`collapsePx == -旧高度`）时横幅挂上，表头长高 40dp 左右，
     *    `collapsePx` 却还是 `-旧高度` —— 合法（区间是 `-新高度..0`）但**可见高度变成 40dp**，
     *    于是列表顶上多出一条「tab 条的下边缘」。那一条在展开态下是正常内容，在收起态下纯属
     *    凭空冒出来的碎片。所以只要之前贴着底，就跟着改成 `-新高度`（继续完全收起）。
     *    ⚠️ 只对「贴着底」这一种状态做，**不能**推广成「保持可见高度不变」：
     *    那样用户在顶部、表头全展开时，横幅一挂上表头会自己缩掉 40dp，更莫名其妙。
     * 2. 写回新高度（**值不变就不写**）。这是本文件所有回写的统一闸门：measure / placement 里
     *    写 snapshot state 会让 Compose 再排一次布局，而回写本身就在布局里 —— 没有闸门
     *    就是每帧一个潜在的无限测量环。`headerHeightPx` 现在只被嵌套滚动回调读
     *    （那不是快照观察者，写它并不会触发重排），闸门仍然必要：一是挡住「高度没变却写」的
     *    无意义失效，二是万一以后有人在 layout 里读它，这道闸门就是唯一让环收敛的刹车。
     * 3. 把 [collapsePx] 夹回新区间。**反向**那种情况（横幅摘掉、表头变矮，`collapsePx` 比
     *    `-新高度` 还小）由这一步兜住：不夹的话 ① 可视高度会被算成负数；② 往下拉时必须先
     *    白白走过那截「超出」的距离才会有反应，手感是「拉了半天表头不动」。
     *    有闸门，所以最多多一次布局就收敛（夹一次之后再进来就相等了）。
     */
    internal fun onMeasured(heightPx: Int) {
        val previous = headerHeightPx
        if (previous != heightPx) {
            if (previous > 0 && collapsePx <= -previous.toFloat()) {
                collapsePx = -heightPx.toFloat()
            }
            headerHeightPx = heightPx
        }
        val limit = -heightPx.toFloat()
        val clamped = collapsePx.coerceIn(limit, 0f)
        if (clamped != collapsePx) collapsePx = clamped
    }

    /**
     * 吸附到两端（0 或 `-headerHeightPx`）。
     *
     * 为什么必须吸附：真机上出现过「表头停在半收起」—— tab 条被拦腰截断、列表却已经在滚
     * （用户描述为「卡一两个组件」「向上滑动有时候会卡」）。中间态一旦被静止保留，
     * 还可能和下一次布局量到的自然高度差几十 px，形成「看得见一条 tab 条、往下拉却没反应」的死区。
     * 拖动过程中仍然完全跟手，只在**手势结束时**收敛到两端。
     */
    internal fun snapToEnds(direction: Int = 0) {
        val height = headerHeightPx.toFloat()
        if (height <= 0f) return
        collapsePx = when {
            // 有明确方向的甩动 ⇒ 顺着用户的方向去端点。没有这一条，往下拉出一半再松手
            // 会因为「离收起端更近」又被吸回收起 —— 真机上就是「往下拉没反应」。
            direction > 0 -> 0f
            direction < 0 -> -height
            // 没有方向信息（极慢松手）⇒ 就近吸附。
            else -> if (collapsePx <= -height / 2f) -height else 0f
        }
    }

    /** 手势回调里累加收起量（`delta` 为负 = 更收起）。顺手夹到合法区间。 */
    internal fun collapseBy(delta: Float) {
        val limit = -headerHeightPx.toFloat()
        val next = (collapsePx + delta).coerceIn(limit, 0f)
        if (next != collapsePx) collapsePx = next
    }
}

@Composable
fun rememberCollapsingHeaderState(): CollapsingHeaderState = remember { CollapsingHeaderState() }

/**
 * 把子树变成「高度会缩、内容整体向上滑出并被裁掉」的表头。
 *
 * ## 为什么是「滑出」而不是「压扁」
 *
 * 最省事的写法是把这个节点的布局高度直接缩掉、让子节点按 `fillMaxHeight` 重排 —— 那是**压扁**：
 * 高度从 200 变 100 时，tab 条会先被从底部削掉一半，观感是「字被拦腰截断」。
 * 这里改成先量**自然高度** `h`，再 `layout(h + collapse)` 并把子节点整体 `place(0, collapse)`：
 * 子节点尺寸与内部布局一个像素都不变，只是**顶边被裁到容器外面**了 —— 裁掉的是
 * 页头/快捷入口那一段，tab 条是**完整地向上滑走**的。
 *
 * ## 为什么必须 `clipToBounds()`
 *
 * 不裁的话，滑出去的那半截仍然画在父容器上方（会盖住状态栏、盖住上一页），
 * 而且**参与命中测试** —— 用户点列表顶部会点到「看不见的 tab 条」。
 * 这正是 AGENTS.md「Compose 触摸陷阱」第 1/2 条的形态：看不见 ≠ 摸不着。
 * `clipToBounds()` 建立的是带 clip 的 graphics layer，绘制与命中测试**一起**被裁掉。
 *
 * ## 为什么在 `layout` 里读 `collapsePx`
 *
 * 布局阶段读 snapshot state 只会把**这一个节点**标脏重排，不会触发重组；
 * 放在 composable 里读才是每帧重组。
 */
fun Modifier.collapsingHeader(state: CollapsingHeaderState): Modifier =
    this.clipToBounds().layout { measurable, constraints ->
        // 高度不设上限地量一次：拿到「展开时的自然高度」。宽度跟随父约束（表头是整行铺满的）。
        val placeable = measurable.measure(Constraints(maxWidth = constraints.maxWidth))
        val naturalHeight = placeable.height
        // 本次布局**只用这里算出来的局部值**，不依赖下面 placement 里的回写 ——
        // 于是「越界的收起量」在**这一帧**就已经被夹住了，不用等下一帧才生效。
        val collapsed = state.collapsePx.coerceIn(-naturalHeight.toFloat(), 0f)
        val visibleHeight = (naturalHeight + collapsed).roundToInt().coerceIn(0, naturalHeight)
        // 用 placeable.width 而不是 constraints.maxWidth：约束里 maxWidth 可能是 Infinity
        // （横向可滚的父容器），当真宽度用会直接崩在 layout() 的参数校验上。
        layout(placeable.width, visibleHeight) {
            placeable.place(0, collapsed.roundToInt())
            // 回写放在 **placement 块**里，与 `Modifier.onSizeChanged` 同一个时机 ——
            // 那是平台认可的「布局阶段写状态」的位置（Compose 会安排一次重排，不会抛异常）。
            // 放在 measure 块里虽然也能跑，但它比平台自带的那个修饰符更早，没必要冒这个险。
            // 内部两处写入都有「值不变就不写」的闸门，所以只在真变化时多排一次。
            state.onMeasured(naturalHeight)
        }
    }

/**
 * 表头收起的嵌套滚动接线。
 *
 * ## 为什么是 `nestedScroll` 而不是 `pointerInput`
 *
 * 内容区的四个 tab 各自带着自己的 `LazyColumn` / `LazyVerticalGrid`，它们本来就要吃掉竖向手势。
 * 在表头或页面上挂 `pointerInput` 要么抢走滚动、要么被列表吃掉永远不触发（两条路都踩过，
 * 见 `PullToRefreshBox` 的注释）。`nestedScroll` 是平台给这件事准备的接口：
 * 它是**祖先**，在列表之前/之后各拿到一次位移，不需要知道列表是谁。
 *
 * ## 分工（用户要的手感）
 *
 * - **上滑**：`onPreScroll` 里**先**把位移吃给表头（列表这一下不动），
 *   收完（`collapsePx == -headerHeightPx`）后返回 `Offset.Zero`，位移照常落到列表；
 * - **下拉**：列表在顶部时吃不动这段位移，剩余位移会走到 `onPostScroll` ——
 *   那里才展开表头。所以「列表没到顶」时下拉是列表自己在滚，绝不会误展开表头。
 *
 * ## 为什么只认 `Drag`
 *
 * `Fling` 是松手后的惯性：表头若在惯性里被展开/收起，用户会觉得「我没拖它它自己动了」。
 * 而且惯性位移是逐帧衰减的，表头会跟着忽快忽慢地滑，比不动更难看。
 * 拖到一半松手 ⇒ 表头停在那里（不做吸附动画），列表照常甩动。
 *
 * ⚠️ Compose 1.8 把 `NestedScrollSource.Drag` 标成了 `@Deprecated`（"replaced by UserInput"，
 * 同一个值的改名，用 `javap` 查 `ui-android:1.8.3` 的 `NestedScrollSource$Companion` 可见）。
 * 这里仍写 `Drag`：本仓库 `PullToRefreshBox` 用的就是它，而且**语义没变**；
 * 等哪天全仓库一起换名时这里跟着换即可，单改一处只会让两处不一致。
 *
 * ## 一个已知（也是接受的）边界：拖动必须从列表起手
 *
 * 表头上**没有**挂任何 `pointerInput`（见上），所以手指落在表头上往下/往上拖不会滚动任何东西 ——
 * 那里压根没有可滚动节点，`nestedScroll` 也就无从派发。这与 material3 的
 * `TopAppBarScrollBehavior` 行为一致；表头收起来之后那块区域就是列表了，手势自然生效。
 */
internal class CollapsingHeaderConnection(
    private val state: CollapsingHeaderState,
) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // v4.1.12 真机修：**不再按 source 过滤**。
        //
        // 旧实现 `if (source != Drag) return Zero`，于是手指松开后的**惯性段**不参与收起 ——
        // 用户一甩，列表继续滚、表头却停在半收起（真机截图：两张快捷卡片只剩半截、tab 条还在，
        // 而列表已经滚过搜索框）。用户描述为「有时候会卡一两个组件」。
        //
        // 惯性位移也是用户输入（新版 API 里 `Drag` 已并入 `UserInput`），必须一起吃。
        // 代价是程序化 scrollToItem 也会带着收表头 —— 那正是期望行为。
        // available.y > 0 是下拉（内容往下走）：那是列表/`onPostScroll` 的活。
        if (available.y >= 0f) return Offset.Zero
        val headerPx = state.headerHeightPx
        if (headerPx <= 0) return Offset.Zero
        // 还能收多少（≤ 0）。= 0 表示已经收到底 ⇒ 返回 Zero 让列表接着滚。
        val canAbsorb = -headerPx.toFloat() - state.collapsePx
        if (canAbsorb >= 0f) return Offset.Zero
        val delta = maxOf(available.y, canAbsorb)
        if (delta >= 0f) return Offset.Zero
        state.collapseBy(delta)
        lastConsumeDirection = -1
        return Offset(0f, delta)
    }

    /**
     * 手势结束（含惯性跑完）⇒ 吸附到两端。
     *
     * 真机症状（用户：「向上滑动也有时候会卡」「卡一两个组件」）：表头停在**半收起**，
     * tab 条被拦腰截断、列表却已经在滚。原因是静止时允许了任意中间值，而这个中间值
     * 还可能与下一次布局量到的自然高度差几十 px（缓存高度与实测高度不一致），
     * 于是出现「看得见一条 tab 条、但往下拉没反应」的死区。
     *
     * 吸附放在 `onPostFling`（所有滚动都结束）而不是 `onPreFling`：后者在手指刚抬起时
     * 就派发，那时惯性还没跑完，吸附会把「还在收」的那一段直接抹掉、视觉上跳一下。
     */
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // 方向优先取**松手时的速度**：往下甩 = 想展开，往上甩 = 想收起。
        // 速度不够（慢慢拖到一半再松手）才用「最后一次消费的方向」，最后才就近吸附。
        val direction = when {
            available.y > FLING_DIRECTION_VELOCITY -> 1
            available.y < -FLING_DIRECTION_VELOCITY -> -1
            else -> lastConsumeDirection
        }
        state.snapToEnds(direction)
        return Velocity.Zero
    }

    /** 最后一次因表头而消费位移的方向：+1 展开、-1 收起、0 没有。 */
    private var lastConsumeDirection = 0

    private companion object {
        /** 判定「有明确方向的甩动」的速度阈值（px/s）。低于它按「就近吸附」处理。 */
        const val FLING_DIRECTION_VELOCITY = 300f
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        // 不按 source 过滤，理由同 [onPreScroll]：惯性段同样要能展开表头。
        // 不变量兜底：列表这次**真的滚动过**（consumed.y != 0）而表头还没收到底 ⇒
        // 立刻补到全收起，绝不停在「半边卡片 + 列表已滚」的中间态。
        if (consumed.y != 0f) {
            val headerPx = state.headerHeightPx
            if (headerPx > 0 && state.collapsePx > -headerPx.toFloat()) {
                state.collapseBy(-headerPx.toFloat() - state.collapsePx)
            }
        }
        // 只有「列表已经在顶部、这段下拉没人要」才会走到这里；available.y > 0 即下拉。
        if (available.y <= 0f) return Offset.Zero
        val current = state.collapsePx
        if (current >= 0f) return Offset.Zero
        val delta = minOf(available.y, -current)
        if (delta <= 0f) return Offset.Zero
        state.collapseBy(delta)
        lastConsumeDirection = 1
        return Offset(0f, delta)
    }
}

/**
 * 把「上滑收表头」挂到**整页最外层容器**上（要和 [Modifier.collapsingHeader] 用同一个 state）。
 *
 * 只处理竖向位移、只消费表头那一段，点按（tab / 快捷入口 / 列表行）完全不经过这里 ——
 * 嵌套滚动只承载**滚动位移**，不承载点击。
 */
fun Modifier.collapsingHeaderScroll(state: CollapsingHeaderState): Modifier =
    this.nestedScroll(CollapsingHeaderConnection(state))
