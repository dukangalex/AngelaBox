package io.nekohasekai.sfa.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

/**
 * Local decompiler for sing-box binary rule-sets (`.srs`).
 *
 * Kernel has no DecompileRuleSet JNI. SagerNet geosite JSON siblings 404, so the
 * provider viewer reads the same `rule-sets/$tag.srs` the kernel already downloaded
 * and dumps domain / domain_suffix the way Clash Verge lists them (`+.suffix`).
 */
object SrsDecoder {
    private const val PREFIX = '\u000d'
    private const val ROOT = '\u000a'
    private const val MAX_ENTRIES = 20_000
    private const val MAX_INFLATED = 8_000_000
    private const val MAX_WALK = 5_000_000

    fun listEntries(file: File): List<String> {
        if (!file.isFile || file.length() < 5L) return emptyList()
        return try {
            decode(file.readBytes())
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun decode(bytes: ByteArray): List<String> {
        if (bytes.size < 5 ||
            bytes[0] != 0x53.toByte() ||
            bytes[1] != 0x52.toByte() ||
            bytes[2] != 0x53.toByte()
        ) {
            return emptyList()
        }
        val raw = inflate(bytes)
        val reader = Cursor(raw)
        val ruleCount = reader.uvarint()
        val out = ArrayList<String>(minOf(ruleCount.toInt().coerceAtLeast(0), 1024))
        var i = 0L
        while (i < ruleCount && out.size < MAX_ENTRIES) {
            readRule(reader, out, 0)
            i++
        }
        return if (out.size > MAX_ENTRIES) out.subList(0, MAX_ENTRIES).toList() else out
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, 4, bytes.size - 4)
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    if (inflater.finished()) break
                } else {
                    out.write(buf, 0, n)
                    if (out.size() > MAX_INFLATED) error("srs too large")
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun readRule(reader: Cursor, out: MutableList<String>, depth: Int) {
        if (depth > 100) error("logical rule nested too deep")
        when (reader.u8()) {
            0 -> readDefaultRule(reader, out)
            1 -> {
                reader.u8()
                val n = reader.uvarint()
                var i = 0L
                while (i < n && out.size < MAX_ENTRIES) {
                    readRule(reader, out, depth + 1)
                    i++
                }
                reader.u8()
            }
            else -> error("unknown rule type")
        }
    }

    private fun readDefaultRule(reader: Cursor, out: MutableList<String>) {
        while (true) {
            val type = reader.u8()
            when (type) {
                255 -> {
                    reader.u8()
                    return
                }
                2 -> dumpMatcher(readMatcher(reader), out)
                1, 3, 4, 8, 10, 11, 12, 13, 14, 15, 17, 23 -> {
                    val prefix = when (type) {
                        3 -> "*"
                        4 -> "regexp:"
                        11 -> "process:"
                        13 -> "package:"
                        else -> ""
                    }
                    for (s in reader.strings()) {
                        if (out.size >= MAX_ENTRIES) return
                        out += prefix + s
                    }
                }
                5, 6 -> skipIpSet(reader)
                0 -> {
                    val n = reader.uvarint()
                    reader.skip((n * 2L).toInt())
                }
                7, 9 -> {
                    val n = reader.uvarint()
                    reader.skip((n * 2L).toInt())
                }
                18 -> {
                    val n = reader.uvarint()
                    reader.skip(n.toInt())
                }
                19, 20 -> Unit
                else -> error("unhandled rule item $type")
            }
            if (out.size >= MAX_ENTRIES) return
        }
    }

    private fun readMatcher(reader: Cursor): Pair<List<String>, List<String>> {
        reader.u8()
        val leaves = reader.u64Slice()
        val labelBitmap = reader.u64Slice()
        val labels = reader.byteSlice()
        val keys = succinctKeys(leaves, labelBitmap, labels)
        return dumpMatcherKeys(keys)
    }

    private fun dumpMatcher(parts: Pair<List<String>, List<String>>, out: MutableList<String>) {
        for (d in parts.first) {
            if (out.size >= MAX_ENTRIES) return
            out += d
        }
        for (p in parts.second) {
            if (out.size >= MAX_ENTRIES) return
            out += "+." + p.trimStart('.')
        }
    }

    private fun dumpMatcherKeys(keys: List<String>): Pair<List<String>, List<String>> {
        val domainMap = LinkedHashSet<String>()
        val prefixMap = LinkedHashSet<String>()
        val prefixList = ArrayList<String>()
        for (rawKey in keys) {
            val key = rawKey.reversed()
            if (key.isEmpty()) continue
            when (key[0]) {
                PREFIX -> prefixMap += key.substring(1)
                ROOT -> prefixList += key.substring(1)
                else -> domainMap += key
            }
        }
        for (raw in prefixMap) {
            if (raw.startsWith(".") && domainMap.contains(raw.substring(1))) {
                domainMap.remove(raw.substring(1))
                prefixList += raw.substring(1)
            } else {
                prefixList += raw
            }
        }
        return domainMap.sorted() to prefixList.sorted()
    }

    private fun succinctKeys(leaves: LongArray, labelBitmap: LongArray, labels: ByteArray): List<String> {
        val result = ArrayList<String>()
        val current = ArrayList<Byte>(32)
        if (getBit(leaves, 0)) result += ""
        data class Frame(var nodeId: Int, var bmIdx: Int)
        val stack = ArrayList<Frame>()
        stack += Frame(0, 0)
        var guard = 0
        while (stack.isNotEmpty()) {
            guard++
            if (guard > MAX_WALK) error("walk overflow")
            val top = stack.last()
            if (getBit(labelBitmap, top.bmIdx)) {
                stack.removeAt(stack.lastIndex)
                if (stack.isNotEmpty()) {
                    if (current.isNotEmpty()) current.removeAt(current.lastIndex)
                    stack.last().bmIdx += 1
                }
                continue
            }
            val labelIndex = top.bmIdx - top.nodeId
            if (labelIndex < 0 || labelIndex >= labels.size) error("label oob")
            current += labels[labelIndex]
            val nextNode = countZeros(labelBitmap, top.bmIdx + 1)
            val nextBm = selectIthOne(labelBitmap, nextNode - 1) + 1
            if (getBit(leaves, nextNode)) {
                val bytes = ByteArray(current.size) { current[it] }
                result += String(bytes, StandardCharsets.UTF_8)
            }
            stack += Frame(nextNode, nextBm)
        }
        return result
    }

    private fun getBit(bm: LongArray, i: Int): Boolean {
        if (i < 0) return false
        val word = i ushr 6
        if (word >= bm.size) return true
        return ((bm[word] ushr (i and 63)) and 1L) != 0L
    }

    private fun popcountUpto(bm: LongArray, i: Int): Int {
        if (i <= 0) return 0
        val words = i ushr 6
        val rem = i and 63
        var n = 0
        val limit = minOf(words, bm.size)
        for (w in 0 until limit) n += java.lang.Long.bitCount(bm[w])
        if (rem > 0 && words < bm.size) {
            val mask = (1L shl rem) - 1L
            n += java.lang.Long.bitCount(bm[words] and mask)
        }
        return n
    }

    private fun countZeros(bm: LongArray, i: Int): Int = i - popcountUpto(bm, i)

    private fun selectIthOne(bm: LongArray, ith: Int): Int {
        var seen = 0
        for (wi in bm.indices) {
            val w = bm[wi]
            val c = java.lang.Long.bitCount(w)
            if (seen + c > ith) {
                var bit = 0
                var x = w
                while (bit < 64) {
                    if ((x and 1L) != 0L) {
                        if (seen == ith) return wi * 64 + bit
                        seen++
                    }
                    x = x ushr 1
                    bit++
                }
            }
            seen += c
        }
        return bm.size * 64
    }

    private fun skipIpSet(reader: Cursor) {
        reader.u8()
        val length = reader.u64()
        var i = 0L
        while (i < length) {
            repeat(2) {
                val addrLen = reader.uvarint()
                reader.skip(addrLen.toInt())
            }
            i++
        }
    }

    private class Cursor(private val buf: ByteArray) {
        private var i = 0

        fun u8(): Int {
            if (i >= buf.size) error("srs eof")
            return buf[i++].toInt() and 0xFF
        }

        fun skip(n: Int) {
            if (n < 0 || i + n > buf.size) error("srs eof")
            i += n
        }

        fun uvarint(): Long {
            var x = 0L
            var s = 0
            while (true) {
                val b = u8()
                if (b < 0x80) {
                    if (s > 63) error("uvarint overflow")
                    return x or (b.toLong() shl s)
                }
                x = x or ((b and 0x7F).toLong() shl s)
                s += 7
                if (s > 63) error("uvarint overflow")
            }
        }

        fun u64(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun u64Slice(): LongArray {
            val n = uvarint().toInt()
            if (n < 0 || n > buf.size) error("slice too large")
            val out = LongArray(n)
            for (idx in 0 until n) out[idx] = u64()
            return out
        }

        fun byteSlice(): ByteArray {
            val n = uvarint().toInt()
            if (n < 0 || i + n > buf.size) error("srs eof")
            val out = buf.copyOfRange(i, i + n)
            i += n
            return out
        }

        fun strings(): List<String> {
            val n = uvarint().toInt()
            if (n < 0) return emptyList()
            val out = ArrayList<String>(n)
            repeat(n) {
                out += String(byteSlice(), StandardCharsets.UTF_8)
            }
            return out
        }
    }
}
