package com.ashchan.cloudsaver.drive.guangya

import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.model.FileSource
import com.ashchan.cloudsaver.core.model.ShareInfo
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.util.UUID

/** 光鸭响应解析辅助（移植自 CASX 的 _is_success/_extract_message/_deep_get 语义，逻辑重写） */
internal object GuangYaResp {

    fun isSuccess(payload: JsonObject?): Boolean {
        if (payload == null) return false
        // 扁平 token 响应（如刷新返回 {"token_type":"Bearer","access_token":...}）视为成功
        if (topLevelToken(payload).isNotBlank()) return true
        if (payload.get("success")?.asBoolean == true) return true
        if (payload.get("ok")?.asBoolean == true) return true
        // 结构化状态码字段：明确成功值判成功；明确错误码判失败；业务状态值（如 status="ACTIVE"）忽略
        for (key in listOf("code", "status", "result")) {
            val el = payload.get(key) ?: continue
            if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) {
                val v = el.asInt
                if (v in setOf(0, 200)) return true
                if (v < 0 || v >= 400) return false
            } else if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                val s = el.asString.trim().lowercase()
                if (s in setOf("0", "200", "ok", "success")) return true
                if (s in setOf("error", "failed", "fail", "-1")) return false
                // 其他（如 ACTIVE / INACTIVE）是业务状态，忽略继续
            }
        }
        // 无状态码时，看错误文案字段
        for (key in listOf("message", "msg", "error", "errorMsg", "errMsg")) {
            val v = payload.get(key)?.asString
            if (!v.isNullOrBlank() && v.trim().lowercase() !in setOf("success", "ok", "200", "0")) return false
        }
        // data 存在且非空 → 成功
        val data = payload.get("data")
        if (data != null && !data.isJsonNull) return true
        // 其余：有内容的 JSON 对象（如 user/me 返回的裸用户对象）视为成功；空对象视为失败
        return payload.keySet().isNotEmpty()
    }

    fun message(payload: JsonObject?): String {
        if (payload == null) return ""
        for (key in listOf("message", "msg")) {
            val v = payload.get(key)?.asString
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    /** 提取 access_token：优先 data 内，其次顶层（扁平 token 响应） */
    fun dataToken(payload: JsonObject?): String {
        val data = payload?.get("data") ?: return topLevelToken(payload)
        if (data.isJsonPrimitive) return data.asString.trim()
        if (data.isJsonObject) {
            for (key in listOf("access_token", "accessToken", "token")) {
                val v = data.asJsonObject.get(key)?.asString
                if (!v.isNullOrBlank()) return v.trim()
            }
        }
        return topLevelToken(payload)
    }

    /** 顶层 access_token / accessToken / token */
    private fun topLevelToken(payload: JsonObject?): String {
        if (payload == null) return ""
        for (key in listOf("access_token", "accessToken", "token")) {
            val v = payload.get(key)?.asString
            if (!v.isNullOrBlank()) return v.trim()
        }
        return ""
    }

    /** 提取响应中的 refresh_token（可能位于 data 内或顶层） */
    fun dataRefreshToken(payload: JsonObject?): String {
        if (payload == null) return ""
        val data = payload.get("data")
        if (data != null && data.isJsonObject) {
            for (key in listOf("refresh_token", "refreshToken")) {
                val v = data.asJsonObject.get(key)?.asString
                if (!v.isNullOrBlank()) return v.trim()
            }
        }
        for (key in listOf("refresh_token", "refreshToken")) {
            val v = payload.get(key)?.asString
            if (!v.isNullOrBlank()) return v.trim()
        }
        return ""
    }

    /** 提取 taskId */
    fun taskId(payload: JsonObject?): String {
        val data = payload?.get("data")
        if (data != null && data.isJsonObject) {
            for (key in listOf("taskId", "task_id", "id")) {
                val v = data.asJsonObject.get(key)?.asString
                if (!v.isNullOrBlank()) return v.trim()
            }
        }
        if (payload != null) {
            for (key in listOf("taskId", "task_id", "id")) {
                val v = payload.get(key)?.asString
                if (!v.isNullOrBlank()) return v.trim()
            }
        }
        return ""
    }

    /** 提取列表项数组：data 是数组 / data 下常见列表字段 / 顶层列表字段 */
    fun items(payload: JsonObject?): JsonArray {
        if (payload == null) return JsonArray()
        val data = payload.get("data")
        if (data == null || data.isJsonNull) return topLevelList(payload)
        if (data.isJsonArray) return data.asJsonArray
        if (data.isJsonObject) {
            for (key in listOf("list", "items", "records", "rows", "fileList", "files")) {
                val v = data.asJsonObject.get(key)
                if (v != null && v.isJsonArray) return v.asJsonArray
            }
        }
        return JsonArray()
    }

    private fun topLevelList(obj: JsonObject): JsonArray {
        for (key in listOf("list", "items", "records", "rows", "fileList", "files")) {
            val v = obj.get(key)
            if (v != null && v.isJsonArray) return v.asJsonArray
        }
        return JsonArray()
    }

    // ---------- 字段提取（CASX _pick_* 语义） ----------
    fun fid(item: JsonObject): String = pick(item, "fid", "fileId", "id", "resId") ?: "0"

    fun name(item: JsonObject): String = pick(item, "file_name", "fileName", "name", "title").orEmpty()

    fun parentId(item: JsonObject): String = pick(item, "parentId", "pdir_fid", "parent_id") ?: "0"

    fun size(item: JsonObject): Long {
        val v = pick(item, "size", "fileSize", "file_size")
        return v?.toLongOrNull() ?: 0L
    }

    fun isDir(item: JsonObject): Boolean {
        for (key in listOf("dir", "isDir", "isFolder", "folder")) {
            val el = item.get(key) ?: continue
            if (el.isJsonPrimitive) {
                if (el.asJsonPrimitive.isBoolean) return el.asBoolean
                if (el.asJsonPrimitive.isString) {
                    val s = el.asString.lowercase()
                    if (s in setOf("true", "1")) return true
                    if (s in setOf("false", "0")) return false
                }
            }
        }
        val fileType = pick(item, "fileType", "type")?.lowercase()
        if (fileType in setOf("0", "dir", "folder", "directory")) return true
        val resType = pick(item, "resType")?.lowercase()
        if (resType in setOf("2", "dir", "folder", "directory")) return true
        return false
    }

    private fun pick(item: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val el = item.get(key) ?: continue
            if (el.isJsonPrimitive && !el.asJsonPrimitive.isBoolean && !el.asJsonPrimitive.isNumber) {
                val s = el.asString
                if (!s.isNullOrBlank()) return s
            } else if (el.isJsonPrimitive) {
                return el.asString
            }
        }
        return null
    }
}

/** 光鸭分享链接解析（移植自 CASX extract_url） */
internal object GuangYaLinkParser {

    fun parse(raw: String): ShareInfo {
        val compact = URLDecoder.decode(raw.replace("？", "?").replace("＆", "&"), "UTF-8")
            .replace(Regex("\\s+"), "")

        // 提取码
        var passcode = ""
        val passcodePatterns = listOf(
            Regex("""[（(]提取码[：:]\s*([a-zA-Z0-9]{4,8})[)）]""", RegexOption.IGNORE_CASE),
            Regex("""[（(]访问码[：:]\s*([a-zA-Z0-9]{4,8})[)）]""", RegexOption.IGNORE_CASE),
            Regex("""提取码[：:]\s*([a-zA-Z0-9]{4,8})""", RegexOption.IGNORE_CASE),
            Regex("""访问码[：:]\s*([a-zA-Z0-9]{4,8})""", RegexOption.IGNORE_CASE),
        )
        var cursor = compact
        for (p in passcodePatterns) {
            val m = p.find(cursor) ?: continue
            passcode = m.groupValues[1]
            cursor = cursor.replace(m.value, "", ignoreCase = true)
            break
        }
        var link = Regex("""(https?://[^\s]*guangyapan\.com[^\s]*)""", RegexOption.IGNORE_CASE)
            .find(cursor)?.groupValues?.get(1)
            ?: if (cursor.contains("guangyapan.com", ignoreCase = true)) {
                if (cursor.startsWith("http")) cursor else "https://${cursor.trimStart('/')}"
            } else {
                raw
            }

        // shareId
        var shareId = ""
        val queryParams = parseQuery(link)
        if (passcode.isBlank()) {
            for (key in listOf("pwd", "code", "passcode", "accessCode")) {
                val v = queryParams[key]
                if (!v.isNullOrBlank()) {
                    passcode = v
                    break
                }
            }
        }
        for (key in listOf("shareId", "share_id", "id", "sid")) {
            val v = queryParams[key]
            if (!v.isNullOrBlank()) {
                shareId = v
                break
            }
        }
        if (shareId.isBlank()) {
            val path = link.substringBefore("?").substringBefore("#")
            for (p in listOf(
                Regex("""/share/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
                Regex("""/s/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
                Regex("""/link/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
                Regex("""/download/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
            )) {
                val m = p.find(path)
                if (m != null) {
                    shareId = m.groupValues[1]
                    break
                }
            }
        }

        // 分享内初始目录
        var pdirFid = "0"
        for (key in listOf("parentId", "parent_id", "pdir_fid", "fid", "fileId")) {
            val v = queryParams[key]
            if (!v.isNullOrBlank()) {
                pdirFid = v
                break
            }
        }
        if (pdirFid == "0") {
            val fragment = link.substringAfter("#", "")
            val fq = parseQuery(fragment)
            for (key in listOf("parentId", "parent_id", "pdir_fid", "fid", "fileId")) {
                val v = fq[key]
                if (!v.isNullOrBlank()) {
                    pdirFid = v
                    break
                }
            }
        }

        return ShareInfo(
            driveType = DriveType.GUANGYA,
            shareId = shareId,
            passcode = passcode,
            pdirFid = pdirFid,
            raw = raw,
        )
    }

    private fun parseQuery(url: String): Map<String, String> {
        val q = url.substringAfter("?", "").substringBefore("#")
        if (q.isBlank()) return emptyMap()
        return q.split("&").mapNotNull { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size != 2) null else kv[0] to URLDecoder.decode(kv[1], "UTF-8")
        }.toMap()
    }
}

/** 设备 ID / traceparent 生成 */
internal object GuangYaHeaders {
    fun newDid(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    fun newTraceparent(): String {
        val traceId = randomHex(32)
        val spanId = randomHex(16)
        return "00-$traceId-$spanId-01"
    }

    private fun randomHex(len: Int): String {
        val bytes = ByteArray(len / 2).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
