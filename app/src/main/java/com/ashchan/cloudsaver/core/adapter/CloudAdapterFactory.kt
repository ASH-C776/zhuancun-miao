package com.ashchan.cloudsaver.core.adapter

import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.drive.guangya.GuangYaAdapter
import com.ashchan.cloudsaver.drive.quark.QuarkAdapter

/**
 * 按网盘类型造适配器。
 *
 * 抽出来是因为现在有两个调用方：`TaskRunner`（执行转存）和新建任务页（列目录选保存路径）。
 * 之前构造函数散在 TaskRunner 里，第二处要用就得抄一份凭证拆分逻辑，容易两边漂移。
 */
object CloudAdapterFactory {

    fun create(drive: DriveType, credential: String): CloudDriveAdapter = when (drive) {
        DriveType.QUARK -> QuarkAdapter(credential)
        DriveType.GUANGYA -> {
            // 光鸭凭证格式 "access||refresh"
            val parts = credential.split("||")
            GuangYaAdapter(
                accessToken = parts.getOrElse(0) { "" },
                refreshToken = parts.getOrElse(1) { "" },
            )
        }
    }
}
