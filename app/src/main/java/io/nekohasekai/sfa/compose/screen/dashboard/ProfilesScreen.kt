package io.nekohasekai.sfa.compose.screen.dashboard

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.PullToPopContainer
import io.nekohasekai.sfa.compose.component.qr.QRCodeDialog
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.compose.screen.profile.ProfileScriptBinderDialog
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.compose.util.QRCodeGenerator
import io.nekohasekai.sfa.compose.util.RelativeTimeFormatter
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.ktx.shareBasename
import io.nekohasekai.sfa.ktx.shareProfile
import io.nekohasekai.sfa.utils.SubscriptionInfo
import io.nekohasekai.sfa.utils.SubscriptionInfoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    navController: NavController,
    onOpenNewProfile: (NewProfileArgs) -> Unit,
    viewModel: DashboardViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var showQRCodeDialog by remember { mutableStateOf(false) }
    var qrCodeProfile by remember { mutableStateOf<Profile?>(null) }
    var scriptProfile by remember { mutableStateOf<Profile?>(null) }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_configuration)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                IconButton(onClick = { viewModel.updateAllRemoteProfiles() }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.update_profile))
                }
            },
        )
    }

    PullToPopContainer(
        enabled = true,
        releaseHint = stringResource(R.string.pull_release_up),
        onPop = { navController.navigateUp() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(LocalScaffoldPadding.current),
        ) {
            if (uiState.profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_profiles),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.profiles, key = { it.id }) { profile ->
                        val info = remember(profile.id, profile.typed.lastUpdated) {
                            SubscriptionInfoStore.get(context, profile.id)
                        }
                        ProfileListCard(
                            profile = profile,
                            selected = profile.id == uiState.selectedProfileId,
                            info = info,
                            onClick = { viewModel.selectProfile(profile.id) },
                            onEdit = { viewModel.editProfile(profile) },
                            onScripts = { scriptProfile = profile },
                            onProviders = { viewModel.openProviders(profile) },
                            onShare = {
                                scope.launch(Dispatchers.IO) {
                                    runCatching { context.shareProfile(profile) }
                                }
                            },
                            onShareURL = {
                                qrCodeProfile = profile
                                showQRCodeDialog = true
                            },
                            onDelete = { viewModel.deleteProfile(profile) },
                        )
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.profiles_add)) },
            )
        }
    }

    if (showAdd) {
        AddProfileSheet(
            onDismiss = { showAdd = false },
            onOpenNewProfile = { args ->
                showAdd = false
                onOpenNewProfile(args)
            },
            onProfileImported = { profile ->
                showAdd = false
                viewModel.editProfile(profile)
            },
        )
    }

    if (showQRCodeDialog && qrCodeProfile != null) {
        val profile = qrCodeProfile!!
        val link = remember(profile) {
            Libbox.generateRemoteProfileImportLink(
                profile.name,
                profile.typed.remoteURL,
            )
        }
        val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
        val qrBitmap = QRCodeGenerator.rememberPrimaryBitmap(link, backgroundColor = surfaceColor)
        QRCodeDialog(
            bitmap = qrBitmap,
            onDismiss = {
                showQRCodeDialog = false
                qrCodeProfile = null
            },
        )
    }

    val bindTarget = scriptProfile
    if (bindTarget != null) {
        ProfileScriptBinderDialog(
            profileId = bindTarget.id,
            profileName = bindTarget.name,
            onDismiss = { scriptProfile = null },
        )
    }
}

@Composable
private fun ProfileListCard(
    profile: Profile,
    selected: Boolean,
    info: SubscriptionInfo?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onScripts: () -> Unit,
    onProviders: () -> Unit,
    onShare: () -> Unit,
    onShareURL: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val updated = RelativeTimeFormatter.format(context, profile.typed.lastUpdated)
    val expire = info?.expireLabel()
    val title = if (expire.isNullOrBlank()) profile.name else "${profile.name}  ·  $expire"
    val trafficLine = when {
        profile.typed.type != TypedProfile.Type.Remote -> null
        info == null || info.unlimited -> stringResource(R.string.profile_traffic_none)
        info.hasQuota -> stringResource(
            R.string.profile_traffic_used,
            info.usedLabel(),
            info.totalLabel(),
        )
        else -> stringResource(R.string.profile_traffic_none)
    }
    val second = when {
        profile.typed.type != TypedProfile.Type.Remote ->
            stringResource(R.string.profile_type_local) + " · " + updated
        info == null || info.unlimited ->
            stringResource(R.string.profile_traffic_unlimited) + " · " + updated
        info.hasQuota ->
            stringResource(R.string.profile_traffic_used, info.usedLabel(), info.totalLabel()) +
                " · " + updated
        else ->
            stringResource(R.string.profile_type_remote) + " · " + updated
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trafficLine != null) {
                    Text(
                        text = trafficLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (info != null && info.hasQuota) {
                    LinearProgressIndicator(
                        progress = { info.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
                Text(
                    text = second,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProfileOverflowMenu(
                profile = profile,
                onEdit = onEdit,
                onScripts = onScripts,
                onProviders = onProviders,
                onShare = onShare,
                onShareURL = onShareURL,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
internal fun ProfileOverflowMenu(
    profile: Profile,
    onEdit: () -> Unit,
    onScripts: () -> Unit,
    onProviders: () -> Unit,
    onShare: () -> Unit,
    onShareURL: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var expandedShareSubmenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val profileData = createProfileContent(profile)
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(profileData)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.success_profile_saved),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "${context.getString(R.string.failed_save_profile)}: ${e.message}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    Box {
        IconButton(
            onClick = {
                showMenu = true
                expandedShareSubmenu = false
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more_options),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = {
                showMenu = false
                expandedShareSubmenu = false
            },
            modifier = Modifier.widthIn(min = 200.dp),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit)) },
                onClick = {
                    showMenu = false
                    onEdit()
                },
                leadingIcon = {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.overlay_scripts)) },
                onClick = {
                    showMenu = false
                    onScripts()
                },
                leadingIcon = {
                    Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rule_providers)) },
                onClick = {
                    showMenu = false
                    onProviders()
                },
                leadingIcon = {
                    Icon(Icons.Default.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_share)) },
                onClick = { expandedShareSubmenu = !expandedShareSubmenu },
                leadingIcon = {
                    Icon(Icons.Default.IosShare, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingIcon = {
                    Icon(
                        imageVector = if (expandedShareSubmenu) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                },
            )
            if (expandedShareSubmenu) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.save_as_file)) },
                    onClick = {
                        showMenu = false
                        saveFileLauncher.launch("${shareBasename(profile.name)}.bpf")
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 24.dp),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share_as_file)) },
                    onClick = {
                        showMenu = false
                        onShare()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.IosShare,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 24.dp),
                        )
                    },
                )
                if (profile.typed.type == TypedProfile.Type.Remote) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_share_url)) },
                        onClick = {
                            showMenu = false
                            onShareURL()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.QrCode2,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 24.dp),
                            )
                        },
                    )
                }
            }
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.menu_delete), color = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    showMenu = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
            )
        }
    }
}

private fun createProfileContent(profile: Profile): ByteArray {
    val content = io.nekohasekai.libbox.ProfileContent()
    content.name = profile.name
    when (profile.typed.type) {
        TypedProfile.Type.Local -> content.type = Libbox.ProfileTypeLocal
        TypedProfile.Type.Remote -> content.type = Libbox.ProfileTypeRemote
    }
    content.config = java.io.File(profile.typed.path).readText()
    content.remotePath = profile.typed.remoteURL
    content.autoUpdate = profile.typed.autoUpdate
    content.autoUpdateInterval = profile.typed.autoUpdateInterval
    content.lastUpdated = profile.typed.lastUpdated.time
    return content.encode()
}
