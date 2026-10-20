package com.ashchan.cloudsaver.drive.quark

import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Url

/**
 * 夸克网盘非官方 Web API（接口参数移植自 CASX QuarkAdapter，逻辑重写）。
 *
 * PC 端：https://drive-pc.quark.cn
 * 移动端（分享兼容）：https://drive-m.quark.cn（需要 cookie 中的 kps/sign/vcode）
 */
interface QuarkApi {

    // ---------- 账号 ----------
    @GET("https://pan.quark.cn/account/info")
    suspend fun accountInfo(@Query("fr") fr: String = "pc", @Query("platform") platform: String = "pc"): JsonObject

    // ---------- 分享 ----------
    @POST("1/clouddrive/share/sharepage/token")
    suspend fun shareToken(@Body body: JsonObject, @Query("pr") pr: String = "ucpro", @Query("fr") fr: String = "pc"): JsonObject

    @GET("1/clouddrive/share/sharepage/detail")
    suspend fun shareDetail(@QueryMap params: Map<String, String>): JsonObject

    @POST("1/clouddrive/share/sharepage/save")
    suspend fun shareSave(@Body body: JsonObject, @QueryMap params: Map<String, String>): JsonObject

    // ---------- 文件管理 ----------
    @GET("1/clouddrive/file/sort")
    suspend fun fileSort(@QueryMap params: Map<String, String>): JsonObject

    @POST("1/clouddrive/file/rename")
    suspend fun rename(@Body body: JsonObject, @Query("pr") pr: String = "ucpro", @Query("fr") fr: String = "pc"): JsonObject

    @POST("1/clouddrive/file")
    suspend fun createDir(@Body body: JsonObject, @Query("pr") pr: String = "ucpro", @Query("fr") fr: String = "pc"): JsonObject

    // ---------- 任务 ----------
    @GET
    suspend fun task(@Url url: String, @QueryMap params: Map<String, String>): JsonObject
}
