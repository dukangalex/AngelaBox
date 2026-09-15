package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.chain.ChainBindings
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
        if (ChainBindings.get(profileId) != null) return
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
                val wrapped = """
                    $code
                    (function () {
                      if (typeof main !== "function") {
                        throw new Error("script must define function main(config)");
                      }
                      if (typeof Packages !== "undefined" || typeof Java !== "undefined") {
                        throw new Error("java bridge is disabled");
                      }
                      var cfg = JSON.parse($quoted);
                      var out = main(cfg);
                      if (out == null) out = cfg;
                      return JSON.stringify(out);
                    })();
                """.trimIndent()
                val result = cx.evaluateString(scope, wrapped, name.ifBlank { "overlay" }, 1, null)
                return Context.toString(result)
            } catch (e: Throwable) {
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
