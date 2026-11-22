package com.ashchan.cloudsaver.drive.guangya

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * 光鸭云盘非官方 Web API（接口参数移植自 CASX GuangyaClientLite，AGPL 逻辑重写）。
 *
 * Base URL：https://api.guangyapan.com/ （account 接口在 https://account.guangyapan.com/）
 */
interface GuangYaApi {

    // ---------- 分享 ----------
    @POST("nd.bizuserres.s/v1/get_share_summary")
    suspend fun shareSummary(@Body body: JsonObject): JsonObject

    @POST("nd.bizuserres.s/v1/get_share_access_token")
    suspend fun shareAccessToken(@Body body: JsonObject): JsonObject

    @POST("nd.bizuserres.s/v1/get_share_page_files_list")
    suspend fun shareFilesList(@Body body: JsonObject): JsonObject

    // ---------- 转存 ----------
    @POST("nd.bizuserres.s/v1/restore_share")
    suspend fun restoreShare(@Body body: JsonObject): JsonObject

    @POST("nd.bizuserres.s/v1/get_task_status")
    suspend fun taskStatus(@Body body: JsonObject): JsonObject

    // ---------- 文件管理 ----------
    @POST("userres/v1/file/get_file_list")
    suspend fun fileList(@Body body: JsonObject): JsonObject

    @POST("nd.bizuserres.s/v1/file/rename")
    suspend fun rename(@Body body: JsonObject): JsonObject

    @POST("nd.bizuserres.s/v1/file/create_dir")
    suspend fun createDir(@Body body: JsonObject): JsonObject

    // ---------- 账号 ----------
    // 注意：光鸭官方 user/me 现为 GET（POST 已废弃，会返回 501）
    @GET("v1/user/me")
    suspend fun userMe(): Response<JsonObject>

    @POST("v1/auth/token")
    suspend fun refreshToken(
        @Header("x-action") xAction: String = "401",
        @Body body: JsonObject,
    ): Response<JsonObject>

    // ---------- 短信验证码登录（account.guangyapan.com） ----------
    // ① 初始化验证：返回 captcha_token（5 分钟有效），或返回需人机验证的 url
    @POST("v1/shield/captcha/init")
    suspend fun captchaInit(@Body body: JsonObject): Response<JsonObject>

    // ② 发送短信验证码（header 带第①步的 captcha_token）
    @POST("v1/auth/verification")
    suspend fun sendVerification(
        @Header("x-captcha-token") captchaToken: String,
        @Body body: JsonObject,
    ): Response<JsonObject>

    // ③ 校验短信验证码（header 带第②步的 verification_id）
    @POST("v1/auth/verification/verify")
    suspend fun verifyCode(
        @Header("x-captcha-verification-id") verificationId: String,
        @Body body: JsonObject,
    ): Response<JsonObject>

    // ④ 完成登录（header 带第①步的 captcha_token），返回 access_token + refresh_token
    @POST("v1/auth/signin")
    suspend fun signIn(
        @Header("x-captcha-token") captchaToken: String,
        @Body body: JsonObject,
    ): Response<JsonObject>
}
