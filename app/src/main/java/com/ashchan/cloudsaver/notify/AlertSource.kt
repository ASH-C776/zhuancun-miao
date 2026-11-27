package com.ashchan.cloudsaver.notify

import com.ashchan.cloudsaver.data.db.TaskEntity

/**
 * 把「一个任务这一轮落盘了哪些集」变成一条提醒。
 *
 * 输入是结构化的 [EpisodeTag]（引擎在转存那一刻就把季/集解析好带出来），
 * 这里不再回头看文件名，命名规则怎么变都不影响通知。
 */
object AlertSource {

    /**
     * @param seasonNo 任务的季设置：>=0 指定季（0 = 特别版）、-1 多季、-2 平铺
     * @param restoredFiles 落盘文件数，只在「一条集号都解析不出」时用来兜底计数
     */
    fun build(
        taskId: Long,
        taskName: String,
        seasonNo: Int,
        items: List<EpisodeTag>,
        restoredFiles: Int,
    ): ShowAlert? {
        val title = AlertText.stripYear(taskName)
        // 文件自身没季号时按任务设置补齐：指定季就是那一季，平铺算第一季，多季不猜
        val defaultSeason: Int? = when {
            seasonNo >= 0 -> seasonNo
            seasonNo == TaskEntity.SEASON_FLAT -> 1
            else -> null
        }
        val raw = items.mapNotNull { tag ->
            val ep = tag.episode ?: return@mapNotNull null
            (tag.season ?: defaultSeason) to ep
        }.distinct()
        // 有集解析不出季号、但同批其它集都指向同一季时，用那一季补齐：
        // 否则 null 会排在最前，标题退化成「E005-E020」，任务明明指定了第 5 季却没有 S05。
        val knownSeasons = raw.mapNotNull { it.first }.distinct()
        val pairs = if (raw.any { it.first == null } && knownSeasons.size == 1) {
            raw.map { knownSeasons.first() to it.second }.distinct()
        } else {
            raw
        }

        if (pairs.isEmpty()) {
            if (restoredFiles <= 0) return null
            return ShowAlert(taskId, title, null, null, null, restoredFiles)
        }
        val sorted = pairs.sortedWith(compareBy<Pair<Int?, Int>> { it.first ?: -1 }.thenBy { it.second })
        return ShowAlert(
            taskId = taskId,
            title = title,
            season = sorted.first().first,
            firstEpisode = sorted.first().second,
            lastEpisode = sorted.last().second,
            count = sorted.size,
            lastSeason = sorted.last().first,
        )
    }
}
