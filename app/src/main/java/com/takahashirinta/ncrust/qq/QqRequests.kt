/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · B：qm 请求体的**纯构造**（无 IO、无 Android 依赖、JVM 可单测）。
 */

package com.takahashirinta.ncrust.qq

import org.json.JSONArray
import org.json.JSONObject

/**
 * `musicu.fcg` 请求体的构造（v2.1.0 · B）。
 *
 * 单独抽出来的理由不是「分层好看」，而是**这些字段名是实测出来的、写错了不会报错**：
 * - 取链的 `filename` / `songmid` / `songtype` 必须**等长并行数组**，少一个元素服务端就
 *   返回空 purl，看起来像「无权限」；
 * - 搜索信封的 key 必须是 **module 名**且**不能带 `comm`**，带了会被判通道不匹配、返回 0 条。
 *
 * 抽成纯函数之后，这两条都能在 JVM 单测里钉住，也能被「打真实服务端」的探针测试复用
 * （见 `QqLiveProbeTest`）。
 */
internal object QqRequests {

    const val VKEY_MODULE = "music.vkey.GetVkey"
    const val VKEY_METHOD = "UrlGetVkey"
    const val SEARCH_MODULE = "music.search.SearchCgiService"
    const val SEARCH_METHOD = "DoSearchForQQMusicMobile"
    const val LYRIC_MODULE = "music.musichallSong.PlayLyricInfo"
    const val LYRIC_METHOD = "GetPlayLyricInfo"
    const val VIP_MODULE = "VipLogin.VipLoginInter"
    const val VIP_METHOD = "vip_login_base"

    /**
     * 批量取链的请求体。**`filename` / `songmid` / `songtype` 三个数组必须等长**，
     * 第 i 个文件名对应第 i 个 songmid。
     *
     * @param mediaMid 拼文件名用的 mid —— **必须传 `file.media_mid`**，不是 `song.mid`。
     */
    fun vkey(
        songMid: String,
        mediaMid: String,
        types: List<QqFileType>,
        uin: String,
        guid: String,
    ): JSONObject {
        val filenames = JSONArray()
        val songmids = JSONArray()
        val songtypes = JSONArray()
        for (t in types) {
            filenames.put(QqQuality.fileNameFor(t, mediaMid))
            songmids.put(songMid)
            songtypes.put(0)
        }
        return JSONObject()
            .put("module", VKEY_MODULE)
            .put("method", VKEY_METHOD)
            .put(
                "param",
                JSONObject()
                    .put("uin", uin)
                    .put("filename", filenames)
                    .put("guid", guid)
                    .put("songmid", songmids)
                    .put("songtype", songtypes)
                    .put("ctx", 0),
            )
    }

    /** 搜索的 `param`。`searchid` 由调用方生成（它含时间与计数器，不该是纯函数）。 */
    fun searchParam(keyword: String, limit: Int, page: Int, searchId: String): JSONObject = JSONObject()
        .put("searchid", searchId)
        .put("query", keyword)
        .put("search_type", 0)
        .put("num_per_page", limit.coerceIn(1, 60))
        .put("page_num", page.coerceAtLeast(1))
        .put("highlight", true)
        .put("grp", true)
        .put("selectors", JSONObject())
        .put("vec_selectors", JSONArray())

    /**
     * 搜索的**完整信封**：key 是 module 名（不是 `"req"`），且**不含 `comm`** ——
     * 实测带 Web comm 会被判通道不匹配、返回 0 条。
     */
    fun searchEnvelope(keyword: String, limit: Int, page: Int, searchId: String): JSONObject =
        JSONObject().put(
            SEARCH_MODULE,
            JSONObject()
                .put("module", SEARCH_MODULE)
                .put("method", SEARCH_METHOD)
                .put("param", searchParam(keyword, limit, page, searchId)),
        )

    /**
     * 旧版 GET 搜索的 URL（主通道，实测比 musicu 稳定）。
     *
     * @param type `t` 参数。**默认 0 = 单曲搜索**；v3.3.0 · 需求 2 用 **7 = 歌词搜索**
     *   （实测 `t=7&w=让我掉下眼泪的` 返回 `data.lyric.list`，第一条就是《成都》- 赵雷）。
     *   默认值保证既有调用点与 v3.2.4 **逐字节一致**（URL 里多一个 `t=0` 也是多余的变化，
     *   所以为 0 时不写进 query）。
     */
    fun legacySearchUrl(keyword: String, limit: Int, page: Int, type: Int = 0): String =
        "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
            "?p=" + page.coerceAtLeast(1) +
            "&n=" + limit.coerceIn(1, 60) +
            "&w=" + java.net.URLEncoder.encode(keyword, "UTF-8") +
            "&format=json&cr=1&new_json=1" +
            // type=0 走**完全不带 t 的历史形状**：加一个 `&t=0` 虽然语义等价，
            // 但会让「本版没有改动单曲搜索」这句话不再能用 diff 直接证明。
            (if (type > 0) "&t=$type" else "")

    /** 歌词请求体。`songId` 是服务端数字 songid（不是我们合成的 id）。 */
    fun lyric(songMid: String, rawSongId: Long): JSONObject = JSONObject()
        .put("module", LYRIC_MODULE)
        .put("method", LYRIC_METHOD)
        .put(
            "param",
            JSONObject()
                .put("crypt", 1)
                .put("lrc_t", 0)
                .put("qrc", 1)
                .put("qrc_t", 0)
                .put("roma", 1)
                .put("roma_t", 0)
                .put("trans", 1)
                .put("trans_t", 0)
                .put("needSingingAnnotations", false)
                .put("type", 1)
                .put("songMid", songMid)
                .put("songId", rawSongId),
        )

    /** 会员状态请求体（匿名也可调，实测 `code=0`）。 */
    fun vip(): JSONObject = JSONObject()
        .put("module", VIP_MODULE)
        .put("method", VIP_METHOD)
        .put("param", JSONObject())

    // ---------------- 手机号验证码登录（v2.1.1） ----------------

    const val LOGIN_MODULE = "music.login.LoginServer"
    const val SEND_PHONE_CODE_METHOD = "SendPhoneAuthCode"
    const val LOGIN_METHOD = "Login"

    /**
     * 发短信验证码。实测（2026-09）：
     * - **`areaCode` 必须是字符串**：传数字 `86` 会被判成畸形请求（`req.code=10006`），
     *   传 `"86"` 才走到正常的参数校验路径（`104400` = 号码非法）；
     * - `tmeAppid` 会被服务端校验（传别的值回 `bad request: unknown tmeAppID`）；
     * - `comm` 需要 `tmeLoginMethod = 3`（见 [QqClient.musicuLogin]）。
     */
    fun sendPhoneAuthCode(phoneNo: String, areaCode: String = QqPhoneLogin.AREA_CODE_CN): JSONObject =
        JSONObject()
            .put("module", LOGIN_MODULE)
            .put("method", SEND_PHONE_CODE_METHOD)
            .put(
                "param",
                JSONObject()
                    .put("tmeAppid", "qqmusic")
                    .put("areaCode", areaCode)
                    .put("phoneNo", phoneNo),
            )

    /**
     * 用短信验证码换凭证。`loginMode = 1` 就是「手机验证码登录」这一种模式
     * （`2` 是 refresh_token 续期，见 PHASE0-QQMUSIC-API.md §4.5.2）。
     */
    fun phoneLogin(phoneNo: String, code: String): JSONObject = JSONObject()
        .put("module", LOGIN_MODULE)
        .put("method", LOGIN_METHOD)
        .put(
            "param",
            JSONObject()
                .put("code", code)
                .put("loginMode", 1)
                .put("phoneNo", phoneNo),
        )

    // ---------------- 用户歌单（v2.2.0 · 只读） ----------------
    // 端点与字段全部来自 docs/verification/v2.2.0/qq-playlist-probe/ 的四轮真机实测，
    // 不是文档推断。三条最容易写错的实测事实：
    //   1) 列表接口返回 **camelCase**（dirId/dirName/songNum/tid），
    //      而详情接口的 dirinfo 是**下划线**（dirid/songnum）—— 同一份数据两套命名；
    //   2) 列表接口的 `param.uin` 是**查询主体**（不是「我是谁」），它公开可读，
    //      **不能**用它的成败判登录态（见 QqPlaylistApi 的登录态说明）；
    //   3) 收藏歌单接口必须传 **encrypt_uin**，传裸 uin 会得到 `80050`。

    const val PLAYLIST_LIST_MODULE = "music.musicasset.PlaylistBaseRead"
    const val PLAYLIST_LIST_METHOD = "GetPlaylistByUin"
    const val PLAYLIST_FAV_MODULE = "music.musicasset.PlaylistFavRead"
    const val PLAYLIST_FAV_METHOD = "CgiGetPlaylistFavInfo"
    const val PLAYLIST_DETAIL_MODULE = "music.srfDissInfo.DissInfo"
    const val PLAYLIST_DETAIL_METHOD = "CgiGetDiss"
    const val USER_INFO_MODULE = "music.UserInfo.userInfoServer"
    const val USER_INFO_METHOD = "GetLoginUserInfo"

    /** 单页上限。实测 2000 也被接受；取 500 是为了与 ncm `LIKED_FILL_PAGE_SIZE` 量级一致。 */
    const val PLAYLIST_PAGE_MAX = 500

    /** 用户自建歌单列表。实测返回里**包含 `dirId=201`「我喜欢」**。 */
    fun playlistList(uin: String): JSONObject = JSONObject()
        .put("module", PLAYLIST_LIST_MODULE)
        .put("method", PLAYLIST_LIST_METHOD)
        .put("param", JSONObject().put("uin", uin))

    /**
     * 收藏（他人）歌单列表。
     *
     * @param encryptUin 从歌单详情的 `dirinfo.encrypt_uin` 取到（实测 28 字符）。
     *   **不能传裸 uin**：实测裸 uin 返回 `80050`。
     */
    fun playlistFavList(encryptUin: String, offset: Int, size: Int): JSONObject = JSONObject()
        .put("module", PLAYLIST_FAV_MODULE)
        .put("method", PLAYLIST_FAV_METHOD)
        .put(
            "param",
            JSONObject()
                .put("uin", encryptUin)
                .put("offset", offset.coerceAtLeast(0))
                .put("size", size.coerceIn(1, 100)),
        )

    /**
     * 歌单详情（含歌曲列表）。
     *
     * 实测两种寻址**等价**：`disstid=0, dirid=<目录号>` 与 `disstid=<tid>, dirid=<目录号>`
     * 都返回同一份数据；「我喜欢」用 `dirid=201` 打开。
     *
     * @param songBegin **offset**（不是页码）。实测越界不报错：`code=0` + 0 首 + `hasmore=0`。
     * @param songNum 每页数量。实测 500/1000/2000 都被接受，服务端按实际总数截断。
     */
    fun playlistDetail(disstid: Long, dirid: Long, songBegin: Int, songNum: Int): JSONObject =
        JSONObject()
            .put("module", PLAYLIST_DETAIL_MODULE)
            .put("method", PLAYLIST_DETAIL_METHOD)
            .put(
                "param",
                JSONObject()
                    .put("disstid", disstid)
                    .put("dirid", dirid)
                    .put("tag", true)
                    .put("song_begin", songBegin.coerceAtLeast(0))
                    .put("song_num", songNum.coerceIn(1, PLAYLIST_PAGE_MAX))
                    .put("userinfo", true)
                    .put("orderlist", true)
                    .put("onlysonglist", false),
            )

    /**
     * 登录态自证 + 用户信息（昵称 / 头像）。
     *
     * **这是唯一能判「登录态还有效吗」的读接口**：实测无 cookie 时返回 `code=1000`，
     * 而 `GetPlaylistByUin` / `CgiGetDiss` / `vip_login_base` 在没有登录态时**照样返回数据**。
     */
    fun loginUserInfo(): JSONObject = JSONObject()
        .put("module", USER_INFO_MODULE)
        .put("method", USER_INFO_METHOD)
        .put("param", JSONObject())

    // ---------------- 登录态续期（v3.4.9） ----------------

    /**
     * 续期的 `loginMode`：**2 = 刷新**（1 = 手机验证码登录）。
     *
     * 依据见 [QqRefreshCredential] 的 KDoc；v2.1.1 的
     * [phoneLogin] 注释里早就记下了这件事（「`2` 是 refresh_token 续期」），
     * 但当时没有把凭证留下来，所以这条知识一直没能变成功能。
     */
    const val LOGIN_MODE_REFRESH = 2

    /**
     * 用已落盘的凭证换一张新票据。**同一个端点、同一个方法，只有 `loginMode` 不同。**
     *
     * ## 参数为什么是「并集」而不是按 `loginType` 挑一套
     *
     * 参考实现（`L-1124/QQMusicApi` 的 `refresh_credential()`）按登录类型分了三支，
     * 三支的参数集**互有出入**（微信支没有 `musicid`，QQ 支没有 `str_musicid`）。
     * 本客户端能拿到凭证的那条路只有手机验证码登录，它的
     * `comm.tmeLoginType = 0` —— **既不是微信(1) 也不是 QQ(2)**，落在参考实现的
     * `case _` 那一支，也就是**并集**。所以这里照并集构造：
     * 多带的字段服务端会忽略，**少带一个却只会静默失败**（回一个空 data），
     * 而静默失败正是这条链路最难查的形态。
     *
     * ## 三个不在凭证里的字段
     *
     * - `refresh_key`：v3.4.9 的凭证里**没有**它（手机号登录的响应里叫 `refreshKey`，
     *   只有走到刷新那一步才会下发）；传空串与参考实现一致（它传的是字段的默认值 `""`）；
     * - `str_musicid`：凭证里没有时用数字 `musicid` 的字符串形态补齐（参考实现的
     *   `target.str_musicid or str(target.musicid)` 就是这个语义）；
     * - `expired_in`：**不是**「从现在起多久过期」，而是**绝对到期时刻**
     *   （参考实现传的是 `expired_at`，`Credential` 的注释写的是「到期时间」）。
     *   首次登录时它等于「签发时间 + `psrf_access_token_expiresAt`」，
     *   那两个值我们都有（cookie 里有后者），所以这里按那个式子**还原**；
     *   还原不出来就传 `0`（参考实现在同样情况下传的也是 `0`）。
     *   ⚠️ 传 `now` 或一个猜出来的未来时刻是**错的**：服务端可能拿它判「这个 access_token
     *   是不是已经过期了」，报一个假值会让一次本来能成功的续期被判失败。
     *
     * @param cookie 当前落盘的 cookie。**只用来兜底 `str_musicid` 与 `expired_in`** ——
     *   这两个值来自同一份凭证，凭证里缺了才回头问 cookie。
     */
    fun refreshCredential(
        credential: QqRefreshCredential,
        cookie: String?,
    ): JSONObject {
        val fallbackMusicId = QqCookie.uinOf(cookie)?.toString()
        val strMusicId = credential.strMusicId
            ?: fallbackMusicId
            ?: credential.musicId.takeIf { it > 0L }?.toString()
            ?: ""
        // 「签发时间 + access_token 寿命」。两个值缺任何一个都只能是 0（未知）。
        val accessTtl = QqCookie.parse(cookie)[KEY_ACCESS_TOKEN_TTL]?.toLongOrNull()
        val expiresAt = if (credential.keyCreatedAt > 0L && accessTtl != null && accessTtl > 0L) {
            credential.keyCreatedAt + accessTtl
        } else {
            0L
        }

        return JSONObject()
            .put("module", LOGIN_MODULE)
            .put("method", LOGIN_METHOD)
            .put(
                "param",
                JSONObject()
                    // —— 身份 ——
                    .put("musicid", credential.musicId)
                    .put("str_musicid", strMusicId)
                    .put("openid", credential.openId.orEmpty())
                    .put("unionid", credential.unionId.orEmpty())
                    // —— 上一张票（服务端据此判断「是续期而不是新登录」）——
                    .put("musickey", credential.musicKey.orEmpty())
                    // —— 续期凭证本体 ——
                    .put("refresh_key", credential.refreshKey.orEmpty())
                    .put("refresh_token", credential.refreshToken.orEmpty())
                    .put("access_token", credential.accessToken.orEmpty())
                    .put("expired_in", expiresAt)
                    // —— 开关 ——
                    .put("loginMode", LOGIN_MODE_REFRESH),
            )
    }

    /** `psrf_access_token_expiresAt` 的 cookie 字段名（[refreshCredential] 的 `expired_in` 兜底来源）。 */
    private const val KEY_ACCESS_TOKEN_TTL = "psrf_access_token_expiresAt"
}
