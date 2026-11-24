package com.ashchan.cloudsaver.update

/**
 * 一个可下载的新版本。来源只用于日志与优先级（同版本优先国内镜像）。
 */
data class UpdateInfo(
    /** 去掉 `v` 前缀的版本名，如 `0.8.0` */
    val versionName: String,
    /** 与本项目 build.gradle 同一算法的版本号：major*10000 + minor*100 + patch */
    val versionCode: Int,
    /** APK 直链（Release 附件里的 browser_download_url） */
    val downloadUrl: String,
    /** `gitee` / `github` */
    val source: String,
)

/**
 * 版本判定的两个纯函数。整件事里唯一值得写单测的地方：判反了就会出现
 * 「提示降级」或「永远提示有新版」这两种最难查的 bug。
 */
object UpdateRules {

    /**
     * tag → versionCode。算法必须与 `app/build.gradle.kts` 里的 `appVersionCode` 完全一致：
     * `major * 10000 + minor * 100 + patch`。
     *
     * 允许 `v0.8.0` / `V0.8` / `0.8.0-debug` / `1.2.3+4` 这些写法，脏数据一律返回 null。
     */
    fun parseVersionCode(raw: String?): Int? {
        val trimmed = raw?.trim().orEmpty().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return null
        val core = trimmed.substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.any { it < 0 || it > 9999 }) return null
        val major = nums[0]
        val minor = nums.getOrElse(1) { 0 }
        val patch = nums.getOrElse(2) { 0 }
        return major * 10000 + minor * 100 + patch
    }

    /**
     * 从两端候选里挑「版本号最高且高于当前版本」的那一个；同版本优先 Gitee（国内可达、下载快）。
     * 没有更新返回 null。
     */
    fun pickLatest(candidates: List<UpdateInfo>, currentCode: Int): UpdateInfo? {
        val fresh = candidates.filter { it.versionCode > currentCode }
        if (fresh.isEmpty()) return null
        return fresh.maxWith(
            compareBy<UpdateInfo> { it.versionCode }
                .thenBy { it.source == "gitee" }
        )
    }
}
