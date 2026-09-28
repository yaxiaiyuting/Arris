/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P1：设置页的 B 站账号块（铁律 23：复用既有账号块形状，不新建登录体系）。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.bili.BiliAuthStore
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 设置页「账号与登录」里的 B 站账号块（v3.2.0 · P1）。
 *
 * 与 `QqAccountBlock` **逐块同形**（标题 → 一行「音源名 + 状态」→ 右侧登录/登出 →
 * 未登录时的说明）—— 铁律 23 要求「复用现有扫码登录框架」，这条同样适用于设置页的形状：
 * 用户在同一个页面里看到两块长得一样的账号区，才不需要重新学一次。
 *
 * ## 三条与 QQ 块不同的地方（都是有理由的）
 *
 * 1. **没有「手机号登录 / 网页登录」第二个入口**：B 站侧本轮只做扫码
 *    （探针 `probe-bili-login.md` 只实测了二维码那一条路）。
 * 2. **状态行不显示会员角标**：`isVip` 的数据来自 `nav` 的 `vipStatus/vipType`，
 *    而**登录态**下这两个字段的形状本轮未实测（无大会员账号）⇒ 不显示比显示一个
 *    可能错的角标好（与 QQ 块「取不到就不显示」同一条纪律）。
 * 3. **未登录时的说明是「音质上限」而不是「扫码可用性」**：B 站扫码不依赖腾讯网关，
 *    没有 QQ 那类「服务不可用就换网页登录」的问题；用户真正需要知道的是
 *    「登录能换来什么」——而这件事本轮**只验证了一半**，所以文案如实写「未验证」。
 *
 * @param onLogin 打开扫码浮层（浮层本体在 [BiliQrLoginDialog]）。
 */
@Composable
internal fun BiliAccountBlock(
    accountTitle: String,
    brand: String,
    notLoggedInText: String,
    loginActionText: String,
    logoutText: String,
    /** 未登录时的音质说明（如实写「是否解锁无损未验证」）。 */
    qualityNote: String,
    onLogin: () -> Unit,
) {
    val context = LocalContext.current
    // 读的是进程内镜像（`BiliAuthStore.init` 在冷启动时播过种），组合期零 IO。
    var loggedIn by remember { mutableStateOf(BiliAuthStore.isLoggedIn(context)) }
    var uname by remember { mutableStateOf(BiliAuthStore.uname(context)) }

    SectionTitle(accountTitle)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!loggedIn) onLogin() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MetroText(text = brand, style = LocalMetroTypography.current.bodyLarge)
            Spacer(Modifier.height(4.dp))
            val status = when {
                !loggedIn -> notLoggedInText
                // 昵称拿不到时也要显示「已登录」——不能因为一个可选的展示字段
                // 就把登录态说成未登录（那会让用户以为登录失败）。
                else -> uname ?: ""
            }
            if (status.isNotEmpty()) {
                MetroText(
                    text = status,
                    style = LocalMetroTypography.current.bodySmall,
                    color = LocalMetroColors.current.onSurfaceVariant,
                )
            }
        }
        if (loggedIn) {
            MetroText(
                text = logoutText,
                style = LocalMetroTypography.current.bodyLarge,
                color = LocalMetroColors.current.primary,
                modifier = Modifier
                    .clickable {
                        BiliAuthStore.clear(context)
                        loggedIn = false
                        uname = null
                    }
                    .padding(8.dp),
            )
        } else {
            MetroText(
                text = loginActionText,
                style = LocalMetroTypography.current.bodyLarge,
                color = LocalMetroColors.current.primary,
                modifier = Modifier
                    .clickable(onClick = onLogin)
                    .padding(8.dp),
            )
        }
    }
    if (!loggedIn) {
        MetroText(
            text = qualityNote,
            style = LocalMetroTypography.current.bodySmall,
            color = LocalMetroColors.current.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}
