package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * sing-box accepts comments and trailing commas. org.json does not.
 * Strip only that subset, and only outside strings, then parse again.
 */
object JsonConfig {
    fun objectOrNull(raw: String): JSONObject? = try {
        JSONObject(raw)
    } catch (_: Exception) {
        try {
            JSONObject(standardize(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun arrayOrNull(raw: String): JSONArray? = try {
        JSONArray(raw)
    } catch (_: Exception) {
        try {
            JSONArray(standardize(raw))
        } catch (_: Exception) {
            null
        }
    }

    /** Comments and trailing commas removed. Already-strict JSON is unchanged in meaning. */
    fun standardize(text: String): String = stripTrailingCommas(stripComments(text))

    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        var escape = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                out.append(c)
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
                i++
                continue
            }
            if (c == '"') {
                inString = true
                out.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < text.length) {
                val next = text[i + 1]
                if (next == '/') {
                    i += 2
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                if (next == '*') {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(text.length)
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun stripTrailingCommas(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        var escape = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                out.append(c)
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
                i++
                continue
            }
            if (c == '"') {
                inString = true
                out.append(c)
                i++
                continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) {
                    i++
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
