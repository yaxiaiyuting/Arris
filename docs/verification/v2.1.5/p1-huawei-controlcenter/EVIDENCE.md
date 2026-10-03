# 华为控制中心媒体卡 · 真机取证（v2.1.5 期间，WGR-W09 / HarmonyOS 4.2 / Android 12）

## 1. 卡片「未在播放」（Ncrust 正在播放时）
命令: adb -s WVQ6R22124000968 shell uiautomator dump  → ui-dump-ncrust-card.xml

content-desc="未在播放" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="true" password="false" selected="false" bounds="[1723,208][2106,497]" xpos="[0,0][0,0]"><node index="0" text="" resource-id="com.android.systemui:id

同一时刻的会话（应用确实在播、且标题就是歌词行）:

## 2. A/B：官方 ncm（第三方应用）在同一张卡上正常

content-desc="The Final Countdown Europe"

## 3. A/B：华为音乐在同一张卡上正常

content-desc="喜欢你 BEYOND"

## 4. 华为 ROM 的判定日志（决定性）

  V HwMediaSessionServiceInner: this app is not in media white list, pkgName: com.takahashirinta.ncrust
  V MediaControlUtils:          this app is not in media white list, pkgName: com.takahashirinta.ncrust
  I MediaSessionService: Media button session is changed to .../androidx.media3.session.id.
  I MediaSessionService: Media button session is changed to .../NcrustSession

## 5. 两条会话的元数据（修复前 vs 修复后）

修复前:
  NcrustSession                metadata: size=5, description=闪闪星光, 紫荆花盛开 · 李荣浩/梁咏琪
  androidx.media3.session.id.  metadata: size=3, description=null, null, null      <- 空（MediaItem.fromUri 无 metadata）
修复后:
  NcrustSession                metadata: size=5, description=快乐炼成泪水是一种勇敢, 修炼爱情 · 林俊杰
  androidx.media3.session.id.  metadata: size=10, description=修炼爱情, 林俊杰      <- 已带歌名/艺人

## 6. 清单差异

  官方 ncm: <receiver ...MediaButtonEventReceiver> with action android.intent.action.MEDIA_BUTTON (priority MAX)
  华为音乐:   MediaButtonIntentReceiver
  Ncrust 修复前: 清单里 receiver 数量 = 0
  Ncrust 修复后: androidx.media3.session.MediaButtonReceiver + MEDIA_BUTTON（已进包，见 aapt2 xmltree）
