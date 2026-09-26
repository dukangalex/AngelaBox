package io.nekohasekai.sfa.compose

import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.isNestedRegionAutoSelect
import io.nekohasekai.sfa.compose.model.withoutNestedRegionAutoSelect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NestedRegionAutoSelectTest {
    @Test
    fun regionAutoSelectHidesOnlyWhenItsSelectorExists() {
        val tags = setOf("🇺🇸 美国", "🇺🇸 美国-自动选择", "其他节点", "其他节点-自动选择", "♻️ 自动选择", "漏网之鱼")
        assertTrue(isNestedRegionAutoSelect("🇺🇸 美国-自动选择", tags))
        assertTrue(isNestedRegionAutoSelect("其他节点-自动选择", tags))
        assertFalse(isNestedRegionAutoSelect("♻️ 自动选择", tags))
        assertFalse(isNestedRegionAutoSelect("自动选择", tags))
        assertFalse(isNestedRegionAutoSelect("🇺🇸 美国", tags))
        assertFalse(isNestedRegionAutoSelect("🇺🇸 美国-自动选择", setOf("🇺🇸 美国-自动选择")))
    }

    @Test
    fun listDropsNestedCardsAndKeepsTheMember() {
        val groups = listOf(
            group("🇺🇸 美国"),
            group("🇺🇸 美国-自动选择"),
            group("♻️ 自动选择"),
        ).withoutNestedRegionAutoSelect()
        assertEquals(listOf("🇺🇸 美国", "♻️ 自动选择"), groups.map { it.tag })
    }

    private fun group(tag: String) = Group(
        tag = tag,
        type = "selector",
        displayType = "Selector",
        selectable = true,
        selected = "",
        isExpand = false,
        items = emptyList(),
    )
}
