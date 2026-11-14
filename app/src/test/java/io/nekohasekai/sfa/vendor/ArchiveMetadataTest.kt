package io.nekohasekai.sfa.vendor

import org.junit.Assert.*
import org.junit.Test

class ArchiveMetadataTest {
    @Test fun missingArchiveIsRejectedAndInvalidated() {
        var rejected = false
        val result = runCatching { requireArchiveMetadata<String>(null) { rejected = true } }
        assertTrue(result.isFailure)
        assertTrue(rejected)
    }
    @Test fun archiveMetadataContinuesToIdentityChecks() {
        var rejected = false
        val metadata = Any()
        assertSame(metadata, requireArchiveMetadata(metadata) { rejected = true })
        assertFalse(rejected)
    }
}
