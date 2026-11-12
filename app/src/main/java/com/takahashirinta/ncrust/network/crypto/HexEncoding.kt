/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.network.crypto

/** 小写十六进制，无分隔符。可直接编码字节数组前缀。 */
@OptIn(ExperimentalStdlibApi::class)
internal fun ByteArray.lowerHex(endIndex: Int = size): String = toHexString(endIndex = endIndex)
