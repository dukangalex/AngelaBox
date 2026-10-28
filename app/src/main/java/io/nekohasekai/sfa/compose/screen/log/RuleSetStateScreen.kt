package io.nekohasekai.sfa.compose.screen.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.utils.RuleSetState

/**
 * 「规则集状态」页：每个规则集的本地地板（打包种子 / 存储副本 /
 * 手动文件 / 缓存副本 / 无）、最近活动与最近一次可用性结论。
 * 这是 136 提示纪律的持久那一半——提示一闪而过，状态在这里
 * 随时可查。数据只来自 app 自己的记录（种子、存储、缓存、可用
 * 性记录），不含内核不透明缓存的任何猜测。从日志页菜单进入，
 * 打开读一次、顶栏可手动刷新，无轮询、不进仪表盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSetStateScreen(onBack: () -> Unit) {
    val viewModel: RuleSetStateViewModel = viewModel()
    val ui by viewModel.ui.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.rule_set_state_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
            actions = {
                IconButton(onClick = { viewModel.load() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.rule_set_state_refresh),
                    )
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(LocalScaffoldPadding.current),
    ) {
        when {
            ui.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            ui.rows.isEmpty() -> Text(
                text = stringResource(R.string.rule_set_state_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(ui.rows, key = { "${it.tag}|${it.url ?: it.urlHash ?: ""}" }) { row ->
                    RuleSetStateRow(row)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RuleSetStateRow(row: RuleSetState.Row) {
    val context = LocalContext.current
    val sourceText = stringResource(
        when (row.sourceKind) {
            RuleSetState.SourceKind.APP_OWNED -> R.string.rule_set_state_source_app
            RuleSetState.SourceKind.MANUAL -> R.string.rule_set_state_source_manual
            RuleSetState.SourceKind.SUBSCRIPTION -> R.string.rule_set_state_source_subscription
        },
    )
    val floorText = stringResource(
        when (row.floor) {
            RuleSetState.Floor.SEED -> R.string.rule_set_state_floor_seed
            RuleSetState.Floor.STORE -> R.string.rule_set_state_floor_store
            RuleSetState.Floor.MANUAL_FILE -> R.string.rule_set_state_floor_manual
            RuleSetState.Floor.WARMER -> R.string.rule_set_state_floor_warmer
            RuleSetState.Floor.NONE -> R.string.rule_set_state_floor_none
        },
    )
    val availabilityText = stringResource(
        when (row.availability) {
            RuleSetState.Availability.AVAILABLE -> R.string.rule_set_state_available
            RuleSetState.Availability.UNAVAILABLE -> R.string.rule_set_state_unavailable
            RuleSetState.Availability.STALE -> R.string.rule_set_state_stale
            RuleSetState.Availability.UNKNOWN -> R.string.rule_set_state_unknown
        },
    )
    val detailParts = listOfNotNull(
        row.entryCount?.let { context.getString(R.string.rule_providers_entries, it) },
        row.sizeBytes?.let { RuleSetState.formatSize(it) },
        row.lastActivityMs?.let {
            stringResource(R.string.rule_set_state_last_activity, RuleSetState.formatDateTime(it))
        },
    )
    val availabilityLine = row.availabilityAtMs?.let {
        "$availabilityText · ${RuleSetState.formatDateTime(it)}"
    } ?: availabilityText

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = row.tag,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "$sourceText · $floorText",
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.floor == RuleSetState.Floor.NONE) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        if (detailParts.isNotEmpty()) {
            Text(
                text = detailParts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = availabilityLine,
            style = MaterialTheme.typography.bodySmall,
            color = if (row.availability == RuleSetState.Availability.UNAVAILABLE) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        val origin = row.url ?: row.urlHash?.let { "#$it" }
        if (origin != null) {
            Text(
                text = origin,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.profileNames.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.rule_set_state_profiles,
                    row.profileNames.joinToString("、"),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
