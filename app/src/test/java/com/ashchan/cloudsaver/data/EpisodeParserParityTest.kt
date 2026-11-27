package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.rename.FileNameContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 两套集号解析的对照表（诊断用，不改生产行为）。
 *
 * - 卡片统计走 [EpisodeKey]（EpisodeStats）
 * - 转存去重/改名走 [FileNameContext]
 *
 * 这里把真实出现过的命名同时喂给两边，收集结果不一致的样本。
 * 只有两边结论一致，才值得合并成一套；不一致的样本正好说明各自的口径差异。
 */
class EpisodeParserParityTest {

    private val names = listOf(
        "219-4K.mp4",
        "218_4K.mkv",
        "201-4k.mp4",
        "斗破苍穹.S05E219.2160p.mkv",
        "S01E05.mkv",
        "S01.E05.mkv",
        "S01_EP5.mkv",
        "第5集.mp4",
        "第 5 话.mp4",
        "EP05.mkv",
        "EP.05.mp4",
        "E216.mp4",
        "216.mp4",
        "01.mp4",
        "1080p.mp4",
        "凡人修仙传.EP01.1080p.mkv",
        "Blue.Planet.III.S01E01.mkv",
        "12-4K.mp4",
        "movie.2024.1080p.mkv",
        "剧场版.mp4",
        "某剧 第12集 2160p.mp4",
        "某剧.S02.E12.1080p.mkv",
        // 审查修复：超过三位数的集号、合集包 —— 两套口径必须一致（以前是 1024/102 与 12/1）
        "海贼王.S01E1024.2160p.mkv",
        "海贼王.S01E1025.2160p.mkv",
        "第1024集.mp4",
        "剧名.1-12.合集.mkv",
        "剧名.100-120.合集.4K.mkv",
    )

    @Test
    fun `两套解析在常见命名上结论一致`() {
        val mismatches = names.mapNotNull { name ->
            val keyEp = EpisodeKey.analyze(name).parsed?.episode
            val renameEp = FileNameContext(name).episodeRaw?.toIntOrNull()
            if (keyEp != renameEp) "$name -> key=$keyEp rename=$renameEp" else null
        }
        assertEquals("解析口径不一致：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }
}
