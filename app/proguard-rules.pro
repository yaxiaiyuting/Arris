# =============================================================================
# Ncrust ProGuard / R8 rules
# =============================================================================
# 目标：release 构建启用 R8 minify + resource shrink，DEX 更小、冷启动更快。
# 保留的都是运行时反射依赖：Gson 反序列化 model、Retrofit interface、
# Kotlin 元数据。

# --- 调试栈跟踪（release 崩溃日志能定位到源码行） ---
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# =============================================================================
# Gson —— 反射构造 data class 需要保留 fields 与无参构造
# =============================================================================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# 保留所有带 @SerializedName 的 field，防止字段名被混淆
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Gson 内部类和 TypeAdapter
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Ncrust 网络模型 —— 全部 keep，Gson 反射用
-keep class com.takahashirinta.ncrust.network.** { *; }
-keep class com.takahashirinta.ncrust.network.model.** { *; }
-keep class com.takahashirinta.ncrust.lyric.** { *; }
# 权益模型（移植 981e9b3）：AccountGetResponse / VipInfoResponse / PlaybackItem 由 Gson 解析；
# SongPrivilege 随队列 / 收藏经 Gson 落盘（network.** 已整包 keep，这里 keep 判定与响应模型，
# 免得 R8 剪掉反射字段后判定永远读到 null）。
-keep class com.takahashirinta.ncrust.account.** { *; }
# v2.2.0：歌单缓存 DTO 与编解码。**这一条是真的踩过坑才加的** ——
# playlist.** 在没有 keep 时，R8 会丢掉 DTO 字段的泛型签名 attribute，
# 导致 Gson 把 List<PlaylistDto> 当成裸 List、元素反序列化成 LinkedTreeMap，
# release 真机上表现为 ClassCastException 崩溃（debug 完全正常）。
# 代码侧已经改成用 TypeToken 解析（见 PlaylistCacheCodec.ListEnvelope 的 KDoc），
# 这里是第二道防线：万一将来有人又写了依赖字段泛型签名的持久化结构。
-keep class com.takahashirinta.ncrust.playlist.** { *; }
# v2.3.0：本地歌单的 DTO 与编解码。**这一条同样是真的踩过坑才加的** ——
# 第一次 release 真机验证时读回 ncrust_local_playlists.xml，发现落盘的 JSON 是
# {"a":"netease","b":"local:...","c":"anonymous","d":"v230test","e":...,"f":...}
# —— R8 把 DTO 的字段名整体混淆成了单字母。同一个 APK 内读写自洽，所以**不崩**，
# 但持久化结构的字段名变成了「构建的副产物」：下一次构建的混淆映射一变，
# 老数据就一条都读不出来 ⇒ 用户的本地歌单在升级时**静默消失**。
# 这与 v2.2.0 那次「R8 丢掉泛型签名 ⇒ Gson 产出 LinkedTreeMap」是同一类问题的两个面：
# **凡是落盘/落网的结构，字段名与泛型签名都是对外契约，不能交给 R8 决定。**
# 回归保护：LocalPlaylistCodecTest 里断言了编解码产物的**确切 key 名**
# （字段改名会让那个用例变红，而不是让用户的数据消失）。
-keep class com.takahashirinta.ncrust.local.** { *; }

# v2.3.0：单曲标签（版权可用性 / 原唱翻唱）。它**不落盘**，keep 的理由与上面不同：
# 它是 Compose 的稳定性判据来源（@Immutable data class），混淆不会破坏正确性。
# 这里不 keep 它 —— 列出来只为说明「为什么 local 要 keep 而 search 不要」。

# =============================================================================
# Retrofit / OkHttp —— 保留 interface 上的 HTTP 注解
# =============================================================================
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Retrofit 2 & OkHttp —— platform 自带，防误删
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# =============================================================================
# Kotlin
# =============================================================================
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.reflect.jvm.internal.impl.builtins.BuiltInsLoaderImpl { *; }

# 协程
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# =============================================================================
# Compose —— compiler + runtime 自带 consumer rules，无需额外
# =============================================================================

# =============================================================================
# Media3 / ExoPlayer —— consumer rules 自带
# =============================================================================

# =============================================================================
# 反射直接命中的常量（BuildConfig）
# =============================================================================
-keep class com.takahashirinta.ncrust.BuildConfig { *; }

# =============================================================================
# WebView JS 接口（登录页用 WebView，未来若加 JS bridge 需在此声明）
# =============================================================================

# v2.4.0：跨源匹配缓存（`crosssource.**`）的 DTO 与编解码。**与 local/playlist 同一类问题** ——
# `ncrust_match_cache.xml` 里存的是 `{"schemaVersion":1,"algorithmVersion":1,…,"aliasesJson":"…"}`，
# 字段名就是跨版本契约。R8 把字段名混淆成单字母时同一 APK 内读写自洽、**不崩**，
# 但下一次构建的映射一变，老数据一条都读不出来 ⇒ 缓存静默失效
# （表现是「每次进页面都重新匹配一次」，用户感知为「变慢了」而不是「坏了」——
#  正因为它不崩，这类问题更难被发现，所以 keep + 断言 key 名的单测两条都要）。
-keep class com.takahashirinta.ncrust.crosssource.** { *; }

# Keep the new, isolated celebration renderer and Compose lambdas below the DEX
# method-size budget. R8 otherwise merges/inlines the whole drawing pipeline and
# dialog contents into >2000-code-unit bodies. Shrinking and renaming stay on.
-keep,allowshrinking,allowobfuscation class com.takahashirinta.ncrust.celebration.** { *; }
-keepclassmembers class com.takahashirinta.ncrust.celebration.BirthdayWebView$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the UI history pump as one small method. Inlining it (and its helpers)
# into MotionClock.frame merged the wave/motion pipelines into 2176 DEX units.
# Preserve this boundary; unused overloads may still shrink and names may change.
-keepclassmembers,allowshrinking,allowobfuscation class com.takahashirinta.ncrust.ui.player.WaveformRing {
    public boolean pump(boolean, float, com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects, long, boolean);
}
