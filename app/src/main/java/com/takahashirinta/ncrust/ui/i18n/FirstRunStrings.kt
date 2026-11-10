package com.takahashirinta.ncrust.ui.i18n

internal data class FirstRunStrings(
    val title: String,
    val summary: String,
    val accountHint: String,
    val playbackHint: String,
    val appearanceHint: String,
    val start: String,
    val skip: String,
)

internal fun firstRunStringsForCode(code: String): FirstRunStrings = when (code) {
    "zh-CN" -> FirstRunStrings(
        "欢迎使用 Arris", "可以先登录音源，再按喜好配置播放和界面。所有选项都能在「用户」页随时调整。",
        "连接网易云音乐或 QQ 音乐", "按网络选择音质和播放偏好",
        "主题配色、波形和界面律动", "开始听歌", "稍后设置",
    )
    "zh-TW" -> FirstRunStrings(
        "歡迎使用 Arris", "可以先登入音源，再依喜好設定播放與介面。所有選項都能在「使用者」頁隨時調整。",
        "連接網易雲音樂或 QQ 音樂", "依網路選擇音質與播放偏好",
        "主題配色、波形和介面律動", "開始聽歌", "稍後設定",
    )
    "ja-JP", "ja-MY" -> FirstRunStrings(
        "Arris へようこそ", "音楽サービスにログインして、再生や外観を好みに合わせましょう。設定は後から変更できます。",
        "NetEase Cloud Music または QQ Music に接続", "回線ごとの音質と再生設定",
        "テーマ、波形、ビート連動", "音楽を聴く", "後で設定",
    )
    "ko-KP" -> FirstRunStrings(
        "Arris에 오신 것을 환영합니다", "음악 서비스에 로그인하고 재생과 화면을 원하는 대로 설정하세요. 설정은 언제든지 바꿀 수 있습니다.",
        "NetEase Cloud Music 또는 QQ Music 연결", "망에 따른 음질 및 재생 설정",
        "테마, 파형과 화면 율동", "음악 듣기", "나중에 설정",
    )
    "de-DE" -> FirstRunStrings(
        "Willkommen bei Arris", "Melde dich bei einem Musikdienst an und passe Wiedergabe und Design an. Du kannst alles später in den Einstellungen ändern.",
        "NetEase Cloud Music oder QQ Music verbinden", "Audioqualität je Netz und Wiedergabeoptionen",
        "Design, Wellenform und Rhythmus", "Musik hören", "Später einrichten",
    )
    "ru-RU" -> FirstRunStrings(
        "Добро пожаловать в Arris", "Войдите в музыкальный сервис и настройте воспроизведение и оформление. Настройки можно изменить позже.",
        "Подключите NetEase Cloud Music или QQ Music", "Качество звука для разных сетей и настройки воспроизведения",
        "Тема, волна и ритм", "Начать слушать", "Настроить позже",
    )
    else -> FirstRunStrings(
        "Welcome to Arris", "Connect a music account and set up playback and appearance. You can change these choices anytime in Settings.",
        "Connect NetEase Cloud Music or QQ Music", "Audio quality by network and playback preferences",
        "Theme, waveform and interface rhythm", "Start listening", "Set up later",
    )
}
