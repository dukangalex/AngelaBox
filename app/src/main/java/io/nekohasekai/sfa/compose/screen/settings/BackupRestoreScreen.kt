package io.nekohasekai.sfa.compose.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.BackupManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun softRelaunchApp(context: android.content.Context) {
    try {
        BoxService.stop()
    } catch (_: Exception) {
    }
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }
    android.os.Process.killProcess(android.os.Process.myPid())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var webdavUrl by remember { mutableStateOf(Settings.webdavUrl) }
    var webdavUser by remember { mutableStateOf(Settings.webdavUser) }
    var webdavPass by remember { mutableStateOf(Settings.webdavPassword) }
    var remoteFile by remember { mutableStateOf(Settings.webdavRemoteFile.ifEmpty { "backup.zip" }) }
    var probeState by remember { mutableIntStateOf(Settings.webdavProbeOk) }
    var probeDetail by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showWebdavEditor by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var restoreCompat by remember { mutableStateOf(Settings.restoreCompat) }

    fun persistProbe(ok: Boolean, detail: String?) {
        val value = if (ok) 1 else 0
        probeState = value
        probeDetail = detail
        Settings.webdavProbeOk = value
    }

    fun runProbe(silent: Boolean = false) {
        if (webdavUrl.isBlank()) {
            probeState = -1
            probeDetail = null
            Settings.webdavProbeOk = -1
            return
        }
        scope.launch {
            if (!silent) busy = true
            val result = withContext(Dispatchers.IO) {
                BackupManager.webdavProbe(webdavUrl, webdavUser, webdavPass, remoteFile)
            }
            val ok = result.getOrDefault(false)
            persistProbe(ok, result.exceptionOrNull()?.message)
            if (!silent) {
                busy = false
                snackbar.showSnackbar(
                    when {
                        ok -> "WebDAV 可访问"
                        result.exceptionOrNull() != null ->
                            "连通失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
                        else -> "WebDAV 不可用"
                    },
                )
            }
        }
    }

    LaunchedEffect(webdavUrl, webdavUser, webdavPass, remoteFile) {
        if (webdavUrl.isBlank()) {
            probeState = -1
            probeDetail = null
            Settings.webdavProbeOk = -1
            return@LaunchedEffect
        }
        while (true) {
            val result = withContext(Dispatchers.IO) {
                BackupManager.webdavProbe(webdavUrl, webdavUser, webdavPass, remoteFile)
            }
            persistProbe(result.getOrDefault(false), result.exceptionOrNull()?.message)
            delay(20_000)
        }
    }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "chainbox-backup.zip")
                    BackupManager.createBackupFile(context, tmp).getOrThrow()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(tmp).use { it.copyTo(out) }
                    } ?: error("无法写入文件")
                }
            }
            busy = false
            snackbar.showSnackbar(if (result.isSuccess) "本地备份完成" else "备份失败: ${result.exceptionOrNull()?.message}")
        }
    }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "chainbox-restore.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    } ?: error("无法读取文件")
                    BackupManager.restoreBackupFile(context, tmp, Settings.restoreCompat).getOrThrow()
                }
            }
            busy = false
            if (result.isSuccess) {
                snackbar.showSnackbar("恢复完成，正在重新加载…")
                delay(400)
                softRelaunchApp(context)
            } else {
                snackbar.showSnackbar("恢复失败: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("备份与恢复") },
            text = {
                Text(
                    "备份内容包括：设置、配置列表、各订阅/配置 JSON。\n\n" +
                        "恢复策略：\n" +
                        "· 覆盖：用备份完全替换当前配置、设置和订阅文件。\n" +
                        "· 兼容：备份里的配置与当前已有数据共存，不覆盖已有配置和设置。\n\n" +
                        "恢复后会自动重新加载应用。\n\n" +
                        "WebDAV 需填写可访问的 HTTPS 目录 URL 以及账号密码。" +
                            "连通性指示灯常驻自动检测，与实际上传使用同一套 GET/HEAD 接口：" +
                            "绿灯可写，红灯认证/网络失败。部分网盘请使用应用密码，而不是登录密码。",
                )
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
        )
    }

    if (showWebdavEditor) {
        AlertDialog(
            onDismissRequest = { showWebdavEditor = false },
            title = { Text("WebDAV 服务器") },
            text = {
                Column {
                    OutlinedTextField(
                        value = webdavUrl,
                        onValueChange = { webdavUrl = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = webdavUser,
                        onValueChange = { webdavUser = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = webdavPass,
                        onValueChange = { webdavPass = it },
                        label = { Text("密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = remoteFile,
                        onValueChange = { remoteFile = it },
                        label = { Text("远程文件名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        Settings.webdavUrl = webdavUrl
                        Settings.webdavUser = webdavUser
                        Settings.webdavPassword = webdavPass
                        Settings.webdavRemoteFile = remoteFile
                    }
                    showWebdavEditor = false
                    runProbe(silent = true)
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showWebdavEditor = false }) { Text("取消") }
            },
        )
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text("备份与恢复") },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = { showHelp = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "说明")
                }
            },
        )
    }
    val scaffoldPadding = LocalScaffoldPadding.current
    Box(Modifier.fillMaxSize().padding(scaffoldPadding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("远程", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ListItem(
                    headlineContent = {
                        Text(if (webdavUrl.isBlank()) "未配置 WebDAV" else webdavUrl)
                    },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val lamp = when (probeState) {
                                1 -> Color(0xFF2E7D32)
                                0 -> Color(0xFFC62828)
                                else -> Color(0xFF9E9E9E)
                            }
                            Box(
                                modifier = Modifier.size(10.dp).clip(CircleShape).background(lamp),
                            )
                            Spacer(Modifier.width(8.dp))
                            val status = when (probeState) {
                                1 -> "连通性：正常"
                                0 -> "连通性：失败${probeDetail?.let { " · $it" } ?: ""}"
                                else -> "连通性：未测试"
                            }
                            Text(status)
                        }
                    },
                    trailingContent = {
                        TextButton(onClick = { showWebdavEditor = true }) { Text("编辑") }
                    },
                )
                ListItem(
                    headlineContent = { Text("文件") },
                    supportingContent = { Text(remoteFile) },
                )
                ListItem(
                    headlineContent = { Text("备份") },
                    supportingContent = { Text("备份数据到 WebDAV") },
                    modifier = Modifier.clickable(enabled = !busy) {
                        scope.launch {
                            if (webdavUrl.isBlank()) {
                                snackbar.showSnackbar("请先配置 WebDAV")
                                return@launch
                            }
                            busy = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val tmp = File(context.cacheDir, "chainbox-backup.zip")
                                    BackupManager.createBackupFile(context, tmp).getOrThrow()
                                    BackupManager.webdavUpload(
                                        webdavUrl, webdavUser, webdavPass, remoteFile, tmp,
                                    ).getOrThrow()
                                }
                            }
                            persistProbe(result.isSuccess, result.exceptionOrNull()?.message)
                            busy = false
                            snackbar.showSnackbar(
                                if (result.isSuccess) "WebDAV 备份完成"
                                else "WebDAV 备份失败: ${result.exceptionOrNull()?.message}",
                            )
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("恢复") },
                    supportingContent = { Text("通过 WebDAV 恢复数据") },
                    modifier = Modifier.clickable(enabled = !busy) {
                        scope.launch {
                            if (webdavUrl.isBlank()) {
                                snackbar.showSnackbar("请先配置 WebDAV")
                                return@launch
                            }
                            busy = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val tmp = File(context.cacheDir, "chainbox-restore.zip")
                                    BackupManager.webdavDownload(
                                        webdavUrl, webdavUser, webdavPass, remoteFile, tmp,
                                    ).getOrThrow()
                                    BackupManager.restoreBackupFile(context, tmp, Settings.restoreCompat).getOrThrow()
                                }
                            }
                            persistProbe(result.isSuccess, result.exceptionOrNull()?.message)
                            busy = false
                            if (result.isSuccess) {
                                snackbar.showSnackbar("恢复完成，正在重新加载…")
                                delay(400)
                                softRelaunchApp(context)
                            } else {
                                snackbar.showSnackbar("WebDAV 恢复失败: ${result.exceptionOrNull()?.message}")
                            }
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("测试连通性") },
                    supportingContent = { Text("自动检测（与备份同一接口），绿灯正常、红灯失败") },
                    modifier = Modifier.clickable(enabled = !busy) { runProbe(silent = false) },
                )
            }

            Text(
                "本地",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ListItem(
                    headlineContent = { Text("备份") },
                    supportingContent = { Text("备份数据到本地") },
                    modifier = Modifier.clickable(enabled = !busy) {
                        createDoc.launch("AngelaBox-backup.zip")
                    },
                )
                ListItem(
                    headlineContent = { Text("恢复") },
                    supportingContent = { Text("通过文件恢复数据") },
                    modifier = Modifier.clickable(enabled = !busy) {
                        openDoc.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                )
            }

            Text(
                "选项",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ListItem(
                    headlineContent = { Text("恢复策略") },
                    trailingContent = {
                        Text(
                            if (restoreCompat) "兼容" else "覆盖",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    supportingContent = {
                        Text(
                            if (restoreCompat) {
                                "兼容模式：备份里的配置与当前已有数据共存，不覆盖已有配置和设置"
                            } else {
                                "覆盖模式：用备份完全替换当前配置、设置和订阅文件"
                            },
                        )
                    },
                    modifier = Modifier.clickable {
                        restoreCompat = !restoreCompat
                        Settings.restoreCompat = restoreCompat
                    },
                )
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
