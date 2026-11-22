package com.ashchan.cloudsaver.drive.guangya

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 光鸭短信验证码登录客户端（独立实例，与转存适配器隔离）。
 *
 * 流程（4 步，均走 account.guangyapan.com）：
 *   ① captcha/init  → 返回 captcha_token，或返回需人机验证的 url
 *   ② verification  → 用 captcha_token 发送短信，返回 verification_id
 *   ③ verify        → 用 verification_id + 验证码，返回 verification_token
 *   ④ signin        → 用 captcha_token + verification_token + 验证码，返回 access/refresh token
 *
 * 仅返回 refresh_token（与现有登录流程一致，登录时只用 refresh 刷新）。
 * 全程在本机直连光鸭官方，不上传任何信息到第三方。
 */
internal object GuangYaSmsLogin {

    private const val ACCOUNT_BASE = "https://account.guangyapan.com/"
    private const val WEB_ORIGIN = "https://www.guangyapan.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    private const val CLIENT_ID = "aMe-8VSlkrbQXpUR"

    private val deviceId: String by lazy { generateDid() }

    private fun generateDid(): String {
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val digest = java.security.MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private val randomHex: (Int) -> String = { len ->
        val chars = "0123456789abcdef"
        (1..len).map { chars.random() }.joinToString("")
    }

    private val api: GuangYaApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("accept", "*/*")
                    .header("content-type", "application/json")
                    .header("origin", WEB_ORIGIN)
                    .header("referer", "$WEB_ORIGIN/")
                    .header("user-agent", USER_AGENT)
                    .header("x-client-id", CLIENT_ID)
                    .header("x-client-version", "0.0.1")
                    .header("x-device-id", deviceId)
                    .header("x-device-model", "chrome%2F147.0.0.0")
                    .header("x-device-name", "PC-Chrome")
                    .header("x-device-sign", "wdi10.$deviceId${randomHex(32)}")
                    .header("x-net-work-type", "NONE")
                    .header("x-os-version", "MacIntel")
                    .header("x-platform-version", "1")
                    .header("x-protocol-version", "301")
                    .header("x-provider-name", "NONE")
                    .header("x-sdk-version", "9.0.2")
                    .build()
                chain.proceed(req)
            }
            .build()
        Retrofit.Builder()
            .baseUrl(ACCOUNT_BASE)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GuangYaApi::class.java)
    }

    /** ① 初始化验证：成功拿 token，或需要人机验证拿 url */
    suspend fun captchaInit(phone: String): CaptchaInitResult = withContext(Dispatchers.IO) {
        val resp = api.captchaInit(
            JsonObject().apply {
                addProperty("client_id", CLIENT_ID)
                addProperty("action", "POST:/v1/auth/verification")
                addProperty("device_id", deviceId)
                add("meta", JsonObject().apply { addProperty("phone_number", formatPhone(phone)) })
            }
        )
        val body = resp.body()
        if (!resp.isSuccessful || body == null) {
            throw IllegalStateException("光鸭初始化验证失败（HTTP ${resp.code()}），请稍后重试或改用手动粘贴 Token")
        }
        // 需要人机验证：返回 url
        val url = extractString(body, listOf("url", "captcha_url", "verify_url"))
        if (!url.isNullOrBlank()) return@withContext CaptchaInitResult.HumanVerify(url)
        // 直接通过：返回 captcha_token
        val token = extractString(body, listOf("captcha_token", "captchaToken", "token"))
        if (!token.isNullOrBlank()) return@withContext CaptchaInitResult.Token(token)
        throw IllegalStateException("光鸭初始化验证返回异常，请稍后重试或改用手动粘贴 Token")
    }

    /** ② 发送短信验证码 */
    suspend fun sendCode(captchaToken: String, phone: String): String = withContext(Dispatchers.IO) {
        val resp = api.sendVerification(
            captchaToken,
            JsonObject().apply {
                addProperty("phone_number", formatPhone(phone))
                addProperty("target", "ANY")
                addProperty("client_id", CLIENT_ID)
            }
        )
        val body = checkOk(resp, "发送验证码")
        return@withContext extractString(body, listOf("verification_id", "verificationId", "id"))
            ?: throw IllegalStateException("光鸭发送验证码未返回验证码 ID，请重试")
    }

    /** ③ 校验短信验证码 */
    suspend fun verifyCode(verificationId: String, code: String, phone: String): String =
        withContext(Dispatchers.IO) {
            val resp = api.verifyCode(
                verificationId,
                JsonObject().apply {
                    addProperty("verification_id", verificationId)
                    addProperty("verification_code", code)
                    addProperty("client_id", CLIENT_ID)
                }
            )
            val body = checkOk(resp, "校验验证码")
            return@withContext extractString(body, listOf("verification_token", "verificationToken", "token"))
                ?: throw IllegalStateException("光鸭校验验证码未返回令牌，请确认验证码是否正确")
        }

    /** ④ 完成登录，返回 refresh_token */
    suspend fun signIn(captchaToken: String, verificationToken: String, code: String, phone: String): String =
        withContext(Dispatchers.IO) {
            val resp = api.signIn(
                captchaToken,
                JsonObject().apply {
                    addProperty("verification_code", code)
                    addProperty("verification_token", verificationToken)
                    addProperty("username", formatPhone(phone))
                    addProperty("client_id", CLIENT_ID)
                }
            )
            val body = checkOk(resp, "登录")
            val refresh = GuangYaResp.dataRefreshToken(body)
            if (refresh.isBlank()) {
                throw IllegalStateException("光鸭登录成功但未返回 Refresh Token，请重试")
            }
            return@withContext refresh
        }

    // ---------- 内部工具 ----------

    private fun checkOk(resp: Response<JsonObject>, stage: String): JsonObject {
        val body = resp.body()
        if (!resp.isSuccessful || body == null) {
            throw IllegalStateException("光鸭${stage}失败（HTTP ${resp.code()}），请稍后重试")
        }
        return body
    }

    /** 从 data 内或顶层提取字符串字段 */
    private fun extractString(payload: JsonObject, keys: List<String>): String? {
        val data = payload.get("data")
        if (data != null && data.isJsonObject) {
            for (k in keys) {
                data.asJsonObject.get(k)?.asString?.takeIf { it.isNotBlank() }?.let { return it.trim() }
            }
        }
        for (k in keys) {
            payload.get(k)?.asString?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        }
        return null
    }

    /** 统一手机号格式：未带国家码则补 +86（光鸭要求带区号，如 +86 13800138000） */
    private fun formatPhone(phone: String): String {
        val p = phone.trim()
        return if (p.startsWith("+")) p else "+86 $p"
    }

    sealed interface CaptchaInitResult {
        val captchaToken: String? get() = null
        val url: String? get() = null
        data class Token(override val captchaToken: String) : CaptchaInitResult
        data class HumanVerify(override val url: String) : CaptchaInitResult
    }
}
