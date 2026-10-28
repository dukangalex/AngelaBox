package io.nekohasekai.sfa.utils

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.nekohasekai.sfa.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OverrideNotice(
    val title: String,
    val reason: String,
    val hint: String,
    val error: Boolean = false,
)

object OverrideStatus {
    private val _notices = MutableStateFlow<List<OverrideNotice>>(emptyList())
    val notices: StateFlow<List<OverrideNotice>> = _notices

    /**
     * 留档去重（v1.0.136）：同一条提示（标题+正文+提示+error 全等）在
     * 窗口内只记一次。启动阶梯会逐级重跑管线、同批提示反复 set()，不
     * 去重的话日志会被同一事实刷掉好几份；窗口外的再次出现是新事件，
     * 照常再记一条。
     */
    private const val RECORD_WINDOW_MS = 5L * 60 * 1000
    private val recentlyRecorded = mutableMapOf<String, Long>()

    fun clear() {
        _notices.value = emptyList()
    }

    fun set(items: List<OverrideNotice>) {
        _notices.value = items
        recordNew(items)
        val errors = items.filter { it.error }
        if (errors.isNotEmpty()) {
            val text = errors.joinToString("\n\n") { "${it.title}\n${it.reason}\n${it.hint}" }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(Application.application, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun add(item: OverrideNotice) {
        set(_notices.value + item)
    }

    /**
     * 展示层关闭：把这些提示从当前列表摘掉（分组卡一次关一组）。直接
     * 写流，不走 set()——关闭动作不能把剩余的 error 提示再 Toast 一遍。
     * 下次启动时管线会 clear 后重发，新事实照常出现；被关掉的只是本轮
     * 这一批，不做跨启动记忆（detour 常驻提示的去重在展示层按指纹做）。
     */
    fun dismiss(items: List<OverrideNotice>) {
        if (items.isEmpty()) return
        _notices.value = _notices.value.filterNot { it in items }
    }

    fun dismiss(item: OverrideNotice) = dismiss(listOf(item))

    private fun fingerprint(notice: OverrideNotice): String =
        notice.title + "\u0001" + notice.reason + "\u0001" + notice.hint + "\u0001" + notice.error

    /** 把本批里还没留过档的提示写入 NoticeLogStore（见 recentlyRecorded 的去重口径）。 */
    private fun recordNew(items: List<OverrideNotice>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val fresh = synchronized(recentlyRecorded) {
            recentlyRecorded.entries.removeIf { now - it.value > RECORD_WINDOW_MS }
            items.filter { notice ->
                val key = fingerprint(notice)
                if (recentlyRecorded.containsKey(key)) {
                    false
                } else {
                    recentlyRecorded[key] = now
                    true
                }
            }
        }
        if (fresh.isNotEmpty()) {
            NoticeLogStore.append(fresh.map { it.toNoticeLogRecord(now) })
        }
    }
}
