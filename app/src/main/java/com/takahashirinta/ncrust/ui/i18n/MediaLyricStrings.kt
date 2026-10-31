/*
 * Copyright (c) 2026 yaxiaiyuting.
 * Arris fork contribution, licensed under GPLv3; see LICENSE.
 */

package com.takahashirinta.ncrust.ui.i18n

data class MediaLyricStrings(
    val bilingual: String, val bilingualHint: String, val primary: String,
    val primaryHint: String, val original: String, val translation: String,
)

internal fun mediaLyricStringsForCode(code: String): MediaLyricStrings = when (code) {
    "zh-CN" -> MediaLyricStrings("双语双层歌词", "媒体面板与实况窗同步显示原文和译文；没有译文时显示原文与歌名／歌手。需开启媒体面板显示歌词。", "媒体歌词主行", "主行大字、副行小字；系统灵动岛收起时可能只显示主行。", "原文在上", "译文在上")
    "zh-TW" -> MediaLyricStrings("雙語雙層歌詞", "媒體面板與即時更新同步顯示原文和譯文；沒有譯文時顯示原文與歌名／歌手。需開啟媒體面板顯示歌詞。", "媒體歌詞主行", "主行大字、副行小字；系統動態島收起時可能只顯示主行。", "原文在上", "譯文在上")
    "ja-JP", "ja-MY" -> MediaLyricStrings("二言語・二段の歌詞", "メディアパネルとライブ更新に原文と翻訳を同期表示します。翻訳がない場合は原文のみ。メディアの歌詞表示を有効にしてください。", "メディア歌詞の主行", "主行は大きく、副行は小さく表示。折りたたんだ表示では主行のみの場合があります。", "原文を上に", "翻訳を上に")
    "ko-KP" -> MediaLyricStrings("두 언어 두 줄 가사", "미디어 패널과 실시간 알림에 원문과 번역을 함께 표시합니다. 번역이 없으면 원문만 표시합니다. 미디어 가사 표시를 켜십시오.", "미디어 가사 첫 줄", "첫 줄은 크게, 둘째 줄은 작게 표시합니다. 축소 상태에서는 첫 줄만 보일 수 있습니다.", "원문 먼저", "번역 먼저")
    "de-DE" -> MediaLyricStrings("Zweisprachige Liedtexte", "Original und Übersetzung synchron im Medienpanel und in Live-Updates. Ohne Übersetzung nur das Original. Medien-Liedtexte müssen aktiviert sein.", "Erste Liedtextzeile", "Hauptzeile groß, zweite Zeile klein. Die kompakte Systemanzeige zeigt eventuell nur die Hauptzeile.", "Original oben", "Übersetzung oben")
    "ru-RU" -> MediaLyricStrings("Двуязычный текст в две строки", "Оригинал и перевод синхронно в медиапанели и Live Updates. Без перевода — только оригинал. Включите текст в медиапанели.", "Основная строка текста", "Основная строка крупнее. В свёрнутом системном индикаторе может быть видна только она.", "Оригинал сверху", "Перевод сверху")
    else -> MediaLyricStrings("Bilingual lyrics", "Show original and translation together in media panels and Live Updates. Without translation, keep original lyrics and song/artist. Enable media-panel lyrics first.", "Primary media lyric", "Larger primary line, smaller secondary line. The collapsed system capsule may show only the primary line.", "Original first", "Translation first")
}
