/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 2：把设置行组件从 `ui/screen/UserScreen.kt` 抽出来。
 *
 * ## 为什么必须抽（而不是复制一份）
 *
 * 一级页（卡片列表）与 7 个二级页都要用同一套行组件。如果二级页各写一份，
 * 「开关行的 touch 语义」「下拉的弹出锚点」就有多份实现，迟早漂移 ——
 * 而这两处各自都有实测踩坑记录（见 [SettingSwitchRow] / [MetroDropdownRow] 的 KDoc）。
 *
 * ## 抽取纪律：**逐字不动**
 *
 * 本文件的四个函数体是从 `UserScreen.kt` **原样搬过来**的（`private` → `internal`），
 * 参数、默认值、`toggleable(Role.Switch)` 语义、`MetroSelectorFlyout` 的锚点写法
 * 一个字节都没改 —— 所以阶段 2 的产物行为与阶段 1 完全一致。
 * 唯一新增的是 [SettingSwitchRow] / [MetroDropdownRow] 的可选 `enabled` 参数（默认 `true`），
 * 供阶段 4/5 的「置灰 + 原因」使用（既有调用点不传 ⇒ 行为不变）。
 *
 * 隐藏一律走「不挂载」而不是 `alpha=0`（AGENTS.md「Compose 触摸陷阱」第 1 条）：
 * 所以这里的 `enabled=false` 是**可见但不可交互**（置灰），要整行消失的调用点必须用 `if`。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.takahashirinta.kanesumi.controls.MetroSelectorFlyout
import io.github.takahashirinta.kanesumi.controls.MetroSwitch
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/** Groove 风分区标题：16sp semi-bold、上留白 4dp、左 16dp。 */
@Composable
internal fun SectionTitle(text: String) {
    MetroText(
        text,
        color = LocalMetroColors.current.onBackground,
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
    )
}

/**
 * 开关设置行：标题与 Switch 同一行垂直居中，描述（可选）另起一行。
 * 描述不参与对齐，避免 Switch 被顶到与描述顶部对齐。
 *
 * P2 · 无障碍与触控：
 *  - 整行挂 [toggleable] + [Role.Switch]：TalkBack 才能把「标题 + 开关」读成一个可切换
 *    控件。Kanesumi 的 MetroSwitch 是裸 Box + pointerInput、自身零 semantics，原先整行对
 *    无障碍服务不存在（用户页 3 个开关全走这里）。开关状态由 toggleable 写入的
 *    ToggleableState 播报 —— 由系统按当前语言朗读，比自造 stateDescription 文案更准，
 *    也不必新增 8 个语言文件的词条（本轮红线）。
 *  - 整行可点后命中区 = 52dp 高的整行，原先只有 52×28dp 的 Switch 本身。
 *  - 点在 Switch 上时回调会走两次：MetroSwitch 自己的 pointerInput 不消费 tap，父级
 *    toggleable 也会收到。但两次携带的都是同一个「取反后的目标值」，而 checked 是外部
 *    提升的状态、两处读到的都是同一次组合的值 —— 净效果仍是翻转一次；各调用点的副作用
 *    （写 prefs / 刷新 ViewModel / ArtistReco.setEnabled）都是幂等的。Kanesumi 不在本仓库
 *    版本控制内，不能改库让它消费这个事件（改了发布产物不可复现）。
 *
 * v2.8.0 阶段 2 新增的可选参数 [enabled]（默认 `true` = 与抽取前逐字节一致）：
 *  - `false` ⇒ 整行 `toggleable(enabled = false)`（不进命中测试、可被无障碍服务识别为禁用）
 *    + 标题降为 `onSurfaceVariant` + Switch 的回调换成空实现（MetroSwitch 是**无状态**的，
 *    checked 由外部传入，所以空回调的净效果就是「点了没反应」，而它本身仍然可见）。
 *  - **不**用 alpha 隐藏：AGENTS.md 触摸陷阱 #1 —— 看不见的节点照样吃事件。
 */
@Composable
internal fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            title,
            color = if (enabled) LocalMetroColors.current.onBackground
            else LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(16.dp))
        MetroSwitch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else { _ -> }
        )
    }
    if (description != null) {
        MetroText(
            description,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.caption,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
    }
}

/**
 * 单行下拉：左侧 label + 右侧「选中值 + ▼」，点击整行弹出垂直菜单。
 *
 * 多语言鲁棒：
 *  - label 用 weight(1f)，任何语言都能换行，不会挤到右侧值。
 *  - 选中值用 maxLines=1 + Ellipsis + widthIn(max=160dp)，极端长文会截断但不会撑破布局。
 *  - 下拉展开的菜单里每项独占一行，完整显示，用户始终能看到完整名字。
 *
 * v2.8.0 阶段 2 新增的可选参数 [enabled]（默认 `true`）：`false` 时整行不可点、
 * 标题降为 `onSurfaceVariant`，但**仍然挂载**（软依赖的语义是「改了没用」而不是「不存在」，
 * 见 `ui/settings/SettingsVisibility.kt` 的 `GatingReason.SWEEP_ANIMATION_INACTIVE`）。
 */
@Composable
internal fun MetroDropdownRow(
    label: String,
    selectedIndex: Int,
    options: List<String>,
    hint: String? = null,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Box 只包住下拉行本身：MetroSelectorFlyout 需要锚在这一行上，
    // 提示文案放在 Box 之外，避免把弹出菜单的锚点推下去。
    Column {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    label,
                    color = if (enabled) LocalMetroColors.current.onBackground
                    else LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                MetroText(
                    options.getOrElse(selectedIndex) { "" },
                    color = if (enabled) LocalMetroColors.current.primary
                    else LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp)
                )
                MetroIcon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = LocalMetroColors.current.onSurfaceVariant,
                    sizeDp = 20.dp,
                )
            }
            // UWP ComboBox 移植:选中项落回锚点原位,菜单从锚点双向展开。
            MetroSelectorFlyout(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                options = options,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
            )
        }
        if (hint != null) {
            MetroText(
                hint,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = TextStyle(fontSize = 12.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            )
        }
    }
}

/** 缓存占用的分项行（v2.0.0 · T3）：左侧名称、右侧数字，缩进一级、弱化显示。 */
@Composable
internal fun CacheUsageLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            label,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = TextStyle(fontSize = 12.sp),
            modifier = Modifier.weight(1f)
        )
        MetroText(
            value,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = TextStyle(fontSize = 12.sp)
        )
    }
}

/**
 * 「左侧标题 + 右侧箭头」的可点行（关于 / 离线缓存管理 / 后台运行 三处共用）。
 *
 * v2.8.0 阶段 3 新增的**组件**（不是搬运）：抽取前这三处各自手写了同一个 8 行 `Row`
 * （`UserScreen.kt:708-735` / `:820-839` / `:845-864`），视觉逐字一致
 * （`padding(h=16, v=14)`、15sp、`ChevronRight` 20dp、`onSurfaceVariant`）。
 *
 * [accent] = true 时标题用主题色（「清除缓存」那一路的既有写法）；
 * [trailing] 非空时右侧渲染一段主题色文字**替代**箭头（「清除缓存」四项分账的既有写法）。
 */
@Composable
internal fun SettingActionRow(
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false,
    trailing: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            label,
            color = if (accent) LocalMetroColors.current.primary
            else LocalMetroColors.current.onBackground,
            style = TextStyle(fontSize = 15.sp),
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            MetroText(
                trailing,
                color = LocalMetroColors.current.primary,
                style = TextStyle(fontSize = 15.sp)
            )
        } else {
            MetroIcon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = LocalMetroColors.current.onSurfaceVariant,
                sizeDp = 20.dp,
            )
        }
    }
}
