package io.nekohasekai.sfa.utils

import android.content.Context
import android.net.ConnectivityManager
import android.os.IBinder
import android.os.Parcel

object ConnectivityBinderUtils {
    @Volatile
    private var cachedBinder: IBinder? = null

    fun getBinder(context: Context): IBinder? {
        val cached = cachedBinder
        if (cached != null && cached.isBinderAlive) {
            return cached
        }

        // Use the public manager lookup to confirm the service is present before the
        // system-server binder lookup used by the Xposed transaction bridge.
        if (context.getSystemService(Context.CONNECTIVITY_SERVICE) !is ConnectivityManager) return null

        val binderFromServiceManager = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, Context.CONNECTIVITY_SERVICE) as? IBinder
        }.getOrNull()

        if (binderFromServiceManager != null) {
            cachedBinder = binderFromServiceManager
            return binderFromServiceManager
        }

        // ConnectivityManager exposes no public API for its system-service binder. Do not
        // reflect into its private mService field: Android blocks that access for this target.
        // If the system-service lookup is unavailable, fail closed and let callers report it.
        return null
    }

    inline fun <T> withParcel(block: (data: Parcel, reply: Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            block(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
