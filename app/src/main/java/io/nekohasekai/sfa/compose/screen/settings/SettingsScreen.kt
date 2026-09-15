package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_settings)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
        )
    }
    val scaffoldPadding = LocalScaffoldPadding.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
            .verticalScroll(rememberScrollState()),
    ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.title_app_settings), style = MaterialTheme.typography.bodyLarge) },
                leadingContent = {
                    Icon(Icons.Outlined.Apps, null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable { navController.navigate("settings/app") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.theme_settings), style = MaterialTheme.typography.bodyLarge) },
                leadingContent = {
                    Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable { navController.navigate("settings/theme") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.core), style = MaterialTheme.typography.bodyLarge) },
                leadingContent = {
                    Icon(Icons.Outlined.Memory, null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable { navController.navigate("settings/core") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.service), style = MaterialTheme.typography.bodyLarge) },
                leadingContent = {
                    Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable { navController.navigate("settings/service") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.profile_override), style = MaterialTheme.typography.bodyLarge) },
                leadingContent = {
                    Icon(Icons.Outlined.FilterAlt, null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable { navController.navigate("settings/profile_override") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.backup_and_restore), style = MaterialTheme.typography.bodyLarge) },
                leadingContent = {
                    Icon(Icons.Outlined.SettingsRemote, null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable { navController.navigate("settings/backup") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.remote_control), style = MaterialTheme.typography.bodyLarge) },
                leadingContent = {
                    Icon(Icons.Outlined.SettingsRemote, null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable { navController.navigate("settings/remote_control") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.privilege_settings), style = MaterialTheme.typography.bodyLarge) },
                leadingContent = {
                    Icon(Icons.Outlined.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable { navController.navigate("settings/privilege") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
}
