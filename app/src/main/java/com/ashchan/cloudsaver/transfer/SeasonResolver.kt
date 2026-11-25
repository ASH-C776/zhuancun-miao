package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.model.EpisodeParser
import com.ashchan.cloudsaver.core.model.NaturalOrder
import com.ashchan.cloudsaver.core.model.ShareNode
import com.ashchan.cloudsaver.data.SeasonParser

/**
 * 季号解析优先级：**文件名 > 季目录名**。
 *
 * 依据（用户口径）：`S02E01` 这种文件名本身就说明是第二季，比外层文件夹叫什么更可信；
 * 文件夹可能被上传者随手命名（`Season 1` 里塞了 S02 的文件很常见）。
 */
enum class SeasonPrefer { FILE, DIR }

/** 一个视频的季号证据来自哪里 */
enum class SeasonSource { FILE, DIR, BOTH, NONE }

/** 单个视频的季号判定结果 */
data class SeasonItem(
    val node: ShareNode,
    /** 文件名里的季号（`S02E01` → 2，`E05` → null） */
    val fileSeason: Int?,
    /** 最近一层能解析出季号的目录名给出的季号（`Season 2` → 2） */
    val dirSeason: Int?,
    /** 给出 [dirSeason] 的那层目录名（用于弹窗里展示证据） */
    val dirName: String,
) {
    /** 两边都有却说不是同一季 —— 唯一需要人来定的情况 */
    val conflict: Boolean get() = fileSeason != null && dirSeason != null && fileSeason != dirSeason

    val source: SeasonSource
        get() = when {
            fileSeason != null && dirSeason != null -> SeasonSource.BOTH
            fileSeason != null -> SeasonSource.FILE
            dirSeason != null -> SeasonSource.DIR
            else -> SeasonSource.NONE
        }

    /** 按优先级取一个季号；都没线索 → null（落剧名根目录） */
    fun season(prefer: SeasonPrefer): Int? = when (prefer) {
        SeasonPrefer.FILE -> fileSeason ?: dirSeason
        SeasonPrefer.DIR -> dirSeason ?: fileSeason
    }
}

/**
 * 一次「季判定」的完整结果。
 *
 * 判定表（每个视频）：
 * | 文件名季号 | 目录季号 | 处理 |
 * |---|---|---|
 * | 有 | 无 | 用文件名，静默 |
 * | 无 | 有 | 用目录名，静默 |
 * | 有一致 | 有 | 直接用，静默 |
 * | 有冲突 | 有 | 默认按文件名；新建页会弹窗确认 |
 * | 无 | 无 | 落根目录，静默 |
 */
data class SeasonPlan(
    val items: List<SeasonItem>,
    /** 超过深度上限没扫的目录（沿用引擎的失败提示口径） */
    val depthSkipped: List<String> = emptyList(),
) {
    val conflicts: List<SeasonItem> get() = items.filter { it.conflict }
    val conflictCount: Int get() = conflicts.size

    fun groups(prefer: SeasonPrefer): Map<Int?, List<ShareNode>> =
        items.groupBy { it.season(prefer) }.mapValues { (_, list) -> list.map { it.node } }

    /** 季号有真实值（含 0 = 特别版）的季数 */
    fun seasonCount(prefer: SeasonPrefer): Int =
        items.mapNotNull { it.season(prefer) }.distinct().size

    /** 一行日志：季判定：Season 1 × 12（按文件名）、Season 2 × 8（按目录名）、3 个冲突 */
    fun summary(prefer: SeasonPrefer): String {
        val bySeason = items.groupBy { it.season(prefer) }.entries
            .filter { it.key != null }
            .sortedBy { it.key }
        val parts = bySeason.map { (season, list) ->
            val fileCnt = list.count { it.source == SeasonSource.FILE || it.source == SeasonSource.BOTH }
            val dirCnt = list.count { it.source == SeasonSource.DIR }
            "Season $season × ${list.size}" + when {
                fileCnt > 0 && dirCnt > 0 -> "（按文件名 $fileCnt / 按目录名 $dirCnt）"
                dirCnt > 0 -> "（按目录名）"
                else -> "（按文件名）"
            }
        }
        val unknown = items.count { it.season(prefer) == null }
        return buildString {
            append("季判定：")
            append(if (parts.isEmpty()) "没有季号线索" else parts.joinToString("、"))
            if (unknown > 0) append("\n无季号 $unknown 个（留在剧名目录下）")
            if (conflictCount > 0) {
                append("\n").append(conflictCount).append(" 个季号冲突（已按")
                append(if (prefer == SeasonPrefer.FILE) "文件名" else "目录名").append("）")
            }
        }
    }

    /** 弹窗里展示的证据（最多 [limit] 条） */
    fun conflictExamples(limit: Int = 3): List<String> =
        conflicts.take(limit).map { item ->
            val dir = item.dirName.ifBlank { "分享目录" }
            "$dir/${item.node.name} —— 文件名是 S%02d，目录写的是第 %d 季".format(item.fileSeason, item.dirSeason)
        }
}

/** 预检结果：只在**季号冲突**时给 UI 弹窗用 */
data class SeasonConflictInfo(
    val count: Int,
    val totalVideos: Int,
    val examples: List<String>,
    val summary: String,
)

object SeasonResolver {

    /**
     * 扫一遍分享树，给每个视频判定季号。
     *
     * @param maxDepth 扫描深度上限（与引擎一致，默认 5）
     */
    fun resolve(tree: ShareNode, maxDepth: Int = 5): SeasonPlan {
        val items = mutableListOf<SeasonItem>()
        val skipped = mutableListOf<String>()
        walk(tree, items, skipped, inherited = null, inheritedName = "", depth = 0, maxDepth = maxDepth)
        return SeasonPlan(items, skipped.toList())
    }

    private fun walk(
        node: ShareNode,
        out: MutableList<SeasonItem>,
        skipped: MutableList<String>,
        inherited: Int?,
        inheritedName: String,
        depth: Int,
        maxDepth: Int,
    ) {
        node.children.sortedWith(compareBy(NaturalOrder) { it.name }).forEach { child ->
            if (child.isDir) {
                if (depth + 1 > maxDepth) {
                    skipped += "深度超限，跳过「${child.name}」"
                    return@forEach
                }
                val dirSeason = SeasonParser.parse(child.name)
                walk(
                    node = child,
                    out = out,
                    skipped = skipped,
                    inherited = dirSeason ?: inherited,
                    inheritedName = if (dirSeason != null) child.name else inheritedName,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                )
            } else if (child.isVideo) {
                out += SeasonItem(
                    node = child,
                    fileSeason = EpisodeParser.seasonOf(child.name),
                    dirSeason = inherited,
                    dirName = inheritedName,
                )
            }
        }
    }
}

