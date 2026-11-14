package io.nekohasekai.sfa.chain

import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.ApplicationDataAccess
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real ChainBindings + real admission, fake Settings (not Room or Android). */
class ChainAdmissionTest {
    companion object {
        @JvmStatic @BeforeClass fun initialize() { ApplicationDataAccess.gate.initialize {} }
    }

    @Test fun mutationsRoundTripUnderAdmission() {
        Settings.chainBindingsJson = ""; Settings.chainEnabled = false
        ChainBindings.put(ChainBinding(1, "entry", 2, "landing"))
        ChainBindings.markMaterialized(1)
        assertTrue(ChainBindings.get(1)!!.materialized)
        assertEquals(1, ChainBindings.bindingsLandingOn(2).size)
        assertEquals(1, ChainBindings.onProfileDeleted(2))
        assertTrue(ChainBindings.get(1)!!.invalidated)
        ChainBindings.remove(1)
        assertFalse(ChainBindings.hasAny())
    }

    @Test fun everyEntryPointRejectsBeforeSettingsMutationDuringExclusive() {
        Settings.chainBindingsJson = ""; Settings.chainEnabled = false
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val exclusive = thread {
            try { ApplicationDataAccess.gate.exclusive(5_000) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            } } catch (t: Throwable) { error.set(t) }
        }
        check(entered.await(5, TimeUnit.SECONDS))
        try {
            val actions = listOf<() -> Any?>(
                { ChainBindings.get(1) }, { ChainBindings.put(ChainBinding(1, "a", 2, "b")) },
                { ChainBindings.remove(1) }, { ChainBindings.onProfileDeleted(1) },
                { ChainBindings.markMaterialized(1) }, { ChainBindings.bindingsLandingOn(2) },
                { ChainBindings.all() }, { ChainBindings.hasAny() },
            )
            actions.forEach { action ->
                try { action(); fail("entry bypassed exclusive admission") }
                catch (_: IllegalStateException) { }
            }
            assertEquals("", Settings.chainBindingsJson)
            assertFalse(Settings.chainEnabled)
        } finally { release.countDown(); exclusive.join(5_000) }
        assertFalse(exclusive.isAlive); error.get()?.let { throw it }
    }

    @Test fun legacyReadMigrationStillWorksWithinOneLease() {
        Settings.chainBindingsJson = ""; Settings.chainEnabled = true
        Settings.chainBoundProfileId = 7; Settings.chainEntryTag = "entry"
        Settings.chainLandingProfileId = 8; Settings.chainLandingTag = "landing"
        Settings.selectedProfile = 7
        assertEquals(8L, ChainBindings.get(7)!!.landingProfileId)
        assertTrue(Settings.chainBindingsJson.contains("landing"))
        ChainBindings.remove(7)
    }
}
