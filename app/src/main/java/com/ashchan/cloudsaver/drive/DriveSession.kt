package com.ashchan.cloudsaver.drive

import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.storage.CredentialStore

/**
 * 统一登录态入口：「等价于点一次设置页的登录」。
 *
 * 背景：凭证一直存在加密存储里，但网盘侧会话会自己掉（光鸭 token 轮换、夸克 cookie 过期），
 * 以前只有启动时的 `autoLogin` 和用户手点设置页登录会重新验证，
 * 选目录 / 跑任务都直接拿凭证建 adapter 就用，于是掉线后只能回设置页手点一下。
 *
 * 这里把「读凭证 → 建 adapter → validateLogin → 回写最新凭证」收成一个动作，
 * 选路径和转存都先走它，用户不用再点登录。
 *
 * 缓存：同一网盘 5 分钟内验证过就跳过账号接口（少一次请求），
 * 真遇上 401 时调用方用 [ensure] 的 `force = true` 再来一次。
 */
object DriveSession {

    private const val VERIFY_TTL_MS = 5 * 60 * 1000L

    private val verifiedAt = mutableMapOf<DriveType, Long>()

    /**
     * 确保该网盘可用。
     *
     * @return 已经验证过登录态、可直接调用的 adapter
     */
    suspend fun ensure(
        drive: DriveType,
        store: CredentialStore,
        force: Boolean = false,
    ): Result<CloudDriveAdapter> = runCatching {
        val credential = store.loadCredential(drive)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("请先在设置里登录${drive.displayName}")

        val adapter = DriveFactory.create(drive, credential)

        if (!force && isFresh(drive)) return@runCatching adapter

        adapter.validateLogin(credential).getOrThrow()
        // 光鸭刷新后 token 会轮换，必须立刻回写，否则下次「自动登录」用的还是旧 token
        DriveFactory.guangYaCredential(adapter)?.let { store.saveCredential(drive, it) }
        verifiedAt[drive] = System.currentTimeMillis()
        adapter
    }

    /** 标记该网盘登录态可疑，下次 [ensure] 强制验证（例如刚遇到 401） */
    fun invalidate(drive: DriveType) {
        verifiedAt.remove(drive)
    }

    /** 登录类错误（区别于网络 / 服务器错误），用于决定是否再强制登录一次 */
    fun isAuthError(error: Throwable?): Boolean {
        val msg = error?.message.orEmpty()
        if (msg.isBlank()) return false
        return AUTH_HINTS.any { msg.contains(it, ignoreCase = true) }
    }

    private fun isFresh(drive: DriveType): Boolean {
        val at = verifiedAt[drive] ?: return false
        return System.currentTimeMillis() - at < VERIFY_TTL_MS
    }

    private val AUTH_HINTS = listOf(
        "401",
        "403",
        "过期",
        "无效",
        "未登录",
        "登录已失效",
        "Cookie",
        "Token",
        "无权限",
        "unauthorized",
    )
}
