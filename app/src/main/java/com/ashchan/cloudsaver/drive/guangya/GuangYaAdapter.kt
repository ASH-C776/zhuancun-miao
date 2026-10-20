package com.ashchan.cloudsaver.drive.guangya

import com.ashchan.cloudsaver.core.adapter.AccountInfo
import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.adapter.RestoredFile
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.model.FileSource
import com.ashchan.cloudsaver.core.model.ShareInfo
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 光鸭云盘适配器。
 *
 * 登录：access_token + refresh_token（Bearer），access_token 过期自动刷新。
 * 转存：restore_share 返回异步任务 task_id，轮询 get_task_status 完成后
 *       通过"目录 diff + 文件名对齐"拿到转存后的新 fid。
 */
class GuangYaAdapter(private var accessToken: String = "", private var refreshToken: String = "") :
    CloudDriveAdapter {

    override val driveType: DriveType = DriveType.GUANGYA

    private val gson = Gson()

    private val api: GuangYaApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("accept", "application/json, text/plain, */*")
                    .header("content-type", "application/json")
                    .header("did", deviceId)
                    .header("dt", "4")
                    .header("origin", WEB_ORIGIN)
                    .header("referer", "$WEB_ORIGIN/")
                    .header("traceparent", GuangYaHeaders.newTraceparent())
                    .header("user-agent", USER_AGENT)
                    .apply {
                        if (accessToken.isNotBlank()) header("authorization", "Bearer $accessToken")
                    }
                    .build()
                val response = chain.proceed(req)
                // 临时调试日志：打印主 API 的请求/响应原文（定位 fileList 解析问题，后续移除）
                runCatching {
                    val buffer = okio.Buffer()
                    req.body?.writeTo(buffer)
                    android.util.Log.i(
                        "GuangYaDebug",
                        "REQ ${req.url} BODY=${buffer.readUtf8()}"
                    )
                    android.util.Log.i(
                        "GuangYaDebug",
                        "RESP ${req.url} CODE=${response.code} BODY=${response.peekBody(50000).string()}"
                    )
                }
                response
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.guangyapan.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(GuangYaApi::class.java)
    }

    private val accountApi: GuangYaApi by lazy {
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
                    .apply {
                        if (accessToken.isNotBlank()) header("authorization", "Bearer $accessToken")
                    }
                    .build()
                chain.proceed(req)
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://account.guangyapan.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(GuangYaApi::class.java)
    }

    /** 刷新后的最新凭证（供上层存回加密存储） */
    fun currentCredential(): String = "$accessToken||$refreshToken"

    // ---------- 登录 ----------
    override suspend fun validateLogin(credential: String): Result<AccountInfo> {
        return try {
            val (at, rt) = splitCredential(credential)
            accessToken = at
            refreshToken = rt

            // 1. 有 access_token 先验证
            if (accessToken.isNotBlank()) {
                val payload = userMeChecked()
                return Result.success(
                    AccountInfo(
                        nickname = extractNickname(payload),
                        raw = payload.toString(),
                    )
                )
            }
            // 2. 用 refresh_token 刷新
            if (refreshToken.isBlank()) {
                throw IllegalStateException("登录已失效，请重新粘贴 Token（或补充 Refresh Token）")
            }
            Result.success(refreshAndVerify())
        } catch (e: Throwable) {
            Result.failure(IOException(readableNetworkError(e)))
        }
    }

    /** 网络/解析类错误转成用户可读的中文提示 */
    private fun readableNetworkError(e: Throwable): String {
        val msg = e.message ?: ""
        return when (e) {
            is java.net.UnknownHostException -> "网络错误：无法解析光鸭服务器域名，请检查网络连接"
            is java.net.ConnectException -> "网络错误：连接光鸭服务器失败，请检查网络"
            is java.net.SocketTimeoutException -> "请求超时：光鸭服务器响应较慢，请稍后重试"
            is java.io.IOException -> if (msg.startsWith("光鸭")) msg else "网络错误：$msg"
            else -> msg
        }
    }

    /** 调 user/me 并给出可读的失败原因 */
    private suspend fun userMeChecked(): JsonObject {
        val resp = accountApi.userMe()
        val body = resp.body()
        if (resp.isSuccessful && body != null && GuangYaResp.isSuccess(body)) return body
        val errBody = if (resp.isSuccessful) null else resp.errorBody()?.string()
        throw IllegalStateException(describeLoginFailure("验证 Token", resp.code(), errBody, body))
    }

    private suspend fun refreshAndVerify(): AccountInfo {
        val resp = accountApi.refreshToken(
            body = JsonObject().apply {
                addProperty("client_id", CLIENT_ID)
                addProperty("grant_type", "refresh_token")
                addProperty("refresh_token", refreshToken)
            }
        )
        val payload = resp.body()
        if (!resp.isSuccessful || payload == null || !GuangYaResp.isSuccess(payload)) {
            val errBody = if (resp.isSuccessful) null else resp.errorBody()?.string()
            throw IllegalStateException(describeLoginFailure("刷新 Token", resp.code(), errBody, payload))
        }
        val newToken = GuangYaResp.dataToken(payload)
        if (newToken.isBlank()) throw IllegalStateException("刷新响应缺少 access_token")
        accessToken = newToken
        // 光鸭若返回新的 refresh_token（轮换策略），一并更新并回写保存
        val newRt = GuangYaResp.dataRefreshToken(payload)
        if (newRt.isNotBlank()) refreshToken = newRt
        val userPayload = userMeChecked()
        return AccountInfo(nickname = extractNickname(userPayload), raw = userPayload.toString())
    }

    /** 把光鸭 HTTP 失败转成用户可读的中文提示 */
    private fun describeLoginFailure(stage: String, code: Int, errBody: String?, body: JsonObject?): String {
        val serverMsg = extractServerMsg(errBody, body)
        val hint = when (code) {
            401 -> "Token 无效或已过期。请重新打开光鸭网页版，复制最新 Authorization 头（不带 Bearer）。"
            403 -> "没有权限（403）。可能 Token 与账号不匹配，请确认复制的 Token 是当前登录账号的。"
            404 -> "接口不存在（404），可能光鸭已变更接口。请告知开发者。"
            429 -> "请求太频繁（429），被限流了。请稍等片刻再试。"
            in 500..599 -> "光鸭服务器错误（$code）。可能是接口临时故障或已变更，请稍后重试。"
            200 -> "请求已到达光鸭，但服务器返回业务错误。请确认 Refresh Token 是最新的（重新登录光鸭网页版复制），或稍后重试。"
            else -> "光鸭拒绝了请求（HTTP $code）。请确认 Token 有效，或稍后重试。"
        }
        return "光鸭 $stage 失败：$hint" + if (serverMsg.isNotBlank()) "\n服务器返回：$serverMsg" else ""
    }

    /** 依次从响应错误体 / 响应体中提取光鸭返回的错误描述；提取不到时回退原始响应文本便于定位 */
    private fun extractServerMsg(errBody: String?, body: JsonObject?): String {
        val rawCandidates = listOfNotNull(errBody?.takeIf { it.isNotBlank() }, body?.toString())
        for (raw in rawCandidates) {
            val msg = runCatching {
                val obj = gson.fromJson(raw, JsonObject::class.java)
                listOf("message", "msg", "error_description", "error", "errorMsg", "errMsg")
                    .firstNotNullOfOrNull { obj.get(it)?.takeIf { j -> j.isJsonPrimitive }?.asString }
            }.getOrNull()
            if (!msg.isNullOrBlank()) return msg
        }
        return rawCandidates.firstOrNull()?.take(300).orEmpty()
    }

    private fun extractNickname(payload: JsonObject): String {
        // 字段优先级与 CASX 一致：nickname → nickName → username → phoneNumber（真实注册名）
        val candidates = listOf("nickname", "nickName", "username", "userName", "phoneNumber", "phone", "name", "account")
        val data = payload.get("data")
        if (data != null && data.isJsonObject) {
            val d = data.asJsonObject
            for (key in candidates) {
                d.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        for (key in candidates) {
            payload.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return "光鸭云盘用户"
    }

    /** 根目录（null/""/"0"/"/"）归一化为空字符串，与 CASX 一致 */
    private fun normParentId(p: String?): String =
        p?.takeIf { it.isNotBlank() && it != "0" && it != "/" && it != "root" } ?: ""

    // ---------- 分享 ----------
    override fun parseShareLink(link: String): Result<ShareInfo> = runCatching {
        val info = GuangYaLinkParser.parse(link)
        if (!info.isValid) throw IllegalArgumentException("无法识别光鸭分享链接")
        info
    }

    override suspend fun resolveShareAccess(share: ShareInfo): Result<ShareInfo> = runCatching {
        // 摘要确认分享存在
        val summary = api.shareSummary(JsonObject().apply { addProperty("shareId", share.shareId) })
        if (!GuangYaResp.isSuccess(summary)) {
            throw IllegalStateException("分享不存在或已失效：${GuangYaResp.message(summary).ifBlank { "请检查链接" }}")
        }
        // 获取访问令牌
        val access = api.shareAccessToken(
            JsonObject().apply {
                addProperty("shareId", share.shareId)
                addProperty("code", share.passcode)
            }
        )
        val token = GuangYaResp.dataToken(access)
        if (token.isBlank()) {
            throw IllegalStateException(
                if (share.passcode.isNotBlank()) "提取码错误或分享不可访问"
                else "该分享需要提取码"
            )
        }
        share.copy(accessToken = token)
    }

    override suspend fun fetchShareChildren(share: ShareInfo, parentId: String?): Result<List<DriveFile>> =
        runCatching {
            if (share.accessToken.isBlank()) throw IllegalStateException("缺少分享访问令牌")
            val items = mutableListOf<DriveFile>()
            var page = 1
            while (true) {
                val payload = api.shareFilesList(
                    JsonObject().apply {
                        addProperty("accessToken", share.accessToken)
                        addProperty("parentId", parentId.orEmpty())
                        addProperty("page", page)
                        addProperty("pageSize", 50)
                        addProperty("orderBy", 0)
                        addProperty("sortType", 0)
                    }
                )
                if (!GuangYaResp.isSuccess(payload)) {
                    throw IllegalStateException("获取分享文件列表失败：${GuangYaResp.message(payload)}")
                }
                val batch = GuangYaResp.items(payload)
                if (batch.size() == 0) break
                for (el in batch) {
                    if (!el.isJsonObject) continue
                    val item = el.asJsonObject
                    items += DriveFile(
                        fid = GuangYaResp.fid(item),
                        name = GuangYaResp.name(item),
                        isDir = GuangYaResp.isDir(item),
                        size = GuangYaResp.size(item),
                        parentFid = GuangYaResp.parentId(item).takeIf { it != "0" },
                        source = FileSource.SHARE,
                    )
                }
                if (batch.size() < 50) break
                page++
                if (page > 200) break
            }
            items
        }

    // ---------- 我的文件 ----------
    override suspend fun listFolders(parentId: String?): Result<List<DriveFile>> = runCatching {
        val items = mutableListOf<DriveFile>()
        var page = 0
        while (true) {
            val payload = api.fileList(
                JsonObject().apply {
                    addProperty("parentId", normParentId(parentId))
                    addProperty("page", page)
                    addProperty("pageSize", 50)
                    addProperty("orderBy", 0)
                    addProperty("sortType", 0)
                }
            )
            if (!GuangYaResp.isSuccess(payload)) {
                throw IllegalStateException("获取目录列表失败：${GuangYaResp.message(payload)}")
            }
            val batch = GuangYaResp.items(payload)
            if (batch.size() == 0) break
            for (el in batch) {
                if (!el.isJsonObject) continue
                val item = el.asJsonObject
                items += DriveFile(
                    fid = GuangYaResp.fid(item),
                    name = GuangYaResp.name(item),
                    isDir = GuangYaResp.isDir(item),
                    size = GuangYaResp.size(item),
                    source = FileSource.MINE,
                )
            }
            if (batch.size() < 50) break
            page++
            if (page > 200) break
        }
        items.filter { it.isDir }
    }

    // ---------- 转存 ----------
    override suspend fun restoreShare(
        share: ShareInfo,
        files: List<DriveFile>,
        parentId: String,
    ): Result<List<RestoredFile>> = runCatching {
        val fileIds = files.map { it.fid }
        if (fileIds.isEmpty()) return@runCatching emptyList()
        if (share.accessToken.isBlank()) throw IllegalStateException("缺少分享访问令牌")

        // 1. 记录转存前目录 fid 集合
        val beforeFids = listDirFids(parentId)

        // 2. 发起转存（异步任务）
        val restore = api.restoreShare(
            JsonObject().apply {
                addProperty("accessToken", share.accessToken)
                add("fileIds", gson.toJsonTree(fileIds))
                addProperty("parentId", normParentId(parentId))
            }
        )
        if (!GuangYaResp.isSuccess(restore)) {
            throw IllegalStateException("转存失败：${GuangYaResp.message(restore).ifBlank { "未知错误" }}")
        }

        // 3. 轮询任务完成
        val taskId = GuangYaResp.taskId(restore)
        if (taskId.isNotBlank()) {
            var done = false
            var failed = false
            var failMsg = ""
            repeat(MAX_TASK_POLLS) {
                val status = api.taskStatus(JsonObject().apply { addProperty("taskId", taskId) })
                if (isTaskFailed(status)) {
                    failed = true
                    failMsg = GuangYaResp.message(status)
                    return@repeat
                }
                if (isTaskDone(status)) {
                    done = true
                    return@repeat
                }
                delay(1000)
            }
            if (failed) throw IllegalStateException("转存任务失败：${failMsg.ifBlank { "未知错误" }}")
            if (!done) throw IllegalStateException("转存任务超时，请稍后在网盘中检查")
        }

        // 4. 目录 diff：按转存前文件名顺序对齐新 fid
        val newTop = diffSavedFiles(parentId, beforeFids, fileIds.size)

        // 5. 若转存的是单个文件夹：展开其内部文件，按文件名对齐分享内文件，便于重命名。
        //    返回列表首位是新文件夹本身（供 TransferManager 改名为剧名），其后是内部文件。
        if (files.size == 1 && files[0].isDir && newTop.size == 1 && newTop[0].isDir) {
            val shareFolder = files[0]
            val shareInner = fetchShareChildren(share, shareFolder.fid).getOrThrow()
            val newInner = listDirFiles(newTop[0].fid)
            val newByName = newInner.associateBy { it.name }
            val pairs = shareInner.mapNotNull { s ->
                newByName[s.name]?.let { n -> RestoredFile(n, s.fid, s.name) }
            }
            val result = mutableListOf(
                RestoredFile(newTop[0], shareFolder.fid, shareFolder.name)
            )
            // 若文件名无法对齐，退回按顺序配对
            if (pairs.isNotEmpty()) {
                result += pairs
            } else if (shareInner.isNotEmpty() && newInner.isNotEmpty()) {
                result += shareInner.zip(newInner).map { (s, n) -> RestoredFile(n, s.fid, s.name) }
            }
            return@runCatching result
        }

        // 6. 普通情况：新条目与 files 顺序一一对应
        newTop.mapIndexed { i, f ->
            val origin = files.getOrNull(i)
            RestoredFile(f, origin?.fid ?: "", origin?.name ?: "")
        }
    }

    override suspend fun renameFile(fileId: String, newName: String): Result<Unit> = runCatching {
        val payload = api.rename(
            JsonObject().apply {
                addProperty("fileId", fileId)
                addProperty("newName", newName)
            }
        )
        if (!GuangYaResp.isSuccess(payload)) {
            throw IllegalStateException("重命名失败：${GuangYaResp.message(payload).ifBlank { "未知错误" }}")
        }
    }

    // ---------- 内部辅助 ----------

    private suspend fun listDirFids(parentId: String): Set<String> {
        val items = mutableListOf<String>()
        var page = 0
        while (true) {
            val payload = api.fileList(
                JsonObject().apply {
                    addProperty("parentId", normParentId(parentId))
                    addProperty("page", page)
                    addProperty("pageSize", 50)
                    addProperty("orderBy", 0)
                    addProperty("sortType", 0)
                }
            )
            if (!GuangYaResp.isSuccess(payload)) break
            val batch = GuangYaResp.items(payload)
            if (batch.size() == 0) break
            for (el in batch) {
                if (el.isJsonObject) items += GuangYaResp.fid(el.asJsonObject)
            }
            if (batch.size() < 50) break
            page++
        }
        return items.toSet()
    }

    /** 目录 diff：返回转存后新增的文件（fid + 原名），按传入数量对齐 */
    private suspend fun diffSavedFiles(parentId: String, beforeFids: Set<String>, expectedCount: Int): List<DriveFile> {
        repeat(MAX_DIFF_RETRIES) {
            val after = listDirFiles(parentId)
            val newItems = after.filter { it.fid !in beforeFids }
            if (newItems.isNotEmpty()) return newItems.take(expectedCount)
            delay(1000)
        }
        throw IllegalStateException("转存任务完成但未定位到新文件，请在网盘确认后手动重命名")
    }

    private suspend fun listDirFiles(parentId: String): List<DriveFile> {
        val items = mutableListOf<DriveFile>()
        var page = 0
        while (true) {
            val payload = api.fileList(
                JsonObject().apply {
                    addProperty("parentId", normParentId(parentId))
                    addProperty("page", page)
                    addProperty("pageSize", 50)
                    addProperty("orderBy", 0)
                    addProperty("sortType", 0)
                }
            )
            if (!GuangYaResp.isSuccess(payload)) break
            val batch = GuangYaResp.items(payload)
            if (batch.size() == 0) break
            for (el in batch) {
                if (!el.isJsonObject) continue
                val item = el.asJsonObject
                items += DriveFile(
                    fid = GuangYaResp.fid(item),
                    name = GuangYaResp.name(item),
                    isDir = GuangYaResp.isDir(item),
                    source = FileSource.SAVED,
                )
            }
            if (batch.size() < 50) break
            page++
        }
        return items
    }

    private fun isTaskDone(payload: JsonObject): Boolean {
        val status = taskStatusValue(payload)
        if (status in setOf("2", "3", "4", "done", "success", "completed", "finish", "finished")) return true
        val msg = GuangYaResp.message(payload).lowercase()
        return msg.contains("完成") || msg.contains("成功") || msg.contains("finished") || msg.contains("success")
    }

    private fun isTaskFailed(payload: JsonObject): Boolean {
        val status = taskStatusValue(payload)
        if (status in setOf("5", "-1", "failed", "error")) return true
        val msg = GuangYaResp.message(payload).lowercase()
        return msg.contains("失败") || msg.contains("error") || msg.contains("failed")
    }

    private fun taskStatusValue(payload: JsonObject): String {
        val data = payload.get("data")
        if (data != null && data.isJsonObject) {
            for (key in listOf("status", "taskStatus", "state")) {
                data.asJsonObject.get(key)?.asString?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        for (key in listOf("status", "taskStatus", "state")) {
            payload.get(key)?.asString?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    companion object {
        private const val WEB_ORIGIN = "https://www.guangyapan.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
        private const val CLIENT_ID = "aMe-8VSlkrbQXpUR"
        private const val MAX_TASK_POLLS = 20
        private const val MAX_DIFF_RETRIES = 15

        private fun splitCredential(credential: String): Pair<String, String> {
            val parts = credential.split("||", limit = 2)
            return parts.getOrElse(0) { "" }.trim() to parts.getOrElse(1) { "" }.trim()
        }

        private fun randomHex(len: Int): String {
            val chars = "0123456789abcdef"
            return (1..len).map { chars.random() }.joinToString("")
        }

        /** 与 CASX generate_did() 一致：md5(token_hex(16)) -> 32 位小写 hex，实例生命周期内固定 */
        private val deviceId: String by lazy { generateDid() }

        private fun generateDid(): String {
            val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val digest = java.security.MessageDigest.getInstance("MD5").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
