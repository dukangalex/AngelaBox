package io.nekohasekai.sfa.compose.screen.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status

/**
 * 「应用提示记录」页：界面上一闪而过的提示（规则集跳过、订阅更新
 * 提示等）的全文留档，数据在 NoticeLogStore（本地文件）。从日志页
 * 的菜单进入。支持搜索 / 筛选 / 导出成文件（LogScreen 自带），清除
 * 按钮会一并清掉留档文件。
 */
@Composable
fun NoticeLogScreen(onBack: () -> Unit) {
    val viewModel: NoticeLogViewModel = viewModel()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadLogs()
    }

    LogScreen(
        serviceStatus = Status.Stopped,
        showStartFab = false,
        showStatusBar = false,
        title = context.getString(R.string.notice_log_title),
        viewModel = viewModel,
        showPause = false,
        showClear = true,
        showStatusInfo = false,
        emptyMessage = context.getString(R.string.notice_log_empty),
        saveFilePrefix = "notice_logs",
        onBack = onBack,
    )
}
