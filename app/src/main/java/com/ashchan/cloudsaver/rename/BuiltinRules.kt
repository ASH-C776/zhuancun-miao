package com.ashchan.cloudsaver.rename

import com.ashchan.cloudsaver.core.model.RenameRule
import com.ashchan.cloudsaver.core.model.RuleMode

/** 内置重命名规则（精简版：仅保留剧集集数 + 通用视频命名；综艺/黑名单已移除，自定义规则用拼装器） */
object BuiltinRules {

    val defaults: List<RenameRule> = listOf(
        RenameRule(
            key = "\$TV_REGEX",
            name = "剧集集数（SxxExx）",
            enabled = true,
            pattern = ".*?([Ss]\\d{1,2})?(?:[第EePpXx.\\-_\\( ]{1,2}|^)(\\d{1,3})(?!\\d).*?\\.(mp4|mkv|mov|m4v|avi|mpeg|ts|zip|cas|iso)",
            replace = "\\1E\\2.\\3",
            mode = RuleMode.RENAME,
            builtin = true,
        ),
        RenameRule(
            key = "\$TV_MAGIC",
            name = "通用视频命名",
            enabled = true,
            pattern = ".*\\.(mp4|mkv|mov|m4v|avi|mpeg|ts|zip|cas|iso)$",
            replace = "{TASKNAME}.{SXX}E{E}.{EXT}",
            mode = RuleMode.RENAME,
            builtin = true,
        ),
    )

    fun byKey(key: String): RenameRule? = defaults.firstOrNull { it.key == key }
}
