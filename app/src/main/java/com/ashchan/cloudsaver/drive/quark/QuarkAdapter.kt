package com.ashchan.cloudsaver.drive.quark

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
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 夸克网盘适配器。
 *
 * 登录：Cookie（建议包含 kps/sign/vcode，移动端分享兼容）。
 * 转存：sharepage/save 返回异步 task_id，轮询 /1/clouddrive/task 直到 status==2，
 *       新 fid 优先取任务响应 save_as.save_as_top_fids，缺失则目录 diff。
 */
class QuarkAdapter(private var cookie: String = "") : CloudDriveAdapter {

    override val driveType: DriveType = DriveType.QUARK

    private val gson = Gson()

    /** cookie 中的移动端参数 */
    private val mparam: Map<String, String> by lazy { matchMparam(cookie) }

    private val api: QuarkApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                var url = chain.request().url

                // 分享接口 + 移动端参数 → 切换移动端域名并附加设备参数（CASX 逻辑）
                if (mparam.isNotEmpty() && url.encodedPath.contains("share") && url.host == PC_HOST) {
                    val mobileParams = linkedMapOf(
                        "device_model" to "M2011K2C",
                        "entry" to "default_clouddrive",
                        "_t_group" to "0%3A_s_vp%3A1",
                        "dmn" to "Mi%2B11",
                        "fr" to "android",
                        "pf" to "3300",
                        "bi" to "35937",
                        "ve" to "7.4.5.680",
                        "ss" to "411x875",
                        "mi" to "M2011K2C",
                        "nt" to "5",
                        "nw" to "0",
                        "kt" to "4",
                        "pr" to "ucpro",
                        "sv" to "release",
                        "dt" to "phone",
                        "data_from" to "ucapi",
                        "kps" to mparam["kps"].orEmpty(),
                        "sign" to mparam["sign"].orEmpty(),
                        "vcode" to mparam["vcode"].orEmpty(),
                        "app" to "clouddrive",
                        "kkkk" to "1",
                    )
                    val builder = url.newBuilder().host(MOBILE_HOST)
                    mobileParams.forEach { (k, v) -> builder.addQueryParameter(k, v) }
                    url = builder.build()
                }

                val req = chain.request().newBuilder()
                    .url(url)
                    .header("content-type", "application/json")
                    .header("user-agent", USER_AGENT)
                    .apply {
                        if (!(mparam.isNotEmpty() && url.host == MOBILE_HOST)) {
                            header("cookie", cookie)
                        }
                    }
                    .build()
                chain.proceed(req)
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://$PC_HOST/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(QuarkApi::class.java)
    }

    // ---------- 登录 ----------
    override suspend fun validateLogin(credential: String): Result<AccountInfo> = runCatching {
        cookie = credential.trim()
        if (cookie.isBlank()) throw IllegalStateException("Cookie 不能为空")
        val payload = api.accountInfo()
        if (!isOk(payload)) throw IllegalStateException("Cookie 无效或已过期（code=${payload.get("code")?.asString ?: "?"}）")
        val data = payload.getAsJsonObject("data")
        val nickname = data?.get("nickname")?.asString
            ?: data?.get("nick_name")?.asString
            ?: "夸克用户"
        AccountInfo(nickname = nickname, raw = payload.toString())
    }

    // ---------- 分享 ----------
    override fun parseShareLink(link: String): Result<ShareInfo> = runCatching {
        val decoded = URLDecoder.decode(link, "UTF-8")
        val pwdId = Regex("""/s/(\w+)""").find(decoded)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("无法识别夸克分享链接（需包含 /s/ 路径）")
        val passcode = Regex("""pwd=(\w+)""").find(decoded)?.groupValues?.get(1).orEmpty()

        // 分享内初始目录：路径中的 32 位 fid
        var pdirFid = "0"
        Regex("""/(\w{32})-?([^/]+)?""").findAll(decoded).toList().lastOrNull()?.let { m ->
            pdirFid = m.groupValues[1]
        }

        ShareInfo(
            driveType = DriveType.QUARK,
            shareId = pwdId,
            passcode = passcode,
            pdirFid = pdirFid,
            raw = link,
        )
    }

    override suspend fun resolveShareAccess(share: ShareInfo): Result<ShareInfo> = runCatching {
        val payload = api.shareToken(
            JsonObject().apply {
                addProperty("pwd_id", share.shareId)
                addProperty("passcode", share.passcode)
            }
        )
        if (!isOk(payload)) {
            throw IllegalStateException(
                when (payload.get("code")?.asInt) {
                    0 -> "分享获取失败"
                    40001 -> "需要提取码"
                    40002 -> "提取码错误"
                    else -> "获取分享令牌失败：${message(payload)}"
                }
            )
        }
        val stoken = payload.getAsJsonObject("data")?.get("stoken")?.asString
            ?: throw IllegalStateException("分享令牌响应缺少 stoken")
        share.copy(accessToken = stoken)
    }

    override suspend fun fetchShareChildren(share: ShareInfo, parentId: String?): Result<List<DriveFile>> =
        runCatching {
            if (share.accessToken.isBlank()) throw IllegalStateException("缺少分享令牌")
            val items = mutableListOf<DriveFile>()
            var page = 1
            while (true) {
                val params = mutableMapOf(
                    "pr" to "ucpro",
                    "fr" to "pc",
                    "pwd_id" to share.shareId,
                    "stoken" to share.accessToken,
                    "pdir_fid" to (parentId ?: share.pdirFid).takeIf { it != "0" }.orEmpty(),
                    "force" to "0",
                    "_page" to page.toString(),
                    "_size" to "50",
                    "_fetch_banner" to "0",
                    "_fetch_share" to "0",
                    "_fetch_total" to "1",
                    "_sort" to "file_type:asc,updated_at:desc",
                    "ver" to "2",
                    "fetch_share_full_path" to "0",
                )
                val payload = api.shareDetail(params)
                if (!isOk(payload)) {
                    throw IllegalStateException("获取分享文件列表失败：${message(payload)}")
                }
                val data = payload.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                val batch = data?.getAsJsonArray("list") ?: break
                if (batch.size() == 0) break
                for (el in batch) {
                    if (!el.isJsonObject) continue
                    val item = el.asJsonObject
                    items += DriveFile(
                        fid = item.get("fid")?.asString ?: continue,
                        name = item.get("file_name")?.asString ?: continue,
                        isDir = item.get("dir")?.asBoolean ?: false,
                        size = item.get("size")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L,
                        fidToken = item.get("fid_token")?.asString,
                        source = FileSource.SHARE,
                    )
                }
                val total = data.getAsJsonObject("metadata")?.get("_total")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0
                if (items.size >= total || batch.size() < 50) break
                page++
                if (page > 200) break
            }
            items
        }

    // ---------- 我的文件 ----------
    override suspend fun listFolders(parentId: String?): Result<List<DriveFile>> = runCatching {
        listDirFiles(parentId).filter { it.isDir }
    }

    override suspend fun listFiles(parentId: String?): Result<List<DriveFile>> = runCatching {
        listDirFiles(parentId)
    }

    // ---------- 转存 ----------
    override suspend fun restoreShare(
        share: ShareInfo,
        files: List<DriveFile>,
        parentId: String,
    ): Result<List<RestoredFile>> = runCatching {
        if (files.isEmpty()) return@runCatching emptyList()
        if (share.accessToken.isBlank()) throw IllegalStateException("缺少分享令牌")

        // 分享文件列表需要 fid_token（从选择列表携带）
        // 转存前记录目录 fid 集合
        val beforeFids = listDirFiles(parentId).map { it.fid }.toSet()

        val payload = api.shareSave(
            JsonObject().apply {
                add("fid_list", gson.toJsonTree(files.map { it.fid }))
                add("fid_token_list", gson.toJsonTree(files.map { it.fidToken.orEmpty() }))
                addProperty("to_pdir_fid", parentId)
                addProperty("pwd_id", share.shareId)
                addProperty("stoken", share.accessToken)
                addProperty("pdir_fid", "0")
                addProperty("scene", "link")
            },
            mapOf(
                "pr" to "ucpro",
                "fr" to "pc",
                "uc_param_str" to "",
                "app" to "clouddrive",
                "__dt" to (System.currentTimeMillis() % 300000).toString(),
                "__t" to (System.currentTimeMillis() / 1000).toString(),
            )
        )
        if (!isOk(payload)) {
            throw IllegalStateException("转存失败：${message(payload).ifBlank { "code=${payload.get("code")}" }}")
        }

        val taskId = payload.getAsJsonObject("data")?.get("task_id")?.asString.orEmpty()
        var savedFids: List<String> = emptyList()

        if (taskId.isNotBlank()) {
            // 轮询任务
            var status = -1
            var lastMsg = ""
            repeat(MAX_TASK_POLLS) {
                val task = queryTask(taskId)
                if (!isOk(task)) {
                    lastMsg = message(task)
                    return@repeat
                }
                status = task.getAsJsonObject("data")?.get("status")?.asInt ?: -1
                if (status == 2) {
                    // 任务完成：优先取任务响应的新 fid 列表
                    val saveAs = task.getAsJsonObject("data")?.getAsJsonObject("save_as")
                    val topFids = saveAs?.getAsJsonArray("save_as_top_fids")
                    if (topFids != null && topFids.size() > 0) {
                        savedFids = topFids.mapNotNull { it.asString }
                    }
                    return@repeat
                }
                if (status in setOf(3, 4, 5, -1)) {
                    lastMsg = "任务状态异常($status)"
                    return@repeat
                }
                delay(500)
            }
            if (status != 2) {
                throw IllegalStateException("转存任务未完成：${lastMsg.ifBlank { "超时" }}，请稍后在网盘确认")
            }
        }

        // 任务响应未提供新 fid → 目录 diff 对齐
        val newFiles = if (savedFids.isNotEmpty()) {
            savedFids.mapIndexed { index, fid ->
                DriveFile(
                    fid = fid,
                    name = files.getOrNull(index)?.name ?: "saved_$index",
                    isDir = false,
                    source = FileSource.SAVED,
                )
            }
        } else {
            diffSavedFiles(parentId, beforeFids, files.size)
        }
        // 统一包装为 RestoredFile（携带对应分享文件 fid 与原名，用于重命名映射）
        newFiles.mapIndexed { index, f ->
            val origin = files.getOrNull(index)
            RestoredFile(f, origin?.fid ?: "", origin?.name ?: "")
        }
    }

    override suspend fun renameFile(fileId: String, newName: String): Result<Unit> = runCatching {
        val payload = api.rename(
            JsonObject().apply {
                addProperty("fid", fileId)
                addProperty("file_name", newName)
            }
        )
        if (!isOk(payload)) {
            throw IllegalStateException("重命名失败：${message(payload).ifBlank { "code=${payload.get("code")}" }}")
        }
    }

    // ---------- 内部辅助 ----------

    private suspend fun queryTask(taskId: String): JsonObject {
        val url = "https://$PC_HOST/1/clouddrive/task"
        return api.task(
            url,
            mapOf(
                "pr" to "ucpro",
                "fr" to "pc",
                "uc_param_str" to "",
                "task_id" to taskId,
                "retry_index" to "0",
                "__dt" to (System.currentTimeMillis() % 300000).toString(),
                "__t" to (System.currentTimeMillis() / 1000).toString(),
            )
        )
    }

    private suspend fun listDirFiles(parentId: String?): List<DriveFile> {
        val items = mutableListOf<DriveFile>()
        var page = 1
        while (true) {
            val payload = api.fileSort(
                mapOf(
                    "pr" to "ucpro",
                    "fr" to "pc",
                    "uc_param_str" to "",
                    "pdir_fid" to (parentId ?: "0"),
                    "_page" to page.toString(),
                    "_size" to "50",
                    "_fetch_total" to "1",
                    "_fetch_sub_dirs" to "0",
                    "_sort" to "file_type:asc,updated_at:desc",
                    "_fetch_full_path" to "0",
                    "fetch_all_file" to "1",
                    "fetch_risk_file_name" to "1",
                )
            )
            if (!isOk(payload)) break
            val data = payload.getAsJsonObject("data")
            val batch = data?.getAsJsonArray("list") ?: break
            if (batch.size() == 0) break
            for (el in batch) {
                if (!el.isJsonObject) continue
                val item = el.asJsonObject
                items += DriveFile(
                    fid = item.get("fid")?.asString ?: continue,
                    name = item.get("file_name")?.asString ?: continue,
                    isDir = item.get("dir")?.asBoolean ?: false,
                    size = item.get("size")?.asLong ?: 0L,
                    source = FileSource.MINE,
                )
            }
            val total = data.getAsJsonObject("metadata")?.get("_total")?.asInt ?: 0
            if (items.size >= total || batch.size() < 50) break
            page++
            if (page > 200) break
        }
        return items
    }

    private suspend fun diffSavedFiles(parentId: String, beforeFids: Set<String>, expectedCount: Int): List<DriveFile> {
        repeat(MAX_DIFF_RETRIES) {
            val after = listDirFiles(parentId)
            val newItems = after.filter { it.fid !in beforeFids }
            if (newItems.isNotEmpty()) return newItems.take(expectedCount)
            delay(500)
        }
        throw IllegalStateException("转存完成但未定位到新文件，请在网盘确认后手动重命名")
    }

    private fun isOk(payload: JsonObject): Boolean {
        // 兼容：夸克部分接口 code 为数字 0，部分返回字符串 "OK"/"ok"
        val code = payload.get("code")
        if (code != null && code.isJsonPrimitive) {
            val p = code.asJsonPrimitive
            if (p.isNumber) return p.asInt == 0
            return p.asString.trim().lowercase() in setOf("0", "ok", "success")
        }
        val status = payload.get("status")
        if (status != null && status.isJsonPrimitive) {
            val p = status.asJsonPrimitive
            if (p.isNumber) return p.asInt == 200
            return p.asString.trim().lowercase() in setOf("200", "ok", "success")
        }
        return false
    }

    private fun message(payload: JsonObject): String {
        payload.get("message")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { return it }
        val data = payload.get("data")
        if (data != null && data.isJsonObject) {
            data.asJsonObject.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    companion object {
        private const val PC_HOST = "drive-pc.quark.cn"
        private const val MOBILE_HOST = "drive-m.quark.cn"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/3.14.2 Chrome/112.0.5615.165 Electron/24.1.3.8 Safari/537.36 Channel/pckk_other_ch"
        private const val MAX_TASK_POLLS = 60
        private const val MAX_DIFF_RETRIES = 20

        private fun matchMparam(cookie: String): Map<String, String> {
            fun grab(name: String): String? =
                Regex("(?<!\\w)$name=([a-zA-Z0-9%+/=]+)[;&]?").find(cookie)?.groupValues?.get(1)
                    ?.replace("%25", "%")
            val kps = grab("kps") ?: return emptyMap()
            val sign = grab("sign") ?: return emptyMap()
            val vcode = grab("vcode") ?: return emptyMap()
            return mapOf("kps" to kps, "sign" to sign, "vcode" to vcode)
        }
    }
}
