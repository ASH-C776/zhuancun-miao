package com.ashchan.cloudsaver.ui.tasks

import com.ashchan.cloudsaver.data.db.RunRecordEntity

/**
 * 运行日志的状态归类。
 *
 * 日志卡片原先只有「成功 / 失败」二元，导致「新增 3 集」和「空跑一轮」长得完全一样。
 * 这里把数据库里已有的字段（ok / 计数 / message）归结成 10 种语义状态，
 * 只做分类、不引入新字段、不改表结构；颜色与文案在 UI 层映射。
 */
enum class RunLogKind {
    /** 有新增文件 */
    NEW,

    /** 没有新文件，但改名或建目录了（整理已有集） */
    TIDY,

    /** 什么都没发生（空跑，多数是「最新了」） */
    IDLE,

    /** 多源里部分源失败，但整轮仍算成功 */
    PARTIAL,

    /** 只有提示（季判定、冲突已按文件名处理等），不算错 */
    NOTE,

    /** 登录失效 */
    FAIL_LOGIN,

    /** 被限流 */
    FAIL_RATE,

    /** 网络异常 */
    FAIL_NET,

    /** 分享里没有可转存的内容 */
    FAIL_EMPTY,

    /** 其它失败 */
    FAILED,
}

data class RunLogStatus(
    val kind: RunLogKind,
    /** 胶囊上的短标签，直接写结论，不写「成功/失败」 */
    val label: String,
)

object RunLogStatusRules {

    fun classify(record: RunRecordEntity): RunLogStatus {
        val message = record.message.trim()

        if (!record.ok) {
            return failedKind(message)
        }

        // 多源部分失败：整轮 ok，但 message 里带着某个源的原因
        if (message.contains("失败") || message.contains("错误")) {
            return RunLogStatus(RunLogKind.PARTIAL, "部分失败")
        }
        if (record.restoredFiles > 0) {
            return RunLogStatus(RunLogKind.NEW, "转存 ${record.restoredFiles} 集")
        }
        if (record.renamedItems > 0) {
            return RunLogStatus(RunLogKind.TIDY, "改名 ${record.renamedItems} 项")
        }
        if (record.restoredDirs > 0) {
            return RunLogStatus(RunLogKind.TIDY, "新建 ${record.restoredDirs} 个目录")
        }
        if (message.isNotBlank()) {
            return RunLogStatus(RunLogKind.NOTE, "提示")
        }
        return RunLogStatus(RunLogKind.IDLE, "无变化")
    }

    /** 失败再细分原因，让人一眼知道「该去登录 / 该等一会 / 该查网络 / 该换链接」 */
    private fun failedKind(message: String): RunLogStatus = when {
        message.contains("登录") || message.contains("令牌") ||
            message.contains("token", true) || message.contains("cookie", true) ||
            message.contains("未授权") || message.contains("401") ->
            RunLogStatus(RunLogKind.FAIL_LOGIN, "登录失效")

        message.contains("频繁") || message.contains("限流") ||
            message.contains("稍后重试") || message.contains("429") ->
            RunLogStatus(RunLogKind.FAIL_RATE, "被限流")

        message.contains("Unable to resolve host", true) || message.contains("超时") ||
            message.contains("timeout", true) || message.contains("连接") ||
            message.contains("网络") || message.contains("SSL", true) ->
            RunLogStatus(RunLogKind.FAIL_NET, "网络异常")

        message.contains("没有找到可转存") || message.contains("没有可转存") ||
            message.contains("没有找到") || message.contains("无可用") ->
            RunLogStatus(RunLogKind.FAIL_EMPTY, "分享无内容")

        else -> RunLogStatus(RunLogKind.FAILED, "失败")
    }
}
