package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable

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

    /** Live 配置覆盖 flags. Missing keys default ON so a script can stay switch-driven. */
    internal fun overlayFlags(): JSONObject = JSONObject()
        .put("chinaDirect", Settings.chinaDirect)
        .put("adsBlock", Settings.adsBlock)
        .put("webrtcProtect", Settings.webrtcProtect)
        .put("disableQuic", Settings.disableQuic)
        .put("excludeCnQuic", Settings.excludeCnQuic)
        .put("disableIpv6", Settings.disableIpv6)
        .put("dnsProtect", Settings.dnsProtect)
        .put("strictRoute", Settings.strictRoute)

    internal fun defaultOverlayFlags(): JSONObject = JSONObject()
        .put("chinaDirect", true)
        .put("adsBlock", true)
        .put("webrtcProtect", true)
        .put("disableQuic", true)
        .put("excludeCnQuic", true)
        .put("disableIpv6", true)
        .put("dnsProtect", true)
        .put("strictRoute", true)

    internal object ScriptEngine {
        private val deadline = ThreadLocal<Long>()

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
            deadline.set(System.currentTimeMillis() + TIMEOUT_MS)
            try {
                return factory.call { cx -> runWithContext(cx, code, configJson, name, overlayJson) }
            } finally {
                deadline.remove()
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
