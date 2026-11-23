package com.ashchan.cloudsaver.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 追剧任务。
 *
 * 设计要点（对应需求「单账号 + 目标季」）：
 * - 不存账号名：执行前按 [driveType] 取唯一凭证，未登录直接拦截。
 * - [seasonFid] 不为空时，本任务只跟这一季（真实分享常是整剧多季合集，一次全转会是 169GB）；
 *   为空表示跟随整个分享。
 * - 清晰度暂不做任务字段：同一季存在 1080p/2160p 时，用户直接在分享里选好那一季的目录。
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 剧名，同时用作网盘里顶层文件夹名 */
    val name: String,
    /** DriveType.name：QUARK / GUANGYA */
    val driveType: String,
    /** 分享链接原文（用户粘贴的那份，含提取码文案） */
    val shareUrl: String,
    val shareId: String,
    val passcode: String = "",
    /** 转存目标文件夹 fid，"0" 表示网盘根目录 */
    val saveFid: String = "0",
    /** 目标文件夹展示名（如「追剧/2026」），仅用于卡片灰字 */
    val savePathLabel: String = "",
    /** 目标季目录 fid：空 = 跟随整个分享 */
    val seasonFid: String = "",
    /** 目标季目录名，如「第 2 季 - 2160p WEB-DL HEVC AAC」 */
    val seasonName: String = "",
    /**
     * 季度编号：0 = 不建季目录（直接 剧名/视频文件 平铺）；
     * 1..N = 转存到 剧名/Season N/ 下平铺。
     *
     * 与 [seasonFid] 的区别：seasonFid 指分享里某个已存在的季目录（用来限定来源），
     * 这里只是目标侧要不要多一层季文件夹，两者互不影响。
     */
    val seasonNo: Int = 0,
    /** 分享内扫描起点 fid，空 = 分享根；实际执行时优先用 seasonFid */
    val startFid: String = "",
    /**
     * 转存范围：
     * - SUBTREE：整棵子树保留层级（「剧名/第 1 季/14 集」）
     * - DIRECT_ONLY：只转该目录下平铺的视频（「剧名/219-4K.mp4」这类，不进下一层大目录）
     */
    val scopeMode: String = SCOPE_SUBTREE,
    /** TMDB id（剧为 tv，电影为 movie） */
    val tmdbId: Long? = null,
    val tmdbMediaType: String = "tv",
    /** 更新日来源：tmdb = 用 TMDB 播出日推断；manual = 用户手点 */
    val updateMode: String = UPDATE_MODE_MANUAL,
    /** 手动设置的更新星期：逗号分隔的 1..7（1=周一，IsoWeekday） */
    val weekdays: String = "",
    /** 播出时刻 HH:mm，可选；填了才进精确唤醒计划 */
    val airTime: String = "",
    /**
     * 命名规则 key（来自 [com.ashchan.cloudsaver.storage.RuleStore]）。
     * - 空串 = 不改名（保持分享里的原文件名）
     * - 其它 = 执行时只用这一条规则算改名映射
     *
     * 之所以按任务存单条规则而不是全局开关：同一部剧在不同盘里命名风格经常不同，
     * 一部用「SxxExx」、另一部要「剧名.SxxEyy」，全局开关做不到。
     */
    val ruleKey: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val UPDATE_MODE_TMDB: String = "tmdb"
        const val UPDATE_MODE_MANUAL: String = "manual"

        const val SCOPE_SUBTREE: String = "SUBTREE"
        const val SCOPE_DIRECT_ONLY: String = "DIRECT_ONLY"
    }
}
