package io.nekohasekai.sfa.utils

import org.junit.Assert.*
import org.junit.Test

class RestoreDatabaseCloseTest {
    @Test fun bothCloseBeforeMutation() {
        val calls = mutableListOf<String>()
        closeDatabasesBeforeRestore({ calls += "settings" }, { calls += "profiles" })
        calls += "replace"
        assertEquals(listOf("settings", "profiles", "replace"), calls)
    }

    @Test fun firstFailurePreventsSecondCloseAndReplacement() {
        val calls = mutableListOf<String>()
        val result = runCatching {
            closeDatabasesBeforeRestore({ calls += "settings"; error("injected") }, { calls += "profiles" })
            calls += "replace"
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("settings"), calls)
    }

    @Test fun secondFailurePreventsReplacement() {
        val calls = mutableListOf<String>()
        val result = runCatching {
            closeDatabasesBeforeRestore({ calls += "settings" }, { calls += "profiles"; error("injected") })
            calls += "replace"
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("settings", "profiles"), calls)
    }
}
