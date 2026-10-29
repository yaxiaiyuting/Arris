package com.takahashirinta.ncrust.network.crypto

import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

object EapiCrypto {
    private val AES_KEY = "e82ckenh8dichen8".toByteArray()
    private const val MAGIC_PREFIX = "nobody"
    private const val MAGIC_SUFFIX = "md5forencrypt"
    private const val SEPARATOR = "-36cd479b6b5-"

    fun encryptParams(url: String, payload: Map<String, Any>): String {
        val parsedUrl = java.net.URL(url)
        val urlPath = parsedUrl.path.replace("/eapi/", "/api/")
        val payloadJson = JSONObject(payload).toString()

        val signStr = "$MAGIC_PREFIX${urlPath}use${payloadJson}$MAGIC_SUFFIX"
        val digest = md5(signStr)
        val paramsStr = "${urlPath}${SEPARATOR}${payloadJson}${SEPARATOR}${digest}"

        return aesEncrypt(paramsStr)
    }

    /**
     * 解密 eapi 响应（部分写接口返回 AES-ECB 加密 JSON，如 /eapi/radio/like）。
     * 与请求同钥匙 e82ckenh8dichen8，PKCS5 去填充后去掉尾部冗余 '\r'。
     */
    /**
     * 解密**裸字节**响应。
     *
     * 有些 eapi 端点（实测：`/eapi/music/audio/match` 听歌识曲）直接返回 AES 密文原始字节，
     * 不做 base64 包装 —— 用 [decryptResponse] 会拿 UTF-8 乱码去当 base64 解码，结果永远是空串，
     * 上层再把乱码丢进 `JSONObject` 就得到 "Value ... of type java.lang.String cannot be
     * converted to JSONObject" 这种看起来像"字符串问题"的报错。
     */
    fun decryptRawBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
            String(cipher.doFinal(bytes), Charsets.UTF_8).trimEnd('\r', '\n', ' ')
        } catch (_: Exception) {
            ""
        }
    }

    fun decryptResponse(body: String): String {
        val bytes = try { java.util.Base64.getDecoder().decode(body) } catch (_: Exception) { return "" }
        if (bytes.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
            String(cipher.doFinal(bytes), Charsets.UTF_8).trimEnd('\r', '\n', ' ')
        } catch (_: Exception) {
            ""
        }
    }

    private fun aesEncrypt(data: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(AES_KEY, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytesToHex(encrypted)
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytesToHex(hash)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}