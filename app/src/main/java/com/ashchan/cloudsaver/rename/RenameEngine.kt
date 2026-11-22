package com.ashchan.cloudsaver.rename

import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.RenamePreview
import com.ashchan.cloudsaver.core.model.RenameRule
import com.ashchan.cloudsaver.core.model.RuleMode
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 重命名规则引擎（纯本地计算，不上传任何文件名或规则）。
 *
 * 规则执行逻辑：
 * 1. 依次运行所有 enabled 规则，按列表顺序取第一条命中的 rename 规则。
 * 2. filter 规则：pattern 不匹配的文件被排除（$BLACK_WORD 的负向前瞻 pattern
 *    因此语义为"含黑名单词的文件被过滤"）。
 * 3. rename 规则：pattern 匹配 → 按 replace 模板生成新名（部分匹配替换，兼容 Python re.sub 语义）。
 * 4. 占位符填充优先级：正则捕获组 > 文件名解析 > 用户默认值（TASKNAME）。
 * 5. 无法解析的占位符保留原文并标记 [hasUnresolved]，供 UI 标红、用户手填。
 */
class RenameEngine {

    /** 执行规则，返回每条文件的预览结果。 */
    fun preview(files: List<DriveFile>, rules: List<RenameRule>, taskName: String): List<RenamePreview> {
        val enabled = rules.filter { it.enabled }
        // 动态补零宽度：按本批文件最大集数的位数（至少 2 位）。如 22 集 → 01；199 集 → 001。
        val maxEp = files.mapNotNull { FileNameContext(it.name).episodeRaw?.toIntOrNull() }.maxOrNull()
        val epWidth = maxEp?.toString()?.length?.coerceAtLeast(2) ?: 2
        return files.map { file ->
            // 1. filter 规则：pattern 匹配 = 保留；不匹配 = 排除
            val filtered = enabled.any { rule ->
                rule.mode == RuleMode.FILTER && !compile(rule.pattern).matcher(file.name).find()
            }
            if (filtered) {
                return@map RenamePreview(
                    file = file,
                    originName = file.name,
                    newName = null,
                    matchedRule = null,
                    hasUnresolved = false,
                    filtered = true,
                )
            }

            // 2. rename 规则：按顺序取第一条命中
            val matched = enabled.firstOrNull { rule ->
                rule.mode == RuleMode.RENAME && compile(rule.pattern).matcher(file.name).find()
            }
            if (matched == null) {
                return@map RenamePreview(
                    file = file,
                    originName = file.name,
                    newName = file.name,
                    matchedRule = null,
                    hasUnresolved = false,
                    filtered = false,
                )
            }

            val applied = applyRule(matched, file.name, taskName, epWidth)
            // 占位符有未解析 → newName 置 null（保持原名，不生成残缺名字）
            val newName = if (applied.unresolvedPlaceholders.isNotEmpty()) null else applied.newName
            RenamePreview(
                file = file,
                originName = file.name,
                newName = newName,
                matchedRule = matched,
                hasUnresolved = applied.unresolvedPlaceholders.isNotEmpty(),
                filtered = false,
            )
        }
    }

    /** 应用单条规则，返回新名与未解析占位符 */
    fun applyRule(rule: RenameRule, fileName: String, taskName: String, epWidth: Int = 2): AppliedRule {
        val matcher = compile(rule.pattern).matcher(fileName)
        if (!matcher.find()) {
            return AppliedRule(fileName, emptyList())
        }

        // 正则反向引用转换：Python 风格 \1E\2  ->  Java 风格 $1E$2
        var result = toJavaReplacement(rule.replace)
        val unresolved = mutableListOf<String>()
        val ctx = FileNameContext(fileName)

        // 占位符填充（优先级：捕获组 > 文件名解析 > 用户默认值）
        if (result.contains("{TASKNAME}")) {
            if (taskName.isBlank()) unresolved.add("{TASKNAME}") else result = result.replace("{TASKNAME}", taskName)
        }
        if (result.contains("{EXT}")) {
            val ext = ctx.ext
            if (ext.isNullOrEmpty()) unresolved.add("{EXT}") else result = result.replace("{EXT}", ext)
        }
        if (result.contains("{SXX}")) {
            val sxx = ctx.season
            // 与 CASX 一致：识别不到季号时默认 S01
            result = result.replace("{SXX}", sxx ?: "S01")
        }
        if (result.contains("{E}")) {
            val e = ctx.episode
            if (e.isNullOrBlank()) unresolved.add("{E}") else result = result.replace("{E}", e.padStart(epWidth, '0'))
        }
        if (result.contains("{II}")) {
            val ii = ctx.episodeRaw
            if (ii == null) unresolved.add("{II}") else result = result.replace("{II}", ii)
        }
        if (result.contains("{PART}")) {
            val part = ctx.part
            if (part == null) {
                result = result.replace("{PART}", "")
            } else {
                result = result.replace("{PART}", part)
            }
        }
        if (result.contains("{DATE}")) {
            val date = ctx.date
            if (date == null) unresolved.add("{DATE}") else result = result.replace("{DATE}", date)
        }

        // 执行替换（等价 Python re.sub：替换全部匹配片段）
        val newName = matcher.reset().replaceAll(result)
        return AppliedRule(newName, unresolved)
    }

    /** 校验规则：正则合法性 + 占位符可解析性（用于保存自定义规则时） */
    fun validate(rule: RenameRule): Result<Unit> = runCatching {
        compile(rule.pattern)
        if (rule.mode == RuleMode.RENAME && rule.replace.contains(Regex("\\{\\w+\\}"))) {
            val unknown = Regex("\\{([A-Z_]+)\\}").findAll(rule.replace)
                .map { it.groupValues[1] }
                .filter { it !in KNOWN_PLACEHOLDERS }
                .toList()
            if (unknown.isNotEmpty()) {
                throw IllegalArgumentException("未知占位符: ${unknown.joinToString()}")
            }
        }
    }

    private fun compile(pattern: String): Pattern = try {
        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
    } catch (e: PatternSyntaxException) {
        throw IllegalArgumentException("正则不合法: ${e.description}", e)
    }

    /** \1 -> $1 */
    private fun toJavaReplacement(pythonReplace: String): String =
        BACKREF.replace(pythonReplace, "\$1")

    data class AppliedRule(
        val newName: String,
        val unresolvedPlaceholders: List<String>,
    )

    companion object {
        private val BACKREF = Regex("\\\\+(\\d+)")
        private val KNOWN_PLACEHOLDERS = setOf("TASKNAME", "SXX", "E", "II", "PART", "DATE", "EXT")
    }
}
