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
}
