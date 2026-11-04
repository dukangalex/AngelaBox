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
            // UI-only resolver rejection fixture. Validate a fixed loopback answer;
            // no connection can be opened and no provider data can be sent.
            InspectionAi.withPreservedDnsFailure(resolve = {
                InspectionAi.checkedSystemAnswers(listOf(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))
            }) { resolve ->
                io.nekohasekai.sfa.utils.RemoteUrlGuard.validate(config.endpoint, io.nekohasekai.sfa.utils.RemoteUrlGuard.Kind.SCRIPT, resolve)
                error("Synthetic test must stop before connecting")
            }
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
            // Synthetic hostname only. Log counts and fixed failure labels, never DNS addresses or exception text.
            val resolved = runCatching { java.net.InetAddress.getAllByName("example.com").toList() }
            resolved.onSuccess { addresses ->
                val fake = addresses.count { io.nekohasekai.sfa.utils.RemoteUrlGuard.isFakeIp(it.address) }
                val allowed = addresses.count { io.nekohasekai.sfa.utils.RemoteUrlGuard.isAddressAllowed(it, io.nekohasekai.sfa.utils.RemoteUrlGuard.Kind.SCRIPT) }
                android.util.Log.i("WABI_ROUTE", "system_dns_count=${addresses.size} fake_count=$fake allowed_count=$allowed")
            }.onFailure { android.util.Log.i("WABI_ROUTE", "system_dns_error=${it.javaClass.simpleName}") }
            fun errorKind(e: Throwable): String = when {
                e is InspectionAi.Failure -> e.reason.name.lowercase(java.util.Locale.ROOT)
                e.message == "无法解析主机，已拒绝" -> "dns_empty"
                e.message == "主机解析到禁止地址，已拒绝" -> "address_denied"
                e is java.net.SocketTimeoutException -> "timeout"
                e is java.io.IOException -> "io"
                e is IllegalArgumentException -> "other_argument"
                else -> "other"
            }
            // Production AI resolver (bounded system DNS, no public-DNS fallback), exercised by synthetic GET only.
            runCatching {
                val c = InspectionAi.withPreservedDnsFailure { resolve ->
                    io.nekohasekai.sfa.utils.HTTPClient.openPinned("https://example.com/", io.nekohasekai.sfa.utils.RemoteUrlGuard.Kind.SCRIPT, resolve = resolve)
                }
                try { android.util.Log.i("WABI_ROUTE", "production_resolver_https_status=${c.responseCode}") } finally { c.disconnect() }
            }.onFailure { android.util.Log.i("WABI_ROUTE", "production_resolver_error=${errorKind(it)} type=${it.javaClass.simpleName}") }
            runCatching {
                val c = io.nekohasekai.sfa.utils.HTTPClient.openPinned(
                    "https://example.com/", io.nekohasekai.sfa.utils.RemoteUrlGuard.Kind.SCRIPT
                )
                try { android.util.Log.i("WABI_ROUTE", "https_status=${c.responseCode}") } finally { c.disconnect() }
            }.onFailure { android.util.Log.i("WABI_ROUTE", "https_error=${it.javaClass.simpleName} kind=${errorKind(it)}") }
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

# Diagnostic-only freshness trace: no IDs, addresses, profile contents or data.
# Keep every production guard and tracker transition unchanged.
p=Path('app/src/main/java/io/nekohasekai/sfa/inspection/InspectionRunner.kt')
s=p.read_text()
a='val tracker = NetworkEvidenceTracker<Network, Pair<Long, Long>, LinkProperties>(singleNetwork) { epoch.incrementAndGet() }'
b='''var cause = "initial"
            val tracker = NetworkEvidenceTracker<Network, Pair<Long, Long>, LinkProperties>(singleNetwork) {
                val next = epoch.incrementAndGet()
                android.util.Log.i("WABI_FRESH", "invalidate callback=${if (singleNetwork) "default" else "underlying"} cause=$cause epoch=$next")
            }'''
assert s.count(a)==1;s=s.replace(a,b)
s=s.replace('override fun onAvailable(network: Network) = tracker.available(network)', 'override fun onAvailable(network: Network) { cause = "available"; tracker.available(network) }')
s=s.replace('override fun onLost(network: Network) = tracker.lost(network)', 'override fun onLost(network: Network) { cause = "lost"; tracker.lost(network) }')
s=s.replace('tracker.capabilities(network, evidenceCapabilityBits(capabilityBits) to transportBits)', 'cause = "capabilities_${capabilityBits.toString(16)}_${transportBits.toString(16)}"\n                    tracker.capabilities(network, evidenceCapabilityBits(capabilityBits) to transportBits)')
s=s.replace('tracker.link(network, LinkProperties.CREATOR.createFromParcel(parcel))', 'cause = "link"\n                        tracker.link(network, LinkProperties.CREATOR.createFromParcel(parcel))')
s=s.replace('tracker.blocked(network, blocked)', 'cause = "blocked_$blocked"\n                    tracker.blocked(network, blocked)')
a='        require(before == stamp(context)) { "stale" }'
b='''        val afterDiagnostic = stamp(context)
        android.util.Log.i("WABI_FRESH", "collection_finish equal=${before == afterDiagnostic} token_equal=${before.token == afterDiagnostic.token} network_equal=${before.network == afterDiagnostic.network} service_equal=${before.tunnelUp == afterDiagnostic.tunnelUp}")
        require(before == stamp(context)) { "stale" }'''
assert s.count(a)==1;s=s.replace(a,b);p.write_text(s)
p=Path('app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/InspectionScreen.kt')
s=p.read_text()
a='            now = System.currentTimeMillis()'
b='''            now = System.currentTimeMillis()
            snapshot?.let { saved ->
                android.util.Log.i("WABI_FRESH", "ui age_ms=${now - saved.collectedAt} token_equal=${saved.contextToken == stamp.token} network_equal=${saved.networkId == stamp.network} service_equal=${saved.serviceUp == stamp.tunnelUp} profile_equal=${saved.profileId == stamp.profile} running=$running config=${config != null}")
            }'''
assert s.count(a)==1;s=s.replace(a,b);p.write_text(s)
print('Added anonymous freshness causes only; all production freshness and networking decisions retained')
