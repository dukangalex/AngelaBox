package io.nekohasekai.sfa.compose.screen.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.RuleSetAvailabilityStore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.utils.RuleSetProviders
import io.nekohasekai.sfa.utils.RuleSetSeeds
import io.nekohasekai.sfa.utils.RuleSetState
import io.nekohasekai.sfa.utils.RuleSetStore
import io.nekohasekai.sfa.utils.RuleSetTriage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「规则集状态」页的数据源：把每个规则集的 app 侧事实快照下来，
 * 交给纯聚合 RuleSetState 合并成行。来源只有 app 自己记录的东西
 * ——137a 种子登记、落盘配置的远程定义（含提供者页手动同步留下的
 * initial_path)、137b 的 URL 键存储、warmer 的按 tag 缓存、136 的
 * 可用性记录；内核自己的规则集缓存是不透明的 bbolt，这里不猜、
 * 不编造。每个来源独立 runCatching：任何一处读坏只丢那一份证据，
 * 不白屏。打开时读一次 + 顶栏手动刷新，无轮询。
 */
class RuleSetStateViewModel : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val rows: List<RuleSetState.Row> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            val rows = withContext(Dispatchers.IO) {
                runCatching { collectRows() }.getOrDefault(emptyList())
            }
            _ui.value = UiState(loading = false, rows = rows)
        }
    }

    /** Accumulator for one (tag, url) across profiles / records. */
    private class Acc(val tag: String, val url: String) {
        val profileNames = LinkedHashSet<String>()
        var appOwned = false
        var manualPath: String? = null
        val records = mutableListOf<RuleSetState.RecordFact>()
    }

    private suspend fun collectRows(): List<RuleSetState.Row> {
        val context = Application.application
        val filesDir = context.filesDir
        val workingDir = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: filesDir
        val now = System.currentTimeMillis()

        val accs = LinkedHashMap<Pair<String, String>, Acc>()
        fun acc(tag: String, url: String): Acc = accs.getOrPut(tag to url) { Acc(tag, url) }

        // 1) Seed registry: app-owned sets get a row even when no
        //    profile currently references them.
        for (seed in RuleSetSeeds.SEEDS.values) {
            acc(seed.tag, seed.url).appOwned = true
        }

        // 2) Stored profiles: subscription definitions, plus the
        //    rule-provider screen's manual sync (a stored definition
        //    carrying an initial_path / path is detectable from the
        //    same parse).
        val profiles = runCatching { ProfileManager.list() }.getOrDefault(emptyList())
        for (profile in profiles) {
            val content = runCatching { File(profile.typed.path).readText() }.getOrNull() ?: continue
            val manualPaths = runCatching {
                RuleSetProviders.parse(content)
                    .filter { it.remote && it.path.isNotBlank() }
                    .associate { it.tag to it.path }
            }.getOrDefault(emptyMap())
            for (ref in runCatching { RuleSetTriage.enumerate(content) }.getOrDefault(emptyList())) {
                val a = acc(ref.tag, ref.url)
                a.profileNames += profile.name
                if (a.manualPath == null) a.manualPath = manualPaths[ref.tag]
            }
        }

        // 3) Availability records from every profile (probe verdicts
        //    and learned kernel-failure needles share the store).
        for ((key, record) in runCatching { RuleSetAvailabilityStore.loadAll() }.getOrDefault(emptyMap())) {
            val parsed = RuleSetState.parseRecordKey(key) ?: continue
            acc(parsed.second, parsed.third).records +=
                RuleSetState.RecordFact(record.available, record.checkedAtMs)
        }

        // Snapshot the two copy stores (names → facts).
        val storeDir = RuleSetStore.storeDir(filesDir)
        val storeEntries = runCatching { RuleSetStore.listEntries(storeDir) }.getOrDefault(emptyList())
        val storeByName = storeEntries.associateBy { it.fileName }
        val warmerDir = File(workingDir, "rule-sets")
        val warmerFiles = runCatching { warmerDir.listFiles() }.getOrNull()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            .orEmpty()
        val warmerByName = warmerFiles.associateBy { it.name }

        val consumedStore = HashSet<String>()
        val consumedWarmer = HashSet<String>()

        fun copyFact(file: File, atMs: Long): RuleSetState.CopyFact = RuleSetState.CopyFact(
            sizeBytes = file.length(),
            atMs = atMs,
            entryCount = runCatching { RuleSetProviders.entryCount(file) }.getOrNull(),
        )

        val inputs = accs.values.map { a ->
            val seedCopy = if (a.appOwned) {
                RuleSetSeeds.installedFile(filesDir, a.tag)
                    ?.takeIf { it.isFile && it.length() > 0L }
                    ?.let { copyFact(it, it.lastModified()) }
            } else {
                null
            }
            // The store names a copy by (tag, format, url); a record-
            // only set's format is unknown, so try both extensions —
            // exactly the two names the store can ever have minted.
            val storeEntry = listOf("binary", "source")
                .map { RuleSetStore.entryFileName(a.tag, it, a.url) }
                .firstNotNullOfOrNull { name ->
                    storeByName[name]?.also { consumedStore += name }
                }
            val storeCopy = storeEntry?.let { copyFact(File(storeDir, it.fileName), it.lastSeenMs) }
            val warmerFile = listOf("binary", "source")
                .map { RuleSetProviders.cacheFile(workingDir, a.tag, it, a.url).name }
                .firstNotNullOfOrNull { name ->
                    warmerByName[name]?.also { consumedWarmer += name }
                }
            val warmerCopy = warmerFile?.let { copyFact(it, it.lastModified()) }
            val manualFile = a.manualPath
                ?.let { runCatching { File(it) }.getOrNull() }
                ?.takeIf { it.isFile && it.length() > 0L }
            RuleSetState.RefInput(
                tag = a.tag,
                url = a.url,
                profileNames = a.profileNames.toList(),
                appOwned = a.appOwned,
                manual = manualFile != null,
                seedCopy = seedCopy,
                storeCopy = storeCopy,
                manualCopy = manualFile?.let { copyFact(it, it.lastModified()) },
                warmerCopy = warmerCopy,
                records = a.records,
            )
        }

        // Copies no profile or record could claim: script-generated
        // sets and leftovers of deleted profiles live here.
        val orphanStore = storeEntries
            .filter { it.fileName !in consumedStore }
            .map { entry ->
                RuleSetState.OrphanStoreInput(
                    fileName = entry.fileName,
                    urlHash = entry.urlHash,
                    tag = entry.fileName.substringAfter('-').substringBeforeLast('.'),
                    copy = copyFact(File(storeDir, entry.fileName), entry.lastSeenMs),
                )
            }
        val knownTags = accs.values.mapTo(HashSet()) { it.tag }
        val orphanWarmer = warmerFiles
            .filter { it.name !in consumedWarmer }
            .groupBy { it.name.substringBeforeLast('.') }
            .filterKeys { stem -> stem !in knownTags && stem !in RuleSetSeeds.SEEDS.keys }
            .values
            .map { sameStem -> sameStem.maxBy { it.length() } }
            .map { file ->
                RuleSetState.OrphanWarmerInput(
                    tag = file.name.substringBeforeLast('.'),
                    copy = copyFact(file, file.lastModified()),
                )
            }

        return RuleSetState.aggregate(inputs, orphanStore, orphanWarmer, now)
    }
}
