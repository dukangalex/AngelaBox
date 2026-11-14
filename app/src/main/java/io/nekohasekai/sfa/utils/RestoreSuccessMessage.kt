package io.nekohasekai.sfa.utils

/** User-visible result text for a successful backup restore. */
internal fun restoreSuccessMessage(
    remoteProfileCount: Int,
    skippedScriptCount: Int,
    skippedBindingCount: Int,
    recoveryCleanupPending: Boolean,
): String = buildString {
    append(
        if (recoveryCleanupPending) {
            "恢复数据已完成，恢复日志待下次启动清理；正在重新加载…"
        } else {
            "恢复完成，正在重新加载…"
        },
    )
    if (remoteProfileCount > 0) {
        append(" · $remoteProfileCount 个远程订阅已恢复（自动更新已关闭）")
    }
    if (skippedScriptCount > 0) {
        append(" · 已跳过 $skippedScriptCount 个叠加脚本")
    }
    if (skippedBindingCount > 0) {
        append(" · 已跳过 $skippedBindingCount 条绑定")
    }
}
