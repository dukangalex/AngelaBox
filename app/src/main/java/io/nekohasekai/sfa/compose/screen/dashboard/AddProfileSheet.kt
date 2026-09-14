package io.nekohasekai.sfa.compose.screen.dashboard

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.qr.QRScanSheet
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.compose.screen.configuration.ProfileImportHandler
import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.ktx.errorDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProfileSheet(
    onDismiss: () -> Unit,
    onOpenNewProfile: (NewProfileArgs) -> Unit,
    onProfileImported: (Profile) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val importHandler = remember { ProfileImportHandler(context) }

    var showQRScanSheet by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportName by remember { mutableStateOf<String?>(null) }
    var pendingQrsData by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val importFromFileLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            uri?.let {
                coroutineScope.launch {
                    when (val parseResult = importHandler.parseUri(uri)) {
                        is ProfileImportHandler.UriParseResult.Success -> {
                            withContext(Dispatchers.Main) {
                                pendingImportName = parseResult.name
                                pendingImportUri = uri
                                showImportConfirmDialog = true
                            }
                        }
                        is ProfileImportHandler.UriParseResult.Error -> {
                            withContext(Dispatchers.Main) {
                                context.errorDialogBuilder(Exception(parseResult.message)).show()
                            }
                        }
                    }
                }
            }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.add_profile),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            ListItem(
                modifier = Modifier.clickable {
                    importFromFileLauncher.launch("*/*")
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.FileUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                headlineContent = {
                    Text(stringResource(R.string.profile_add_import_file))
                },
                supportingContent = {
                    Text(stringResource(R.string.import_from_file_description))
                },
            )

            ListItem(
                modifier = Modifier.clickable {
                    showQRScanSheet = true
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                headlineContent = {
                    Text(stringResource(R.string.profile_add_scan_qr_code))
                },
                supportingContent = {
                    Text(stringResource(R.string.scan_qr_code_description))
                },
            )

            ListItem(
                modifier = Modifier.clickable {
                    onDismiss()
                    onOpenNewProfile(NewProfileArgs())
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.CreateNewFolder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                headlineContent = {
                    Text(stringResource(R.string.profile_add_create_manually))
                },
                supportingContent = {
                    Text(stringResource(R.string.create_new_profile_description))
                },
            )
        }
    }

    if (showImportConfirmDialog && pendingImportName != null) {
        AlertDialog(
            onDismissRequest = {
                showImportConfirmDialog = false
                pendingImportName = null
                pendingQrsData = null
                pendingImportUri = null
            },
            title = { Text(stringResource(R.string.import_profile_confirm_title)) },
            text = { Text(stringResource(R.string.import_profile_confirm_message, pendingImportName!!)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        val qrsData = pendingQrsData
                        val importUri = pendingImportUri
                        pendingImportName = null
                        pendingQrsData = null
                        pendingImportUri = null
                        coroutineScope.launch {
                            if (qrsData != null) {
                                when (val result = importHandler.importFromQRSData(qrsData)) {
                                    is ProfileImportHandler.ImportResult.Success -> {
                                        withContext(Dispatchers.Main) {
                                            onDismiss()
                                            onProfileImported(result.profile)
                                        }
                                    }
                                    is ProfileImportHandler.ImportResult.Error -> {
                                        withContext(Dispatchers.Main) {
                                            context.errorDialogBuilder(Exception(result.message)).show()
                                        }
                                    }
                                }
                            } else if (importUri != null) {
                                when (val result = importHandler.importFromUri(importUri)) {
                                    is ProfileImportHandler.ImportResult.Success -> {
                                        withContext(Dispatchers.Main) {
                                            onDismiss()
                                            onProfileImported(result.profile)
                                        }
                                    }
                                    is ProfileImportHandler.ImportResult.Error -> {
                                        withContext(Dispatchers.Main) {
                                            context.errorDialogBuilder(Exception(result.message)).show()
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.import_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        pendingImportName = null
                        pendingQrsData = null
                        pendingImportUri = null
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showQRScanSheet) {
        QRScanSheet(
            onDismiss = { showQRScanSheet = false },
            onScanResult = { result ->
                showQRScanSheet = false
                when (result) {
                    is QRScanResult.QRSData -> {
                        coroutineScope.launch {
                            when (val parseResult = importHandler.parseQRSData(result.data)) {
                                is ProfileImportHandler.QRSParseResult.Success -> {
                                    withContext(Dispatchers.Main) {
                                        pendingImportName = parseResult.name
                                        pendingQrsData = result.data
                                        showImportConfirmDialog = true
                                    }
                                }
                                is ProfileImportHandler.QRSParseResult.Error -> {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(Exception(parseResult.message)).show()
                                    }
                                }
                            }
                        }
                    }
                    is QRScanResult.RemoteProfile -> {
                        coroutineScope.launch {
                            when (val parseResult = importHandler.parseQRCode(result.uri.toString())) {
                                is ProfileImportHandler.QRCodeParseResult.RemoteProfile -> {
                                    withContext(Dispatchers.Main) {
                                        onDismiss()
                                        onOpenNewProfile(
                                            NewProfileArgs(
                                                importName = parseResult.name,
                                                importUrl = parseResult.url,
                                            ),
                                        )
                                    }
                                }
                                is ProfileImportHandler.QRCodeParseResult.LocalProfile -> {
                                    when (val importResult = importHandler.importFromQRCode(result.uri.toString())) {
                                        is ProfileImportHandler.ImportResult.Success -> {
                                            withContext(Dispatchers.Main) {
                                                onDismiss()
                                                onProfileImported(importResult.profile)
                                            }
                                        }
                                        is ProfileImportHandler.ImportResult.Error -> {
                                            withContext(Dispatchers.Main) {
                                                context.errorDialogBuilder(Exception(importResult.message)).show()
                                            }
                                        }
                                    }
                                }
                                is ProfileImportHandler.QRCodeParseResult.Error -> {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(Exception(parseResult.message)).show()
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}
