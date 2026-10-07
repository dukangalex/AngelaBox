package io.nekohasekai.sfa.update

import android.content.Context

@Suppress("UNUSED_PARAMETER")
fun checkFDroidUpdate(context: Context): UpdateInfo? {
    // AngelaBox publishes only GitHub Releases. Never fetch F-Droid APKs.
    return null
}
