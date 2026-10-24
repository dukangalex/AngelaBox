package io.nekohasekai.sfa.utils

/**
 * Immutable refs for the third-party rule-sets the app points at.
 *
 * A rule-set decides whether a domain is proxied or dialled directly, so the
 * URL is a supply-chain input: whoever can push to `SagerNet/sing-geosite`'s
 * `rule-set` branch — or to the CDN in front of it — can silently flip traffic
 * that should be tunnelled onto the local network interface, while the
 * dashboard still says "connected".
 *
 * `@rule-set` is a mutable branch, `@20261024005255` is a release tag. Pinning
 * makes the fetched bytes content-addressed by release date instead of
 * "whatever is on the branch right now". The tag is bumped on purpose, after
 * reading the diff, together with [RuleSetPinsTest] and the default overlay
 * script (`app/src/main/assets/scripts/airport-region.js`), which carries the
 * same pins for the script path.
 *
 * Both repositories publish date-named tags. A date tag is treated as immutable
 * here; any other ref (branch names such as `rule-set`, `main`, `master`) is
 * replaced by the pin.
 */
object RuleSetPins {
    /** SagerNet/sing-geosite release tag, minted 2026-10-24. */
    const val SING_GEOSITE_TAG: String = "20261024005255"

    /** SagerNet/sing-geoip release tag, minted 2026-10-12. */
    const val SING_GEOIP_TAG: String = "20261012"

    private const val JSDELIVR = "https://testingcf.jsdelivr.net"

    private val IMMUTABLE_REF = Regex("^[0-9]{8,14}$")

    private val PINS: Map<String, String> =
        mapOf(
            "SagerNet/sing-geosite" to SING_GEOSITE_TAG,
            "SagerNet/sing-geoip" to SING_GEOIP_TAG,
        )

    /** `https://<host>/gh/<owner>/<repo>@<ref><path>` — jsDelivr's GitHub form. */
    private val JSDELIVR_GH =
        Regex("^https?://[^/]+/gh/([^/@]+)/([^/@]+)@([^/]+)(.*)$")

    private val RAW_GITHUB =
        Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)(/.*)?$")

    /** Pinned tag for `owner/repo`, or null when the repository is not pinned. */
    fun tagFor(repo: String): String? = PINS[repo]

    /**
     * Returns [url] with a mutable ref replaced by the pinned tag when the URL
     * names one of the pinned repositories. Every other URL — including an
     * already-pinned one, another owner's rule-set, or a non-GitHub URL — is
     * returned unchanged.
     */
    fun pin(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return url
        JSDELIVR_GH.matchEntire(raw)?.let { m ->
            val (owner, repo, ref, path) = m.destructured
            val pinned = PINS["$owner/$repo"] ?: return raw
            if (IMMUTABLE_REF.matches(ref)) return raw
            // Rebuild with the jsDelivr host the URL already used, so a custom
            // mirror keeps working.
            return raw.substringBefore("/gh/") + "/gh/$owner/$repo@$pinned$path"
        }
        RAW_GITHUB.matchEntire(raw)?.let { m ->
            val owner = m.groupValues[1]
            val repo = m.groupValues[2]
            val ref = m.groupValues[3]
            val path = m.groupValues[4]
            val pinned = PINS["$owner/$repo"] ?: return raw
            if (IMMUTABLE_REF.matches(ref)) return raw
            return "$JSDELIVR/gh/$owner/$repo@$pinned$path"
        }
        return raw
    }

    /**
     * True when [url] is a pinned, immutable rule-set URL for one of the
     * repositories this app fetches from. Used by the regression guard.
     */
    fun isPinned(url: String): Boolean = pin(url) == url.trim() && tagFor(repoOf(url)) != null

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
    fun adsRuleSetUrl(): String =
        pin("$JSDELIVR/gh/SagerNet/sing-geosite@rule-set/geosite-category-ads-all.srs")

    /** Base URL for an official `geoip-*` file, pinned. */
    fun geoipBase(): String = "$JSDELIVR/gh/SagerNet/sing-geoip@$SING_GEOIP_TAG/"

    /** Base URL for an official `geosite-*` file, pinned. */
    fun geositeBase(): String = "$JSDELIVR/gh/SagerNet/sing-geosite@$SING_GEOSITE_TAG/"
}
