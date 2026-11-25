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
     * 季度编号，取值见 [SEASON_FLAT] / [SEASON_SPECIALS] / [SEASON_MULTI]：
     * - `-2` 平铺：不建季目录，视频直接放 `剧名/` 下（= 任务里没选季度）
     * - `0`  特别版：真实存在的 Season 0，放 `剧名/Season 0/` 下
     * - `1..99` 指定季：放 `剧名/Season N/`
     * - `-1` 多季：按每个文件自身的季号分组，各自进 `剧名/Season N/`
     *
     * 与 [seasonFid] 的区别：seasonFid 指分享里某个已存在的季目录（用来限定来源），
     * 这里只是目标侧要不要多一层季文件夹，两者互不影响。
     */
    val seasonNo: Int = SEASON_FLAT,
    /**
     * 自定义分季：本季在「全剧连续集号」里的起始集（0 = 不自定义）。
     *
     * 用于 TMDB 把多篇合并成一季的剧（如《一念永恒》TMDB 只有 S1 200 集，
     * 腾讯却分 4 篇、每篇集号从 1 起）。填 166 + [seasonEpCount]=46，
     * 程序就拿全剧最新集 189 换算成本季第 24 集。
     */
    val seasonStartEp: Int = 0,
    /** 自定义分季：本季总集数（0 = 不自定义，走 TMDB 的季集数） */
    val seasonEpCount: Int = 0,
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
    /**
     * TMDB 海报的 `poster_path`（如 `/abc.jpg`）。
     * 图片本体按 tmdbId 缓存在 `filesDir/posters/<tmdbId>.jpg`，这里只存路径，
     * 换机/清缓存后能按它重新下载；没绑 TMDB 或没海报时为空串。
     */
    val posterPath: String = "",
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
    /**
     * 网盘文件夹名是否带 TMDB ID。
     * true 且绑定了 TMDB 时，目录名是「剧名 tmdb-12345」，否则就是纯剧名。
     */
    val folderWithTmdbId: Boolean = false,
    val enabled: Boolean = true,
    /** 置顶：卡片列表里排在未置顶之前（仅影响显示顺序，不影响执行顺序/调度） */
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val UPDATE_MODE_TMDB: String = "tmdb"
        const val UPDATE_MODE_MANUAL: String = "manual"

        const val SCOPE_SUBTREE: String = "SUBTREE"
        const val SCOPE_DIRECT_ONLY: String = "DIRECT_ONLY"

        /**
         * 季度取值约定：
         * - [`SEASON_FLAT`] 平铺（任务里没选季度，视频直接放在剧名目录下）
         * - [`SEASON_SPECIALS`] 特别版 / Season 0（**这是真实的季**，放 `剧名/Season 0/`）
         * - `1..99` 只取该季，放在 `剧名/Season N/`
         * - [`SEASON_MULTI`] 多季：按文件自身季号分组，各进 `剧名/Season N/`
         */
        const val SEASON_FLAT: Int = -2
        const val SEASON_SPECIALS: Int = 0
        const val SEASON_MULTI: Int = -1
    }
}
