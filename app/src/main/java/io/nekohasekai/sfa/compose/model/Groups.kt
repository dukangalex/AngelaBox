package io.nekohasekai.sfa.compose.model

import androidx.compose.runtime.Immutable
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItem
import io.nekohasekai.libbox.OutboundGroupItemIterator

@Immutable
data class Group(
    val tag: String,
    val type: String,
    val displayType: String,
    val selectable: Boolean,
    val selected: String,
    val isExpand: Boolean,
    val items: List<GroupItem>,
) {
    constructor(item: OutboundGroup) : this(
        item.tag,
        item.type,
        Libbox.proxyDisplayType(item.type),
        item.selectable,
        item.selected,
        item.isExpand,
        sortItemsByDelay(item.items.toList().map { GroupItem(it) }),
    )
}

private fun sortItemsByDelay(items: List<GroupItem>): List<GroupItem> {
    if (items.none { it.urlTestDelay > 0 }) return items
    return items.sortedWith(
        compareBy<GroupItem> { item -> if (item.urlTestDelay > 0) 0 else 1 }
            .thenBy { item -> if (item.urlTestDelay > 0) item.urlTestDelay else Int.MAX_VALUE },
    )
}

@Immutable
data class GroupItem(
    val tag: String,
    val type: String,
    val displayType: String,
    val urlTestTime: Long,
    val urlTestDelay: Int,
) {
    constructor(item: OutboundGroupItem) : this(
        item.tag,
        item.type,
        Libbox.proxyDisplayType(item.type),
        item.urlTestTime,
        item.urlTestDelay,
    )
}

internal fun OutboundGroupItemIterator.toList(): List<OutboundGroupItem> {
    val list = mutableListOf<OutboundGroupItem>()
    while (hasNext()) {
        list.add(next())
    }
    return list
}
