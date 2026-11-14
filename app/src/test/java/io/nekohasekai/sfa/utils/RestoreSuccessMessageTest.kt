package io.nekohasekai.sfa.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreSuccessMessageTest {
    @Test
    fun pendingJournalCleanupIsReportedWithoutCallingRestoreIncomplete() {
        val message = restoreSuccessMessage(
            remoteProfileCount = 0,
            skippedScriptCount = 0,
            skippedBindingCount = 0,
            recoveryCleanupPending = true,
        )

        assertTrue(message.contains("恢复数据已完成"))
        assertTrue(message.contains("恢复日志待下次启动清理"))
        assertTrue(message.contains("正在重新加载"))
    }

    @Test
    fun ordinaryRestoreDoesNotShowCleanupWarning() {
        val message = restoreSuccessMessage(
            remoteProfileCount = 0,
            skippedScriptCount = 0,
            skippedBindingCount = 0,
            recoveryCleanupPending = false,
        )

        assertTrue(message.startsWith("恢复完成"))
        assertFalse(message.contains("恢复日志"))
    }

    @Test
    fun otherRestoreDetailsRemainVisibleAlongsideCleanupNotice() {
        val message = restoreSuccessMessage(
            remoteProfileCount = 2,
            skippedScriptCount = 1,
            skippedBindingCount = 3,
            recoveryCleanupPending = true,
        )

        assertTrue(message.contains("2 个远程订阅已恢复"))
        assertTrue(message.contains("已跳过 1 个叠加脚本"))
        assertTrue(message.contains("已跳过 3 条绑定"))
        assertTrue(message.contains("恢复日志待下次启动清理"))
    }
}
