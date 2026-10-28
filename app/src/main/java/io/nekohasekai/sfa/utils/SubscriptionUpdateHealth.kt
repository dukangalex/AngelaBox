package io.nekohasekai.sfa.utils

import android.content.Context
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile

/**
 * 订阅更新健康：追踪各远程订阅的连续更新失败次数，并在仪表盘给出健康提醒。
 *
 * 覆盖审计 Top3 的「订阅健康主动提醒」：
 * 订阅过期 / 流量耗尽或将尽 / 连续更新失败达到阈值 → 仪表盘横幅。
 */
object SubscriptionUpdateHealth {

    private const val PREFS = "subscription_update_health"
    private const val KEY_FAIL_PREFIX = "fail_"
    private const val KEY_ERROR_PREFIX = "err_"
    private const val KEY_WARN_PREFIX = "warn_"
    private const val KEY_WARN_SEQ_PREFIX = "warnseq_"
    private const val KEY_WARN_DISMISS_PREFIX = "warndismiss_"
    private const val KEY_ATTEMPT_PREFIX = "attempt_"

    /** 连续失败达到该次数后在仪表盘横幅提醒 */
    const val FAILURE_ALERT_THRESHOLD = 3

    /** 流量用到该比例后提醒「即将用尽」 */
    private const val QUOTA_WARN_RATIO = 0.9f

    /** E-1 退避基数：首次失败后至少等这么久再由后台重试，之后翻倍。 */
    private const val BACKOFF_BASE_SECONDS = 900L

    /**
     * E-8: 流量类结论只认这么新的 userinfo 快照。快照只在抓取成功时刷新，
     * 关掉自动更新后它可以是几周前的值——拿旧值报「流量已用尽」是误报；
     * 过期判断是绝对时间，不受这条影响。
     */
    private const val QUOTA_SNAPSHOT_MAX_AGE_MS = 7L * 24 * 3600 * 1000

    fun recordSuccess(context: Context, profileId: Long) {
        if (profileId < 0L) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_FAIL_PREFIX + profileId)
            .remove(KEY_ERROR_PREFIX + profileId)
            .remove(KEY_ATTEMPT_PREFIX + profileId)
            // X-4: 成功更新（含前台手动更新）必须清掉旧的「跳过/修正」警告，
            // 否则横幅会一直展示上一次后台更新留下的旧内容。本次更新若仍有
            // 警告，调用方在 recordSuccess 之后用 recordImportWarnings 重记。
            .remove(KEY_WARN_PREFIX + profileId)
            .apply()
    }

    /** E-1: 每次真正发起抓取尝试时记时间戳，成功/失败都记。 */
    fun recordAttempt(context: Context, profileId: Long) {
        if (profileId < 0L) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ATTEMPT_PREFIX + profileId, System.currentTimeMillis())
            .apply()
    }

    fun lastAttempt(context: Context, profileId: Long): Long {
        if (profileId < 0L) return 0L
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_ATTEMPT_PREFIX + profileId, 0L)
    }

    /**
     * E-1: 失败后的退避秒数。指数增长（15m、30m、1h、2h…），但永远不超过
     * 该订阅自己的更新间隔——退避不会把正常订阅饿死，最差也按它自己的
     * 周期重试一次；手动更新不走这条判断，永远直通。
     */
    internal fun backoffSeconds(failureStreak: Int, intervalSeconds: Long): Long {
        if (failureStreak <= 0 || intervalSeconds <= 0L) return 0L
        var backoff = BACKOFF_BASE_SECONDS
        var doublings = failureStreak - 1
        while (doublings > 0 && backoff < intervalSeconds) {
            backoff = minOf(backoff * 2, intervalSeconds)
            doublings--
        }
        return minOf(backoff, intervalSeconds)
    }

    /**
     * E-1: 到期判断。lastUpdated 只在成功时更新，单看它会让失败（包括 404
     * 这类永久失败）的订阅在每个 worker 周期里被全阶梯重抓。现在叠加
     * 「距上次尝试须超过退避时长」，死订阅不再跟着全场最短周期空转。
     */
    internal fun isDue(
        nowMs: Long,
        lastUpdatedMs: Long,
        lastAttemptMs: Long,
        failureStreak: Int,
        intervalSeconds: Long,
    ): Boolean {
        if (intervalSeconds <= 0L) return true
        if (nowMs - lastUpdatedMs < intervalSeconds * 1000L) return false
        if (lastAttemptMs <= 0L) return true
        return nowMs - lastAttemptMs >= backoffSeconds(failureStreak, intervalSeconds) * 1000L
    }

    fun recordFailure(context: Context, profileId: Long, error: Throwable) {
        if (profileId < 0L) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_FAIL_PREFIX + profileId, 0) + 1
        prefs.edit()
            .putInt(KEY_FAIL_PREFIX + profileId, count)
            .putString(KEY_ERROR_PREFIX + profileId, sanitizeError(error))
            .apply()
    }

    fun consecutiveFailures(context: Context, profileId: Long): Int {
        if (profileId < 0L) return 0
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FAIL_PREFIX + profileId, 0)
    }

    fun lastError(context: Context, profileId: Long): String? {
        if (profileId < 0L) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ERROR_PREFIX + profileId, null)
    }

    /**
     * 记录上次成功更新时的"跳过/修正"提示（如跳过 N 个不支持的节点）。
     * 更新成功不等于没有丢失，提示必须留下来给仪表盘横幅展示。
     */
    fun recordImportWarnings(context: Context, profileId: Long, warnings: List<String>) {
        if (profileId < 0L) return
        val text = warnings.filter { it.isNotBlank() }.joinToString("\n")
            .replace(Regex("https?://[^\\s\"'<>]+"), "[链接已隐藏]")
            .take(500)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .apply {
                if (text.isBlank()) {
                    remove(KEY_WARN_PREFIX + profileId)
                } else {
                    putString(KEY_WARN_PREFIX + profileId, text)
                    // AB-025: every update that leaves warnings is a
                    // new batch. Bump the batch sequence so a dismissal
                    // of the previous batch never hides this one, even
                    // when the text happens to be identical.
                    putLong(
                        KEY_WARN_SEQ_PREFIX + profileId,
                        prefs.getLong(KEY_WARN_SEQ_PREFIX + profileId, 0L) + 1L,
                    )
                }
            }
            .apply()
        // v1.0.136: 更新提示不再常驻横幅——全文进应用提示记录留档
        // （日志 → 应用提示记录 / 诊断导出），界面只在仪表盘闪一行汇总。
        if (text.isNotBlank()) {
            NoticeLogStore.append(
                listOf(
                    NoticeLogRecord(
                        timestampMs = System.currentTimeMillis(),
                        source = NoticeLog.SOURCE_SUBSCRIPTION,
                        title = "订阅更新提示",
                        reason = text,
                        hint = "",
                        error = false,
                    ),
                ),
            )
        }
    }

    /** 当前更新提示的批次序号（AB-025 的 warnseq）：闪现去重按（订阅， 批次）记。 */
    fun importWarningsBatch(context: Context, profileId: Long): Long {
        if (profileId < 0L) return 0L
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_WARN_SEQ_PREFIX + profileId, 0L)
    }

    /**
     * AB-025: 用户在横幅上关掉某订阅的这批更新提示。只记录当前批次序号，
     * 下次更新产生新一批提示（序号递增）时会重新显示。
     */
    fun dismissImportWarnings(context: Context, profileId: Long) {
        if (profileId < 0L) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Batches recorded before the sequence existed count as batch 1.
        val batch = maxOf(prefs.getLong(KEY_WARN_SEQ_PREFIX + profileId, 0L), 1L)
        prefs.edit()
            .putLong(KEY_WARN_SEQ_PREFIX + profileId, batch)
            .putLong(KEY_WARN_DISMISS_PREFIX + profileId, batch)
            .apply()
    }

    private fun importWarningsDismissed(context: Context, profileId: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val batch = prefs.getLong(KEY_WARN_SEQ_PREFIX + profileId, 0L)
        // Batches recorded before the sequence existed (batch 0) were
        // never dismissible; they show until the user closes them,
        // which normalizes them to batch 1 via dismissImportWarnings.
        if (batch <= 0L) return false
        return prefs.getLong(KEY_WARN_DISMISS_PREFIX + profileId, 0L) >= batch
    }

    fun lastImportWarnings(context: Context, profileId: Long): String? {
        if (profileId < 0L) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WARN_PREFIX + profileId, null)
    }

    /**
     * 错误信息只保留脱敏后的首条 message，URL（含订阅 token）一律抹掉，
     * 避免凭据落进 SharedPreferences。
     */
    private fun sanitizeError(error: Throwable): String {
        var current: Throwable? = error
        var message: String? = null
        while (current != null && message.isNullOrBlank()) {
            message = current.message?.trim()?.takeIf { it.isNotEmpty() }
            current = current.cause
        }
        var text = message ?: error.javaClass.simpleName
        text = text.replace(Regex("https?://[^\\s\"'<>]+"), "[链接已隐藏]")
        if (text.length > 160) text = text.take(160) + "…"
        return text
    }

    enum class IssueKind {
        EXPIRED,
        QUOTA_EXHAUSTED,
        QUOTA_LOW,
        UPDATE_FAILED,
        IMPORT_WARNINGS,
    }

    data class HealthIssue(
        val profileName: String,
        val kind: IssueKind,
        val detail: String,
        /** Owning profile; only set for IMPORT_WARNINGS (dismiss target). */
        val profileId: Long = -1L,
    )

    /** 评估所有远程订阅的健康状况，供仪表盘横幅展示。 */
    suspend fun evaluate(context: Context): List<HealthIssue> {
        val issues = mutableListOf<HealthIssue>()
        val now = System.currentTimeMillis()
        val profiles = runCatching { ProfileManager.list() }.getOrDefault(emptyList())
        for (profile in profiles) {
            if (profile.typed.type != TypedProfile.Type.Remote) continue
            val name = profile.name.ifBlank { context.getString(R.string.profile_type_remote) }
            // 订阅信息：过期 / 流量
            val info = runCatching { SubscriptionInfoStore.get(context, profile.id) }.getOrNull()
            if (info != null) {
                val expireAt = info.expireAt
                if (expireAt != null && expireAt > 0L && expireAt * 1000L < now) {
                    val label = info.expireLabel()
                    val detail = if (label != null) {
                        context.getString(R.string.subscription_health_expired_detail, label)
                    } else {
                        ""
                    }
                    issues.add(HealthIssue(name, IssueKind.EXPIRED, detail))
                    // 已过期就不再重复报流量
                    continue
                }
                if (info.hasQuota && info.isQuotaFresh(now, QUOTA_SNAPSHOT_MAX_AGE_MS)) {
                    val usage = context.getString(
                        R.string.subscription_health_quota_used,
                        info.usedLabel(),
                        info.totalLabel(),
                    )
                    when {
                        info.used >= info.total ->
                            issues.add(HealthIssue(name, IssueKind.QUOTA_EXHAUSTED, usage))
                        info.progress >= QUOTA_WARN_RATIO ->
                            issues.add(HealthIssue(name, IssueKind.QUOTA_LOW, usage))
                    }
                }
            }
            // 上次更新成功但有"跳过/修正"提示：也要让用户看见；
            // 用户已关闭的这一批不再显示（AB-025），下次更新的新批次会重新出现。
            val importWarnings = lastImportWarnings(context, profile.id)
            if (!importWarnings.isNullOrBlank() && !importWarningsDismissed(context, profile.id)) {
                issues.add(HealthIssue(name, IssueKind.IMPORT_WARNINGS, importWarnings, profile.id))
            }
            // 连续更新失败
            val failures = consecutiveFailures(context, profile.id)
            if (failures >= FAILURE_ALERT_THRESHOLD) {
                val error = lastError(context, profile.id)
                val detail = if (!error.isNullOrBlank()) {
                    context.getString(
                        R.string.subscription_health_update_failed_detail,
                        failures,
                        error,
                    )
                } else {
                    context.getString(
                        R.string.subscription_health_update_failed_detail_simple,
                        failures,
                    )
                }
                issues.add(HealthIssue(name, IssueKind.UPDATE_FAILED, detail))
            }
        }
        return issues
    }
}
