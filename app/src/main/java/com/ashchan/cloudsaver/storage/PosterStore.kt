package com.ashchan.cloudsaver.storage

import android.content.Context
import com.ashchan.cloudsaver.data.db.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * TMDB 海报本地缓存。
 *
 * 口径：
 * - 一张图约 30–60KB，按 `tmdbId` 存成 `filesDir/posters/<tmdbId>.jpg`，同一部剧多个任务共用一份；
 * - 只在「用户选中搜索结果、确认绑定」那一刻下载一次；之后卡片/编辑页都读本地文件，不联网；
 * - 删除任务时按引用计数清理：还有别的任务用同一个 tmdbId 就保留；
 * - App 启动做一次孤儿清理，只删本地缓存，不碰网盘。
 */
class PosterStore(context: Context) {

    private val dir = File(context.filesDir, "posters")
    private val http = OkHttpClient()

    /** 该剧的海报本地文件（不保证存在） */
    fun fileFor(tmdbId: Long): File = File(dir, "$tmdbId.jpg")

    /** 有没有可显示的海报：要有 posterPath，也要本地文件真的在 */
    fun available(task: TaskEntity): Boolean {
        val id = task.tmdbId ?: return false
        if (task.posterPath.isBlank()) return false
        return fileFor(id).isFile
    }

    /**
     * 确保本地有这张海报；已有就不再请求。
     * @return 是否最终可用（下载失败/没路径都返回 false，调用方退回占位图）
     */
    suspend fun ensure(tmdbId: Long, posterPath: String): Boolean = withContext(Dispatchers.IO) {
        if (posterPath.isBlank()) return@withContext false
        val target = fileFor(tmdbId)
        if (target.isFile && target.length() > 0) return@withContext true
        runCatching {
            dir.mkdirs()
            val url = "$IMAGE_BASE${posterPath.trim()}"
            val resp = http.newCall(Request.Builder().url(url).get().build()).execute()
            resp.use {
                if (!it.isSuccessful) return@runCatching false
                val body = it.body ?: return@runCatching false
                val tmp = File(dir, "$tmdbId.tmp")
                body.byteStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (tmp.length() <= 0) {
                    tmp.delete()
                    return@runCatching false
                }
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }
            true
        }.getOrDefault(false)
    }

    /** 删掉某个 tmdbId 的缓存（只在没有其它任务引用时调用） */
    fun delete(tmdbId: Long) {
        runCatching { fileFor(tmdbId).delete() }
    }

    /** 孤儿清理：保留 [keep] 里列出的 tmdbId，其余文件删掉 */
    fun gc(keep: Set<Long>) {
        runCatching {
            dir.listFiles()?.forEach { file ->
                val id = file.nameWithoutExtension.toLongOrNull() ?: return@forEach
                if (id !in keep) file.delete()
            }
        }
    }

    companion object {
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w342"
    }
}
