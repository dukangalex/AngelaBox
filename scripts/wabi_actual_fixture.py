"""Review instrumentation only: preserve Android collection and prevent external send."""
from pathlib import Path
p=Path('app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/InspectionScreen.kt')
s=p.read_text();old='    LaunchedEffect(changes) {'
new='''    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            InspectionStore.saveConfig(context, InspectionStore.AiConfig("https://example.com/v1/chat/completions", "local-android-test", "synthetic-not-a-real-key"))
        }
    }
    LaunchedEffect(changes) {'''
assert s.count(old)==1;s=s.replace(old,new)
needle='            review = InspectionReview(result, ai, InspectionPolicy.payload(result, BuildConfig.VERSION_NAME, CoreIdentity.libboxOrPin()), InspectionRunner.stamp(context))'
replacement=needle+'\n            android.util.Log.i("WABI_REVIEW", android.util.Base64.encodeToString(review!!.payload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))'
assert s.count(needle)==1;s=s.replace(needle,replacement);p.write_text(s)
print('Instrumented synthetic config and pre-send review payload only; collection unchanged')

p=Path('app/src/main/java/io/nekohasekai/sfa/inspection/InspectionAi.kt')
s=p.read_text()
start=s.index('    suspend fun analyze(config: InspectionStore.AiConfig, payload: String, stillCurrent: () -> Boolean, language:')
end=s.index('    // RemoteUrlGuard intentionally folds',start)
replacement='''    suspend fun analyze(config: InspectionStore.AiConfig, payload: String, stillCurrent: () -> Boolean, language: InspectionAdvice.Language = InspectionAdvice.Language.SIMPLIFIED): String {
        android.util.Log.i("WABI_TEST", "AI_REQUEST_ATTEMPT")
        return analyzeUsing(config, payload, stillCurrent, language = language) { _, _ ->
            android.util.Log.i("WABI_TEST", "AI_TRANSPORT_OPEN")
            throw java.io.IOException("Synthetic acceptance blocks all external transport")
        }
    }

'''
s=s[:start]+replacement+s[end:];p.write_text(s)
print('Instrumented call counters; transport opener fails locally, never sends externally')

# Isolated synthetic GET, no model data or user config. Local source interface
# is an observation only, not proof of Android DNS or VPN underlying routing.
p=Path('app/src/main/java/io/nekohasekai/sfa/inspection/InspectionRunner.kt')
s=p.read_text()
a='        require(before == stamp(context)) { "stale" }'
b='''        if (before.network != null) {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            android.util.Log.i("WABI_ROUTE", "https_begin tunnel=${before.tunnelUp} active_vpn=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)}")
            runCatching { java.net.InetAddress.getAllByName("example.com").size }
                .onSuccess { android.util.Log.i("WABI_ROUTE", "system_dns_count=$it") }
                .onFailure { android.util.Log.i("WABI_ROUTE", "system_dns_error=${it.javaClass.simpleName}") }
            runCatching {
                val c = io.nekohasekai.sfa.utils.HTTPClient.openPinned(
                    "https://example.com/", io.nekohasekai.sfa.utils.RemoteUrlGuard.Kind.SCRIPT
                )
                try { android.util.Log.i("WABI_ROUTE", "https_status=${c.responseCode}") } finally { c.disconnect() }
            }.onFailure { android.util.Log.i("WABI_ROUTE", "https_error=${it.javaClass.simpleName}") }
        }
        require(before == stamp(context)) { "stale" }'''
assert s.count(a)==1;s=s.replace(a,b);p.write_text(s)
p=Path('app/src/main/java/io/nekohasekai/sfa/utils/HTTPClient.kt')
s=p.read_text()
a='''    private fun pin(socket: Socket): Socket {
        delegate.setHostname(socket, hostname)'''
b='''    private fun pin(socket: Socket): Socket {
        val iface = runCatching { java.net.NetworkInterface.getByInetAddress(socket.localAddress)?.name }.getOrNull()
        val routeClass = when {
            iface == null -> "unknown"
            iface.startsWith("tun") -> "tun"
            iface.startsWith("wlan") -> "wifi"
            iface.startsWith("rmnet") -> "cellular"
            iface.startsWith("eth") -> "ethernet"
            else -> "other"
        }
        android.util.Log.i("WABI_ROUTE", "socket_local_interface=$routeClass")
        delegate.setHostname(socket, hostname)'''
assert s.count(a)==1;s=s.replace(a,b);p.write_text(s)
