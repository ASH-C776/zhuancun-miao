package com.ashchan.cloudsaver.data

/**
 * 一个任务的分享链接列表整理（纯函数，方便单测）。
 *
 * 口径：一个任务最多 5 个源；空行视为未填、直接丢弃；重复链接只留第一次出现的那行；
 * 一行都没有时退回任务自己的 [fallback]（老数据只有 tasks.shareUrl 时仍能跑）。
 */
object SourceLinks {

    /** 一个任务最多几个分享链接（用户口径：5 个以内，硬限制） */
    const val MAX_SOURCES: Int = 5

    fun normalize(raw: List<String>, fallback: String = ""): List<String> {
        val cleaned = raw.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SOURCES)
        if (cleaned.isNotEmpty()) return cleaned
        return listOf(fallback.trim()).filter { it.isNotEmpty() }
    }
}
