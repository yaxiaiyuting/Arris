package com.takahashirinta.ncrust.ui.i18n

data class CelebrationStrings(
    val dateTitle: String, val dateHint: String, val notSet: String,
    val enabledTitle: String, val enabledHint: String,
    val animationTitle: String, val animationHint: String, val previewTitle: String,
    val monthLabel: String, val dayLabel: String, val save: String, val clear: String,
    val cancel: String, val close: String, val continueListening: String, val replay: String,
    val micStart: String, val micListening: String, val micHint: String,
    val micDenied: String, val micTimeout: String, val micUnavailable: String, val manualBlow: String,
    val eggTitle: String, val eggCaption: String, val openEgg: String, val taps: String,
    val titles: List<String>, val captions: List<String>, val wishes: List<String>,
    val micCalibrating: String,
) {
    fun remaining(count: Int) = taps.replace("%d", count.toString())
}

/** One group per locale; birthday data and wish text never come from an account API. */
fun celebrationFor(code: String): CelebrationStrings {
    var labels: String
    val titles: String
    val captions: String
    val wishes: String
    when (code) {
        "zh-CN" -> {
            labels = "生日|只保存月日；2月29日在非闰年按2月28日庆祝。可随时删除。|尚未设置|生日祝福|生日当天打开软件时庆祝，每年一次。|彩蛋动效|控制生日与隐藏彩蛋；关闭后保留静态祝福。|预览生日祝福|月|日|保存|删除生日|取消|关闭|继续听歌|再看一次|用吹气许愿|正在听，请轻轻吹气…|仅在这次许愿时使用麦克风，约8秒；不保存或上传音频。也可点按吹灭。|麦克风未获授权，可以点按吹灭。|没有识别到吹气，可以再试一次或点按吹灭。|麦克风暂不可用，可以点按吹灭。|点一下吹灭|自由听众席|为自由，也为每一首好音乐。|打开彩蛋|再点%d次，听众席见。"
            titles = "有一份甜，送给你。|先把甜甜的放好。|点亮小小的愿望。|许个愿吧。|愿望，已经出发。|愿望，已经出发。|这一刻，为你绽放。|生日快乐。|生日快乐。"
            captions = "Tux 正在赶来的路上。|这份小惊喜，只为今天的你。|一盏烛光，一点期待。|慢慢来。想好了，再轻轻吹一口气。|把这一口气，交给好事发生。|灯光暗了下来。|烟花正从夜色里升起。|这一场小小的烟花，为你而开。|这一场小小的烟花，为你而开。"
            wishes = "愿你耳边，常有喜欢的歌。|愿你心里，常有值得期待的事。|新的一岁，按自己的节奏发光。"
        }
        "zh-TW" -> {
            labels = "生日|只儲存月日；2月29日在非閏年按2月28日慶祝。可隨時刪除。|尚未設定|生日祝福|生日當天開啟軟體時慶祝，每年一次。|彩蛋動畫|控制生日與隱藏彩蛋；關閉後保留靜態祝福。|預覽生日祝福|月|日|儲存|刪除生日|取消|關閉|繼續聽歌|再看一次|用吹氣許願|正在聽，請輕輕吹氣…|僅在這次許願時使用麥克風，約8秒；不儲存或上傳音訊。也可點按吹熄。|麥克風未獲授權，可以點按吹熄。|沒有辨識到吹氣，可以重試或點按吹熄。|麥克風暫時無法使用，可以點按吹熄。|點一下吹熄|自由聽眾席|為自由，也為每一首好音樂。|開啟彩蛋|再點%d次，聽眾席見。"
            titles = "有一份甜，送給你。|先把甜甜的放好。|點亮小小的願望。|許個願吧。|願望，已經出發。|願望，已經出發。|這一刻，為你綻放。|生日快樂。|生日快樂。"
            captions = "Tux 正在趕來的路上。|這份小驚喜，只為今天的你。|一盞燭光，一點期待。|慢慢來。想好了，再輕輕吹一口氣。|把這一口氣，交給好事發生。|燈光暗了下來。|煙火正從夜色裡升起。|這一場小小的煙火，為你而開。|這一場小小的煙火，為你而開。"
            wishes = "願你耳邊，常有喜歡的歌。|願你心裡，常有值得期待的事。|新的一歲，按自己的節奏發光。"
        }
        "ja-JP", "ja-MY" -> {
            labels = "誕生日|月と日のみ保存。閏年以外の2月29日は2月28日にお祝いします。いつでも削除できます。|未設定|誕生日のお祝い|誕生日にアプリを開いたとき、年に一度お祝いします。|イースターエッグの動き|オフにすると静止画でお祝いします。|誕生日のお祝いを試す|月|日|保存|誕生日を削除|キャンセル|閉じる|音楽に戻る|もう一度|息を吹いて願う|そっと息を吹いてください…|今回だけ約8秒マイクを使います。音声の保存や送信はしません。タップでも消せます。|マイクが許可されていません。タップでも消せます。|息を検出できませんでした。再試行かタップで消せます。|マイクを使えません。タップで消せます。|タップして消す|自由なリスナーの席|自由と、すべての素敵な音楽のために。|隠し画面を開く|あと%d回で、リスナーの席へ。"
            titles = "甘い贈り物をあなたに。|ケーキをそっと置いて。|小さな願いに灯りを。|願いごとをどうぞ。|願いが旅立ちました。|願いが旅立ちました。|あなたのために咲く。|お誕生日おめでとう。|お誕生日おめでとう。"
            captions = "Tux が向かっています。|今日のあなたへの小さな贈り物。|ろうそくの灯りに、期待を込めて。|ゆっくり考えて、そっと息を吹いてね。|素敵なことが訪れますように。|灯りが消えていきます。|夜空に花火が昇ります。|この小さな花火を、あなたに。|この小さな花火を、あなたに。"
            wishes = "好きな歌が、いつもそばに。|楽しみなことが、心にありますように。|新しい一年も、あなたのリズムで。"
            if (code == "ja-MY") {
                // Keep this locale's existing concise kanji-led voice.
                labels = "誕生日|月 日 而已 保存、非閏年 二月廿九 者 二月廿八 祝。何時 除 可。|未設|誕生日 祝|誕生日 起 時、年 一度 祝。|彩蛋 動|關 後 靜 祝 保。|誕生日 祝 試|月|日|保存|誕生日 除|取消|閉|音樂 返|再 見|息 吹 願|輕 息 吹…|此 度 約八秒 麥 使用、音 保存 傳送 無。點 消 可。|麥 許 無、點 消 可。|息 検 無、再 試 又 點 消 可。|麥 使用 不可、點 消 可。|點 消|自由 聴者 席|自由、及 每 良 音樂 爲。|彩蛋 開|再%d度 點、聴者 席 見。"
            }
        }
        "ko-KP" -> {
            labels = "생일|월과 일만 저장합니다. 윤년이 아닌 해의 2월 29일은 2월 28일에 축하합니다. 언제든 지울 수 있습니다.|설정 안 함|생일 축하|생일에 앱을 열면 해마다 한 번 축하합니다.|숨은 화면 움직임|끄면 정지 화면으로 축하합니다.|생일 축하 미리보기|월|일|저장|생일 지우기|취소|닫기|음악으로 돌아가기|다시 보기|입김으로 소원 빌기|살짝 입김을 불어 주세요…|이번에만 약 8초 동안 마이크를 사용합니다. 소리는 저장하거나 전송하지 않습니다. 눌러서 끌 수도 있습니다.|마이크 권한이 없습니다. 눌러서 끌 수 있습니다.|입김을 감지하지 못했습니다. 다시 하거나 눌러서 꺼 주세요.|마이크를 쓸 수 없습니다. 눌러서 꺼 주세요.|눌러서 끄기|자유로운 청취자석|자유와 모든 좋은 음악을 위하여.|숨은 화면 열기|%d번 더 누르면 청취자석으로."
            titles = "달콤한 선물을 드려요.|케이크를 살며시 놓아요.|작은 소원에 불을 켜요.|소원을 빌어 보세요.|소원이 출발했어요.|소원이 출발했어요.|당신을 위해 피어나요.|생일을 축하합니다.|생일을 축하합니다."
            captions = "Tux가 오고 있어요.|오늘의 당신을 위한 작은 선물.|촛불에 기대를 담아요.|천천히 생각하고 살짝 불어 주세요.|좋은 일이 찾아오기를.|불빛이 사라집니다.|밤하늘로 불꽃이 올라갑니다.|이 작은 불꽃은 당신을 위해.|이 작은 불꽃은 당신을 위해."
            wishes = "좋아하는 노래가 늘 곁에 있기를.|기대할 일이 마음에 늘 있기를.|새로운 한 해도 당신의 박자로."
        }
        "de-DE" -> {
            labels = "Geburtstag|Nur Monat und Tag. Der 29. Februar wird sonst am 28. gefeiert. Jederzeit löschbar.|Nicht festgelegt|Geburtstagsgruß|Einmal im Jahr beim Öffnen am Geburtstag feiern.|Animationen der Überraschungen|Aus zeigt einen stillen Geburtstagsgruß.|Geburtstagsgruß ansehen|Monat|Tag|Speichern|Geburtstag löschen|Abbrechen|Schließen|Weiter Musik hören|Noch einmal|Mit Pusten wünschen|Jetzt sanft pusten…|Das Mikrofon wird nur jetzt etwa 8 Sekunden genutzt. Kein Speichern oder Senden. Tippen funktioniert auch.|Kein Mikrofonzugriff. Tippen funktioniert auch.|Kein Pusten erkannt. Erneut versuchen oder tippen.|Mikrofon nicht verfügbar. Tippen funktioniert auch.|Kerzen antippen und löschen|Platz für freie Zuhörer|Für Freiheit und jede gute Musik.|Überraschung öffnen|Noch %d-mal tippen."
            titles = "Etwas Süßes für dich.|Den Kuchen sanft abstellen.|Ein Licht für deinen Wunsch.|Wünsch dir etwas.|Dein Wunsch ist unterwegs.|Dein Wunsch ist unterwegs.|Dieser Moment gehört dir.|Alles Gute zum Geburtstag.|Alles Gute zum Geburtstag."
            captions = "Tux ist auf dem Weg.|Eine kleine Überraschung für dich.|Kerzenlicht voller Vorfreude.|Lass dir Zeit und puste dann sanft.|Mögen gute Dinge geschehen.|Das Licht wird dunkel.|Feuerwerk steigt in die Nacht.|Dieses kleine Feuerwerk ist für dich.|Dieses kleine Feuerwerk ist für dich."
            wishes = "Möge deine Lieblingsmusik dich begleiten.|Möge es immer etwas zum Freuen geben.|Dein neues Jahr, dein eigener Rhythmus."
        }
        "ru-RU" -> {
            labels = "День рождения|Только месяц и день. В невисокосный год 29 февраля отмечаем 28-го. Можно удалить.|Не задан|Поздравление|Раз в год при открытии приложения в день рождения.|Анимация сюрпризов|При отключении остаётся статичное поздравление.|Посмотреть поздравление|Месяц|День|Сохранить|Удалить дату|Отмена|Закрыть|Вернуться к музыке|Ещё раз|Задуть свечи дыханием|Теперь легонько подуйте…|Микрофон включится примерно на 8 секунд. Звук не сохраняется и не отправляется. Можно нажать.|Нет доступа к микрофону. Можно задуть нажатием.|Дыхание не распознано. Повторите или нажмите.|Микрофон недоступен. Можно задуть нажатием.|Задуть нажатием|Место свободного слушателя|За свободу и каждую прекрасную песню.|Открыть сюрприз|Нажмите ещё %d раз."
            titles = "Немного сладкого для вас.|Поставим торт поудобнее.|Зажжём маленькое желание.|Загадайте желание.|Желание уже в пути.|Желание уже в пути.|Этот миг — для вас.|С днём рождения!|С днём рождения!"
            captions = "Tux уже идёт.|Маленький сюрприз для вас.|Свет свечи и немного надежды.|Не спешите. Затем легонько подуйте.|Пусть случаются хорошие вещи.|Свет гаснет.|Фейерверк взлетает в ночь.|Этот маленький салют — для вас.|Этот маленький салют — для вас."
            wishes = "Пусть рядом звучат любимые песни.|Пусть всегда будет чего ждать с радостью.|В новом году жизни — в своём ритме."
        }
        else -> {
            labels = "Birthday|Only month and day are saved. February 29 is celebrated on February 28 in other years. Delete anytime.|Not set|Birthday wishes|Celebrate once a year when you open the app on your birthday.|Easter egg animation|Turn off for a still greeting.|Preview birthday wishes|Month|Day|Save|Delete birthday|Cancel|Close|Back to music|Watch again|Make a wish with a puff|Listening — blow gently…|The microphone is used only now, for about 8 seconds. Audio is never saved or sent. Tapping works too.|Microphone permission was not granted. You can tap instead.|No puff detected. Try again or tap instead.|Microphone unavailable. You can tap instead.|Tap to blow out|A seat for free listeners|For freedom, and every beautiful song.|Open Easter egg|Tap %d more times."
            titles = "Something sweet for you.|Let's set the cake down.|A little light for your wish.|Make a wish.|Your wish is on its way.|Your wish is on its way.|This moment blooms for you.|Happy birthday.|Happy birthday."
            captions = "Tux is on the way.|A little surprise, just for you.|A candle and a little hope.|Take your time. Then blow gently.|Let good things find you.|The lights fade away.|Fireworks rise into the night.|This little firework show is for you.|This little firework show is for you."
            wishes = "May your favourite songs stay close.|May there always be something to look forward to.|A new year, shining at your own pace."
        }
    }
    val l = labels.split('|')
    val calibration=when(code) {"zh-CN"->"请先安静片刻，正在准备麦克风…";"zh-TW"->"請先安靜片刻，正在準備麥克風…";"ja-JP"->"少し静かにお待ちください…";"ja-MY"->"暫 靜 待…";"ko-KP"->"잠시 조용히 기다려 주세요…";"de-DE"->"Einen Moment bitte still sein…";"ru-RU"->"Немного тишины, готовим микрофон…";else->"A quiet moment — preparing the microphone…"}
    return CelebrationStrings(l[0],l[1],l[2],l[3],l[4],l[5],l[6],l[7],l[8],l[9],l[10],l[11],l[12],l[13],l[14],l[15],l[16],l[17],l[18],l[19],l[20],l[21],l[22],l[23],l[24],l[25],l[26],titles.split('|'),captions.split('|'),wishes.split('|'),calibration)
}
