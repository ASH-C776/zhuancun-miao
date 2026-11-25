package com.ashchan.cloudsaver.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 数据库迁移链守卫（纯 JVM，不需要真机）：
 * 迁移必须从 1 一路连续到当前版本，不能少一环。
 * 加字段时如果忘了写迁移，或者把版本号改了却没补迁移，这里会直接报红。
 */
class MigrationChainTest {

    @Test
    fun `迁移链从 1 到当前版本连续无断档`() {
        var expected = 1
        AppDatabase.MIGRATIONS.forEach { migration ->
            assertEquals("迁移起点不连续", expected, migration.startVersion)
            assertEquals("迁移终点不连续", expected + 1, migration.endVersion)
            expected = migration.endVersion
        }
        assertEquals("迁移链没有覆盖到当前 schema 版本", AppDatabase.SCHEMA_VERSION, expected)
    }
}
