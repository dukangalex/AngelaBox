package io.nekohasekai.sfa.utils

import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs user `function main(config)` scripts against the live sing-box JSON.
 * Clash-only keys are not translated here; scripts must emit official types.
 */
object ConfigScriptOverride {
    private const val TIMEOUT_MS = 5_000L
    private const val MAX_JSON_CHARS = 1_500_000

    fun apply(root: JSONObject, profileId: Long) {
        OverlayScripts.refreshStaleSample()
        val scripts = OverlayScripts.enabledFor(profileId)
        if (scripts.isEmpty()) return
        var current = root.toString()
        val failures = mutableListOf<String>()
        scripts.forEach { script ->
            try {
                val next = ScriptEngine.run(script.code, current, script.name)
                JSONObject(next)
                current = next
            } catch (e: Exception) {
                failures += "${script.name}：${e.message ?: "执行失败"}"
            }
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
        if (failures.isNotEmpty()) {
            throw IllegalStateException(failures.joinToString("；"))
        }
    }

    internal object ScriptEngine {
        private val factoryReady = AtomicBoolean(false)
        private val deadline = ThreadLocal<Long>()

        fun run(code: String, configJson: String, name: String): String {
            if (code.length > OverlayScripts.MAX_CODE_CHARS) {
                throw IllegalStateException("脚本过长")
            }
            if (configJson.length > MAX_JSON_CHARS) {
                throw IllegalStateException("配置过大，无法套用脚本")
            }
            if (!code.contains("function main")) {
                throw IllegalStateException("脚本需要 function main(config)")
            }
            ensureFactory()
            val cx = Context.enter()
            deadline.set(System.currentTimeMillis() + TIMEOUT_MS)
            try {
                cx.setOptimizationLevel(-1)
                cx.languageVersion = Context.VERSION_ES6
                cx.setInstructionObserverThreshold(20_000)
                val scope: Scriptable = cx.initSafeStandardObjects()
                cx.setClassShutter { className ->
                    className.startsWith("org.mozilla.javascript.") ||
                        className == "java.lang.String" ||
                        className == "java.lang.Boolean" ||
                        className == "java.lang.Integer" ||
                        className == "java.lang.Long" ||
                        className == "java.lang.Double" ||
                        className == "java.lang.Float" ||
                        className == "java.lang.Number" ||
                        className == "java.lang.Object"
                }
                val quoted = JSONObject.quote(configJson)
                val wrapped = """
                    $code
                    (function () {
                      if (typeof main !== "function") {
                        throw new Error("script must define function main(config)");
                      }
                      var cfg = JSON.parse($quoted);
                      var out = main(cfg);
                      if (out == null) out = cfg;
                      return JSON.stringify(out);
                    })();
                """.trimIndent()
                val result = cx.evaluateString(scope, wrapped, name.ifBlank { "overlay" }, 1, null)
                return Context.toString(result)
            } catch (e: Exception) {
                val message = e.message?.take(240) ?: "脚本执行失败"
                throw IllegalStateException(message, e)
            } finally {
                deadline.remove()
                Context.exit()
            }
        }

        private fun ensureFactory() {
            if (!factoryReady.compareAndSet(false, true)) return
            if (ContextFactory.hasExplicitGlobal()) return
            ContextFactory.initGlobal(
                object : ContextFactory() {
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
                },
            )
        }
    }
}
