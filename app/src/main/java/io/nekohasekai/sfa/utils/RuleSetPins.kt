package io.nekohasekai.sfa.utils

/**
 * Canonical refs for the third-party rule-sets the app points at.
 *
 * A rule-set decides whether a domain is proxied or dialled directly, so
 * the URL is a supply-chain input — but it first has to EXIST. The
 * 2026-10-27 startup audit established that the compiled `.srs` files
 * live only on the generated `rule-set` branch of `SagerNet/sing-geosite`
 * and `SagerNet/sing-geoip` (files at the branch root). The date tags
 * this object used to pin (`@<yyyymmdd>` / `@<yyyymmddhhmmss>`) address
 * source snapshots of those repositories, which contain no compiled
 * sets at all: every date-tag URL returns 404, and a cold start with an
 * empty kernel cache failed on exactly that.
 *
 * A commit-SHA pin was considered and rejected: upstream automation
 * rewrites the `rule-set` branch as orphan commits (the branch head has
 * no parents), so yesterday's SHA is not durably addressable — the
 * branch ref is the only stable live form. The supply-chain exposure of
 * a mutable branch is accepted deliberately and recorded here; it is
 * the lesser failure next to a pin that can never download.
 *
 * [pin] therefore normalises every URL for the two repositories onto
 * the branch form: any ref (branch name or dead date tag) becomes
 * `rule-set`, and a source-layout `rule-set/` path prefix is stripped
 * because the same file sits at the branch root. Only `.srs` paths are
 * normalised — anything else is returned untouched, since no live
 * target for it is known.
 *
 * The default overlay script (`app/src/main/assets/scripts/airport-region.js`)
 * carries the same bases for the script path, and [RuleSetPinsTest]
 * plus `scripts/test_security_guards.py` keep the two in sync.
 */
object RuleSetPins {
    /** The only ref of SagerNet/sing-geosite that serves compiled rule-sets. */
    const val SING_GEOSITE_REF: String = "rule-set"

    /** The only ref of SagerNet/sing-geoip that serves compiled rule-sets. */
    const val SING_GEOIP_REF: String = "rule-set"

    private const val JSDELIVR = "https://testingcf.jsdelivr.net"

    private val PINS: Map<String, String> =
        mapOf(
            "SagerNet/sing-geosite" to SING_GEOSITE_REF,
            "SagerNet/sing-geoip" to SING_GEOIP_REF,
        )

    /** `https://<host>/gh/<owner>/<repo>@<ref><path>` — jsDelivr's GitHub form. */
    private val JSDELIVR_GH =
        Regex("^https?://[^/]+/gh/([^/@]+)/([^/@]+)@([^/]+)(.*)$")

    private val RAW_GITHUB =
        Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)(/.*)?$")

    /** Canonical ref for `owner/repo`, or null when the repository is not pinned. */
    fun refFor(repo: String): String? = PINS[repo]

    /**
     * The branch-root path of the compiled file [path] names, or null
     * when [path] does not name an `.srs` file. The source repositories
     * keep compiled files under a `rule-set/` directory; on the
     * generated branch the same file sits at the root.
     */
    private fun compiledPath(path: String): String? {
        var rest = path.removePrefix("/")
        if (rest.startsWith("rule-set/")) rest = rest.removePrefix("rule-set/")
        if (!rest.lowercase().endsWith(".srs")) return null
        return "/$rest"
    }

    /**
     * Returns [url] normalised onto the canonical branch form when the
     * URL names one of the pinned repositories and an `.srs` file.
     * Every other URL — another owner's rule-set, a non-`.srs` path, or
     * a non-GitHub URL — is returned unchanged.
     */
    fun pin(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return url
        JSDELIVR_GH.matchEntire(raw)?.let { m ->
            val (owner, repo, _, path) = m.destructured
            val pinned = PINS["$owner/$repo"] ?: return raw
            val compiled = compiledPath(path) ?: return raw
            // Rebuild with the jsDelivr host the URL already used, so a custom
            // mirror keeps working.
            return raw.substringBefore("/gh/") + "/gh/$owner/$repo@$pinned$compiled"
        }
        RAW_GITHUB.matchEntire(raw)?.let { m ->
            val owner = m.groupValues[1]
            val repo = m.groupValues[2]
            val path = m.groupValues[4]
            val pinned = PINS["$owner/$repo"] ?: return raw
            val compiled = compiledPath(path) ?: return raw
            return "$JSDELIVR/gh/$owner/$repo@$pinned$compiled"
        }
        return raw
    }

    /**
     * True when [url] is already the canonical branch URL for one of
     * the repositories this app fetches from. Used by the regression guard.
     */
    fun isPinned(url: String): Boolean = pin(url) == url.trim() && refFor(repoOf(url)) != null

    private fun repoOf(url: String): String {
        JSDELIVR_GH.matchEntire(url.trim())?.let { m ->
            return "${m.groupValues[1]}/${m.groupValues[2]}"
        }
        RAW_GITHUB.matchEntire(url.trim())?.let { m ->
            return "${m.groupValues[1]}/${m.groupValues[2]}"
        }
        return ""
    }

    /** Official ads list the runtime ad-block overlay injects. */
    fun adsRuleSetUrl(): String = pin("$JSDELIVR/gh/SagerNet/sing-geosite@rule-set/geosite-category-ads-all.srs")

    /** Base URL for an official `geoip-*` file on the rule-set branch. */
    fun geoipBase(): String = "$JSDELIVR/gh/SagerNet/sing-geoip@$SING_GEOIP_REF/"

    /** Base URL for an official `geosite-*` file on the rule-set branch. */
    fun geositeBase(): String = "$JSDELIVR/gh/SagerNet/sing-geosite@$SING_GEOSITE_REF/"
}
