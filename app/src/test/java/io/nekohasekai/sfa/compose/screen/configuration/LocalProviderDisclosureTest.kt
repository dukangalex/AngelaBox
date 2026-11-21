package io.nekohasekai.sfa.compose.screen.configuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProviderDisclosureTest {
    @Test
    fun qrAndFileImportsUseTheSameDisclosureGate() {
        // Both QRS-decoded configs and files use ProfileSource.Import;
        // neither requires a content URI to reach the gate.
        assertTrue(requiresProviderDisclosure(ProfileSource.Import))
        assertFalse(requiresProviderDisclosure(ProfileSource.CreateNew))
    }

    @Test
    fun embeddedProviderProbeCapturesUrlWithoutDownloadingIt() {
        val config = """
            proxy-providers:
              sample:
                type: http
                url: https://example.com/subscription.yaml
            proxy-groups:
              - name: PROXY
                type: select
                use:
                  - sample
            rules:
              - MATCH,PROXY
        """.trimIndent()
        assertEquals(listOf("https://example.com/subscription.yaml"), embeddedFetchUrls(config))
    }

    @Test
    fun nativeConfigWithoutProvidersNeedsNoDisclosure() {
        assertTrue(embeddedFetchUrls("{\"outbounds\":[]}").isEmpty())
    }
}
