package io.nekohasekai.sfa.bg

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.ProfileBindingStorage
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.ConfigCompat
import io.nekohasekai.sfa.utils.ConfigGate
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.SubscriptionUpdateHealth
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

class UpdateProfileWork {
    companion object {
        private const val WORK_NAME = "UpdateProfile"
        private const val TAG = "UpdateProfileWork"
        private const val INITIAL_PROFILE_ID = "initial_profile_id"

        /** Queue one durable, network-constrained retry for a newly saved subscription. */
        fun enqueueInitialImport(profileId: Long) {
            if (profileId <= 0L) return
            val request = OneTimeWorkRequestBuilder<UpdateTask>()
                .setInputData(workDataOf(INITIAL_PROFILE_ID to profileId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(Application.application).enqueueUniqueWork(
                "UpdateProfileInitial_$profileId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelInitialImport(profileId: Long) {
            if (profileId <= 0L) return
            WorkManager.getInstance(Application.application)
                .cancelUniqueWork("UpdateProfileInitial_$profileId")
        }

        /** E-2: one update round's time budget; WorkManager kills a run at ~10 min. */
        private const val ROUND_BUDGET_MS = 8L * 60 * 1000

        suspend fun reconfigureUpdater() {
            runCatching {
                reconfigureUpdater0()
            }.onFailure {
                Log.e(TAG, "reconfigureUpdater", it)
            }
        }

        private suspend fun reconfigureUpdater0() {
            val remoteProfiles =
                ProfileManager.list()
                    .filter { it.typed.type == TypedProfile.Type.Remote && it.typed.autoUpdate }
            if (remoteProfiles.isEmpty()) {
                WorkManager.getInstance(Application.application).cancelUniqueWork(WORK_NAME)
                return
            }

            var minDelay =
                remoteProfiles.minByOrNull { it.typed.autoUpdateInterval }!!.typed.autoUpdateInterval.toLong()
            val nowSeconds = System.currentTimeMillis() / 1000L
            val minInitDelay =
                remoteProfiles.minOf { (it.typed.autoUpdateInterval * 60) - (nowSeconds - (it.typed.lastUpdated.time / 1000L)) }
            if (minDelay < 15) minDelay = 15
            WorkManager.getInstance(Application.application).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                    .apply {
                        if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                        setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                        // 审计 B1：离线时不按周期唤醒空转，等有网络再跑
                        setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                    }
                    .build(),
            )
        }
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            var selectedProfileUpdated = false
            val initialProfileId = inputData.getLong(INITIAL_PROFILE_ID, -1L)
            val initialImport = initialProfileId > 0L
            val remoteProfiles =
                ProfileManager.list()
                    .filter {
                        it.typed.type == TypedProfile.Type.Remote &&
                            if (initialImport) it.id == initialProfileId else it.typed.autoUpdate
                    }
            if (remoteProfiles.isEmpty()) return Result.success()
            var hasTransientFailure = false
            var hasDeferredWork = false
            val selectedProfile = Settings.selectedProfile
            // E-2: WorkManager kills a run after ~10 minutes. Without a round
            // budget one slow server could starve every profile behind it and
            // the tail would be skipped silently (no attempt, no record).
            // Stop with time to spare and let Result.retry() pick the rest up.
            val roundDeadlineMs = System.currentTimeMillis() + ROUND_BUDGET_MS
            for (profile in remoteProfiles) {
                // E-1: due-ness is interval AND failure backoff. lastUpdated
                // only moves on success, so a dead profile used to be
                // re-fetched in full on every worker run — which runs at the
                // shortest interval of ALL profiles (floor 15 min).
                val due = initialImport || SubscriptionUpdateHealth.isDue(
                    nowMs = System.currentTimeMillis(),
                    lastUpdatedMs = profile.typed.lastUpdated.time,
                    lastAttemptMs = SubscriptionUpdateHealth.lastAttempt(applicationContext, profile.id),
                    failureStreak = SubscriptionUpdateHealth.consecutiveFailures(applicationContext, profile.id),
                    intervalSeconds = profile.typed.autoUpdateInterval * 60L,
                )
                if (!due) {
                    continue
                }
                if (System.currentTimeMillis() > roundDeadlineMs) {
                    hasDeferredWork = true
                    break
                }
                SubscriptionUpdateHealth.recordAttempt(applicationContext, profile.id)
                try {
                    // E-2: the round deadline is also threaded into adapt,
                    // so provider fetches inside one profile are bounded by
                    // it too, not only the between-profile check above.
                    val fetched = io.nekohasekai.sfa.utils.SubscriptionInfoStore.fetchRemoteDeferred(profile.typed.remoteURL)
                    val detailed = ConfigCompat.sanitizeRemoteDetailed(fetched.content, deadlineMs = roundDeadlineMs)
                    val content = detailed.content
                    // Validate and commit all background updates under the same profile lock as edits/deletes.
                    // That closes the gap between reading the row and atomically writing its file.
                    when (val gate = ConfigGate.validate(content, ConfigGate::libboxCheck)) {
                        is ConfigGate.Result.Rejected -> throw IllegalStateException(gate.reason)
                        is ConfigGate.Result.Accepted -> Unit
                    }
                    var updateProfile = profile
                    val updateApplied = ProfileManager.commitPendingImport(
                        expected = profile,
                        isStopped = { isStopped },
                        requireAutoUpdate = !initialImport,
                    ) { current ->
                        updateProfile = current
                        val file = File(current.typed.path)
                        val oldContent = try {
                            if (file.isFile) ProfileBindingStorage.readText(file) else null
                        } catch (_: Exception) {
                            null
                        }
                        if (oldContent != content) {
                            ProfileBindingStorage.writeAtomically(file, content)
                        }
                        current.typed.lastUpdated = Date()
                        val updatedRows = ProfileManager.updateWhileLocked(current)
                        if (updatedRows == 0) {
                            false
                        } else {
                            fetched.userInfo?.let {
                                io.nekohasekai.sfa.utils.SubscriptionInfoStore.put(applicationContext, current.id, it)
                            }
                            if (current.id == selectedProfile && oldContent != content) {
                                selectedProfileUpdated = true
                            }
                            // Only initial imports can select an otherwise unselected profile.
                            if (initialImport && Settings.selectedProfile < 0L) {
                                Settings.selectedProfile = current.id
                                selectedProfileUpdated = true
                            }
                            true
                        }
                    }
                    if (!updateApplied) continue
                    SubscriptionUpdateHealth.recordSuccess(applicationContext, updateProfile.id)
                    // 规范化替代的弹药：把这份订阅的远程规则集顺手下载进
                    // 本地缓存，启动时远程下不下来能用缓存顶上，不再只能
                    // 跳过。尽力而为，失败不影响更新结果，受整轮预算约束。
                    warmRuleSetCache(content, roundDeadlineMs)
                    // 更新成功但有"跳过/修正"提示：记下来，仪表盘横幅展示，不藏报错。
                    SubscriptionUpdateHealth.recordImportWarnings(
                        applicationContext,
                        updateProfile.id,
                        detailed.notes,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "update profile ${profile.name}", e)
                    SubscriptionUpdateHealth.recordFailure(applicationContext, profile.id, e)
                    // 审计 B1：只有网络抖动类失败才退避重试；404/401/403、订阅被删等
                    // 永久失败不再无意义重试，失败计数由 SubscriptionUpdateHealth 累积，
                    // 达阈值后仪表盘横幅提醒（下个周期仍会再试一次）
                    if (HTTPClient.isTransientUpdateFailure(e)) {
                        hasTransientFailure = true
                    }
                }
            }
            if (selectedProfileUpdated) {
                // X-5 (worker side): exactly one reload, after all writes of
                // this round are done — never one per profile. Serializing a
                // reload against a foreground start is BoxService's job; the
                // worker's part is to not multiply reloads and to reload only
                // when the selected profile's content actually changed.
                runCatching {
                    Libbox.newStandaloneCommandClient().serviceReload()
                }
            }

            // E-2: profiles left over by the round budget are deferred, not
            // dropped — retry re-enters doWork and the due check (their
            // lastUpdated never moved) picks them up.
            return if (hasTransientFailure || hasDeferredWork) {
                Result.retry()
            } else {
                Result.success()
            }
        }

        /**
         * Best-effort download of a freshly updated profile's remote rule
         * sets into the shared rule-set cache (the same files the startup
         * normalization substitute points local rule sets at). The
         * decision logic is RuleSetCacheWarmer's, shared with the
         * import-time warm-up (AB-019) so both paths fill exactly the
         * files the substitute looks for; bounded by the round deadline
         * — warming must not starve real updates.
         */
        private fun warmRuleSetCache(content: String, deadlineMs: Long) {
            runCatching { RuleSetWarmup.warmNow(content, deadlineMs) }
        }
    }
}
