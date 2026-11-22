package com.ashchan.cloudsaver.drive.quark

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2 夸克分享条目解析回归测试。
 *
 * JSON 取自 2026-11-22 实测 `/1/clouddrive/share/sharepage/detail`
 * （分享 `https://pan.quark.cn/s/225348afbc93`，结构为
 * 根 →「斗 P穹4」→「之前季番」「1-200」+ 219-4K.mp4 等平铺视频 → 「1-99」…）。
 *
 * 核心教训：转存需要的 token 字段是 **`share_fid_token`**，旧 `fid_token` 已不下发。
 */
class QuarkShareItemTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `目录条目 携带 share_fid_token`() {
        val item = obj(
            """{"fid":"fe844a6bab4e4518bb2d093238468add","file_name":"1-99",
               |"pdir_fid":"a27a98adf4f840af9293ce3a89bc3fa3","category":0,"file_type":0,
               |"size":0,"include_items":99,"share_fid_token":"dce24f8cfe71d0aa0e184f817cdcd9a9",
               |"dir":true}""".trimMargin()
        )
        val file = parseShareItem(item)!!
        assertEquals("fe844a6bab4e4518bb2d093238468add", file.fid)
        assertEquals("1-99", file.name)
        assertTrue(file.isDir)
        assertEquals("dce24f8cfe71d0aa0e184f817cdcd9a9", file.fidToken)
    }

    @Test
    fun `平铺视频条目`() {
        val item = obj(
            """{"fid":"2718c945bf1143eaa60daa7bafb85a19","file_name":"219-4K.mp4",
               |"size":2345678901,"format_type":"mp4","dir":false,
               |"share_fid_token":"abcdef0123456789abcdef0123456789"}""".trimMargin()
        )
        val file = parseShareItem(item)!!
        assertFalse(file.isDir)
        assertEquals("219-4K.mp4", file.name)
        assertEquals(2345678901L, file.size)
        assertEquals("abcdef0123456789abcdef0123456789", file.fidToken)
    }

    @Test
    fun `旧字段 fid_token 仍然兼容`() {
        val item = obj(
            """{"fid":"f1","file_name":"a.mp4","dir":false,"fid_token":"legacy-token"}"""
        )
        assertEquals("legacy-token", parseShareItem(item)!!.fidToken)
    }

    @Test
    fun `缺 fid 或 file_name 返回 null`() {
        assertNull(parseShareItem(obj("""{"file_name":"x.mp4"}""")))
        assertNull(parseShareItem(obj("""{"fid":"f2"}""")))
    }
}
