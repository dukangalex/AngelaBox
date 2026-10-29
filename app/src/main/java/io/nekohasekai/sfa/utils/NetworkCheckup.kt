package io.nekohasekai.sfa.utils

import android.content.Context
import io.nekohasekai.sfa.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

/**
 * 一键网络体检：TUN 连通性、DNS、节点延迟、订阅状态四项检查，
 * 最后给一句人话结论（审计"我的用户视角"想法 1）。
 *
 * 设计原则（DESIGN.md"失败要看得见"）：
 * - 每一项独立 try/catch + 超时，单项失败不拖垮整轮体检；
 * - 结论只说测到的事：DNS 项只报告"是否观测到劫持信号"，不宣称"绝对干净"；
 * - 隧道 up 时绝不直连公共 DNS（RemoteUrlGuard 的注释是红线），DNS 对比只用系统解析。
 */
object NetworkCheckup {

    enum class ItemId { TUNNEL, DNS, LATENCY, SUBSCRIPTION }

    enum class ItemStatus { OK, WARN, FAIL, SKIP }

    /**
     * [code] 是给 [decideVerdict] 用的机器可读码；[detail] 是已本地化的展示文案。
     */
    data class Item(
        val id: ItemId,
        val status: ItemStatus,
        val code: String,
        val detail: String,
    )

    enum class VerdictKind {
        ALL_OK,
        NO_NETWORK,
        SERVICE_STOPPED,
        SUBSCRIPTION_BAD,
        TUNNEL_BROKEN,
        TUNNEL_UNCERTAIN,
        DNS_HIJACKED,
        NODE_SLOW,
        SUBSCRIPTION_UNHEALTHY,
    }

    data class Result(
        val items: List<Item>,
        val verdict: VerdictKind,
        /** 结论里的参数：订阅问题带 issue 文案，节点慢带毫秒数，其余为空。 */
        val verdictDetail: String = "",
    )

    // 双目标轮流探测：单目标在部分出口/网络下本身就慢或不可达，
    // 只测一个会把目标的问题算到节点头上（审计 A-1/A-2）。
    private val TUNNEL_PROBE_URLS = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
    )
    private const val PROBE_ROUNDS = 2
    private const val PROBE_CONNECT_TIMEOUT_MS = 5000
    private const val PROBE_READ_TIMEOUT_MS = 5000
    private const val TUNNEL_PROBE_BUDGET_MS = 24_000L
    private const val CHECK_TIMEOUT_MS = 12_000L
    private const val LATENCY_WAIT_MS = 12_000L
    private const val LATENCY_ACCEPT_STALE_MS = 4_000L
    private const val SLOW_LATENCY_MS = 1500

    /**
     * 纯函数：按优先级从四项结果推导一句话结论的种类。
     * 纯 JVM，可单测。
     */
    fun decideVerdict(
        hasNetwork: Boolean,
        tunnelUp: Boolean,
        items: List<Item>,
    ): VerdictKind {
        if (!hasNetwork) return VerdictKind.NO_NETWORK
        if (!tunnelUp) return VerdictKind.SERVICE_STOPPED
        val byId = items.associateBy { it.id }
        val sub = byId[ItemId.SUBSCRIPTION]
        if (sub != null && (sub.code == "expired" || sub.code == "quota_exhausted")) {
            return VerdictKind.SUBSCRIPTION_BAD
        }
        val tun = byId[ItemId.TUNNEL]
        val lat = byId[ItemId.LATENCY]
        // 旁证校准（审计 A-1）：探测超时是"这次没测通"，不等于"节点坏了"。
        // 若同轮延迟是本轮新鲜测得的（code ok/slow，而非接受的旧值
        // ok_stale/slow_stale），说明节点路径确实在回包，单凭探测超时
        // 不能判 TUNNEL_BROKEN，先降级为"不确定"。
        val latencyFresh = lat != null && (lat.code == "ok" || lat.code == "slow")
        val tunnelTimeoutExcused = tun != null && tun.status == ItemStatus.FAIL &&
            tun.code == "timeout" && latencyFresh
        if (tun != null && tun.status == ItemStatus.FAIL && !tunnelTimeoutExcused) {
            return VerdictKind.TUNNEL_BROKEN
        }
        val dns = byId[ItemId.DNS]
        if (dns != null && dns.status == ItemStatus.FAIL) return VerdictKind.DNS_HIJACKED
        if (lat != null && lat.code == "slow") return VerdictKind.NODE_SLOW
        // run() 已降级的探测项（WARN + timeout_downgraded）或上面刚被旁证
        // 豁免的原始超时项：结论是"不确定"——不是全好，也不是节点坏。
        if (tunnelTimeoutExcused || (tun != null && tun.code == "timeout_downgraded")) {
            return VerdictKind.TUNNEL_UNCERTAIN
        }
        if (sub != null && sub.status == ItemStatus.WARN) return VerdictKind.SUBSCRIPTION_UNHEALTHY
        return VerdictKind.ALL_OK
    }

    /**
     * 跑完四项检查。必须在后台线程调用（内部切 Dispatchers.IO）。
     *
     * @param hasNetwork 手机是否有活动网络（ConnectivityManager.activeNetwork != null）
     * @param tunnelUp 隧道是否 up（TunnelGate.up）
     * @param outboundTag 当前出口 tag（取法与 DashboardViewModel.testSelectedDelay 一致），拿不到传 ""
     * @param currentDelayMs 返回该 tag 最新已知延迟（ms），无数据返回 null
     * @param triggerUrlTest 触发一次 urlTest；实现里吞掉异常
     * @param onStep 每开始一项时回调（调用方自己切回主线程更新 UI）
     */
    suspend fun run(
        context: Context,
        hasNetwork: Boolean,
        tunnelUp: Boolean,
        outboundTag: String,
        currentDelayMs: () -> Int?,
        triggerUrlTest: (String) -> Unit,
        onStep: (ItemId) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val items = mutableListOf<Item>()
        onStep(ItemId.TUNNEL)
        items += checkTunnel(context, tunnelUp)
        onStep(ItemId.DNS)
        items += checkDns(context, tunnelUp)
        onStep(ItemId.LATENCY)
        items += checkLatency(context, tunnelUp, outboundTag, currentDelayMs, triggerUrlTest)
        onStep(ItemId.SUBSCRIPTION)
        items += checkSubscription(context)
        downgradeTunnelTimeout(items, context)
        val verdict = decideVerdict(hasNetwork, tunnelUp, items)
        val detail = when (verdict) {
            VerdictKind.SUBSCRIPTION_BAD, VerdictKind.SUBSCRIPTION_UNHEALTHY ->
                items.firstOrNull { it.id == ItemId.SUBSCRIPTION }?.detail.orEmpty()
            VerdictKind.NODE_SLOW, VerdictKind.TUNNEL_UNCERTAIN -> msOf(items)
            else -> ""
        }
        Result(items, verdict, detail)
    }

    /**
     * 与 [decideVerdict] 的旁证校准配套（审计 A-1）：探测最终超时、但同轮
     * 延迟是新鲜测得的（节点路径确实在回包）时，把 TUN 项从 FAIL 降为
     * WARN 并换上文案，避免红行和结论横幅一起误报"节点连不上"。
     * 延迟只是接受的旧值（ok_stale/slow_stale）时不降级：旧值证明不了
     * 节点现在可用（审计 A-4/簇 D 联动 3）。
     */
    private fun downgradeTunnelTimeout(items: MutableList<Item>, context: Context) {
        val lat = items.firstOrNull { it.id == ItemId.LATENCY } ?: return
        if (lat.code != "ok" && lat.code != "slow") return
        val index = items.indexOfFirst { it.id == ItemId.TUNNEL }
        if (index < 0) return
        val tun = items[index]
        if (tun.status != ItemStatus.FAIL || tun.code != "timeout") return
        items[index] = tun.copy(
            status = ItemStatus.WARN,
            code = "timeout_downgraded",
            detail = context.getString(R.string.checkup_tunnel_timeout_but_node_ok),
        )
    }

    private fun msOf(items: List<Item>): String {
        val detail = items.firstOrNull { it.id == ItemId.LATENCY }?.detail.orEmpty()
        return Regex("\\d+").find(detail)?.value.orEmpty()
    }

    // ---- TUN 连通性 ----

    private suspend fun checkTunnel(context: Context, tunnelUp: Boolean): Item {
        if (!tunnelUp) {
            return Item(
                ItemId.TUNNEL,
                ItemStatus.SKIP,
                "not_started",
                context.getString(R.string.checkup_tunnel_not_started),
            )
        }
        // 外层预算耗尽（withTimeoutOrNull 返回 null）本身就是"没测通"，
        // 归类为超时而不是网络错误（审计 A-3）。
        val probed = withTimeoutOrNull(TUNNEL_PROBE_BUDGET_MS) { probeTunnel() }
            ?: TunnelProbe.Timeout
        return when (probed) {
            is TunnelProbe.Ok -> Item(
                ItemId.TUNNEL,
                ItemStatus.OK,
                "ok",
                context.getString(R.string.checkup_tunnel_ok),
            )
            is TunnelProbe.HttpError -> Item(
                ItemId.TUNNEL,
                ItemStatus.FAIL,
                "http_error",
                context.getString(R.string.checkup_tunnel_http_error, probed.code),
            )
            TunnelProbe.Timeout -> Item(
                ItemId.TUNNEL,
                ItemStatus.FAIL,
                "timeout",
                context.getString(R.string.checkup_tunnel_timeout),
            )
            TunnelProbe.DnsFailure -> Item(
                ItemId.TUNNEL,
                ItemStatus.FAIL,
                "dns_failure",
                context.getString(R.string.checkup_tunnel_dns_failure),
            )
            TunnelProbe.IoError -> Item(
                ItemId.TUNNEL,
                ItemStatus.FAIL,
                "io_error",
                context.getString(R.string.checkup_tunnel_io_error),
            )
        }
    }

    internal sealed interface TunnelProbe {
        data object Ok : TunnelProbe
        data object Timeout : TunnelProbe
        data object DnsFailure : TunnelProbe
        data object IoError : TunnelProbe
        data class HttpError(val code: Int) : TunnelProbe
    }

    /**
     * 多次尝试的合并规则（纯函数，供单测）：任一次通了就是通；
     * 有 HTTP 回应说明路径能到服务器，优先报它；全军覆没于 DNS
     * 才算 DNS 失败；只要有一次是超时，结果至少算超时而不是 IO 错误。
     */
    internal fun reduceProbeAttempts(attempts: List<TunnelProbe>): TunnelProbe {
        attempts.firstOrNull { it is TunnelProbe.Ok }?.let { return it }
        attempts.firstOrNull { it is TunnelProbe.HttpError }?.let { return it }
        if (attempts.isNotEmpty() && attempts.all { it is TunnelProbe.DnsFailure }) {
            return TunnelProbe.DnsFailure
        }
        if (attempts.any { it is TunnelProbe.Timeout }) return TunnelProbe.Timeout
        if (attempts.any { it is TunnelProbe.DnsFailure }) return TunnelProbe.DnsFailure
        return TunnelProbe.IoError
    }

    /**
     * 隧道 up 时 App 自身流量走 TUN（默认不排除自身，见 HTTPClient.dialByName 注释），
     * 所以普通 HTTPS 请求就能验证隧道连通性。双目标轮流试两轮（审计 A-1/A-2）：
     * 第一轮顺带把 DNS 与链路预热，单次瞬时抖动/单目标不可达不再定格结论；
     * 总时长由 checkTunnel 的外层预算封顶。
     */
    private fun probeTunnel(): TunnelProbe {
        val attempts = mutableListOf<TunnelProbe>()
        repeat(PROBE_ROUNDS) {
            for (url in TUNNEL_PROBE_URLS) {
                val result = probeTunnelOnce(url)
                if (result is TunnelProbe.Ok) return result
                attempts += result
            }
        }
        return reduceProbeAttempts(attempts)
    }

    private fun probeTunnelOnce(url: String): TunnelProbe {
        var conn: HttpURLConnection? = null
        return try {
            conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = PROBE_CONNECT_TIMEOUT_MS
            conn.readTimeout = PROBE_READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.connect()
            val code = conn.responseCode
            if (code in 200..299) TunnelProbe.Ok else TunnelProbe.HttpError(code)
        } catch (_: SocketTimeoutException) {
            TunnelProbe.Timeout
        } catch (_: UnknownHostException) {
            TunnelProbe.DnsFailure
        } catch (e: CancellationException) {
            // 协程取消不是网络错误：必须上抛，否则外层超时会被吞成
            // IoError、结论归类错误（审计 A-3）。
            throw e
        } catch (_: Exception) {
            TunnelProbe.IoError
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ---- DNS ----

    private suspend fun checkDns(context: Context, tunnelUp: Boolean): Item = withTimeoutOrNull(CHECK_TIMEOUT_MS) { probeDns(context, tunnelUp) }
        ?: Item(
            ItemId.DNS,
            ItemStatus.WARN,
            "error",
            context.getString(R.string.checkup_dns_error),
        )

    /**
     * 劫持探测：查一个保证 NXDOMAIN 的随机 canary（RFC 2606 .invalid 永不解析）。
     * 任何返回 A 记录的解析器都在劫持——FakeIP 除外（见 [isFakeIp]）。
     *
     * 注意：隧道 up 时只用系统解析（此时走隧道 DNS）；直连公共 DNS 的原始查询
     * 只在隧道 down 时做，且只用于区分"系统 DNS 被劫持"还是"本地网络整体劫持"。
     */
    private fun probeDns(context: Context, tunnelUp: Boolean): Item {
        val canary = "nq${System.currentTimeMillis().toString(36)}${(0..9999).random().toString(36)}.dnscheck.invalid"
        val addresses = try {
            InetAddress.getAllByName(canary)
        } catch (_: UnknownHostException) {
            emptyArray()
        } catch (_: Exception) {
            return Item(
                ItemId.DNS,
                ItemStatus.WARN,
                "error",
                context.getString(R.string.checkup_dns_error),
            )
        }
        if (addresses.isEmpty()) {
            return Item(
                ItemId.DNS,
                ItemStatus.OK,
                "ok",
                context.getString(R.string.checkup_dns_ok),
            )
        }
        if (addresses.all(::isFakeIp)) {
            // FakeIP 会给所有域名返回假地址（含 .invalid 金丝雀），这是预期行为，不是劫持。
            return Item(
                ItemId.DNS,
                ItemStatus.OK,
                "fakeip",
                context.getString(R.string.checkup_dns_fakeip),
            )
        }
        if (!tunnelUp) {
            // 隧道没开：用原始 DNS 直连公共 resolver 做交叉验证。
            val directHijacked = runCatching {
                RemoteUrlGuard.resolveOutsideTunnel(canary).isNotEmpty()
            }.getOrDefault(false)
            return if (directHijacked) {
                Item(
                    ItemId.DNS,
                    ItemStatus.FAIL,
                    "hijacked_local",
                    context.getString(R.string.checkup_dns_hijacked_local),
                )
            } else {
                Item(
                    ItemId.DNS,
                    ItemStatus.FAIL,
                    "hijacked_system",
                    context.getString(R.string.checkup_dns_hijacked_system),
                )
            }
        }
        return Item(
            ItemId.DNS,
            ItemStatus.FAIL,
            "hijacked",
            context.getString(R.string.checkup_dns_hijacked_tunnel),
        )
    }

    /**
     * sing-box / Clash 系默认 FakeIP 段 198.18.0.0/15（RFC 2544 benchmarking）。
     * 供单测，做成 internal。
     */
    internal fun isFakeIp(address: InetAddress): Boolean {
        val b = address.address
        if (b.size != 4) return false
        return (b[0].toInt() and 0xFF) == 198 && ((b[1].toInt() and 0xFF) and 0xFE) == 18
    }

    // ---- 节点延迟 ----

    private suspend fun checkLatency(
        context: Context,
        tunnelUp: Boolean,
        outboundTag: String,
        currentDelayMs: () -> Int?,
        triggerUrlTest: (String) -> Unit,
    ): Item {
        if (!tunnelUp || outboundTag.isBlank()) {
            return Item(
                ItemId.LATENCY,
                ItemStatus.SKIP,
                "skipped",
                context.getString(R.string.checkup_latency_not_started),
            )
        }
        val before = runCatching { currentDelayMs() }.getOrNull()
        runCatching { triggerUrlTest(outboundTag) }
        // 等一次新鲜的 urlTest 回填；4 秒后仍是旧值也接受，但要分清
        // "本轮新鲜测得"（fresh）和"接受的旧值"（stale）：旧值证明不了
        // 节点现在的状态，结论层不许拿它当旁证、文案也要注明（审计 A-4）。
        val start = System.currentTimeMillis()
        var latest = before
        var fresh = false
        while (System.currentTimeMillis() - start < LATENCY_WAIT_MS) {
            delay(500)
            latest = runCatching { currentDelayMs() }.getOrNull()
            val elapsed = System.currentTimeMillis() - start
            if (latest != null && latest > 0) {
                if (latest != before) {
                    fresh = true
                    break
                }
                if (elapsed >= LATENCY_ACCEPT_STALE_MS) break
            }
        }
        val ms = latest
        return when {
            ms == null || ms <= 0 -> Item(
                ItemId.LATENCY,
                ItemStatus.WARN,
                "no_data",
                context.getString(R.string.checkup_latency_no_data),
            )
            ms > SLOW_LATENCY_MS && fresh -> Item(
                ItemId.LATENCY,
                ItemStatus.WARN,
                "slow",
                context.getString(R.string.checkup_latency_slow, ms),
            )
            ms > SLOW_LATENCY_MS -> Item(
                ItemId.LATENCY,
                ItemStatus.WARN,
                "slow_stale",
                context.getString(R.string.checkup_latency_slow_stale, ms),
            )
            fresh -> Item(
                ItemId.LATENCY,
                ItemStatus.OK,
                "ok",
                context.getString(R.string.checkup_latency_ok, ms),
            )
            else -> Item(
                ItemId.LATENCY,
                ItemStatus.OK,
                "ok_stale",
                context.getString(R.string.checkup_latency_ok_stale, ms),
            )
        }
    }

    // ---- 订阅状态 ----

    private suspend fun checkSubscription(context: Context): Item {
        val issues = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
            runCatching { SubscriptionUpdateHealth.evaluate(context) }.getOrDefault(emptyList())
        }.orEmpty()
        if (issues.isEmpty()) {
            return Item(
                ItemId.SUBSCRIPTION,
                ItemStatus.OK,
                "ok",
                context.getString(R.string.checkup_subscription_ok),
            )
        }
        val severity = mapOf(
            SubscriptionUpdateHealth.IssueKind.EXPIRED to 3,
            SubscriptionUpdateHealth.IssueKind.QUOTA_EXHAUSTED to 3,
            SubscriptionUpdateHealth.IssueKind.UPDATE_FAILED to 2,
            SubscriptionUpdateHealth.IssueKind.QUOTA_LOW to 1,
            SubscriptionUpdateHealth.IssueKind.IMPORT_WARNINGS to 1,
        )
        val worst = issues.maxByOrNull { severity[it.kind] ?: 0 } ?: issues.first()
        val extra = if (issues.size > 1) {
            context.getString(R.string.checkup_subscription_more, issues.size - 1)
        } else {
            ""
        }
        val (status, code) = when (worst.kind) {
            SubscriptionUpdateHealth.IssueKind.EXPIRED ->
                ItemStatus.FAIL to "expired"
            SubscriptionUpdateHealth.IssueKind.QUOTA_EXHAUSTED ->
                ItemStatus.FAIL to "quota_exhausted"
            SubscriptionUpdateHealth.IssueKind.UPDATE_FAILED ->
                ItemStatus.WARN to "update_failed"
            SubscriptionUpdateHealth.IssueKind.QUOTA_LOW ->
                ItemStatus.WARN to "quota_low"
            SubscriptionUpdateHealth.IssueKind.IMPORT_WARNINGS ->
                ItemStatus.WARN to "import_warnings"
        }
        return Item(ItemId.SUBSCRIPTION, status, code, worst.detail + extra)
    }
}
