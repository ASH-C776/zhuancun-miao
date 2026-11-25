package com.ashchan.cloudsaver.data

/**
 * 编辑任务时合并「自定义分季」两个字段。
 *
 * 背景：新建页已经不再暴露这两个输入框，但引擎仍会读它们（TaskRunner 用它算本季集数）。
 * 如果保存时一律写 0，老任务里用户当年填过的值会被静默清空、统计口径退回 TMDB。
 *
 * 约定：入参为 null 或非正数 = 用户这次没有表达意见 → 沿用旧值；
 * 只有显式给出正数才覆盖。以后若恢复输入框，0 也不代表「清空」，清空请另走专门的入口。
 */
object SeasonCustomMerge {

    fun merge(
        baseStartEp: Int,
        baseEpCount: Int,
        inputStartEp: Int?,
        inputEpCount: Int?,
    ): Pair<Int, Int> {
        val start = inputStartEp?.takeIf { it > 0 } ?: baseStartEp
        val count = inputEpCount?.takeIf { it > 0 } ?: baseEpCount
        return start to count
    }
}
