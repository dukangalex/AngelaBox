package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs user `function main(config)` scripts against the live sing-box JSON.
 * Clash-only keys are not translated here; scripts must emit official types.
 *
 * Overlay switches are injected as a JS global `overlay` so the script can
 * emit one gated rule set. The App does not write a second copy of those
 * rules while a script is bound.
 */
object ConfigScriptOverride {
    private const val TIMEOUT_MS = 5_000L
    private const val MAX_JSON_CHARS = 1_500_000

    fun apply(root: JSONObject, profileId: Long) {
        OverlayScripts.refreshStaleSample()
        val scripts = OverlayScripts.enabledFor(profileId)
        if (scripts.isEmpty()) return
        applyAll(root, scripts, overlayFlags().toString())
    }

    /**
     * All-or-nothing: scripts run against a working copy and [root] is
     * only rewritten when every script succeeded. A failing script used
     * to leave the earlier scripts' output in place (already written
     * back before the failure was reported), so the kernel ran a config
     * nobody designed; now a failure leaves [root] untouched and the
     * caller decides the fallback (App overlay switches take over).
     */
    internal fun applyAll(root: JSONObject, scripts: List<OverlayScript>, overlayJson: String) {
        var current = root.toString()
        for (script in scripts) {
            val next = try {
                ScriptEngine.run(script.code, current, script.name, overlayJson)
            } catch (e: Exception) {
                throw IllegalStateException("${script.name}：${e.message ?: "执行失败"}")
            }
            JSONObject(next)
            current = next
        }
        val parsed = JSONObject(current)
        val names = root.names()
        if (names != null) {
            val keys = (0 until names.length()).map { names.getString(it) }
            keys.forEach { root.remove(it) }
        }
        val incoming = parsed.keys()
        while (incoming.hasNext()) {
            val key = incoming.next()
            root.put(key, parsed.get(key))
        }
    }

    /**
     * One switch in the script `overlay` contract: its JS key name, how
     * to read the live value, and the fallback [defaultOverlayFlags]
     * reports. This table is the single source for both flag builders
     * (design v2 §2.2: the mapping is shared with the 系统显式 page /
     * migration — same keys, same declared defaults). Existing key
     * names and semantics never change; new switches only append.
     */
    private data class OverlaySwitch(
        val name: String,
        val read: () -> Boolean,
        val defaultValue: Boolean,
    )

    private val overlaySwitches: List<OverlaySwitch> = listOf(
        // Legacy umbrella key, kept for scripts written against the old
        // contract. chinaDirect was split into three switches; the
        // umbrella now tracks the IP/domain one (design v2 §2.2).
        OverlaySwitch("chinaDirect", { Settings.chinaIpDomainDirect }, true),
        OverlaySwitch("chinaIpDomainDirect", { Settings.chinaIpDomainDirect }, true),
        OverlaySwitch("chinaPublicDnsDirect", { Settings.chinaPublicDnsDirect }, true),
        OverlaySwitch("lanDirect", { Settings.lanDirect }, true),
        OverlaySwitch("privacyProtect", { Settings.privacyProtect }, false),
        OverlaySwitch("vpnLeakProtect", { Settings.vpnLeakProtect }, true),
        OverlaySwitch("adsBlock", { Settings.adsBlock }, true),
        OverlaySwitch("webrtcProtect", { Settings.webrtcProtect }, true),
        OverlaySwitch("disableQuic", { Settings.disableQuic }, true),
        OverlaySwitch("excludeCnQuic", { Settings.excludeCnQuic }, true),
        OverlaySwitch("disableIpv6", { Settings.disableIpv6 }, true),
        OverlaySwitch("dnsProtect", { Settings.dnsProtect }, true),
        OverlaySwitch("strictRoute", { Settings.strictRoute }, true),
    )

    /** Live 系统显式 flags. Missing keys default ON so a script can stay switch-driven. */
    internal fun overlayFlags(): JSONObject {
        val flags = JSONObject()
        for (switch in overlaySwitches) flags.put(switch.name, switch.read())
        return flags
    }

    internal fun defaultOverlayFlags(): JSONObject {
        val flags = JSONObject()
        for (switch in overlaySwitches) flags.put(switch.name, switch.defaultValue)
        return flags
    }

    internal object ScriptEngine {
        private val deadline = ThreadLocal<Long>()

        /**
         * Watchdog pool for [run]. The instruction observer only fires
         * between interpreted instructions, so a single native builtin
         * call (a 100 MB JSON.parse, a giant stringify) sails straight
         * past the 5 s deadline while the caller — often the service
         * start path — stays blocked. Evaluation therefore runs on a
         * worker and the caller waits with a hard [TIMEOUT_MS] bound.
         * Threads are daemons and the pool is cached, so a script stuck
         * in an uninterruptible native call is abandoned, never waited
         * on, and does not wedge later runs behind it.
         */
        private val watchdogExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "overlay-script-watchdog").apply { isDaemon = true }
        }

        /**
         * Dedicated factory, never the process-global one: the 5 s
         * deadline lives in [factory]'s instruction observer, and the
         * old lazy global install silently never happened when another
         * component had already installed a global factory — scripts
         * could then run forever. A private factory's contexts always
         * carry the observer, whatever the global state is.
         */
        private val factory: ContextFactory = object : ContextFactory() {
            override fun makeContext(): Context {
                val cx = super.makeContext()
                cx.setInstructionObserverThreshold(20_000)
                cx.setOptimizationLevel(-1)
                return cx
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                val limit = deadline.get() ?: return
                if (System.currentTimeMillis() > limit) {
                    throw IllegalStateException("脚本执行超时")
                }
            }
        }

        fun run(
            code: String,
            configJson: String,
            name: String,
            overlayJson: String? = null,
        ): String {
            if (code.length > OverlayScripts.MAX_CODE_CHARS) {
                throw IllegalStateException("脚本过长")
            }
            if (configJson.length > MAX_JSON_CHARS) {
                throw IllegalStateException("配置过大，无法套用脚本")
            }
            if (!code.contains("function main")) {
                throw IllegalStateException("脚本需要 function main(config)")
            }
            // The deadline ThreadLocal must live on the executing thread:
            // the factory's observer reads it there.
            val future = watchdogExecutor.submit(
                Callable {
                    deadline.set(System.currentTimeMillis() + TIMEOUT_MS)
                    try {
                        factory.call { cx -> runWithContext(cx, code, configJson, name, overlayJson) }
                    } finally {
                        deadline.remove()
                    }
                },
            )
            return try {
                future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // One native call outran the observer. Abandon the worker
                // (cancel is best-effort; the daemon thread is never joined)
                // and report the same timeout the observer path produces.
                future.cancel(true)
                throw IllegalStateException("脚本执行超时")
            } catch (e: ExecutionException) {
                throw (e.cause as? Exception)
                    ?: IllegalStateException(e.cause?.message ?: "脚本执行失败", e.cause)
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw IllegalStateException("脚本执行被中断", e)
            }
        }

        private fun runWithContext(
            cx: Context,
            code: String,
            configJson: String,
            name: String,
            overlayJson: String?,
        ): String {
            try {
                cx.setOptimizationLevel(-1)
                cx.languageVersion = Context.VERSION_ES6
                cx.setInstructionObserverThreshold(20_000)
                val scope: Scriptable = cx.initSafeStandardObjects()
                arrayOf(
                    "Packages", "JavaAdapter", "JavaImporter", "getClass",
                    "Java", "java", "javax", "org", "com", "edu", "net", "android",
                ).forEach { name ->
                    if (scope.has(name, scope)) scope.delete(name)
                }
                cx.setClassShutter { className ->
                    val n = className ?: return@setClassShutter false
                    if (n.startsWith("org.mozilla.javascript.")) {
                        val leaf = n.substringAfterLast('.')
                        return@setClassShutter !leaf.contains("Java") && !leaf.contains("LiveConnect")
                    }
                    n == "java.lang.String" ||
                        n == "java.lang.Boolean" ||
                        n == "java.lang.Integer" ||
                        n == "java.lang.Long" ||
                        n == "java.lang.Double" ||
                        n == "java.lang.Float" ||
                        n == "java.lang.Number" ||
                        n == "java.lang.Object"
                }
                val quoted = JSONObject.quote(configJson)
                val overlayLiteral = overlayJson?.takeIf { it.isNotBlank() }
                    ?: defaultOverlayFlags().toString()
                val wrapped = """
                    var overlay = $overlayLiteral;
                    $code
                    (function () {
                      if (typeof main !== "function") {
                        throw new Error("script must define function main(config)");
                      }
                      if (typeof Packages !== "undefined" || typeof Java !== "undefined") {
                        throw new Error("java bridge is disabled");
                      }
                      if (typeof overlay !== "object" || overlay == null) {
                        overlay = {};
                      }
                      var cfg = JSON.parse($quoted);
                      var out = main(cfg);
                      if (out == null) out = cfg;
                      return JSON.stringify(out);
                    })();
                """.trimIndent()
                val result = cx.evaluateString(scope, wrapped, name.ifBlank { "overlay" }, 1, null)
                val out = Context.toString(result)
                // Rhino has no heap cap; the output size is the practical
                // bound on what a runaway script can allocate its way into
                // (a ballooning config is also useless to the kernel).
                if (out.length > MAX_JSON_CHARS) {
                    throw IllegalStateException("脚本产出的配置过大")
                }
                return out
            } catch (e: Throwable) {
                val message = e.message?.take(240) ?: "脚本执行失败"
                throw IllegalStateException(message, e)
            }
        }
    }
}
