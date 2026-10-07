package io.nekohasekai.sfa.chain

data class ChainPathHop(
    val role: Role,
    val label: String = "",
    val detail: String = "",
    val highlighted: Boolean = false,
) {
    enum class Role {
        Device,
        Entry,
        Exit,
        Landing,
        Destination,
    }
}

data class ChainPath(
    val chained: Boolean,
    val hops: List<ChainPathHop>,
    val entryTag: String = "",
    val landingTag: String = "",
    val landingProfileName: String = "",
    val profileName: String = "",
) {
    companion object {
        fun regular(profileName: String = "", exitTag: String = ""): ChainPath {
            return ChainPathBuilder.build(
                profileName = profileName,
                defaultOutboundTag = exitTag,
                binding = null,
                landingProfileName = null,
            )
        }
    }
}

data class GroupHint(
    val tag: String,
    val selected: String = "",
    val delays: Map<String, Int> = emptyMap(),
)

data class LiveHop(
    val role: ChainPathHop.Role,
    val title: String,
    val subtitle: String = "",
    val delayMs: Int = 0,
    val highlighted: Boolean = false,
)

data class LiveTopology(
    val running: Boolean = false,
    val chained: Boolean = false,
    val mode: String = "",
    val hops: List<LiveHop> = emptyList(),
    val destinations: List<String> = emptyList(),
    val activeConnections: Int = 0,
    val flowing: Boolean = false,
    val flowNodes: List<FlowNode> = emptyList(),
    val flowLinks: List<FlowLink> = emptyList(),
) {
    companion object {
        fun idle(): LiveTopology = LiveTopologyBuilder.fromPath(ChainPath.regular(), running = false)
    }
}

/**
 * Planned hops from the saved binding. Live topology overlays current
 * selector/urltest picks and active connection chains on top of this.
 */
object ChainPathBuilder {
    fun build(
        profileName: String?,
        defaultOutboundTag: String?,
        binding: ChainBinding?,
        landingProfileName: String?,
    ): ChainPath {
        val profile = profileName?.trim().orEmpty()
        val exitLabel = defaultOutboundTag?.trim().orEmpty().ifEmpty { profile }
        if (binding == null) {
            return ChainPath(
                chained = false,
                hops = listOf(
                    ChainPathHop(ChainPathHop.Role.Device),
                    ChainPathHop(
                        role = ChainPathHop.Role.Exit,
                        label = exitLabel,
                        detail = profile,
                    ),
                    ChainPathHop(ChainPathHop.Role.Destination),
                ),
                profileName = profile,
            )
        }
        val landingName = landingProfileName?.trim().orEmpty()
        val entryLabel = binding.entryTag.trim().ifEmpty { exitLabel }
        val landingDetail = when {
            landingName.isNotEmpty() && landingName != profile -> landingName
            profile.isNotEmpty() -> profile
            else -> ""
        }
        return ChainPath(
            chained = true,
            hops = listOf(
                ChainPathHop(ChainPathHop.Role.Device),
                ChainPathHop(
                    role = ChainPathHop.Role.Entry,
                    label = entryLabel,
                    detail = profile,
                    highlighted = true,
                ),
                ChainPathHop(
                    role = ChainPathHop.Role.Landing,
                    label = binding.landingTag.trim(),
                    detail = landingDetail,
                    highlighted = true,
                ),
                ChainPathHop(ChainPathHop.Role.Destination),
            ),
            entryTag = binding.entryTag.trim(),
            landingTag = binding.landingTag.trim(),
            landingProfileName = landingName,
            profileName = profile,
        )
    }
}

object LiveTopologyBuilder {
    fun fromPath(
        path: ChainPath,
        running: Boolean,
        mode: String = "",
        groups: List<GroupHint> = emptyList(),
        liveChain: List<String> = emptyList(),
        destinations: List<String> = emptyList(),
        activeConnections: Int = 0,
        flowing: Boolean = false,
        samples: List<FlowSample> = emptyList(),
    ): LiveTopology {
        val modeNorm = mode.trim()
        val direct = running && modeNorm.equals("direct", ignoreCase = true)
        val hops = when {
            direct -> listOf(
                LiveHop(ChainPathHop.Role.Device, title = ""),
                LiveHop(ChainPathHop.Role.Exit, title = "DIRECT"),
                LiveHop(ChainPathHop.Role.Destination, title = destinations.firstOrNull().orEmpty()),
            )
            running -> liveHops(path, groups, liveChain, destinations)
            else -> path.hops.map { plannedHop(it) }
        }
        val chained = if (direct) false else path.chained
        val (flowNodes, flowLinks) = TrafficFlowBuilder.build(
            samples = samples,
            path = path,
            hops = hops,
            chained = chained,
            destinations = destinations,
            running = running,
        )
        return LiveTopology(
            running = running,
            chained = chained,
            mode = modeNorm,
            hops = hops,
            destinations = destinations,
            activeConnections = activeConnections,
            flowing = flowing && running,
            flowNodes = flowNodes,
            flowLinks = flowLinks,
        )
    }

    private fun plannedHop(hop: ChainPathHop): LiveHop = LiveHop(
        role = hop.role,
        title = hop.label,
        subtitle = hop.detail,
        highlighted = hop.highlighted,
    )

    private fun liveHops(
        path: ChainPath,
        groups: List<GroupHint>,
        liveChain: List<String>,
        destinations: List<String>,
    ): List<LiveHop> {
        val useful = liveChain.map { ChainRuntimeCompiler.displayHopTag(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !TrafficFlowBuilder.isDirectTag(it) }
            .distinct()
        val destTitle = destinations.firstOrNull().orEmpty()
        if (path.chained) {
            val entryMembers = membersOf(path.entryTag, groups)
            val landingMembers = membersOf(path.landingTag, groups)
            val sameHop = path.entryTag.isNotBlank() &&
                path.entryTag == path.landingTag &&
                path.landingProfileName.ifBlank { path.profileName } == path.profileName
            val entryLive = useful.firstOrNull { hop ->
                hop in entryMembers && (sameHop || hop !in landingMembers)
            } ?: useful.firstOrNull { hop -> hop !in landingMembers }
            val landLive = useful.lastOrNull { hop ->
                hop in landingMembers && (sameHop || hop !in entryMembers)
            } ?: useful.lastOrNull { hop -> hop !in entryMembers }
            val entryPick = resolve(path.entryTag, groups, entryLive)
            val landPick = resolve(path.landingTag, groups, landLive)
            val entryTitle = displayNonDirect(entryPick.first, path.entryTag, groups)
            var landTitle = displayNonDirect(landPick.first, path.landingTag, groups)
            if (!sameHop && landTitle.isNotBlank() && landTitle == entryTitle) {
                landTitle = displayNonDirect(
                    leafOf(path.landingTag, groups, 0).first,
                    path.landingTag,
                    groups,
                )
                if (landTitle == entryTitle) {
                    landTitle = TrafficFlowBuilder.prettyHop(path.landingTag).ifBlank { landTitle }
                }
            }
            return listOf(
                LiveHop(ChainPathHop.Role.Device, title = ""),
                LiveHop(
                    role = ChainPathHop.Role.Entry,
                    title = entryTitle,
                    subtitle = path.entryTag.ifBlank { path.profileName },
                    delayMs = delayOf(entryTitle, groups).takeIf { it > 0 } ?: entryPick.second,
                    highlighted = true,
                ),
                LiveHop(
                    role = ChainPathHop.Role.Landing,
                    title = landTitle,
                    subtitle = path.landingProfileName.ifBlank { path.landingTag },
                    delayMs = delayOf(landTitle, groups).takeIf { it > 0 } ?: landPick.second,
                    highlighted = true,
                ),
                LiveHop(ChainPathHop.Role.Destination, title = destTitle),
            )
        }
        val exitTag = path.hops.firstOrNull { it.role == ChainPathHop.Role.Exit }?.label.orEmpty()
        val exitPick = resolve(exitTag, groups, useful.lastOrNull())
        val exitTitle = exitPick.first.ifBlank { exitTag }
        return listOf(
            LiveHop(ChainPathHop.Role.Device, title = ""),
            LiveHop(
                role = ChainPathHop.Role.Exit,
                title = if (TrafficFlowBuilder.isDirectTag(exitTitle)) {
                    leafOf(exitTag, groups, 0).first.ifBlank { exitTag }
                } else {
                    exitTitle
                },
                subtitle = path.profileName,
                delayMs = exitPick.second,
            ),
            LiveHop(ChainPathHop.Role.Destination, title = destTitle),
        )
    }

    private fun pickLive(
        useful: List<String>,
        groupTag: String,
        groups: List<GroupHint>,
        preferFirst: Boolean,
    ): String? {
        val members = membersOf(groupTag, groups)
        val matched = useful.filter { hop ->
            hop in members || members.any { ChainRuntimeCompiler.displayHopTag(it) == hop }
        }
        return when {
            matched.isNotEmpty() -> if (preferFirst) matched.first() else matched.last()
            preferFirst -> useful.firstOrNull()
            else -> useful.lastOrNull()
        }
    }

    private fun membersOf(groupTag: String, groups: List<GroupHint>): Set<String> {
        val key = ChainRuntimeCompiler.displayHopTag(groupTag).ifBlank { groupTag.trim() }
        val group = findGroup(groupTag, groups)
        return buildSet {
            add(key)
            if (group != null) {
                add(group.tag)
                add(ChainRuntimeCompiler.displayHopTag(group.tag))
                if (group.selected.isNotBlank()) add(group.selected)
                addAll(group.delays.keys)
            }
        }.filter { it.isNotBlank() }.toSet()
    }

    private fun findGroup(tag: String, groups: List<GroupHint>): GroupHint? {
        val raw = tag.trim()
        val key = TrafficFlowBuilder.groupKey(tag)
        if (raw.isEmpty() && key.isEmpty()) return null
        groups.find { group ->
            group.tag == raw || ChainRuntimeCompiler.displayHopTag(group.tag) == raw
        }?.let { return it }
        val keyed = groups.filter { TrafficFlowBuilder.groupKey(it.tag) == key && key.isNotEmpty() }
        return keyed.singleOrNull() ?: keyed.find { it.tag.endsWith(key) }
    }

    private fun resolve(
        groupTag: String,
        groups: List<GroupHint>,
        liveOverride: String?,
    ): Pair<String, Int> {
        val override = liveOverride?.trim().orEmpty()
        if (override.isNotEmpty()) {
            val overrideGroup = findGroup(override, groups)
            if (overrideGroup != null) {
                val leaf = leafOf(overrideGroup.tag, groups, 0)
                if (leaf.first.isNotEmpty() &&
                    TrafficFlowBuilder.groupKey(leaf.first) != TrafficFlowBuilder.groupKey(override)
                ) {
                    return leaf
                }
                if (TrafficFlowBuilder.looksLikeGroupTag(override) || overrideGroup.selected.isNotBlank()) {
                    return leaf
                }
            }
            if (TrafficFlowBuilder.looksLikeGroupTag(override)) {
                val planned = leafOf(groupTag, groups, 0)
                if (planned.first.isNotEmpty() && !TrafficFlowBuilder.looksLikeGroupTag(planned.first)) {
                    return planned
                }
            }
            val delay = delayOf(override, groups)
            return override to delay
        }
        return leafOf(groupTag, groups, 0)
    }

    private fun leafOf(tag: String, groups: List<GroupHint>, depth: Int): Pair<String, Int> {
        val key = ChainRuntimeCompiler.displayHopTag(tag).ifBlank { tag.trim() }
        if (key.isEmpty() || depth > 6) return key to 0
        val group = findGroup(tag, groups) ?: return key to 0
        val selected = group.selected.trim()
        if (selected.isEmpty() || selected == key || TrafficFlowBuilder.groupKey(selected) == TrafficFlowBuilder.groupKey(key)) {
            return key to (group.delays[key] ?: 0)
        }
        val nested = findGroup(selected, groups)
        return if (nested != null) {
            leafOf(selected, groups, depth + 1)
        } else {
            selected to (group.delays[selected] ?: 0)
        }
    }

    private fun displayNonDirect(
        candidate: String,
        groupTag: String,
        groups: List<GroupHint>,
    ): String {
        val shown = candidate.trim()
        if (shown.isNotEmpty() &&
            !TrafficFlowBuilder.isDirectTag(shown) &&
            findGroup(shown, groups) == null &&
            !TrafficFlowBuilder.looksLikeGroupTag(shown)
        ) {
            return shown
        }
        val fromCandidate = if (shown.isNotEmpty()) leafOf(shown, groups, 0).first else ""
        if (fromCandidate.isNotEmpty() &&
            !TrafficFlowBuilder.isDirectTag(fromCandidate) &&
            findGroup(fromCandidate, groups) == null &&
            !TrafficFlowBuilder.looksLikeGroupTag(fromCandidate)
        ) {
            return fromCandidate
        }
        val leaf = leafOf(groupTag, groups, 0).first
        if (leaf.isNotEmpty() &&
            !TrafficFlowBuilder.isDirectTag(leaf) &&
            !TrafficFlowBuilder.looksLikeGroupTag(leaf)
        ) {
            return leaf
        }
        return shown.ifBlank { groupTag.trim() }
    }

    private fun delayOf(tag: String, groups: List<GroupHint>): Int {
        val keys = buildList {
            add(tag)
            val shown = ChainRuntimeCompiler.displayHopTag(tag)
            if (shown.isNotBlank()) add(shown)
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        groups.forEach { g ->
            keys.forEach { key ->
                g.delays[key]?.let { if (it > 0) return it }
            }
        }
        return 0
    }
}
