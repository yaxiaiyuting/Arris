/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust

/**
 * P1 · 大屏幕模式的方向策略**纯判定**（可脱离 Android 单测）。
 *
 * 需求是「进入时请求横屏，进入横屏后立刻放宽成 SENSOR，绝不长期强制锁横屏」。
 * 字面照做会踩一个坑，所以这里多了一层物理朝向门控，两个判定都在本文件里：
 *
 *  - 「进入时横屏」用 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`：用户此刻还竖着拿手机
 *    （按钮就在竖屏播放器里），只有强制横屏能让窗口立刻转过去。
 *  - 「立刻放宽成 SENSOR」不能无条件做：`SCREEN_ORIENTATION_SENSOR` 是**跟随传感器**
 *    的（它忽略系统的自动旋转锁），设备还竖着/平放在桌上时系统会在下一帧把窗口转回竖屏，
 *    大屏模式当场自我退出。所以放宽的前提是「设备物理上确实已经横过来了」
 *    —— 见 [isPhysicallyLandscape] / [shouldRelaxToSensor]。
 *
 * 门控只影响「什么时候放宽」，不影响「一定会放宽」：用户一把手机转横，方向立刻交给
 * 传感器；此后转回竖屏就是配置回到竖屏 → 退出大屏模式（需求里的第二条退出路径）。
 */
object BigScreenOrientation {

    /** `OrientationEventListener.ORIENTATION_UNKNOWN`（读不到方向时回调 -1）。 */
    const val ORIENTATION_UNKNOWN = -1

    /**
     * v1.8.0 · T4：自动进入大屏前的**稳定等待**（毫秒）。
     *
     * 用户快速把手机来回转时，`onConfigurationChanged` 会连续到达；平台自己已经有
     * 45° 滞回，但"转过去又马上转回来"仍会让大屏模式进进出出。这里的做法不是"丢弃
     * 短间隔内的转换"（那会丢掉最后一次、把状态留在错误的一侧），而是**合并**：
     * 每次方向变化都把等待重新计时，只有方向真正稳定 [AUTO_ENTER_SETTLE_MS] 之后才提交。
     * Compose 的 `LaunchedEffect(key)` 在 key 变化时自动取消上一个协程，天然就是这个语义。
     */
    const val AUTO_ENTER_SETTLE_MS = 250L

    /**
     * v1.8.0 · T4：当前应当写入 `Activity.requestedOrientation` 的方向意图。
     *
     * 不直接返回 `ActivityInfo` 常量是为了让判定能脱离 Android 单测（[BigScreenOrientationTest]）。
     * 映射在 `MainActivity.applyOrientationPolicy()` 里做，只有一处。
     */
    enum class DesiredOrientation {
        /** 手机竖屏锁定：auto-rotate 关且不在大屏（v1.7.0 及以前的默认行为）。 */
        PORTRAIT,

        /** 进入大屏的第一步：用户此刻还竖着拿手机，只有强制横屏能让窗口立刻转过去。 */
        SENSOR_LANDSCAPE,

        /** 跟随传感器（**不理会系统"自动旋转"锁**，见 [orientationFor] 的注释）。 */
        SENSOR,

        /** 不限制方向：平板 / 折叠展开 / 车机，避免信箱模式黑边。 */
        UNSPECIFIED,
    }

    /**
     * v1.8.0 · T4 / v2.0.0 · T1-A：方向策略的**唯一判定**。优先级从高到低：
     *
     *  1. **auto-rotate 关 + 大屏** → [DesiredOrientation.SENSOR_LANDSCAPE]：**保持横屏**
     *     （v2.0.0 · T1-A 新增，见下面「保持横屏」小节）；
     *  2. **大屏模式未放宽** → [DesiredOrientation.SENSOR_LANDSCAPE]：立刻转过去；
     *  3. **大屏模式已放宽** → [DesiredOrientation.SENSOR]：交给传感器，转回竖屏即退出；
     *  4. **auto-rotate 开** → [DesiredOrientation.SENSOR]；
     *  5. auto-rotate 关 → 大屏设备 UNSPECIFIED、手机 PORTRAIT（= v1.7.0 行为）。
     *
     * ### 「保持横屏」（v2.0.0 · T1-A，**偏离 v1.8.0 的 P1 契约**）
     *
     * v1.8.0 的契约是「绝不长期锁横屏」：大屏放宽成 SENSOR 后手机转回竖屏就退出大屏
     * （理由写的是"否则用户想退出就只剩按钮和返回键两条路"）。PCL110 真机反馈证明
     * 这条对 **auto-rotate 关的用户**是错的 —— 他是**显式**按 ⤢ 进来的，意图就是
     * "我要横屏的播放器"；而 SENSOR 会在手机稍微一歪（躺床上、放支架、手腕转动）时
     * 立刻翻回竖屏：配置变竖屏 ⇒ 退出大屏 ⇒ auto-rotate 关又把它锁成 PORTRAIT，
     * **即使把手机转回横向也回不来了**。用户原话：「我希望保持横屏的时候它自动切换竖屏模式了」。
     *
     * 所以判据按**用户意图的来源**分流：
     *  - auto-rotate **开** ⇒ 用户是"跟随手机方向"派：转横进大屏、转竖出大屏（双向，行为不变）；
     *  - auto-rotate **关** ⇒ 用户是"我要横屏"派：⤢ 进大屏后**保持横屏**（SENSOR_LANDSCAPE
     *    只让传感器决定左横还是右横，不会因手机一歪就翻竖屏），退出只有 ⤢ / 返回键两条路 ——
     *    与 v1.8.0 担心的"只剩两条路"一致，但那本来就是关掉这个开关时用户自己的选择。
     *
     * ⚠️ **应用内开关 ≠ 系统开关**：这里用 `SENSOR` / `SENSOR_LANDSCAPE` 而不是 `USER`，
     * 即应用自己决定要不要跟随传感器，**既不改写、也不读取**
     * `Settings.System.ACCELEROMETER_ROTATION`（PCL110 实测该系统锁就是"锁竖屏"；
     * 若改成读它，应用内开关会退化成"看着是开的、实际不转"）。
     */
    fun orientationFor(
        autoRotate: Boolean,
        bigScreen: Boolean,
        bigScreenRelaxed: Boolean,
        isLargeScreen: Boolean,
    ): DesiredOrientation = when {
        bigScreen && !autoRotate -> DesiredOrientation.SENSOR_LANDSCAPE
        bigScreen && !bigScreenRelaxed -> DesiredOrientation.SENSOR_LANDSCAPE
        bigScreen -> DesiredOrientation.SENSOR
        autoRotate -> DesiredOrientation.SENSOR
        isLargeScreen -> DesiredOrientation.UNSPECIFIED
        else -> DesiredOrientation.PORTRAIT
    }

    /**
     * v1.8.0 · T4：是否应该**自动进入**大屏模式。四个条件缺一不可：
     *
     *  - `autoRotate`：开关关着时转屏不触发（⤢ 按钮仍可手动进）；
     *  - `playerExpanded`：**触发范围限定在播放器界面**。首页 / 库 / 搜索转横屏不进大屏；
     *  - `windowLandscape`：窗口真的已经横过来了（旋转有延迟，提前进会在竖屏窗口里
     *    塞一个横屏两栏布局）；
     *  - `!bigScreen`：已经在里面就不重复进。
     *
     * "用户手动退出后不要立刻又自动进去"这件事**不在这里判**：它靠"触发源是
     * 方向变化 / 播放器展开这两个边沿"来保证 —— 手动退出（按钮 / 返回键）不改变这两个
     * 输入，所以不会重新触发。见 MainActivity 里那个 LaunchedEffect 的 key 选择。
     */
    fun shouldAutoEnterBigScreen(
        autoRotate: Boolean,
        playerExpanded: Boolean,
        windowLandscape: Boolean,
        bigScreen: Boolean,
    ): Boolean = autoRotate && playerExpanded && windowLandscape && !bigScreen

    /**
     * 设备是否物理横向。
     *
     * `OrientationEventListener` 的语义：0° = 自然方向竖直（竖屏正持），
     * 90° / 270° = 左右横向，180° = 倒竖。判定取 |45°..135°| 与 |225°..315°|
     * 这两段（即"偏离竖持超过 45°"），与 Android 平台自身的旋转 hysteresis 同量级；
     * 平放时传感器给不出稳定值，平台会回 [-1]，一律当作"不是横向"。
     */
    fun isPhysicallyLandscape(degrees: Int): Boolean {
        if (degrees == ORIENTATION_UNKNOWN) return false
        val d = ((degrees % 360) + 360) % 360
        return d in 45..135 || d in 225..315
    }

    /**
     * 是否可以把方向从"强制横屏"放宽成 `SCREEN_ORIENTATION_SENSOR`。
     *
     * 幂等：已经放宽过就不再重复设置（每次设置 requestedOrientation 都会触发一次
     * 方向重算）。没进大屏模式时永远不放宽。
     */
    fun shouldRelaxToSensor(bigScreen: Boolean, alreadyRelaxed: Boolean, degrees: Int): Boolean =
        bigScreen && !alreadyRelaxed && isPhysicallyLandscape(degrees)

    /**
     * 配置回调里是否应该因为"回到竖屏"而退出大屏模式。
     *
     * 这是「旋转回竖屏 = 退出大屏」这条路径的唯一判据：大屏模式期间配置变成竖屏，
     * 只有两种可能 —— 用户把手机转回去了，或系统拒绝了横屏请求；两种都应该退。
     */
    fun shouldExitOnConfiguration(bigScreen: Boolean, orientationLandscape: Boolean): Boolean =
        bigScreen && !orientationLandscape
}
