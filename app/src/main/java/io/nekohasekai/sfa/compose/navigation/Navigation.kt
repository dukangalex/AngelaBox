package io.nekohasekai.sfa.compose.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.nekohasekai.sfa.compose.screen.configuration.NewProfileScreen
import io.nekohasekai.sfa.compose.screen.connections.ConnectionDetailsRoute
import io.nekohasekai.sfa.compose.screen.connections.ConnectionsPage
import io.nekohasekai.sfa.compose.screen.connections.ConnectionsViewModel
import io.nekohasekai.sfa.compose.screen.dashboard.DashboardScreen
import io.nekohasekai.sfa.compose.screen.dashboard.DashboardViewModel
import io.nekohasekai.sfa.compose.screen.dashboard.GroupsCard
import io.nekohasekai.sfa.compose.screen.dashboard.groups.GroupsViewModel
import io.nekohasekai.sfa.compose.screen.log.LogScreen
import io.nekohasekai.sfa.compose.screen.log.LogViewModel
import io.nekohasekai.sfa.compose.screen.privilegesettings.PrivilegeSettingsManageScreen
import io.nekohasekai.sfa.compose.screen.profile.EditProfileRoute
import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScreen
import io.nekohasekai.sfa.compose.screen.settings.AppSettingsScreen
import io.nekohasekai.sfa.compose.screen.settings.BackupRestoreScreen
import io.nekohasekai.sfa.compose.screen.settings.CoreSettingsScreen
import io.nekohasekai.sfa.compose.screen.settings.EditRemoteServerScreen
import io.nekohasekai.sfa.compose.screen.settings.FDroidMirrorScreen
import io.nekohasekai.sfa.compose.screen.settings.PrivilegeSettingsScreen
import io.nekohasekai.sfa.compose.screen.settings.ProfileOverrideScreen
import io.nekohasekai.sfa.compose.screen.settings.RemoteControlScreen
import io.nekohasekai.sfa.compose.screen.settings.ServiceSettingsScreen
import io.nekohasekai.sfa.compose.screen.settings.SettingsScreen
import io.nekohasekai.sfa.compose.screen.settings.ThemeSettingsScreen
import io.nekohasekai.sfa.compose.screen.settings.TailscaleFontPickerScreen
import io.nekohasekai.sfa.compose.screen.settings.TailscaleGhosttyConfigEditorScreen
import io.nekohasekai.sfa.compose.screen.settings.TailscaleTerminalConfigScreen
import io.nekohasekai.sfa.compose.screen.settings.TailscaleThemePickerScreen
import io.nekohasekai.sfa.compose.screen.tools.ChainBuilderScreen
import io.nekohasekai.sfa.compose.screen.tools.CrashReportDetailScreen
import io.nekohasekai.sfa.compose.screen.tools.CrashReportFileContentScreen
import io.nekohasekai.sfa.compose.screen.tools.CrashReportListScreen
import io.nekohasekai.sfa.compose.screen.tools.CrashReportMetadataScreen
import io.nekohasekai.sfa.compose.screen.tools.NetworkQualityScreen
import io.nekohasekai.sfa.compose.screen.tools.OOMReportDetailScreen
import io.nekohasekai.sfa.compose.screen.tools.OOMReportFileContentScreen
import io.nekohasekai.sfa.compose.screen.tools.OOMReportListScreen
import io.nekohasekai.sfa.compose.screen.tools.OOMReportMetadataScreen
import io.nekohasekai.sfa.compose.screen.tools.OpenConnectStatusViewModel
import io.nekohasekai.sfa.compose.screen.tools.OpenVPNStatusViewModel
import io.nekohasekai.sfa.compose.screen.tools.OutboundPickerScreen
import io.nekohasekai.sfa.compose.screen.tools.PowerReportDetailScreen
import io.nekohasekai.sfa.compose.screen.tools.PowerReportFileContentScreen
import io.nekohasekai.sfa.compose.screen.tools.PowerReportListScreen
import io.nekohasekai.sfa.compose.screen.tools.PowerReportMetadataScreen
import io.nekohasekai.sfa.compose.screen.tools.STUNTestScreen
import io.nekohasekai.sfa.compose.screen.tools.ScriptListScreen
import io.nekohasekai.sfa.compose.screen.tools.TailscaleSSHSharedViewModel
import io.nekohasekai.sfa.compose.screen.tools.TailscaleStatusViewModel
import io.nekohasekai.sfa.compose.screen.tools.ToolsScreen
import io.nekohasekai.sfa.compose.screen.usbip.USBIPStatusViewModel
import io.nekohasekai.sfa.constant.Status

private val nestedTween = tween<androidx.compose.ui.unit.IntOffset>(
    durationMillis = 180,
    easing = FastOutSlowInEasing,
)
private val nestedFadeIn = tween<Float>(durationMillis = 140, easing = FastOutSlowInEasing)
private val nestedFadeOut = tween<Float>(durationMillis = 110, easing = FastOutSlowInEasing)
private val fadeTween = tween<Float>(durationMillis = 90, easing = FastOutSlowInEasing)
private val fadeOutTween = tween<Float>(durationMillis = 70, easing = FastOutSlowInEasing)

private val slideInFromRight: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.EnterTransition = {
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = nestedTween) + fadeIn(nestedFadeIn)
}
private val slideOutToRight: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.ExitTransition = {
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = nestedTween) + fadeOut(nestedFadeOut)
}
private val slideInFromLeft: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.EnterTransition = {
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = nestedTween) + fadeIn(nestedFadeIn)
}
private val slideOutToLeft: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.ExitTransition = {
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = nestedTween) + fadeOut(nestedFadeOut)
}
private val tabEnter: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.EnterTransition = {
    fadeIn(fadeTween)
}
private val tabExit: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.ExitTransition = {
    fadeOut(fadeOutTween)
}

@Composable
fun NavHost(
    navController: NavHostController,
    serviceStatus: Status = Status.Stopped,
    showStartFab: Boolean = false,
    showStatusBar: Boolean = false,
    newProfileArgs: NewProfileArgs = NewProfileArgs(),
    onClearNewProfileArgs: () -> Unit = {},
    onOpenNewProfile: (NewProfileArgs) -> Unit = {},
    dashboardViewModel: DashboardViewModel? = null,
    logViewModel: LogViewModel? = null,
    groupsViewModel: GroupsViewModel? = null,
    connectionsViewModel: ConnectionsViewModel? = null,
    tailscaleStatusViewModel: TailscaleStatusViewModel? = null,
    tailscaleSSHSharedViewModel: TailscaleSSHSharedViewModel? = null,
    usbIPStatusViewModel: USBIPStatusViewModel? = null,
    openConnectStatusViewModel: OpenConnectStatusViewModel? = null,
    openVPNStatusViewModel: OpenVPNStatusViewModel? = null,
    modifier: Modifier = Modifier,
) {
    androidx.navigation.compose.NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        modifier = modifier,
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) {
        composable(
            Screen.Dashboard.route,
            enterTransition = tabEnter,
            exitTransition = tabExit,
            popEnterTransition = tabEnter,
            popExitTransition = tabExit,
        ) {
            if (dashboardViewModel != null) {
                DashboardScreen(
                    serviceStatus = serviceStatus,
                    showStartFab = showStartFab,
                    showStatusBar = false,
                    onOpenNewProfile = onOpenNewProfile,
                    onOpenChainBuilder = { navController.navigate("tools/chain_builder") },
                    viewModel = dashboardViewModel,
                )
            } else {
                DashboardScreen(
                    serviceStatus = serviceStatus,
                    showStartFab = showStartFab,
                    showStatusBar = false,
                    onOpenNewProfile = onOpenNewProfile,
                    onOpenChainBuilder = { navController.navigate("tools/chain_builder") },
                )
            }
        }
        composable(
            Screen.Log.route,
            enterTransition = tabEnter,
            exitTransition = tabExit,
            popEnterTransition = tabEnter,
            popExitTransition = tabExit,
        ) {
            LogScreen(serviceStatus = serviceStatus, showStartFab = showStartFab, showStatusBar = showStatusBar)
        }
        composable(
            Screen.Groups.route,
            enterTransition = tabEnter,
            exitTransition = tabExit,
            popEnterTransition = tabEnter,
            popExitTransition = tabExit,
        ) {
            if (groupsViewModel != null) {
                GroupsCard(
                    serviceStatus = serviceStatus,
                    viewModel = groupsViewModel,
                    showTopBar = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                GroupsCard(serviceStatus = serviceStatus, showTopBar = true, modifier = Modifier.fillMaxSize())
            }
        }
        composable(
            Screen.Connections.route,
            enterTransition = tabEnter,
            exitTransition = tabExit,
            popEnterTransition = tabEnter,
            popExitTransition = tabExit,
        ) {
            if (connectionsViewModel != null) {
                ConnectionsPage(
                    serviceStatus = serviceStatus,
                    viewModel = connectionsViewModel,
                    showTitle = false,
                    showTopBar = true,
                    onConnectionClick = { connectionId ->
                        navController.navigate("connections/detail/${Uri.encode(connectionId)}")
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ConnectionsPage(
                    serviceStatus = serviceStatus,
                    showTitle = false,
                    showTopBar = true,
                    onConnectionClick = { connectionId ->
                        navController.navigate("connections/detail/${Uri.encode(connectionId)}")
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composable(ProfileRoutes.NewProfile) {
            DisposableEffect(Unit) { onDispose { onClearNewProfileArgs() } }
            NewProfileScreen(
                importName = newProfileArgs.importName,
                importUrl = newProfileArgs.importUrl,
                qrsData = newProfileArgs.qrsData,
                onNavigateBack = {
                    onClearNewProfileArgs()
                    navController.navigateUp()
                },
                onProfileCreated = { profileId ->
                    onClearNewProfileArgs()
                    navController.navigate(ProfileRoutes.editProfile(profileId)) {
                        popUpTo(ProfileRoutes.NewProfile) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = ProfileRoutes.EditProfile,
            arguments = listOf(navArgument("profileId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getLong("profileId") ?: -1L
            EditProfileRoute(
                profileId = profileId,
                onNavigateBack = { navController.navigateUp() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable("connections/detail/{connectionId}") { backStackEntry ->
            val connectionId = backStackEntry.arguments?.getString("connectionId")
            if (connectionId != null) {
                if (connectionsViewModel != null) {
                    ConnectionDetailsRoute(
                        connectionId = connectionId,
                        serviceStatus = serviceStatus,
                        viewModel = connectionsViewModel,
                        onBack = { navController.navigateUp() },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ConnectionDetailsRoute(
                        connectionId = connectionId,
                        serviceStatus = serviceStatus,
                        onBack = { navController.navigateUp() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composable(
            Screen.Tools.route,
            enterTransition = tabEnter,
            exitTransition = tabExit,
            popEnterTransition = tabEnter,
            popExitTransition = tabExit,
        ) {
            val tailscaleViewModel: TailscaleStatusViewModel = tailscaleStatusViewModel ?: viewModel()
            val sshSharedViewModel: TailscaleSSHSharedViewModel = tailscaleSSHSharedViewModel ?: viewModel()
            val usbIPViewModel: USBIPStatusViewModel = usbIPStatusViewModel ?: viewModel()
            val openConnectViewModel: OpenConnectStatusViewModel = openConnectStatusViewModel ?: viewModel()
            val openVPNViewModel: OpenVPNStatusViewModel = openVPNStatusViewModel ?: viewModel()
            ToolsScreen(
                navController = navController,
                tailscaleViewModel = tailscaleViewModel,
                sshSharedViewModel = sshSharedViewModel,
                usbIPViewModel = usbIPViewModel,
                openConnectViewModel = openConnectViewModel,
                openVPNViewModel = openVPNViewModel,
                showStatusBar = showStatusBar,
            )
        }
        composable(route = "tools/chain_builder", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            ChainBuilderScreen(navController = navController, serviceStatus = serviceStatus)
        }
        composable(route = "tools/scripts", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            ScriptListScreen(navController = navController, serviceStatus = serviceStatus)
        }
        composable(route = "tools/network_quality", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            NetworkQualityScreen(navController = navController, serviceStatus = serviceStatus)
        }
        composable(route = "tools/stun_test", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            STUNTestScreen(navController = navController, serviceStatus = serviceStatus)
        }
        composable(route = "tools/outbound_picker", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            OutboundPickerScreen(navController = navController, selectedOutbound = "")
        }
        composable(
            route = "tools/outbound_picker/{selectedOutbound}",
            arguments = listOf(navArgument("selectedOutbound") { type = NavType.StringType; defaultValue = "" }),
            enterTransition = slideInFromRight,
            exitTransition = slideOutToLeft,
            popEnterTransition = slideInFromLeft,
            popExitTransition = slideOutToRight,
        ) { be ->
            val selected = Uri.decode(be.arguments?.getString("selectedOutbound").orEmpty())
            OutboundPickerScreen(navController = navController, selectedOutbound = selected)
        }
        composable(
            route = "settings",
            enterTransition = tabEnter,
            exitTransition = tabExit,
            popEnterTransition = tabEnter,
            popExitTransition = tabExit,
        ) {
            SettingsScreen(navController = navController)
        }
        composable(route = "settings/app", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            AppSettingsScreen(navController = navController)
        }
        composable(route = "settings/theme", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            ThemeSettingsScreen(navController = navController)
        }
        composable(route = "settings/core", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            CoreSettingsScreen(navController = navController)
        }
        composable(route = "settings/service", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            ServiceSettingsScreen(navController = navController, serviceStatus = serviceStatus)
        }
        composable(route = "settings/profile_override", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            ProfileOverrideScreen(navController = navController, serviceStatus = serviceStatus)
        }
        composable(route = "settings/profile_override/manage", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            PerAppProxyScreen(onBack = { navController.navigateUp() }, serviceStatus = serviceStatus)
        }
        composable(route = "settings/backup", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            BackupRestoreScreen(navController = navController)
        }
        composable(route = "settings/remote_control", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            RemoteControlScreen(navController = navController)
        }
        composable(route = "settings/remote_control/new", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            EditRemoteServerScreen(navController = navController)
        }
        composable(
            route = "settings/remote_control/edit/{serverId}",
            arguments = listOf(navArgument("serverId") { type = NavType.LongType }),
            enterTransition = slideInFromRight,
            exitTransition = slideOutToLeft,
            popEnterTransition = slideInFromLeft,
            popExitTransition = slideOutToRight,
        ) { backStackEntry ->
            val serverId = backStackEntry.arguments?.getLong("serverId") ?: -1L
            EditRemoteServerScreen(navController = navController, serverId = serverId)
        }
        composable(route = "settings/privilege", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            PrivilegeSettingsScreen(navController = navController)
        }
        composable(route = "settings/privilege/manage", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            PrivilegeSettingsManageScreen(onBack = { navController.navigateUp() })
        }
        composable(route = "settings/fdroid_mirror", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            FDroidMirrorScreen(navController = navController)
        }
        composable(route = "settings/tailscale/terminal_config", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            TailscaleTerminalConfigScreen(navController = navController)
        }
        composable(
            route = "settings/tailscale/theme_picker/{isDark}",
            arguments = listOf(navArgument("isDark") { type = NavType.BoolType }),
            enterTransition = slideInFromRight,
            exitTransition = slideOutToLeft,
            popEnterTransition = slideInFromLeft,
            popExitTransition = slideOutToRight,
        ) { backStackEntry ->
            val isDark = backStackEntry.arguments?.getBoolean("isDark") ?: false
            TailscaleThemePickerScreen(navController = navController, isDark = isDark)
        }
        composable(
            route = "settings/tailscale/config_editor/{isDark}",
            arguments = listOf(navArgument("isDark") { type = NavType.BoolType }),
            enterTransition = slideInFromRight,
            exitTransition = slideOutToLeft,
            popEnterTransition = slideInFromLeft,
            popExitTransition = slideOutToRight,
        ) { backStackEntry ->
            val isDark = backStackEntry.arguments?.getBoolean("isDark") ?: false
            TailscaleGhosttyConfigEditorScreen(navController = navController, isDark = isDark)
        }
        composable(route = "settings/tailscale/font_picker", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            TailscaleFontPickerScreen(navController = navController)
        }
        composable(route = "tools/crash_report", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            CrashReportListScreen(navController = navController)
        }
        composable(route = "tools/crash_report/{reportId}", arguments = listOf(navArgument("reportId") { type = NavType.StringType }), enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) { be ->
            CrashReportDetailScreen(navController = navController, reportId = be.arguments?.getString("reportId").orEmpty())
        }
        composable(route = "tools/crash_report/{reportId}/metadata", arguments = listOf(navArgument("reportId") { type = NavType.StringType }), enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) { be ->
            CrashReportMetadataScreen(navController = navController, reportId = be.arguments?.getString("reportId").orEmpty())
        }
        composable(route = "tools/crash_report/{reportId}/file/{fileKind}", arguments = listOf(navArgument("reportId") { type = NavType.StringType }, navArgument("fileKind") { type = NavType.StringType }), enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) { be ->
            CrashReportFileContentScreen(navController = navController, reportId = be.arguments?.getString("reportId").orEmpty(), fileKind = be.arguments?.getString("fileKind").orEmpty())
        }
        composable(route = "tools/oom_report", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            OOMReportListScreen(navController = navController)
        }
        composable(route = "tools/oom_report/{reportId}", arguments = listOf(navArgument("reportId") { type = NavType.StringType }), enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) { be ->
            OOMReportDetailScreen(navController = navController, reportId = be.arguments?.getString("reportId").orEmpty())
        }
        composable(route = "tools/oom_report/{reportId}/metadata", arguments = listOf(navArgument("reportId") { type = NavType.StringType }), enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) { be ->
            OOMReportMetadataScreen(navController = navController, reportId = be.arguments?.getString("reportId").orEmpty())
        }
        composable(route = "tools/oom_report/{reportId}/file/{fileKind}", arguments = listOf(navArgument("reportId") { type = NavType.StringType }, navArgument("fileKind") { type = NavType.StringType }), enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) { be ->
            OOMReportFileContentScreen(navController = navController, reportId = be.arguments?.getString("reportId").orEmpty(), fileKind = be.arguments?.getString("fileKind").orEmpty())
        }
        composable(route = "tools/power_report", enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            PowerReportListScreen(navController = navController)
        }
        composable(route = "tools/power_report/{reportId}", arguments = listOf(navArgument("reportId") { type = NavType.StringType }), enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) { be ->
            PowerReportDetailScreen(navController = navController, reportId = be.arguments?.getString("reportId").orEmpty())
        }
        composable(route = "tools/power_report/{reportId}/metadata", arguments = listOf(navArgument("reportId") { type = NavType.StringType }), enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) { be ->
            PowerReportMetadataScreen(navController = navController, reportId = be.arguments?.getString("reportId").orEmpty())
        }
        composable(route = "tools/power_report/{reportId}/file/{fileKind}", arguments = listOf(navArgument("reportId") { type = NavType.StringType }, navArgument("fileKind") { type = NavType.StringType }), enterTransition = slideInFromRight, exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) { be ->
            PowerReportFileContentScreen(navController = navController, reportId = be.arguments?.getString("reportId").orEmpty(), fileKind = be.arguments?.getString("fileKind").orEmpty())
        }
    }
}
