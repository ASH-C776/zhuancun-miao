package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.model.NaturalOrder
import com.ashchan.cloudsaver.core.model.ShareNode
import com.ashchan.cloudsaver.core.model.VideoTypes
import com.ashchan.cloudsaver.data.EpisodeKey

/** 一个视频文件在本轮转存里的定位（纯分类结果，不含网络） */
enum class ItemKind { MAIN, MOVIE, UNMATCHED }

data class ClassifiedItem(
    val node: ShareNode,
    /** 季号：文件名优先，其次最近一层季目录名；都没有 = null */
    val season: Int?,
    val episode: Int?,
    val kind: ItemKind,
    /** 集号证据强度 3 强 / 2 中 / 1 弱；未命中为 0 */
    val quality: Int,
    /** 有多个候选数字、判不出集号 */
    val ambiguous: Boolean = false,
) {
    val name: String get() = node.name

    /** 同集去重用的键：季（缺省统一成 -1）+ 集 */
    val key: String? get() = episode?.let { "${season ?: -1}#$it" }
}

/**
 * 转存前的文件分类与裁剪（纯函数）。
 *
 * 用户口径（一句话）：**能命中（解析出唯一季+集、且能改名）的才转存**，其余不转。
 *
 * 分类：
 * - [ItemKind.MAIN]：命中，正常转存（同集多版本只留一个）
 * - [ItemKind.MOVIE]：剧场版 / 电影版，整体跳过（也不进集数统计）
 * - [ItemKind.UNMATCHED]：解析不出集号或集号有歧义，不转存
 *
 * 裁剪：把树里不该转的叶子摘掉，空目录一并摘掉，再交给引擎——
 * 引擎本身一行不改，避免在转存主链路上加分支。
 */
object TransferClassifier {

    data class Plan(
        val tree: ShareNode,
        val kept: List<ClassifiedItem>,
        val movies: List<ClassifiedItem>,
        val unmatched: List<ClassifiedItem>,
        val duplicates: List<ClassifiedItem>,
        /** 命中集合里出现的不同集数 */
        val distinctEpisodes: Int,
        val filterEnabled: Boolean,
    ) {
        val hasWork: Boolean get() = kept.isNotEmpty()

        /** 一行结论，写进运行日志的「说明」里 */
        fun summary(): String = buildString {
            if (!filterEnabled) {
                append("未选命名规则：按原名转存 ").append(kept.size).append(" 个视频")
                return@buildString
            }
            append("分享里命中 ").append(distinctEpisodes).append(" 集（").append(kept.size).append(" 个文件待核对）")
            // 每条结论一行，日志里不再挤成一大段
            if (duplicates.isNotEmpty()) append("\n同集多版本 ").append(duplicates.size).append(" 个只留 1 个")
            if (unmatched.isNotEmpty()) append("\n未命中 ").append(unmatched.size).append(" 个未转存")
            if (movies.isNotEmpty()) append("\n剧场版 ").append(movies.size).append(" 个跳过")
        }
    }

    /** 版本优先级：重置/修正 > V2/V3 > 高分辨率 > 其它（同集只留分最高的一个） */
    private val VERSION_WORDS = listOf("重置版", "重置", "重製", "修正版", "修正", "修复", "repackage", "repack", "remaster")
    private val HIGH_RES = listOf("2160p", "4k", "1080p")

    /**
     * 给整棵树做分类。
     *
     * @param filterEnabled false = 任务没选命名规则，沿用老行为全部转存（只做剧场版剔除以外的分类统计）
     */
    fun plan(tree: ShareNode, filterEnabled: Boolean = true): Plan {
        val all = classify(tree)
        val movies = all.filter { it.kind == ItemKind.MOVIE }
        val unmatched = all.filter { it.kind == ItemKind.UNMATCHED }
        val main = all.filter { it.kind == ItemKind.MAIN }

        // 同集多版本：按版本优先级留一个，其余记进 duplicates
        val byKey = main.groupBy { it.key ?: it.node.fid }
        val deduped = mutableListOf<ClassifiedItem>()
        val duplicates = mutableListOf<ClassifiedItem>()
        byKey.forEach { (_, group) ->
            if (group.size == 1) {
                deduped += group.first()
            } else {
                // 版本优先级高的留；同分时文件名靠前的留
                val best = group.sortedWith(
                    compareByDescending<ClassifiedItem> { preference(it.name) }.thenBy { it.name }
                ).first()
                deduped += best
                duplicates += group.filter { it.node.fid != best.node.fid }
            }
        }

        // 没选命名规则时沿用老行为：所有视频都按原名转存（未命中的也不丢），
        // 但剧场版仍然要剪掉：以前这里原样返回整棵树，「剧场版一律不转」只写在文案里，
        // 引擎照样把剧场版转进任务目录，还会把「最近有新集」的排序顶上去。
        val kept = if (filterEnabled) deduped else all.filter { it.kind != ItemKind.MOVIE }
        val keepFids = kept.map { it.node.fid }.toSet()
        val pruned = prune(tree, keepFids) ?: emptyTree(tree)

        return Plan(
            tree = pruned,
            kept = kept,
            movies = movies,
            unmatched = unmatched,
            duplicates = duplicates,
            distinctEpisodes = kept.mapNotNull { it.key }.distinct().size,
            filterEnabled = filterEnabled,
        )
    }

    /** 逐层展开，文件按「目录链季号兜底」判定 */
    fun classify(tree: ShareNode, maxDepth: Int = 5): List<ClassifiedItem> {
        val out = mutableListOf<ClassifiedItem>()
        fun walk(node: ShareNode, dirs: List<String>, depth: Int) {
            node.children.sortedWith(compareBy(NaturalOrder) { it.name }).forEach { child ->
                if (child.isDir) {
                    // 与引擎保持同一深度上限：更深层的文件引擎也不会转，别把它们算进「命中」
                    if (depth + 1 <= maxDepth) walk(child, dirs + child.name, depth + 1)
                } else if (VideoTypes.isVideo(child.name) && !VideoTypes.isNoise(child.name)) {
                    val a = EpisodeKey.analyze(child.name, dirs)
                    val kind = when {
                        a.movie -> ItemKind.MOVIE
                        a.isHit -> ItemKind.MAIN
                        else -> ItemKind.UNMATCHED
                    }
                    out += ClassifiedItem(
                        node = child,
                        season = a.parsed?.season,
                        episode = a.parsed?.episode,
                        kind = kind,
                        quality = a.parsed?.quality ?: 0,
                        ambiguous = a.ambiguous,
                    )
                }
            }
        }
        walk(tree, emptyList(), 0)
        return out
    }

    /** 同一集里谁更值得留：重置/修正 > V2/V3 > 高分辨率 > 文件名更短 */
    fun preference(name: String): Int {
        val lower = name.lowercase()
        var score = 0
        if (VERSION_WORDS.any { lower.contains(it) }) score += 6
        Regex("""(?i)\bv\d{1,2}\b""").find(lower)?.let {
            score += 4 + it.value.substring(1).toIntOrNull().orZero()
        }
        if (HIGH_RES.any { lower.contains(it) }) score += 2
        return score
    }

    private fun Int?.orZero(): Int = this ?: 0

    /** 只保留 [keepFids] 里的文件；空目录一并摘掉。根节点为空时返回 null */
    fun prune(node: ShareNode, keepFids: Set<String>): ShareNode? {
        if (!node.isDir) return if (node.fid in keepFids) node else null
        val kids = node.children.mapNotNull { prune(it, keepFids) }
        if (kids.isEmpty()) return null
        return node.copy(children = kids)
    }

    private fun emptyTree(tree: ShareNode): ShareNode = tree.copy(children = emptyList())
}
