package io.nekohasekai.sfa.bg

/** Immutable package selection, with a fresh iterator for each kernel start attempt. */
internal class PerAppPackageSnapshot(
    enabled: Boolean,
    includeMode: Boolean,
    packages: Set<String>,
    ownPackage: String,
) {
    val includes: Boolean = enabled && includeMode
    val excludes: Boolean = enabled && !includeMode
    private val includePackages = if (includes) packages.toSet() + ownPackage else emptySet()
    private val excludePackages = if (excludes) packages.toSet() - ownPackage else emptySet()

    fun includeIterator(): Iterator<String> = includePackages.iterator()
    fun excludeIterator(): Iterator<String> = excludePackages.iterator()
}
