/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 移植自上游 Ncrust c14b1be / 3e462c4 的应用内更新链路。
 */

package com.takahashirinta.ncrust.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.takahashirinta.ncrust.network.crypto.lowerHex
import java.io.File
import java.security.MessageDigest

/** 用系统包管理器读取身份，更新校验规则由 [ApkVerification] 负责。 */
object ApkArchive {
    /** 已安装的本应用。读不到返回 null（调用方按「校验不了」处理，不放行）。 */
    fun readInstalled(context: Context): ApkIdentity? =
        read { context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES) }

    /** 磁盘上的 APK 文件。文件不是合法 APK / 读不到时返回 null。 */
    fun readArchive(context: Context, file: File): ApkIdentity? {
        if (!file.isFile) return null
        return read {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    private inline fun read(load: () -> PackageInfo?): ApkIdentity? {
        val info = runCatching(load).getOrNull() ?: return null
        return ApkIdentity(
            packageName = info.packageName,
            versionCode = info.longVersionCode,
            signatureDigests = signatureDigestsOf(info)
        )
    }

    /** 多签名取当前签名，单签名保留轮换历史。 */
    private fun signatureDigestsOf(info: PackageInfo): List<String> {
        val signing = info.signingInfo ?: return emptyList()
        val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners
        else signing.signingCertificateHistory
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures?.map { digest.digest(it.toByteArray()).lowerHex() }.orEmpty()
    }
}
