package com.takahashirinta.ncrust.audit

import com.google.gson.JsonParser
import com.takahashirinta.ncrust.lyric.LyricClock
import com.takahashirinta.ncrust.ui.player.currentLineIndex
import com.takahashirinta.ncrust.update.AppVersion
import com.takahashirinta.ncrust.update.CheckOutcome
import com.takahashirinta.ncrust.update.ForkRelease
import com.takahashirinta.ncrust.update.UpdateChecker
import com.takahashirinta.ncrust.update.UpdateInfo
import com.takahashirinta.ncrust.update.UpdateRecovery
import com.takahashirinta.ncrust.update.evaluateReleases
import java.io.File

fun main(args: Array<String>) {
    val feed = File(args.single()).readText()
    val previous = AppVersion(3, 7, 32)
    val current = AppVersion(3, 7, 33)
    val actual = "Arris-v3.7.33-lts-gpl.apk"
    val rows = JsonParser.parseString(feed).asJsonArray
    val release = rows.map { it.asJsonObject }.single { it["tag_name"].asString == "v3.7.33-gpl" }
    val asset = release["assets"].asJsonArray.single().asJsonObject
    check(asset["name"].asString == actual)
    check(ForkRelease.parseAsset(actual) == null)
    val outcome = evaluateReleases(listOf(feed), previous)
    check(outcome == CheckOutcome.UpToDate)
    println("LTS01 archived feed: releases=${rows.size()}, asset=${asset["name"].asString}, 3.7.32 -> $outcome")

    // Hold the asset URL, id, size and version tag constant. Change only the display name.
    asset.addProperty("name", "Arris-v3.7.33-gpl.apk")
    val renamed = evaluateReleases(listOf(rows.toString()), previous) as CheckOutcome.Found
    check(renamed.info.version == current && renamed.info.assetUrl == asset["browser_download_url"].asString)
    println("LTS01 name-only control: 3.7.32 -> ${renamed.info.version}, same URL and asset id")

    val cachedMainline = UpdateInfo(
        AppVersion(4, 0, 14), "Arris-v4.0.14-gpl.apk", "https://example.invalid/main.apk",
        100, "v4.0.14-gpl", null, null, null
    )
    check(!UpdateChecker.sameChannel(cachedMainline.releaseTag, cachedMainline.version, current))
    val restored = UpdateRecovery.restore(cachedMainline, current)
    check(restored == cachedMainline)
    println("LTS03 cached mainline: fresh channel gate=false, cold restore keeps ${restored?.version}")

    val starts = longArrayOf(2_000L, 10_000L, 13_000L)
    val before = 10_200L
    val seekTo = 9_500L
    val shown = LyricClock.displayPosition(before, seekTo, starts, sweeping = false)
    check(shown == before)
    check(currentLineIndex(seekTo, starts) == 0 && currentLineIndex(shown, starts) == 1)
    val atNextBoundary = LyricClock.displayPosition(shown, 10_000L, starts, sweeping = false)
    check(atNextBoundary == before)
    println("LTS04 short backward seek: audio=$seekTo -> row=0, display=$shown -> row=1; stays at $atNextBoundary on the re-crossing")
}
