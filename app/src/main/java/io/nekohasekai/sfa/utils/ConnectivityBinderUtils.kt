package io.nekohasekai.sfa.utils

import android.content.Context
import android.net.ConnectivityManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log

object ConnectivityBinderUtils {
    private const val TAG = "ConnectivityBinderUtils"

    @Volatile
    private var cachedBinder: IBinder? = null

    fun getBinder(context: Context): IBinder? {
        val cached = cachedBinder
        if (cached != null && cached.isBinderAlive) {
            return cached
        }

        // Try getting binder from ServiceManager first
        val binderFromServiceManager = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, Context.CONNECTIVITY_SERVICE) as? IBinder
        }.getOrNull()

        if (binderFromServiceManager != null) {
            cachedBinder = binderFromServiceManager
            return binderFromServiceManager
        }

        // Fallback to ConnectivityManager.mService if ServiceManager lookup did not return a binder
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val binderFromCm = runCatching {
                val field = cm.javaClass.getDeclaredField("mService")
                field.isAccessible = true
                val service = field.get(cm) as? android.os.IInterface
                service?.asBinder()
            }.getOrNull()

            if (binderFromCm != null) {
                cachedBinder = binderFromCm
                return binderFromCm
            }
        }

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
