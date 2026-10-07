package com.takahashirinta.ncrust.ui.i18n

import android.content.Context
import androidx.compose.runtime.compositionLocalOf

data class LanguagePreset(
    val code: String,
    val displayName: String,
    val strings: Strings
)

val languagePresets: List<LanguagePreset> = listOf(
    LanguagePreset("zh-CN", "简体中文", zhCN),
    LanguagePreset("zh-TW", "繁體中文", zhTW),
    LanguagePreset("en-US", "English", en),
    LanguagePreset("ja-JP", "日本語", jpJP),
    LanguagePreset("ja-MY", "万葉仮名", jpMY),
    LanguagePreset("ko-KP", "조선어", koNK),
    LanguagePreset("de-DE", "Deutsch", deDE),
    LanguagePreset("ru-RU", "Русский", ruRU),
)

val LocalStrings = compositionLocalOf { zhCN }

private const val PREFS_NAME = "ncrust_settings"
private const val KEY_LANGUAGE = "language_code"

/**
 * v3.4.8：当前语言的**进程内镜像**。
 *
 * ## 为什么需要它
 *
 * B 站字幕语言偏好的 `auto` 档语义是「跟随应用语言」，而取词路径
 * （`BiliSourceProvider.fetchLyric`）是一个**没有 `Context` 的单例**，
 * 拿不到 [getSavedLanguageCode]。
 *
 * ## 为什么放在这里而不是 `BiliPrefs`
 *
 * 键 `language_code` 的归属是这个文件（唯一写入点是 [saveLanguageCode]）。
 * 让 `BiliPrefs` 去读一个属于 i18n 的键，等于凭空造出第二条读路径 ——
 * 那正是本项目在多处踩过的「同一个前提有两种写法」。
 *
 * ## 与既有读取路径的关系
 *
 * [getSavedLanguageCode] **一行未动**，仍然是所有带 `Context` 的调用点
 * （`PlaybackService` / `PlayerViewModel` / Widget / 设置页）的唯一入口。
 * 镜像只是给「拿不到 Context」的那一处补的一个**只读**视图；
 * 写入仍然只有 [saveLanguageCode] 一个口（它同时刷新镜像，盘上与内存不会漂移）。
 */
@Volatile
private var languageCodeMirror: String = "zh-CN"

/** 冷启动时调用一次（`MainActivity` 与 `BiliPrefs.init` 同处）。 */
fun initLanguageMirror(context: Context) {
    languageCodeMirror = getSavedLanguageCode(context)
}

/** 当前语言代码（**读内存**，可安全地在组合期与无 `Context` 的路径上调用）。 */
fun currentLanguageCode(): String = languageCodeMirror

/** 只给单测用：直接改进程内镜像（**不落盘**）。 */
internal fun setLanguageCodeForTest(code: String) {
    languageCodeMirror = code
}

fun getSavedLanguageCode(context: Context): String =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_LANGUAGE, "zh-CN") ?: "zh-CN"

fun saveLanguageCode(context: Context, code: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_LANGUAGE, code).apply()
    // 先落盘、再改内存（与 `BiliPrefs.setEnabled` 同序）：反过来的话写盘失败会让
    // 本次进程按新语言渲染、下次冷启动又回到旧语言。
    languageCodeMirror = code
}

fun stringsForCode(code: String): Strings =
    languagePresets.find { it.code == code }?.strings
        // 旧版 "en-UK" 已并入 "en-US"，保留映射避免老用户语言设置静默回退中文
        ?: if (code == "en-UK") en else zhCN
