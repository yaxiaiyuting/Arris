package com.takahashirinta.ncrust.network
import com.takahashirinta.ncrust.network.model.SongUrlResponse
import com.takahashirinta.ncrust.network.model.*
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface NcmApi {
    /**
     * 搜索单曲。
     *
     * `offset` 是 v3.4.11 加的**分页**参数：这个接口本来就是分页的
     * （`docs/verification/v2.3.0/probe-copyright.md` 里记的实测请求就是
     * `{"s":…,"type":1,"limit":30,"offset":0}`），只是此前**只有第一页的调用点** ——
     * 于是用户看到的结果永远是 30 条，而界面上没有任何「还有更多」的出口。
     */
    @FormUrlEncoded
    @POST("api/cloudsearch/pc")
    suspend fun search(
        @Field("s") keyword: String,
        @Field("type") type: Int = 1,
        @Field("limit") limit: Int = 30,
        @Field("offset") offset: Int = 0
    ): SearchResponse

    @FormUrlEncoded
    @POST("api/cloudsearch/pc")
    suspend fun searchAlbum(
        @Field("s") keyword: String,
        @Field("type") type: Int = 10,
        @Field("limit") limit: Int = 30,
        @Field("offset") offset: Int = 0
    ): SearchResponse

    @FormUrlEncoded
    @POST("api/cloudsearch/pc")
    suspend fun searchArtist(
        @Field("s") keyword: String,
        @Field("type") type: Int = 100,
        @Field("limit") limit: Int = 30,
        @Field("offset") offset: Int = 0
    ): SearchResponse

    /**
     * 按歌词搜索（v3.3.0）—— `type=1006`。
     *
     * 实测（2026-10，匿名）：`s=让我掉下眼泪的&type=1006` → `code:200`、`songCount:60`，
     * 第一条是《成都》- 赵雷。返回体里的 `result.songs` 与 `type=1` **同形**，
     * 所以调用方直接复用单曲的展示与播放链路，不需要新模型。
     *
     * ⚠️ 没有复用 [search] 是因为 `type` 有 Kotlin 默认值 `1`：那个默认值在
     * Retrofit 的 `@Field` 上是**编译期常量**，改不了运行时分支。多写一个方法
     * 比把默认值改成参数化的哨兵值清楚 —— 后者会让「不传 type」的语义变得含糊。
     */
    @FormUrlEncoded
    @POST("api/cloudsearch/pc")
    suspend fun searchLyric(
        @Field("s") keyword: String,
        @Field("type") type: Int = 1006,
        @Field("limit") limit: Int = 30,
        @Field("offset") offset: Int = 0
    ): SearchResponse

    @FormUrlEncoded
    @POST("api/v3/song/detail")
    suspend fun getSongDetail(
        @Field("c") c: String
    ): SongDetailResponse

    @FormUrlEncoded
    @POST("api/song/lyric")
    suspend fun getLyric(
        @Field("id") id: Long,
        @Field("cp") cp: String = "false",
        @Field("tv") tv: String = "-1",
        @Field("lv") lv: String = "-1",
        // v1.9.2 实测：rv=0 与 rv=-1 对同一首歌返回**逐字节相同**的 body（22704409 / 1959528822 /
        // 3431697106 / 16686599 四首，sha256 一致），romalrc 本来就在响应里。所以本版**不动这个参数**
        // （不新增请求、不改载荷），只是把 romalrc 解析出来用。若将来服务端改成只在 rv=-1 时返回，
        // 把默认值改成 "-1" 即可 —— 仍是既有字段，不是新请求。
        @Field("rv") rv: String = "0",
        @Field("kv") kv: String = "0",
        // v1.5.0 · B：yv=-1 是「要逐字歌词」的开关。实测 yv 一次带回 yrc + ytlrc +
        // yromalrc 三个字段（ytlrc 其实是**行级** LRC 译文，不是逐字，所以客户端只取 yrc）；
        // 不传 yv 时响应里连 yrc 这个 key 都不存在。
        @Field("yv") yv: String = "-1",
        @Field("ytv") ytv: String = "0",
        @Field("yrv") yrv: String = "0"
    ): LyricResponse

    @FormUrlEncoded
    @POST("api/song/enhance/player/url/v1")
    suspend fun getSongUrl(
        @Field("ids") ids: String,
        @Field("level") level: String,
        @Field("encodeType") encodeType: String = "flac"
    ): SongUrlResponse

    // ====== 修复：专辑详情是 GET 请求 ======
    @GET("api/v1/album/{id}")
    suspend fun getAlbumDetail(
        @Path("id") id: Long
    ): AlbumDetailResponse

    // ====== 艺人详情：尝试 GET 请求 ======
    @GET("api/artist/detail/{id}")
    suspend fun getArtistDetail(
        @Path("id") id: Long
    ): ArtistDetailResponse

    // ====== 艺人专辑列表：尝试 GET 请求 ======
    @GET("api/artist/albums/{id}")
    suspend fun getArtistAlbums(
        @Path("id") id: Long,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): ArtistAlbumsResponse

    // ====== 用户详情（公开主页信息，任意 uid） ======
    @GET("api/v1/user/detail/{uid}")
    suspend fun getUserDetail(
        @Path("uid") uid: Long
    ): UserDetailResponse

    // ====== 推荐歌单（首页个性化，不需要登录） ======
    @GET("api/personalized")
    suspend fun getPersonalized(
        @Query("limit") limit: Int = 30
    ): PersonalizedResponse

    // ====== 新碟上架 ======
    @GET("api/album/new")
    suspend fun getNewAlbums(
        @Query("area") area: Int = 0,
        @Query("limit") limit: Int = 10,
        @Query("offset") offset: Int = 0
    ): NewAlbumsResponse
}