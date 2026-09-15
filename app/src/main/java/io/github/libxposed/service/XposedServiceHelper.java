package io.github.libxposed.service;

import android.os.IBinder;
import android.util.Log;
import androidx.annotation.NonNull;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

@SuppressWarnings("unused")
public final class XposedServiceHelper {

  private static final int MAX_CACHED_SERVICES = 4;

  /** Callback interface for Xposed service. */
  public interface OnServiceListener {
    /** Callback when the service is connected. */
    void onServiceBind(@NonNull XposedService service);

    /** Callback when the service is dead. */
    void onServiceDied(@NonNull XposedService service);
  }

  private static final String TAG = "XposedServiceHelper";
  private static final Set<XposedService> mCache = new HashSet<>();
  private static OnServiceListener mListener = null;

  static void onBinderReceived(IBinder binder) {
    if (binder == null) return;
    synchronized (mCache) {
      try {
        XposedService service = new XposedService(IXposedService.Stub.asInterface(binder));
        if (mListener == null) {
          if (mCache.size() >= MAX_CACHED_SERVICES) {
            Log.w(TAG, "Ignoring Xposed binder: service cache is full");
            return;
          }
          mCache.add(service);
        } else {
          binder.linkToDeath(() -> mListener.onServiceDied(service), 0);
          mListener.onServiceBind(service);
        }
      } catch (Throwable t) {
        Log.e(TAG, "onBinderReceived", t);
      }
    }
  }

  /** Register a ServiceListener to receive service binders from Xposed frameworks. */
  public static void registerListener(OnServiceListener listener) {
    synchronized (mCache) {
      mListener = listener;
      if (!mCache.isEmpty()) {
        for (Iterator<XposedService> it = mCache.iterator(); it.hasNext(); ) {
          try {
            XposedService service = it.next();
            service.getRaw().asBinder().linkToDeath(() -> mListener.onServiceDied(service), 0);
            mListener.onServiceBind(service);
          } catch (Throwable t) {
            Log.e(TAG, "registerListener", t);
            it.remove();
          }
        }
        mCache.clear();
      }
    }
  }
}
