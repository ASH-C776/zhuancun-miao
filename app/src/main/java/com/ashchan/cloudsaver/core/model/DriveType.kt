package com.ashchan.cloudsaver.core.model

/** 支持的网盘类型（MVP：光鸭 + 夸克） */
enum class DriveType(val displayName: String) {
    GUANGYA("光鸭云盘"),
    QUARK("夸克网盘");

    companion object {
        fun fromName(name: String): DriveType? = entries.firstOrNull { it.name.equals(name, true) }
    }
}
