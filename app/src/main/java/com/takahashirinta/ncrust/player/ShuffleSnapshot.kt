/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import com.google.gson.annotations.SerializedName

/** 与完整队列身份绑定，旧版本无记录或队列变化时回落到新洗牌。 */
data class ShuffleSnapshot(
    @SerializedName("queue_keys") val queueKeys: List<String>? = null,
    @SerializedName("order") val order: List<Int>? = null,
    @SerializedName("position") val position: Int = 0,
) {
    fun matches(keys: List<String>, currentIndex: Int): Boolean {
        val indices = order ?: return false
        return keys.isNotEmpty() && queueKeys == keys && indices.size == keys.size &&
            indices.toSet() == keys.indices.toSet() && position in indices.indices &&
            indices[position] == currentIndex
    }
}
