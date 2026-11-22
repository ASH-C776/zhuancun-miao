package com.ashchan.cloudsaver.drive.guangya

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.3 光鸭真实接口回归测试。
 *
 * JSON 片段取自 2026-11-22 对真实分享
 * `https://www.guangyapan.com/s/1962143988912746595_aeWw8Ru726g3wcPx` 的实测响应
 * （抽取字段，去掉了 thumbnail 等无关字段）。真实结构：
 * 根 →「斗破苍穹 (2017)」→「第 N 季 - 2160p WEB-DL HEVC AAC」→ 14 个 mp4。
 *
 * 锁定要点：目录靠 `resType=2` 判定（`dirType` 对文件也恒为 1，不能用），
 * 文件名是 `fileName`，fid 是 `fileId`，token 在 `data.accessToken`（驼峰）。
 */
class GuangYaRespTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `目录条目解析为 isDir 且 fid 取 fileId`() {
        val item = obj(
            """{"fileId":"1962132554321489932","fileName":"斗破苍穹 (2017)","depth":1,
               |"dirType":1,"resType":2,"ctime":1793098456,"auditStatus":4}""".trimMargin()
        )
        assertEquals("1962132554321489932", GuangYaResp.fid(item))
        assertEquals("斗破苍穹 (2017)", GuangYaResp.name(item))
        assertTrue(GuangYaResp.isDir(item))
    }

    @Test
    fun `季目录解析为 isDir`() {
        val item = obj(
            """{"fileId":"1962133151179350039","fileName":"第 1 季 - 2160p WEB-DL HEVC AAC",
               |"parentId":"1962132554321489932","depth":2,"dirType":1,"resType":2}""".trimMargin()
        )
        assertTrue(GuangYaResp.isDir(item))
        assertEquals("1962132554321489932", GuangYaResp.parentId(item))
    }

    @Test
    fun `剧集文件解析为文件 并带出大小与父目录`() {
        val item = obj(
            """{"fileId":"1962133333333333333",
               |"fileName":"Fights Break Sphere S01E01 - 第 1 集 - 2160p WEB-DL HEVC AAC.mp4",
               |"fileSize":910910020,"parentId":"1962133151179350039","depth":3,
               |"mineType":"video/mp4","fileType":1,"dirType":1,"resType":1,"ext":".mp4"}""".trimMargin()
        )
        assertFalse(GuangYaResp.isDir(item))
        assertEquals(910910020L, GuangYaResp.size(item))
        assertEquals("1962133151179350039", GuangYaResp.parentId(item))
        assertEquals(
            "Fights Break Sphere S01E01 - 第 1 集 - 2160p WEB-DL HEVC AAC.mp4",
            GuangYaResp.name(item)
        )
    }

    @Test
    fun `海报与 nfo 同样判为文件`() {
        val poster = obj(
            """{"fileId":"1962132945582948418","fileName":"poster.jpg","fileSize":500459,
               |"fileType":1,"dirType":1,"resType":1,"ext":".jpg"}""".trimMargin()
        )
        val nfo = obj(
            """{"fileId":"1962133071504334885","fileName":"tvshow.nfo","fileSize":5839,
               |"fileType":11,"dirType":1,"resType":1,"ext":".nfo"}""".trimMargin()
        )
        assertFalse(GuangYaResp.isDir(poster))
        assertFalse(GuangYaResp.isDir(nfo))
    }

    @Test
    fun `访问令牌从 data accessToken 驼峰字段提取`() {
        val payload = obj(
            """{"msg":"success","data":{"accessToken":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig"}}"""
        )
        assertTrue(GuangYaResp.isSuccess(payload))
        assertEquals(
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig",
            GuangYaResp.dataToken(payload)
        )
    }

    @Test
    fun `文件列表从 data list 提取`() {
        val payload = obj(
            """{"msg":"success","data":{"total":1,"list":[
               |{"fileId":"1962132554321489932","fileName":"斗破苍穹 (2017)","dirType":1,"resType":2}],
               |"cursor":50}}""".trimMargin()
        )
        val items = GuangYaResp.items(payload)
        assertEquals(1, items.size())
        assertEquals("斗破苍穹 (2017)", GuangYaResp.name(items[0].asJsonObject))
    }

    @Test
    fun `参数错误响应判为失败`() {
        assertFalse(GuangYaResp.isSuccess(obj("""{"code":112,"msg":"参数错误"}""")))
    }

    @Test
    fun `分享摘要响应判为成功`() {
        val payload = obj(
            """{"msg":"success","data":{"title":"斗破苍穹 (2017)","shareId":
               |"1962143988912746595_aeWw8Ru726g3wcPx","totalFileNum":310}}""".trimMargin()
        )
        assertTrue(GuangYaResp.isSuccess(payload))
    }
}
