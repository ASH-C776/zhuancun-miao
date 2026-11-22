package com.ashchan.cloudsaver.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃 / 未捕获异常落盘。
 *
 * 纯单机 App 没有服务端日志，用户遇到「点一下就没反应」时唯一能自证的就是这个文件。
 * 只做两件事：把堆栈追加到 `filesDir/crash.log`，并把老日志裁到 [MAX_SIZE] 以内。
 * 不做上传、不做后台服务 —— 与「零依赖单机」的口径一致。
 */
object AppLog {

    private const val FILE_NAME = "crash.log"
    private const val MAX_SIZE = 256 * 1024L
    private const val TAG = "AppLog"

    /**
     * 安装全局未捕获异常钩子。调用一次即可（重复调用会把旧的 handler 串成链，导致日志重复）。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, "未捕获异常 @ ${thread.name}", error)
            } catch (t: Throwable) {
                Log.e(TAG, "写崩溃日志失败：${t.message}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 手动记录一条异常（协程内部兜底用，不中断流程） */
    fun record(context: Context, scene: String, error: Throwable) {
        try {
            write(context.applicationContext, scene, error)
        } catch (t: Throwable) {
            Log.e(TAG, "记录异常失败：${t.message}")
        }
    }

    private fun write(context: Context, scene: String, error: Throwable) {
        val file = File(context.filesDir, FILE_NAME)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val text = buildString {
            appendLine("---- $time ----")
            appendLine(scene)
            appendLine(Log.getStackTraceString(error))
        }
        file.appendText(text)

        if (file.length() > MAX_SIZE) {
            // 超限只保留后半段，避免长期使用后把存储吃掉
            val content = file.readText()
            file.writeText(content.takeLast((MAX_SIZE / 2).toInt()))
        }
        Log.e(TAG, "$scene：${error.message}")
    }

    /** 导出当前日志内容（「运行日志」页底部展示用） */
    fun read(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return ""
        return try {
            file.readText()
        } catch (t: Throwable) {
            "读取失败：${t.message}"
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).runCatching { delete() }
    }
}
