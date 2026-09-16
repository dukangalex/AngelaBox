package io.github.libxposed.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class XposedProvider extends ContentProvider {

  private static final String TAG = "XposedProvider";
  private static final String EXPECTED_DESCRIPTOR = "io.github.libxposed.service.IXposedService";

  /** Exact names only. Package prefixes are not a security boundary. */
  private static final Set<String> TRUSTED_PACKAGES =
      new HashSet<>(Arrays.asList("org.lsposed.manager", "org.lsposed.daemon"));

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
      if (!TRUSTED_PACKAGES.contains(pkg)) continue;
      if (packageUidMatches(pm, pkg, uid) && hasReleaseSigningCert(pm, pkg)) {
        return true;
      }
    }
    return false;
  }

  private static boolean packageUidMatches(PackageManager pm, String pkg, int uid) {
    try {
      return pm.getPackageUid(pkg, 0) == uid;
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean hasReleaseSigningCert(PackageManager pm, String pkg) {
    Signature[] signatures = signingCerts(pm, pkg);
    if (signatures == null || signatures.length == 0) {
      return false;
    }
    boolean anyValid = false;
    for (Signature sig : signatures) {
      X509Certificate cert = parseX509(sig.toByteArray());
      if (cert == null) continue;
      String subject = cert.getSubjectX500Principal().getName().toLowerCase(Locale.US);
      if (subject.contains("android debug") || subject.contains("cn=android debug")) {
        return false;
      }
      anyValid = true;
    }
    return anyValid;
  }

  @Nullable
  private static Signature[] signingCerts(PackageManager pm, String pkg) {
    try {
      if (Build.VERSION.SDK_INT >= 28) {
        PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
        SigningInfo signing = info.signingInfo;
        if (signing == null) return null;
        return signing.getApkContentsSigners();
      }
      @SuppressWarnings("deprecation")
      PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
      @SuppressWarnings("deprecation")
      Signature[] signatures = info.signatures;
      return signatures;
    } catch (Exception e) {
      return null;
    }
  }

  @Nullable
  private static X509Certificate parseX509(byte[] der) {
    try {
      return (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(der));
    } catch (Exception e) {
      return null;
    }
  }
}
