package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.model.ShareNode

/**
 * 转存范围推荐。
 *
 * 用户实测口径（资源发布者的常见习惯）：
 * - **根目录平铺** 当前季的视频（`219-4K.mp4`…），旧的集数收在**下一级目录**里
 * - 也有整季目录结构（「剧名/第 1 季/14 集」）
 *
 * 所以建任务时的默认应该是推断出来的，不是固定 SUBTREE：
 * - 目标目录下**直接有视频** → DIRECT_ONLY（只跟这一层的新集，不去动下层归档）
 * - 目标目录下只有子目录 → SUBTREE（整棵子树保留层级）
 * - 空目录 → SUBTREE（交给用户在执行时再看）
 */
object ScopeAdvisor {

    fun recommend(node: ShareNode): TreeSyncEngine.Scope =
        if (node.children.any { it.isVideo }) {
            TreeSyncEngine.Scope.DIRECT_ONLY
        } else {
            TreeSyncEngine.Scope.SUBTREE
        }

    fun label(scope: TreeSyncEngine.Scope): String = when (scope) {
        TreeSyncEngine.Scope.DIRECT_ONLY -> "只跟当前目录的视频"
        TreeSyncEngine.Scope.SUBTREE -> "整棵目录（含旧集归档）"
    }

    fun fromName(name: String): TreeSyncEngine.Scope = when (name) {
        "DIRECT_ONLY" -> TreeSyncEngine.Scope.DIRECT_ONLY
        else -> TreeSyncEngine.Scope.SUBTREE
    }
}
