package com.ashchan.cloudsaver.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** 去重：同一批集只提醒一次；重启（重新 load）后仍然不重弹 */
class AlertStoreTest {

    private val alert = ShowAlert(1, "斗破苍穹", 5, 207, 209, 3)
    private val sameBatch = ShowAlert(1, "斗破苍穹", 5, 207, 209, 3)

    @Test
    fun `第一次是新的，标记后不是`() {
        var backing: Set<String> = emptySet()
        val store = AlertStore(load = { backing }, save = { backing = it })
        assertTrue(store.isNew(alert))
        store.mark(alert, now = 1_000L)
        assertFalse(store.isNew(sameBatch))
    }

    @Test
    fun `下一批集仍会提醒`() {
        var backing: Set<String> = emptySet()
        val store = AlertStore(load = { backing }, save = { backing = it })
        store.mark(alert, now = 1_000L)
        val next = ShowAlert(1, "斗破苍穹", 5, 210, null, 1)
        assertTrue(store.isNew(next))
    }

    @Test
    fun `指纹带集数，集数变了算新批次`() {
        var backing: Set<String> = emptySet()
        val store = AlertStore(load = { backing }, save = { backing = it })
        store.mark(ShowAlert(1, "斗破苍穹", 5, 207, null, 1), now = 1_000L)
        assertTrue(store.isNew(ShowAlert(1, "斗破苍穹", 5, 207, null, 2)))
    }

    @Test
    fun `超过 30 天自动清掉`() {
        val now = 10_000_000_000L
        val old = "1|5|207|207|1@${now - 40L * 24 * 3600 * 1000}"
        val fresh = "1|5|208|208|1@${now - 1000}"
        val kept = AlertStore.prune(setOf(old, fresh), now = now)
        assertEquals(setOf(fresh), kept)
    }

    @Test
    fun `清空后可以重新提醒`() {
        var backing: Set<String> = emptySet()
        val store = AlertStore(load = { backing }, save = { backing = it })
        store.mark(alert, now = 1_000L)
        store.clear()
        assertTrue(store.isNew(alert))
    }
}
