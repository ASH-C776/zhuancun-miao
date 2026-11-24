package com.ashchan.cloudsaver.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 更新源：读 Release 列表 + 下载 APK。
 *
 * 界面层只认这个接口，不认识 Gitee / GitHub 的域名与 JSON 结构。
 * **失败全部静默**：检测是锦上添花，网络不通、结构变了、解析失败一律吞掉，不能变成用户看到的报错。
 */
interface UpdateSource {
    /** 检测并返回「有更新的且未被取消过」的版本；没有则 null */
    suspend fun check(currentCode: Int, dismissedCode: Int): UpdateInfo?

    /** 下载 APK；成功返回本地文件，失败删除半截文件并返回 null。进度按整数上报 */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File?
}

/**
 * 双端（Gitee + GitHub）Release 实现。
 *
 * 仓库坐标见 [GITHUB_REPO] / [GITEE_REPO]；Gitee 留空表示暂不发那边，只靠 GitHub。
 * 两端接口结构一致，可以复用同一个解析器。
 */
class DefaultUpdateSource(private val context: Context) : UpdateSource {

    companion object {
        /** GitHub `owner/repo` */
        const val GITHUB_REPO = "ASH-C776/zhuancun-miao"
        /** Gitee `owner/repo`；与 GitHub 同名镜像，同版本优先用 Gitee（国内下载快） */
        const val GITEE_REPO = "kijeye/zhuancun-miao"

        private const val GITHUB_API = "https://api.github.com/repos/%s/releases"
        private const val GITEE_API = "https://gitee.com/api/v5/repos/%s/releases"
    }

    /**
     * 独立的干净 HTTP 客户端：不复用业务客户端（那边挂着 NAS token 拦截器与自签证书 pin），
     * 后台任务也不该被慢慢拖住。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun check(currentCode: Int, dismissedCode: Int): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val candidates = coroutineScope {
                val gitee = async {
                    runCatching { fetchReleases(giteeUrl(), "gitee") }.getOrNull().orEmpty()
                }
                val github = async {
                    runCatching { fetchReleases(githubUrl(), "github") }.getOrNull().orEmpty()
                }
                gitee.await() + github.await()
            }
            val latest = UpdateRules.pickLatest(candidates, currentCode) ?: return@withContext null
            // 用户点过取消的版本不再弹；更高版本仍会弹
            if (latest.versionCode == dismissedCode) null else latest
        }

    private fun githubUrl(): String = GITHUB_API.format(GITHUB_REPO)

    private fun giteeUrl(): String = if (GITEE_REPO.isBlank()) "" else GITEE_API.format(GITEE_REPO)

    /** 拉一端的 Release 列表并解析成候选（prerelease / draft 直接跳过，不推测试包） */
    private fun fetchReleases(apiUrl: String, source: String): List<UpdateInfo> {
        if (apiUrl.isBlank()) return emptyList()
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "zhuancun-miao-app")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            val arr = JSONArray(body)
            val out = mutableListOf<UpdateInfo>()
            for (i in 0 until arr.length()) {
                val rel = arr.getJSONObject(i)
                if (rel.optBoolean("prerelease") || rel.optBoolean("draft")) continue
                val code = UpdateRules.parseVersionCode(rel.optString("tag_name")) ?: continue
                val assets = rel.optJSONArray("assets") ?: continue
                var url: String? = null
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name = asset.optString("name")
                    // 只认 .apk 附件，排除 Gitee 自动生成的 source.zip / tar.gz
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        url = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                        if (url != null) break
                    }
                }
                if (url == null) continue
                out += UpdateInfo(
                    versionName = rel.optString("tag_name").trim().removePrefix("v").removePrefix("V"),
                    versionCode = code,
                    downloadUrl = url,
                    source = source,
                )
            }
            return out
        }
    }

    override suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "update").apply { mkdirs() }
            val file = File(dir, "转存喵-${info.versionName}.apk")
            var lastPercent = -1
            try {
                val request = Request.Builder().url(info.downloadUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val total = response.body?.contentLength() ?: 0L
                    val body = response.body ?: return@withContext null
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total > 0) {
                                    val percent = (written * 100 / total).toInt()
                                    // 只在整数变化时上报，避免每 64KB 触发一次重组
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(percent.coerceIn(0, 100))
                                    }
                                }
                            }
                        }
                    }
                    // 两道校验：有 Content-Length 时必须对得上；最小 1KB 防 0 字节 / HTML 错误页
                    if (total > 0 && file.length() != total) {
                        file.delete()
                        return@withContext null
                    }
                }
                if (file.length() < 1024) {
                    file.delete()
                    return@withContext null
                }
                onProgress(100)
                file
            } catch (_: Exception) {
                runCatching { file.delete() }
                null
            }
        }
}
