package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.rename.FileNameContext

/**
 * 一部剧这一轮新增了哪些集 —— 只用于通知文案，不参与统计与判断。
 *
 * 文案口径（用户定的）：
 * - 标题：`斗破苍穹更新 S05E207`（剧名去掉年份/TMDB 尾巴，季集跟在“更新”后面）
 * - 正文：`共 1 集`；连更多集写范围 `S05E207-E209`，仍是一条
 */
data class ShowUpdate(
    /** 任务 id：用来给每条通知一个稳定编号，重发不堆叠 */
    val taskId: Long,
    /** 剧名（已去掉「(2017)」这类年份与 TMDB 尾巴） */
    val title: String,
    /** 季号；判不出为 null */
    val season: Int?,
    val firstEpisode: Int?,
    val lastEpisode: Int?,
    /** 实际新增的集数（去重后） */
    val count: Int,
) {

    /** `S05E207` / `S05E207-E209` / 判不出集号时为空 */
    val episodeLabel: String?
        get() {
            val first = firstEpisode ?: return null
            val last = lastEpisode ?: first
            val head = episodeTag(season, first)
            if (last == first) return head
            // 同一季时尾号省略 S05（`S05E207-E209`），跨季则两头都写全
            val tail = if (season != null && season > 0) {
                "E" + last.toString().padStart(3, '0')
            } else {
                episodeTag(season, last)
            }
            return "$head-$tail"
        }

    /** 通知标题：`斗破苍穹更新 S05E207`；判不出集号退化成 `斗破苍穹更新` */
    val notificationTitle: String
        get() = episodeLabel?.let { "${title}更新 $it" } ?: "${title}更新"

    /** 通知正文：`共 3 集` */
    val notificationBody: String
        get() = "共 $count 集"

    companion object {

        /** 去掉剧名里的年份尾巴：「斗破苍穹 (2017)」→「斗破苍穹」 */
        fun stripYear(raw: String): String {
            var name = raw.trim()
            // 只裁结尾的年份括号，剧名中间的括号（「(2024) 特别篇」）原样保留
            name = name.replace(Regex("""\s*[（(]\s*(?:18|19|20)\d{2}\s*[）)]\s*$"""), "")
            name = name.replace(Regex("""\s*[（(]\s*tmdb\s*[：:]?\s*\d+\s*[）)]\s*$""", RegexOption.IGNORE_CASE), "")
            // 裸年份尾巴（「斗破苍穹 2017」）：只在结尾、且前面有内容时裁掉
            name = name.replace(Regex("""(?<=\S)\s+(?:18|19|20)\d{2}$"""), "")
            return name.trim().ifEmpty { raw.trim() }
        }

        /** `S05` + `207` → `S05E207`；季号为空退化成 `E207` */
        fun episodeTag(season: Int?, episode: Int): String {
            val ep = "E" + episode.toString().padStart(3, '0')
            return if (season != null && season > 0) "S${season.toString().padStart(2, '0')}$ep" else ep
        }

        /**
         * 从本轮落盘文件名推出「新增了哪几集」。
         *
         * @param seasonNo 任务的季设置：>=0 指定季（0 = 特别版）、-1 多季、-2 平铺
         * @param names 本轮真正落盘的文件名（改名后的最终名字）
         * @param restoredFiles 落盘文件数，仅用于「名字都解析不出集号」时的兜底计数
         */
        fun from(
            taskId: Long,
            taskName: String,
            seasonNo: Int,
            names: List<String>,
            restoredFiles: Int,
        ): ShowUpdate? {
            val title = stripYear(taskName)
            // 文件自身没写季号时按任务设置补齐：指定季就是那一季，平铺按第一季，多季不猜
            val defaultSeason: Int? = when {
                seasonNo >= 0 -> seasonNo
                seasonNo == com.ashchan.cloudsaver.data.db.TaskEntity.SEASON_FLAT -> 1
                else -> null
            }
            val pairs = mutableListOf<Pair<Int?, Int>>()
            names.forEach { name ->
                val ctx = FileNameContext(name)
                val ep = ctx.episodeRaw?.toIntOrNull()?.takeIf { it in 1..9999 } ?: return@forEach
                val season = ctx.season?.drop(1)?.toIntOrNull() ?: defaultSeason
                pairs += season to ep
            }
            if (pairs.isEmpty()) {
                if (restoredFiles <= 0) return null
                return ShowUpdate(taskId, title, null, null, null, restoredFiles)
            }
            val distinct = pairs.distinct().sortedWith(
                compareBy<Pair<Int?, Int>> { it.first ?: -1 }.thenBy { it.second }
            )
            return ShowUpdate(
                taskId = taskId,
                title = title,
                season = distinct.first().first,
                firstEpisode = distinct.first().second,
                lastEpisode = distinct.last().second,
                count = distinct.size,
            )
        }
    }
}
