package io.nekohasekai.sfa.compose.screen.profile

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private val nestedTween = tween<androidx.compose.ui.unit.IntOffset>(
    durationMillis = 180,
    easing = FastOutSlowInEasing,
)
private val nestedFadeIn = tween<Float>(durationMillis = 140, easing = FastOutSlowInEasing)
private val nestedFadeOut = tween<Float>(durationMillis = 110, easing = FastOutSlowInEasing)

@Composable
fun EditProfileRoute(profileId: Long, onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    if (profileId == -1L) {
        LaunchedEffect(Unit) {
            onNavigateBack()
        }
        return
    }

    val navController = rememberNavController()
    val sharedViewModel: EditProfileViewModel = viewModel()

    LaunchedEffect(profileId) {
        sharedViewModel.loadProfile(profileId)
    }

    NavHost(
        navController = navController,
        startDestination = "edit_profile",
        modifier = modifier,
    ) {
        composable(
            route = "edit_profile",
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = nestedTween,
                    ) + fadeIn(nestedFadeIn)
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = nestedTween,
                    ) + fadeOut(nestedFadeOut)
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = nestedTween,
                    ) + fadeIn(nestedFadeIn)
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = nestedTween,
                    ) + fadeOut(nestedFadeOut)
            },
        ) {
            EditProfileScreen(
                profileId = profileId,
                onNavigateBack = onNavigateBack,
                onNavigateToIconSelection = { currentIconId ->
                    navController.navigate("icon_selection/${Uri.encode(currentIconId ?: "null")}") {
                        launchSingleTop = true
                    }
                },
                onNavigateToEditContent = { isReadOnly ->
                    navController.navigate("edit_content/$isReadOnly") {
                        launchSingleTop = true
                    }
                },
                viewModel = sharedViewModel,
            )
        }

        composable(
            route = "icon_selection/{currentIconId}",
            arguments =
            listOf(
                navArgument("currentIconId") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = nestedTween,
                    ) + fadeIn(nestedFadeIn)
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = nestedTween,
                    ) + fadeOut(nestedFadeOut)
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = nestedTween,
                    ) + fadeIn(nestedFadeIn)
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = nestedTween,
                    ) + fadeOut(nestedFadeOut)
            },
        ) { backStackEntry ->
            val currentIconId =
                backStackEntry.arguments?.getString("currentIconId")
                    ?.takeIf { it != "null" }

            IconSelectionScreen(
                currentIconId = currentIconId,
                onIconSelected = { iconId ->
                    sharedViewModel.updateIcon(iconId)
                    navController.popBackStack("edit_profile", inclusive = false)
                },
                onNavigateBack = {
                    navController.popBackStack("edit_profile", inclusive = false)
                },
            )
        }

        composable(
            route = "edit_content/{isReadOnly}",
            arguments =
            listOf(
                navArgument("isReadOnly") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = nestedTween,
                    ) + fadeIn(nestedFadeIn)
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = nestedTween,
                    ) + fadeOut(nestedFadeOut)
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = nestedTween,
                    ) + fadeIn(nestedFadeIn)
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = nestedTween,
                    ) + fadeOut(nestedFadeOut)
            },
        ) { backStackEntry ->
            val isReadOnly = backStackEntry.arguments?.getBoolean("isReadOnly") ?: false

            EditProfileContentScreen(
                profileId = profileId,
                onNavigateBack = {
                    navController.popBackStack("edit_profile", inclusive = false)
                },
                isReadOnly = isReadOnly,
            )
        }
    }
}
