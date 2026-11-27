package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.RenameRule
import com.ashchan.cloudsaver.core.model.VideoTypes
import com.ashchan.cloudsaver.data.SeasonParser
import com.ashchan.cloudsaver.data.TaskRepository
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import com.ashchan.cloudsaver.rename.FileNameContext
import com.ashchan.cloudsaver.rename.RenameEngine
import com.ashchan.cloudsaver.storage.CredentialStore
import com.ashchan.cloudsaver.storage.RuleStore
import com.ashchan.cloudsaver.drive.DriveSession
import com.ashchan.cloudsaver.core.model.DriveType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 统一重命名：只对**任务已转存目录里**的文件重跑命名规则。
 *
 * 硬边界（用户口径）：
 * - 只改文件名，**绝不移动文件、不新建目录、不改变层级**；
 * - 目录名一律不动（季号以文件所在目录为准）；
 * - 目标名已被别的文件占用 → 记冲突，跳过（不覆盖）；
 * - 解析不出集号的 → 跳过并计数（不猜）；
 * - 同一批里两个文件算出同一个新名 → 全部记冲突，避免一季的文件撞成一个名字。
 *
 * 与 [TreeSyncEngine] 的区别：那边是「转存 + 改名」一条链，这里只做后者，
 * 因此可以独立于转存重跑，修复「转存完了但改名被中断」的残留。
 */
class RenamePass(
    private val repo: TaskRepository,
    private val credentialStore: CredentialStore,
    private val ruleStore: RuleStore? = null,
) {

    companion object {
        private const val MAX_DEPTH = 3
        private const val CONCURRENCY = 2
        private const val ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 1000L
        private const val THROTTLE_DELAY_MS = 2500L
    }

    data class Result(
        val scanned: Int,
        val renamed: Int,
        val alreadyOk: Int,
        val conflicts: Int,
        val failed: Int,
        val skipped: Int,
        val failures: List<String>,
        val notes: List<String>,
        /** 整体是否成功（没有失败项，且不是因为前置条件缺失直接退出） */
        val ok: Boolean,
    )

    private data class Entry(
        val dirFid: String,
        val dirNames: List<String>,
        val season: Int,
        val file: DriveFile,
    )

    /**
     * @return 是否整体成功（没有任何改名失败）；扫描/规则层面的硬错误会抛异常给调用方
     */
    suspend fun run(
        task: TaskEntity,
        onPhase: (String) -> Unit = {},
    ): Result {
        val startedAt = System.currentTimeMillis()
        val key = task.ruleKey.trim()
        if (key.isEmpty()) {
            return finish(task, startedAt, "任务没有设置命名规则，无需规范化")
        }
        val store = ruleStore ?: return finish(task, startedAt, "读不到命名规则")
        val rule = store.loadRules().firstOrNull { it.key == key && it.enabled }
            ?: return finish(task, startedAt, "命名规则「$key」不存在或已停用")

        val drive = DriveType.fromName(task.driveType) ?: return finish(task, startedAt, "未知网盘类型：${task.driveType}")
        val adapter = DriveSession.ensure(drive, credentialStore)
            .getOrElse { return finish(task, startedAt, it.message ?: "登录已失效") }
        if (adapter is com.ashchan.cloudsaver.drive.guangya.GuangYaAdapter) {
            // access_token 每轮会轮换，回写避免下次掉线
            runCatching { credentialStore.saveCredential(DriveType.GUANGYA, adapter.currentCredential()) }
        }

        onPhase("扫描目录")
        val baseFid = task.saveFid.ifBlank { "0" }
        val folderName = taskNameOf(task)
        val baseItems = adapter.listFiles(baseFid).getOrThrow()
        val nameFid = baseItems.firstOrNull { it.isDir && it.name == folderName }?.fid
            ?: throw IllegalStateException("找不到任务目录「$folderName」，可能已被移动或删除")

        val roots = resolveRoots(adapter, task, nameFid)
        val entries = mutableListOf<Entry>()
        roots.forEach { (fid, dirNames) -> collect(adapter, fid, dirNames, entries) }

        onPhase("识别集号 ${entries.size}")
        val scanned = entries.size
        if (scanned == 0) return finish(task, startedAt, "目录里没有视频文件", scanned = 0)

        // 按目录分批算新名：补零宽度按该目录的最大集号决定（与转存时一致）
        val byDir = entries.groupBy { it.dirFid }
        val plans = mutableListOf<Triple<Entry, String, Boolean>>() // entry / 新名 / 是否已正确
        var unparsed = 0
        var unmatched = 0
        byDir.forEach { (_, list) ->
            val width = list.mapNotNull { episodeOf(it.file.name) }.maxOrNull()?.toString()?.length?.coerceAtLeast(2) ?: 2
            list.forEach { entry ->
                val ep = episodeOf(entry.file.name)
                if (ep == null) {
                    unparsed++
                    return@forEach
                }
                val newName = applyRule(rule, entry, width, task.name) ?: run {
                    unmatched++
                    return@forEach
                }
                plans += Triple(entry, newName, newName == entry.file.name)
            }
        }

        // 冲突预判：目标名已存在，或同一目录内两个文件撞同一新名
        val existing = mutableMapOf<String, MutableSet<String>>()
        entries.forEach { e ->
            existing.getOrPut(e.dirFid) { mutableSetOf() } += e.file.name
        }
        val plannedNames = mutableMapOf<String, MutableSet<String>>()
        // 本轮会被改掉的原名：用于放行「整体移位」式改名（01→02、02→03 这类不该被判冲突）
        val plannedAway = mutableMapOf<String, MutableSet<String>>()
        plans.forEach { (entry, _, same) ->
            if (!same) plannedAway.getOrPut(entry.dirFid) { mutableSetOf() } += entry.file.name
        }
        val todo = mutableListOf<Pair<Entry, String>>()
        var alreadyOk = 0
        var conflicts = 0
        val conflictNotes = mutableListOf<String>()
        plans.forEach { (entry, newName, same) ->
            if (same) {
                alreadyOk++
                return@forEach
            }
            val used = existing[entry.dirFid].orEmpty()
            val movingAway = plannedAway[entry.dirFid].orEmpty()
            if (newName in used && newName !in movingAway) {
                conflicts++
                if (conflictNotes.size < 5) conflictNotes += "${entry.file.name} → $newName（已存在）"
                return@forEach
            }
            val bucket = plannedNames.getOrPut(entry.dirFid) { mutableSetOf() }
            if (!bucket.add(newName)) {
                conflicts++
                if (conflictNotes.size < 5) conflictNotes += "${entry.file.name} → $newName（同集撞名）"
                return@forEach
            }
            todo += entry to newName
        }

        // 真正改名：并发 2 + 退避重试（光鸭连续改名会限流）
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val renamedCounter = java.util.concurrent.atomic.AtomicInteger(0)
        if (todo.isNotEmpty()) {
            val total = todo.size
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            coroutineScope {
                val slots = Semaphore(CONCURRENCY)
                todo.map { (entry, newName) ->
                    async(Dispatchers.IO) {
                        slots.withPermit {
                            val reason = renameWithRetry(adapter, entry.file.fid, newName)
                            if (reason == null) {
                                renamedCounter.incrementAndGet()
                            } else {
                                failures += "改名失败：${entry.file.name} → $newName（$reason）"
                            }
                            val n = done.incrementAndGet()
                            onPhase("重命名 $n/$total")
                        }
                    }
                }.awaitAll()
            }
        }

        val renamed = renamedCounter.get()
        onPhase("收尾")
        val notes = mutableListOf<String>()
        notes += "检查 $scanned 个 · 改名 $renamed · 已正确 $alreadyOk · 冲突 $conflicts · 失败 ${failures.size}" +
            (if (unparsed > 0) " · 无法识别 $unparsed" else "") +
            (if (unmatched > 0) " · 规则不匹配 $unmatched" else "")
        notes += conflictNotes
        return finish(
            task = task,
            startedAt = startedAt,
            message = "",
            scanned = scanned,
            renamed = renamed,
            alreadyOk = alreadyOk,
            conflicts = conflicts,
            failed = failures.size,
            skipped = unparsed + unmatched,
            failures = failures.toList(),
            notes = notes,
        )
    }

    // ---------------- 目录 --------------

    /** 本次要扫的根目录：指定季只扫该季；平铺扫剧名目录；多季扫所有子目录 + 根下文件 */
    private suspend fun resolveRoots(
        adapter: CloudDriveAdapter,
        task: TaskEntity,
        nameFid: String,
    ): List<Pair<String, List<String>>> {
        val items = adapter.listFiles(nameFid).getOrNull().orEmpty()
        return when {
            task.seasonNo == TaskEntity.SEASON_MULTI -> {
                // 只从剧名根目录递归一次：collect 本身已经下钻所有子目录，
                // 以前再把每个 Season 目录单独当一个根，同一批文件会被扫两遍 ——
                // 「检查 N / 冲突 Z」直接翻倍，第二次必然和自己撞名。
                listOf(nameFid to emptyList())
            }
            task.seasonNo >= TaskEntity.SEASON_SPECIALS -> {
                val seasonName = "Season ${task.seasonNo}"
                val seasonFid = items.firstOrNull { it.isDir && it.name == seasonName }?.fid
                    ?: throw IllegalStateException("找不到季目录「$seasonName」")
                listOf(seasonFid to listOf(seasonName))
            }
            else -> listOf(nameFid to emptyList())
        }
    }

    /** 递归收集视频文件，跳过过深目录；不跟随任何「移动/新建」动作 */
    private suspend fun collect(
        adapter: CloudDriveAdapter,
        dirFid: String,
        dirNames: List<String>,
        out: MutableList<Entry>,
        depth: Int = 0,
    ) {
        val items = adapter.listFiles(dirFid).getOrNull() ?: return
        val season = SeasonParser.parse(dirNames.lastOrNull().orEmpty())
            ?: dirNames.mapNotNull { SeasonParser.parse(it) }.lastOrNull()
            ?: TaskEntity.SEASON_FLAT
        items.filter { !it.isDir && VideoTypes.isVideoLike(it.name) }.forEach {
            out += Entry(dirFid, dirNames, season, it)
        }
        if (depth >= MAX_DEPTH) return
        items.filter { it.isDir }.forEach { child ->
            collect(adapter, child.fid, dirNames + child.name, out, depth + 1)
        }
    }

    // ---------------- 命名 --------------

    private fun episodeOf(fileName: String): Int? = FileNameContext(fileName).episodeRaw?.toIntOrNull()

    /**
     * 生成新名；规则没匹配上返回 null（该文件记为「规则不匹配」而跳过）。
     *
     * 季号以所在目录为准：把 `{SXX}` 提前替换掉，避免文件名里的季号和目录不一致时改错。
     */
    private fun applyRule(rule: RenameRule, entry: Entry, epWidth: Int, taskName: String): String? {
        val fileName = entry.file.name
        // 规则自身是否命中（applyRule 不暴露匹配状态，先用同一个正则判断）
        val matched = runCatching {
            java.util.regex.Pattern.compile(rule.pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(fileName).find()
        }.getOrDefault(false)
        if (!matched) return null

        val season = when {
            entry.season >= 0 -> entry.season
            else -> 1
        }
        val replace = rule.replace.replace("{SXX}", "S%02d".format(season))
        val applied = RenameEngine().applyRule(rule.copy(replace = replace), fileName, taskName = taskName, epWidth = epWidth)
        if (applied.unresolvedPlaceholders.isNotEmpty()) return null
        val newName = applied.newName.trim()
        if (newName.isEmpty() || newName.contains('/')) return null
        return newName
    }

    private suspend fun renameWithRetry(adapter: CloudDriveAdapter, fid: String, newName: String): String? {
        var reason: String? = "改名请求异常"
        var attempt = 0
        while (attempt < ATTEMPTS) {
            val result = runCatching { adapter.renameFile(fid, newName) }.getOrNull()
            if (result?.isSuccess == true) return null
            reason = result?.exceptionOrNull()?.message ?: "改名请求异常（$newName）"
            attempt++
            if (attempt < ATTEMPTS) {
                val wait = if (isThrottled(reason.orEmpty())) THROTTLE_DELAY_MS * attempt else RETRY_DELAY_MS * attempt
                delay(wait)
            }
        }
        return reason
    }

    private fun isThrottled(message: String): Boolean =
        message.contains("频繁") || message.contains("127") || message.contains("rate", true)

    // ---------------- 收尾 --------------

    /** 统一落运行日志（trigger = rename），不动任务快照里的集数 */
    private suspend fun finish(
        task: TaskEntity,
        startedAt: Long,
        message: String,
        scanned: Int = 0,
        renamed: Int = 0,
        alreadyOk: Int = 0,
        conflicts: Int = 0,
        failed: Int = 0,
        skipped: Int = 0,
        failures: List<String> = emptyList(),
        notes: List<String> = emptyList(),
    ): Result {
        val allNotes = if (message.isNotBlank()) notes + message else notes
        val report = TreeSyncEngine.Report(
            restoredDirs = 0,
            restoredFiles = 0,
            renamedItems = renamed,
            skippedFiles = alreadyOk + skipped,
            failures = failures,
            explicitOk = message.isBlank() && failures.isEmpty(),
            notes = allNotes,
        )
        repo.finishRun(
            task = task,
            trigger = RunRecordEntity.TRIGGER_RENAME,
            startedAt = startedAt,
            report = report,
            localEpisodes = 0,
            localLastEpisode = 0,
            tmdbLatest = 0,
            tmdbTotal = 0,
            saveSnapshot = false,
        )
        return Result(scanned, renamed, alreadyOk, conflicts, failed, skipped, failures, allNotes, report.ok)
    }

    private fun taskNameOf(task: TaskEntity): String {
        val base = task.name.trim()
        val id = task.tmdbId
        return if (task.folderWithTmdbId && id != null) "$base tmdb-$id" else base
    }
}




