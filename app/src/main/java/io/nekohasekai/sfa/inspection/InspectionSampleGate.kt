package io.nekohasekai.sfa.inspection

/** Called under the store lock: later samples and clears supersede earlier work. */
internal class InspectionSampleGate {
    private var generation = 0L
    private var manual: Long? = null
    fun begin(isManual: Boolean): Long {
        if (!isManual && manual != null) return -1L
        val ticket = ++generation
        if (isManual) manual = ticket
        return ticket
    }
    fun end(ticket: Long) {
        if (manual == ticket) manual = null
    }
    fun invalidate() {
        generation++
        manual = null
    }
    fun mayCommit(ticket: Long): Boolean = ticket >= 0 && ticket == generation
}
