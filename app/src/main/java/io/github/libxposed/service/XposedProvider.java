package io.github.libxposed.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Locale;

public final class XposedProvider extends ContentProvider {

  private static final String TAG = "XposedProvider";
  private static final String EXPECTED_DESCRIPTOR = "io.github.libxposed.service.IXposedService";

  @Override
  public boolean onCreate() {
    return false;
  }

  @Nullable
  @Override
  public Cursor query(
      @NonNull Uri uri,
      @Nullable String[] projection,
      @Nullable String selection,
      @Nullable String[] selectionArgs,
      @Nullable String sortOrder) {
    return null;
  }

  @Nullable
  @Override
  public String getType(@NonNull Uri uri) {
    return null;
  }

  @Nullable
  @Override
  public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
    return null;
  }

  @Override
  public int delete(
      @NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
    return 0;
  }

  @Override
  public int update(
      @NonNull Uri uri,
      @Nullable ContentValues values,
      @Nullable String selection,
      @Nullable String[] selectionArgs) {
    return 0;
  }

  @Nullable
  @Override
  public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
    if (!method.equals(IXposedService.SEND_BINDER) || extras == null) {
      return null;
    }
    if (!isTrustedCaller()) {
      Log.w(TAG, "rejected binder from uid=" + Binder.getCallingUid());
      return null;
    }
    IBinder binder = extras.getBinder("binder");
    if (binder == null) {
      return null;
    }
    try {
      String descriptor = binder.getInterfaceDescriptor();
      if (descriptor == null || !EXPECTED_DESCRIPTOR.equals(descriptor)) {
        Log.w(TAG, "rejected binder with unexpected descriptor");
        return null;
      }
    } catch (RemoteException e) {
      Log.w(TAG, "rejected binder: descriptor unreadable", e);
      return null;
    }
    XposedServiceHelper.onBinderReceived(binder);
    return new Bundle();
  }

  private boolean isTrustedCaller() {
    int uid = Binder.getCallingUid();
    if (uid == Process.SYSTEM_UID || uid == 0 || uid == Process.SHELL_UID) {
      return true;
    }
    if (uid == Process.myUid()) {
      return true;
    }
    if (getContext() == null) {
      return false;
    }
    PackageManager pm = getContext().getPackageManager();
    String[] pkgs;
    try {
      pkgs = pm.getPackagesForUid(uid);
    } catch (Exception e) {
      return false;
    }
    if (pkgs == null) {
      return false;
    }
    for (String pkg : pkgs) {
      if (pkg == null) continue;
      String p = pkg.toLowerCase(Locale.US);
      if (p.startsWith("org.lsposed.")
          || p.startsWith("io.github.libxposed.")
          || p.startsWith("org.meowcat.edxposed")
          || p.equals("de.robv.android.xposed.installer")) {
        return true;
      }
    }
    return false;
  }
}
