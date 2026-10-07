package io.nekohasekai.sfa.vendor

/**
 * Production signing identity for AngelaBox 1.0.x.
 *
 * Certificate CN=ChainBox, created 2026-09-05. This key was never committed
 * to git. Do **not** rotate it: every 1.0.x APK is signed with it, and a
 * rotation would force users to uninstall.
 *
 * The historically leaked SagerNet JKS (CN 猫羽 世界, SHA-256 32250a4b…)
 * was never used on published 1.0.x builds.
 */
object ReleaseTrust {
    const val PACKAGE_NAME = "io.chainbox.app"
    const val CERT_SHA256 = "e7041217f276a7cd860b2e210f6f7d91590f263730e09fbf73bae929d6994151"
    const val LEAKED_SFA_CERT_SHA256 = "32250a4b5f3a6733df57a3b9ec16c38d2c7fc5f2f693a9636f8f7b3be3549641"
}
