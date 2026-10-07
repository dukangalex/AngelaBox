package io.nekohasekai.sfa.chain

data class FlowSample(
    val source: String = "",
    val rule: String = "",
    val outbound: String = "",
    val chain: List<String> = emptyList(),
    val dest: String = "",
)

data class FlowNode(
    val id: String,
    val label: String,
    val column: Int,
    val weight: Int = 1,
    val direct: Boolean = false,
)

data class FlowLink(
    val fromId: String,
    val toId: String,
    val weight: Int = 1,
    val direct: Boolean = false,
)

data class PlacedNode(
    val node: FlowNode,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

data class PlacedRibbon(
    val columnFrom: Int,
    val columnTo: Int,
    val x0: Float,
    val x1: Float,
    val y0Top: Float,
    val y0Bottom: Float,
    val y1Top: Float,
    val y1Bottom: Float,
    val direct: Boolean = false,
)

/**
 * Left-to-right live path with at most four columns:
 * source → matching rule → hop(s) / exit.
 * Remote hosts are not a fifth column — they crowd the labels.
 */
object TrafficFlowBuilder {
    private const val MAX_PER_COLUMN = 8
    internal const val MAX_COLUMN = 3

    fun build(
        samples: List<FlowSample>,
        path: ChainPath,
        hops: List<LiveHop> = emptyList(),
        chained: Boolean = path.chained,
        destinations: List<String> = emptyList(),
        running: Boolean = false,
    ): Pair<List<FlowNode>, List<FlowLink>> {
        if (samples.isNotEmpty()) {
            return fromSamples(samples, chained, path, hops)
        }
        return fromHops(path, hops, chained)
    }

    private fun fromSamples(
        samples: List<FlowSample>,
        chained: Boolean,
        path: ChainPath,
        hops: List<LiveHop>,
    ): Pair<List<FlowNode>, List<FlowLink>> {
        val counts = LinkedHashMap<String, Int>()
        val links = LinkedHashMap<Pair<String, String>, Int>()
        val directIds = HashSet<String>()
        fun bump(id: String, n: Int = 1) {
            counts[id] = (counts[id] ?: 0) + n
        }
        fun link(a: String, b: String, n: Int = 1) {
            if (a == b) return
            val key = a to b
            links[key] = (links[key] ?: 0) + n
        }
        samples.forEach { sample ->
            val hopTags = hopLabelsFor(sample, chained, path, hops)
            if (hopTags.isEmpty()) return@forEach
            val src = idOf(0, prettySource(sample.source))
            val rule = idOf(1, prettyRule(sample.rule))
            val hopIds = hopTags.mapIndexed { index, tag ->
                val col = when {
                    hopTags.size == 1 && isDirectTag(tag) -> if (chained) MAX_COLUMN else 2
                    chained && hopTags.size >= 2 -> (2 + index).coerceAtMost(MAX_COLUMN)
                    else -> 2
                }
                val id = idOf(col, tag)
                if (isDirectTag(tag)) directIds.add(id)
                id
            }
            bump(src)
            bump(rule)
            hopIds.forEach { bump(it) }
            link(src, rule)
            var prev = rule
            hopIds.forEach { hop ->
                link(prev, hop)
                prev = hop
            }
        }
        val hasExit = counts.keys.any { columnOf(it) >= 2 }
        if (chained && !hasExit) {
            return fromHops(path, hops, chained = true)
        }
        return finish(counts, links, directIds)
    }

    private fun hopLabelsFor(
        sample: FlowSample,
        chained: Boolean,
        path: ChainPath,
        hops: List<LiveHop>,
    ): List<String> {
        val fromChain = sample.chain
            .map { prettyHop(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        val leaf = prettyHop(sample.outbound)
        val tags = when {
            fromChain.isNotEmpty() -> fromChain
            leaf.isNotEmpty() -> listOf(leaf)
            else -> emptyList()
        }
        if (tags.isNotEmpty() && tags.all { isDirectTag(it) }) {
            return listOf("DIRECT")
        }
        if (chained) {
            val real = tags.filter { it.isNotEmpty() && !isDirectTag(it) }
            if (real.size >= 2) {
                return listOf(
                    expandHopLabel(real.first(), hops, path, landing = false),
                    expandHopLabel(real.last(), hops, path, landing = true),
                )
            }
            return chainedHopPair(path, hops, sample)
        }
        return tags.ifEmpty { listOf("proxy") }
    }

    internal fun expandHopLabel(
        tag: String,
        hops: List<LiveHop>,
        path: ChainPath,
        landing: Boolean,
    ): String {
        val pretty = prettyHop(tag)
        val hop = if (landing) {
            hops.firstOrNull { it.role == ChainPathHop.Role.Landing }
                ?: hops.firstOrNull { it.role == ChainPathHop.Role.Exit }
        } else {
            hops.firstOrNull { it.role == ChainPathHop.Role.Entry }
        }
        val title = prettyHop(hop?.title.orEmpty())
        val sub = prettyHop(hop?.subtitle.orEmpty())
        val planned = prettyHop(if (landing) path.landingTag else path.entryTag)
        if (title.isNotBlank() && title != pretty) {
            val matchesGroup = groupKey(pretty) == groupKey(sub) ||
                pretty == sub ||
                groupKey(pretty) == groupKey(planned) ||
                looksLikeGroupTag(pretty)
            if (matchesGroup && (!looksLikeGroupTag(title) || groupKey(title) != groupKey(pretty))) {
                return title
            }
        }
        return pretty
    }

    internal fun groupKey(tag: String): String {
        val shown = ChainRuntimeCompiler.displayHopTag(tag).ifBlank { tag }.trim()
        return shown.replaceFirst(LEADING_DECOR, "").trim()
    }

    internal fun looksLikeGroupTag(tag: String): Boolean {
        val key = groupKey(tag)
        if (key.isEmpty()) return false
        return key.contains("自动选择") ||
            key.contains("節點選擇") ||
            key.contains("节点选择") ||
            key.contains("负载均衡") ||
            key.contains("負載均衡") ||
            key.contains("手动选择") ||
            key.contains("urltest", ignoreCase = true) ||
            key.contains("selector", ignoreCase = true) ||
            key.equals("proxy", ignoreCase = true)
    }

    /**
     * Chained path is always two hop columns: entry then landing.
     * Kernel samples often report a single group tag (urltest/selector);
     * live hops and the saved binding fill in the missing side so the
     * diagram does not collapse to 自动选择 / one leaf.
     */
    internal fun chainedHopPair(
        path: ChainPath,
        hops: List<LiveHop> = emptyList(),
        sample: FlowSample? = null,
    ): List<String> {
        val liveEntry = hops.firstOrNull { it.role == ChainPathHop.Role.Entry }
        val liveLanding = hops.firstOrNull { it.role == ChainPathHop.Role.Landing }
            ?: hops.lastOrNull { it.role == ChainPathHop.Role.Exit }
        val plannedEntry = prettyHop(path.entryTag)
        val plannedLanding = prettyHop(path.landingTag)
        val entryGroup = prettyHop(liveEntry?.subtitle.orEmpty()).ifBlank { plannedEntry }
        val landingGroup = prettyHop(liveLanding?.subtitle.orEmpty()).ifBlank { plannedLanding }
        val sampleTags = buildList {
            sample?.chain?.forEach { add(prettyHop(it)) }
            sample?.let { add(prettyHop(it.outbound)) }
        }.filter { it.isNotEmpty() && !isDirectTag(it) }.distinct()
        val liveEntryTitle = prettyHop(liveEntry?.title.orEmpty())
        val liveLandingTitle = prettyHop(liveLanding?.title.orEmpty())
        var landing = liveLandingTitle
            .ifBlank { sampleTags.lastOrNull { it != liveEntryTitle }.orEmpty() }
            .ifBlank { plannedLanding }
            .ifBlank { "landing" }
        if (landing == liveEntryTitle && plannedLanding.isNotBlank() && plannedLanding != liveEntryTitle) {
            landing = plannedLanding
        }
        var entry = when {
            liveEntryTitle.isNotBlank() && liveEntryTitle != landing -> liveEntryTitle
            sampleTags.size >= 2 && sampleTags.first() != landing -> sampleTags.first()
            entryGroup.isNotBlank() && entryGroup != landing -> entryGroup
            liveEntryTitle.isNotBlank() -> liveEntryTitle
            else -> entryGroup.ifBlank { "entry" }
        }
        if (entry == landing) {
            when {
                entryGroup.isNotBlank() && liveLandingTitle.isNotBlank() &&
                    entryGroup != liveLandingTitle && liveLandingTitle != liveEntryTitle -> {
                    entry = entryGroup
                    landing = liveLandingTitle
                }
                plannedEntry.isNotBlank() && plannedLanding.isNotBlank() && plannedEntry != plannedLanding -> {
                    entry = if (liveEntryTitle.isNotBlank()) liveEntryTitle else plannedEntry
                    landing = plannedLanding
                }
                entryGroup.isNotBlank() && landingGroup.isNotBlank() && entryGroup != landingGroup -> {
                    entry = entryGroup
                    landing = landingGroup
                }
            }
        }
        return listOf(entry.ifBlank { "entry" }, landing.ifBlank { "landing" })
    }

    private fun fromHops(
        path: ChainPath,
        hops: List<LiveHop>,
        chained: Boolean,
    ): Pair<List<FlowNode>, List<FlowLink>> {
        val labels = mutableListOf<String>()
        val directFlags = mutableListOf<Boolean>()
        fun addLabel(label: String, direct: Boolean = isDirectTag(label)) {
            labels += label
            directFlags += direct
        }
        addLabel("Device", false)
        addLabel("<final>", false)
        if (chained) {
            chainedHopPair(path, hops).forEach { addLabel(it) }
        } else {
            val live = hops.filter {
                it.role != ChainPathHop.Role.Device && it.role != ChainPathHop.Role.Destination
            }
            if (live.isNotEmpty()) {
                live.forEach { hop ->
                    val title = prettyHop(hop.title).ifBlank {
                        prettyHop(hop.subtitle)
                    }.ifBlank {
                        val raw = when (hop.role) {
                            ChainPathHop.Role.Entry -> path.entryTag
                            ChainPathHop.Role.Landing -> path.landingTag
                            ChainPathHop.Role.Exit -> path.profileName
                            else -> "proxy"
                        }
                        prettyHop(raw).ifBlank { if (isDirectTag(raw)) "DIRECT" else "proxy" }
                    }
                    addLabel(title)
                }
            } else {
                addLabel(
                    path.hops.firstOrNull { it.role == ChainPathHop.Role.Exit }?.label
                        ?.ifBlank { path.profileName }
                        .orEmpty()
                        .ifBlank { "proxy" },
                )
            }
        }
        val counts = LinkedHashMap<String, Int>()
        val links = LinkedHashMap<Pair<String, String>, Int>()
        val directIds = HashSet<String>()
        val ids = labels.mapIndexed { index, label ->
            val id = idOf(index.coerceAtMost(MAX_COLUMN), label)
            if (directFlags.getOrNull(index) == true) directIds.add(id)
            id
        }
        ids.forEach { counts[it] = 1 }
        for (i in 0 until ids.size - 1) {
            links[ids[i] to ids[i + 1]] = 1
        }
        return finish(counts, links, directIds)
    }

    private fun finish(
        counts: Map<String, Int>,
        links: Map<Pair<String, String>, Int>,
        directIds: Set<String>,
    ): Pair<List<FlowNode>, List<FlowLink>> {
        val byCol = counts.entries.groupBy { columnOf(it.key) }
        val kept = mutableSetOf<String>()
        val overflowOf = HashMap<Int, String>()
        val nodes = mutableListOf<FlowNode>()
        byCol.keys.sorted().forEach { col ->
            val ranked = byCol[col].orEmpty().sortedByDescending { it.value }
            val direct = ranked.filter { it.key in directIds || isDirectTag(labelOf(it.key)) }
            val proxy = ranked.filter { candidate -> direct.none { it.key == candidate.key } }
            val keepProxy = proxy.take((MAX_PER_COLUMN - direct.size).coerceAtLeast(1))
            val head = keepProxy + direct
            head.forEach { (id, weight) ->
                kept.add(id)
                val label = labelOf(id)
                nodes += FlowNode(
                    id = id,
                    label = label,
                    column = col,
                    weight = weight,
                    direct = id in directIds || isDirectTag(label),
                )
            }
            val rest = proxy.drop(keepProxy.size)
            if (rest.isNotEmpty()) {
                val extra = rest.sumOf { it.value }
                val id = idOf(col, "+${rest.size}")
                kept.add(id)
                overflowOf[col] = id
                nodes += FlowNode(id = id, label = "+${rest.size}", column = col, weight = extra)
            }
        }
        val flowLinks = links.mapNotNull { (pair, weight) ->
            val from = if (pair.first in kept) pair.first else overflowOf[columnOf(pair.first)]
            val to = if (pair.second in kept) pair.second else overflowOf[columnOf(pair.second)]
            if (from == null || to == null || from !in kept || to !in kept) return@mapNotNull null
            FlowLink(
                fromId = from,
                toId = to,
                weight = weight,
                direct = from in directIds || to in directIds,
            )
        }
        val merged = LinkedHashMap<Pair<String, String>, Int>()
        val mergedDirect = HashMap<Pair<String, String>, Boolean>()
        flowLinks.forEach { link ->
            val key = link.fromId to link.toId
            merged[key] = (merged[key] ?: 0) + link.weight
            mergedDirect[key] = (mergedDirect[key] == true) || link.direct
        }
        return nodes to merged.map { (key, weight) ->
            FlowLink(key.first, key.second, weight, mergedDirect[key] == true)
        }
    }

    internal fun prettySource(raw: String): String {
        val value = raw.trim().ifBlank { "<unknown>" }
        val host = when {
            value.startsWith("[") -> value.substringBefore(']').removePrefix("[")
            value.count { it == ':' } == 1 -> value.substringBefore(':')
            else -> value
        }
        return host.ifBlank { "<unknown>" }
    }

    internal fun prettyRule(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value == "final" || value == "<final>") return "<final>"
        extractAssigned(value)?.let { extracted ->
            val name = stripRuleName(extracted)
            if (name.isNotEmpty()) return name.take(18)
        }
        return stripRuleName(value).take(18).ifBlank { value.take(18) }
    }

    internal fun prettyHop(raw: String): String {
        val shown = ChainRuntimeCompiler.displayHopTag(raw).trim()
        val base = when {
            shown.isNotEmpty() -> shown
            else -> {
                val t = raw.trim()
                if (t.isEmpty()) return ""
                if (t.startsWith(ChainRuntimeCompiler.GENERATED_PREFIX)) return ""
                if (t == ChainRuntimeCompiler.LEGACY_CHAIN_TAG) return ""
                if (t.startsWith(ChainRuntimeCompiler.LEGACY_PREFIX)) {
                    t.removePrefix(ChainRuntimeCompiler.LEGACY_PREFIX)
                } else {
                    t
                }
            }
        }
        if (isDirectTag(base)) return "DIRECT"
        return shortenNodeName(base)
    }

    internal fun shortenNodeName(name: String, maxChars: Int = 16): String {
        var s = name.trim()
        if (s.isEmpty()) return s
        s = s.replaceFirst(Regex("^chainbox-(landing|entry|chain)-\\d+-"), "")
        s = s.replaceFirst(Regex("^chainbox-(landing|entry|chain)-"), "")
        val clipped = PROTO_TAIL.replaceFirst(s, "")
        if (clipped.length >= 2) s = clipped
        s = s.trim(' ', '-', '_', '[', ']')
        if (s.length > maxChars) s = s.take(maxChars - 1) + "…"
        return s.ifBlank { name.take(maxChars) }
    }

    internal fun prettyDest(raw: String): String {
        var value = raw.trim().ifBlank { "<unknown>" }
        if (value.endsWith(":443") || value.endsWith(":80")) {
            value = value.substringBeforeLast(':')
        }
        return if (value.length <= 22) value else value.take(19) + "…"
    }

    internal fun isDirectTag(tag: String): Boolean {
        val t = tag.trim()
        if (t.isEmpty()) return false
        val key = t.removePrefix("🔰 ").replace(" ", "").lowercase()
        if (key == "http-direct" || key.startsWith("dns-")) return false
        return key == "direct" || key == "直连" || key == "angela-direct"
    }

    private fun extractAssigned(value: String): String? {
        val match = ASSIGNMENT.find(value) ?: return null
        return match.groupValues[2].trim()
    }

    private fun stripRuleName(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("[")) s = s.removePrefix("[").substringBefore(']').trim()
        s = s.trim().removeSurrounding("\"").removeSurrounding("'")
        s = s.substringBefore(',').trim().removeSurrounding("\"").removeSurrounding("'")
        s = s.substringAfterLast('/')
        s = s.substringAfterLast(':')
        s = s.removePrefix("geosite-").removePrefix("geoip-").removePrefix("category-")
        if (s.startsWith("rule_set", ignoreCase = true)) return ""
        return s.trim()
    }

    private fun idOf(column: Int, label: String): String = "$column|$label"
    private fun columnOf(id: String): Int = id.substringBefore('|').toIntOrNull() ?: 0
    private fun labelOf(id: String): String = id.substringAfter('|', id)

    private val ASSIGNMENT = Regex(
        """(?i)(rule_set|ruleset|geosite|geoip|domain_suffix|domain_keyword|domain|ip_cidr|ipcidr)\s*=\s*(.+)""",
    )
    private val PROTO_TAIL = Regex(
        """(?i)[-_\s\[]+(vless|vmess|trojan|hysteria2?|tuic|wireguard|shadowsocks|\bss\b|anytls).*""",
    )
    private val LEADING_DECOR = Regex("^[\\p{So}\\p{Sk}\\uFE0F\\u200D\\s]+")
}

object SankeyLayout {
    fun requiredHeight(
        nodes: List<FlowNode>,
        pad: Float,
        gapY: Float,
        minHeights: Map<String, Float>,
        floor: Float,
    ): Float {
        if (nodes.isEmpty()) return floor
        val byCol = nodes.groupBy { it.column }
        val needed = byCol.values.maxOf { col ->
            val proxy = col.filter { !it.direct }
            val direct = col.filter { it.direct }
            fun mins(list: List<FlowNode>) =
                list.map { node -> (minHeights[node.id] ?: 16f).coerceAtLeast(16f) }
            val laneGap = if (proxy.isNotEmpty() && direct.isNotEmpty()) gapY * 2.2f else 0f
            val extraGaps =
                (proxy.size - 1).coerceAtLeast(0) + (direct.size - 1).coerceAtLeast(0)
            pad * 2f + mins(proxy).sum() + mins(direct).sum() + gapY * extraGaps + laneGap
        }
        return needed.coerceAtLeast(floor)
    }

    fun layout(
        nodes: List<FlowNode>,
        links: List<FlowLink>,
        width: Float,
        height: Float,
        nodeWidth: Float,
        pad: Float,
        minHeights: Map<String, Float> = emptyMap(),
        gapY: Float = 10f,
    ): Pair<List<PlacedNode>, List<PlacedRibbon>> {
        if (nodes.isEmpty() || width <= 0f || height <= 0f) {
            return emptyList<PlacedNode>() to emptyList()
        }
        val columns = nodes.groupBy { it.column }.toSortedMap()
        val nCols = columns.size.coerceAtLeast(1)
        val inner = (width - 2f * pad).coerceAtLeast(nodeWidth * nCols)
        val layerWidth = inner / nCols
        val placed = ArrayList<PlacedNode>(nodes.size)
        val byId = HashMap<String, PlacedNode>(nodes.size)
        columns.entries.forEachIndexed { index, (_, colNodes) ->
            val x = pad + index * layerWidth
            val proxy = colNodes.filter { !it.direct }
            val direct = colNodes.filter { it.direct }
            val ordered = proxy + direct
            val hasSplit = proxy.isNotEmpty() && direct.isNotEmpty()
            val laneGap = if (hasSplit) gapY * 2.2f else 0f
            val mins = ordered.map { node -> (minHeights[node.id] ?: 16f).coerceAtLeast(16f) }
            val extraGaps =
                (proxy.size - 1).coerceAtLeast(0) + (direct.size - 1).coerceAtLeast(0)
            val used = mins.sum() + gapY * extraGaps + laneGap
            val innerH = (height - pad * 2f).coerceAtLeast(used)
            var y = pad + ((innerH - used) / 2f).coerceAtLeast(0f)
            ordered.forEachIndexed { i, node ->
                val h = mins[i]
                val item = PlacedNode(node, x, y, nodeWidth, h)
                placed += item
                byId[node.id] = item
                y += h
                val laneBreak = hasSplit && i == proxy.lastIndex
                y += when {
                    laneBreak -> laneGap
                    i < ordered.lastIndex -> gapY
                    else -> 0f
                }
            }
        }
        val outgoing = links.groupBy { it.fromId }
        val incoming = links.groupBy { it.toId }
        val outCursor = HashMap<String, Float>()
        val inCursor = HashMap<String, Float>()
        val ribbons = ArrayList<PlacedRibbon>(links.size)
        val orderedLinks = links.sortedBy { if (it.direct) 1 else 0 }
        orderedLinks.forEach { link ->
            val from = byId[link.fromId] ?: return@forEach
            val to = byId[link.toId] ?: return@forEach
            val fromTotal = outgoing[from.node.id]?.sumOf { it.weight }?.coerceAtLeast(1) ?: 1
            val toTotal = incoming[to.node.id]?.sumOf { it.weight }?.coerceAtLeast(1) ?: 1
            val fh = (from.h * link.weight / fromTotal).coerceAtLeast(2f)
            val th = (to.h * link.weight / toTotal).coerceAtLeast(2f)
            val fy = outCursor.getOrPut(from.node.id) { from.y }
            val ty = inCursor.getOrPut(to.node.id) { to.y }
            ribbons += PlacedRibbon(
                columnFrom = from.node.column,
                columnTo = to.node.column,
                x0 = from.x + from.w,
                x1 = to.x,
                y0Top = fy,
                y0Bottom = fy + fh,
                y1Top = ty,
                y1Bottom = ty + th,
                direct = link.direct || from.node.direct || to.node.direct,
            )
            outCursor[from.node.id] = fy + fh
            inCursor[to.node.id] = ty + th
        }
        return placed to ribbons
    }
}
