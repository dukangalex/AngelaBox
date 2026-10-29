package io.nekohasekai.sfa.utils

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper

/** Controls whether this app task is listed in Android's Recents screen. */
object RecentTaskVisibility {
    fun setExcluded(context: Context, excluded: Boolean) {
        val activity = context.findActivity() ?: return
        val appTask = activity.getSystemService(ActivityManager::class.java)
            ?.appTasks
            ?.firstOrNull { it.taskInfo?.id == activity.taskId }
            ?: return
        appTask.setExcludeFromRecents(excluded)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
