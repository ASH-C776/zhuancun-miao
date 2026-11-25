package com.ashchan.cloudsaver.core.model

/**
 * 「一集」的结构化标识：季号 + 集号，都是可空数值。
 *
 * 引擎在落盘那一刻解析出来（文件名是唯一来源），之后传给通知层就**不再碰字符串**，
 * 命名规则怎么改都不会把通知算错。
 */
data class EpisodeTag(val season: Int? = null, val episode: Int? = null)
