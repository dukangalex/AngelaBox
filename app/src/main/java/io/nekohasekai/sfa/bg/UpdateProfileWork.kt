package io.nekohasekai.sfa.bg

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.ConfigCompat
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.SubscriptionUpdateHealth
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

class UpdateProfileWork {
    companion object {
        private const val WORK_NAME = "UpdateProfile"
        private const val TAG = "UpdateProfileWork"

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
            val remoteProfiles =
                ProfileManager.list()
                    .filter { it.typed.type == TypedProfile.Type.Remote && it.typed.autoUpdate }
            if (remoteProfiles.isEmpty()) return Result.success()
            var hasTransientFailure = false
            val selectedProfile = Settings.selectedProfile
            for (profile in remoteProfiles) {
                val lastSeconds =
                    (System.currentTimeMillis() - profile.typed.lastUpdated.time) / 1000L
                if (lastSeconds < profile.typed.autoUpdateInterval * 60) {
                    continue
                }
                try {
                    val content = ConfigCompat.sanitizeRemote(
                        io.nekohasekai.sfa.utils.SubscriptionInfoStore.fetchRemote(
                            profile.typed.remoteURL,
                            profile.id,
                            applicationContext,
                        ),
                    )
                    Libbox.checkConfig(content)
                    val file = File(profile.typed.path)
                    if (file.readText() != content) {
                        File(profile.typed.path).writeText(content)
                        if (profile.id == selectedProfile) {
                            selectedProfileUpdated = true
                        }
                    }
                    profile.typed.lastUpdated = Date()
                    ProfileManager.update(profile)
                    SubscriptionUpdateHealth.recordSuccess(applicationContext, profile.id)
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
                runCatching {
                    Libbox.newStandaloneCommandClient().serviceReload()
                }
            }
            return if (hasTransientFailure) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }
}
