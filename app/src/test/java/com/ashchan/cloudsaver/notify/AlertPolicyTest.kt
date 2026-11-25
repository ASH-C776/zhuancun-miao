package com.ashchan.cloudsaver.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判定矩阵：免打扰 > 锁屏 > 前台 > 横幅；已提醒过永远不发 */
class AlertPolicyTest {

    private val alert = ShowAlert(1, "斗破苍穹", 5, 207, null, 1)

    @Test
    fun `正常情况走横幅`() {
        val d = AlertPolicy.decide(alert, AlertEnv(), alreadyNotified = false)
        assertEquals(AlertLevel.BANNER, d.level)
        assertEquals(AlertReason.NEW_EPISODES, d.reason)
        assertTrue(d.publish)
    }

    @Test
    fun `App 在前台只进通知栏`() {
        val d = AlertPolicy.decide(alert, AlertEnv(appForeground = true), alreadyNotified = false)
        assertEquals(AlertLevel.NORMAL, d.level)
        assertEquals(AlertReason.APP_FOREGROUND, d.reason)
    }

    @Test
    fun `锁屏仍走横幅但说明原因`() {
        val d = AlertPolicy.decide(alert, AlertEnv(screenLocked = true), alreadyNotified = false)
        assertEquals(AlertLevel.BANNER, d.level)
        assertEquals(AlertReason.SCREEN_LOCKED, d.reason)
    }

    @Test
    fun `免打扰只进通知栏`() {
        val d = AlertPolicy.decide(alert, AlertEnv(dndOn = true), alreadyNotified = false)
        assertEquals(AlertLevel.NORMAL, d.level)
        assertEquals(AlertReason.DND_ON, d.reason)
    }

    @Test
    fun `免打扰优先于前台`() {
        val d = AlertPolicy.decide(
            alert,
            AlertEnv(appForeground = true, screenLocked = true, dndOn = true),
            alreadyNotified = false,
        )
        assertEquals(AlertReason.DND_ON, d.reason)
    }

    @Test
    fun `提醒过的不再发`() {
        val d = AlertPolicy.decide(alert, AlertEnv(), alreadyNotified = true)
        assertEquals(AlertLevel.SILENT, d.level)
        assertEquals(AlertReason.ALREADY_NOTIFIED, d.reason)
        assertFalse(d.publish)
    }

    @Test
    fun `没有新集不发`() {
        val d = AlertPolicy.decide(null, AlertEnv(), alreadyNotified = false)
        assertEquals(AlertLevel.SILENT, d.level)
        assertEquals(AlertReason.NO_NEW_EPISODES, d.reason)
    }
}
