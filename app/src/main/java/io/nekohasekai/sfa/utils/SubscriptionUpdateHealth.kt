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

    /** 连续失败达到该次数后在仪表盘横幅提醒 */
    const val FAILURE_ALERT_THRESHOLD = 3

    /** 流量用到该比例后提醒「即将用尽」 */
    private const val QUOTA_WARN_RATIO = 0.9f

    fun recordSuccess(context: Context, profileId: Long) {
        if (profileId < 0L) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_FAIL_PREFIX + profileId)
            .remove(KEY_ERROR_PREFIX + profileId)
            .apply()
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
    }

    data class HealthIssue(
        val profileName: String,
        val kind: IssueKind,
        val detail: String,
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
                if (info.hasQuota) {
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
