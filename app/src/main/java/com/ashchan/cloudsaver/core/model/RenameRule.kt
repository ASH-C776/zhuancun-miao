package com.ashchan.cloudsaver.core.model

/** 重命名规则模型（与任务说明中的字段一一对应） */
data class RenameRule(
    /** 唯一标识，如 $TV_REGEX / 自定义 uuid */
    val key: String,
    /** 展示名称 */
    val name: String,
    /** 是否默认启用 */
    val enabled: Boolean = true,
    /** 匹配正则（原始字符串，运行时编译） */
    val pattern: String,
    /** 替换模板（含占位符 {TASKNAME}/{E}/{EXT}... 或正则反向引用 \1E\2） */
    val replace: String,
    /** filter：仅筛选（命中则排除）；rename：筛选并改名 */
    val mode: RuleMode = RuleMode.RENAME,
    /** 是否内置规则 */
    val builtin: Boolean = false,
)

enum class RuleMode { FILTER, RENAME }

/** 规则执行结果 */
data class RuleMatch(
    val rule: RenameRule,
    val newName: String?,
)

/** 单条文件的预览结果 */
data class RenamePreview(
    val file: DriveFile,
    val originName: String,
    val newName: String?,
    /** 命中的规则；null 表示未命中任何 rename 规则（保持原名） */
    val matchedRule: RenameRule?,
    /** 占位符是否全部可解析（不可解析的置空时标红提示） */
    val hasUnresolved: Boolean,
    /** 是否被 filter 规则排除 */
    val filtered: Boolean,
)
