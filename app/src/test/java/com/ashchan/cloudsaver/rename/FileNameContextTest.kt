package com.ashchan.cloudsaver.rename

import com.ashchan.cloudsaver.core.model.DriveFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 文件名占位符解析：集号必须「先精确后宽松」。
 *
 * 回归用例来自真机日志（仙剑奇侠传三，光鸭分享）：
 * `Sword.and.Fairy.3.2025.S01E02.2160p.25fps.H.265.10bit.SDR.DDP 2.0.mp4`
 * 片名里那个 "3"（第三部）位置比 S01E02 靠前，早期实现用单个 alternation 正则 + find，
 * 取到的是最靠前的 "3" → 整季 26 集全被算成 E03，改名全撞到同一个名字。
 */
class FileNameContextTest {

    private val realNames = listOf(
        "Sword.and.Fairy.3.2025.S01E02.2160p.25fps.H.265.10bit.SDR.DDP 2.0.mp4",
        "Sword.and.Fairy.3.2025.S01E03.2160p.25fps.H.265.10bit.SDR.DDP 2.0.mp4",
        "Sword.and.Fairy.3.2025.S01E11.2160p.25fps.H.265.10bit.SDR.DDP 2.0.mp4",
        "Sword.and.Fairy.3.2025.S01E07.2160p.WEB-DL.25fps.H.265.10bit.SDR.DDP 2.0.mp4",
    )

    @Test
    fun `片名带部数时 仍按 SxxExx 取集号`() {
        assertEquals("02", FileNameContext(realNames[0]).episodeRaw)
        assertEquals("03", FileNameContext(realNames[1]).episodeRaw)
        assertEquals("11", FileNameContext(realNames[2]).episodeRaw)
        assertEquals("07", FileNameContext(realNames[3]).episodeRaw)
        assertEquals("S01", FileNameContext(realNames[0]).season)
    }

    @Test
    fun `同一季所有文件按通用命名得到各自不同的新名`() {
        val engine = RenameEngine()
        val rule = BuiltinRules.byKey("\$TV_MAGIC")!!
        val files = realNames.map { DriveFile(fid = it, name = it, isDir = false) }
        val previews = engine.preview(files, listOf(rule), "仙剑奇侠传三")
        val names = previews.map { it.newName }
        assertEquals(listOf("仙剑奇侠传三.S01E02.mp4", "仙剑奇侠传三.S01E03.mp4", "仙剑奇侠传三.S01E11.mp4", "仙剑奇侠传三.S01E07.mp4"), names)
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `中式集数写法优先于片名里的数字`() {
        assertEquals("5", FileNameContext("斗破苍穹 3 第 5 集 2160p.mkv").episodeRaw)
    }

    @Test
    fun `开头就是集号的文件仍可解析`() {
        assertEquals("219", FileNameContext("219-4K.mp4").episodeRaw)
    }

    @Test
    fun `日期型文件名不会被当成集号`() {
        val ctx = FileNameContext("20251123.mp4")
        assertNull(ctx.episodeRaw)
    }
}
