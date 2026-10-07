/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.model.ArtistDetail
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 首页「音乐人推荐」卡片（v1.4.0 · 任务 B）。
 *
 * 触发判定在 [com.takahashirinta.ncrust.reco.ArtistReco.shouldShow]（本地计算），
 * 这里只负责把艺人主体拉出来渲染。艺人信息用 [RetrofitClient.api] 的
 * `api/artist/albums/{id}` —— 一次请求同时拿到 artist 主体（名称/头像）与专辑数。
 * 不用 `/eapi/v1/artist/detail`：B0 实测它已 400 失效。
 * 样式沿用首页既有信息卡：封面 72dp、副标题一行省略号；**圆角**自 v2.5.0 · B 起生效
 * —— 旧设计正典是「直角、无圆角」，那一版这里没有任何裁切。
 */
@Composable
fun ArtistRecoCard(
    artistId: Long,
    modifier: Modifier = Modifier,
    onClick: (Long) -> Unit
) {
    val strings = LocalStrings.current
    var artist by remember(artistId) { mutableStateOf<ArtistDetail?>(null) }

    LaunchedEffect(artistId) {
        artist = runCatching { RetrofitClient.api.getArtistAlbums(artistId).artist }.getOrNull()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(artistId) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                // v2.5.0 · B：占位底板也要一起裁圆，否则方形底会从圆形头像的四角露出来。
                .clip(AppShapes.full)
                .background(LocalMetroColors.current.surfaceVariant)
        ) {
            val pic = artist?.picUrl
            if (!pic.isNullOrEmpty()) {
                Image(
                    painter = rememberAsyncImagePainter(CoverUrls.large(pic)),
                    contentDescription = null,
                    // 艺人头像是人像 ⇒ 圆形（AppShapes.full）+ 1dp 描边，不套用方封面的尺寸规则。
                    modifier = Modifier
                        .fillMaxSize()
                        .appCoverFrame(shape = AppShapes.full),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MetroText(
                text = artist?.name ?: strings.artistRecoTitle,
                color = LocalMetroColors.current.onBackground,
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            MetroText(
                text = strings.artistRecoDesc,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = TextStyle(fontSize = 13.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
