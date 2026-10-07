package io.nekohasekai.sfa.compose.screen.settings

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.Appearance
import io.nekohasekai.sfa.compose.theme.ThemeSeed
import io.nekohasekai.sfa.compose.theme.ThemeSeeds
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThemeSettingsScreen(navController: NavController) {
    Appearance.ensureLoaded()
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.theme_settings)) },
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            Card(colors = cardColors, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.DarkMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            stringResource(R.string.theme_mode),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ModeChip(
                            modifier = Modifier.weight(1f),
                            selected = Appearance.mode == "system",
                            icon = Icons.Outlined.PhoneAndroid,
                            label = stringResource(R.string.theme_mode_system),
                            onClick = { Appearance.applyMode("system") },
                        )
                        ModeChip(
                            modifier = Modifier.weight(1f),
                            selected = Appearance.mode == "light",
                            icon = Icons.Outlined.LightMode,
                            label = stringResource(R.string.theme_mode_light),
                            onClick = { Appearance.applyMode("light") },
                        )
                        ModeChip(
                            modifier = Modifier.weight(1f),
                            selected = Appearance.mode == "dark",
                            icon = Icons.Outlined.DarkMode,
                            label = stringResource(R.string.theme_mode_dark),
                            onClick = { Appearance.applyMode("dark") },
                        )
                    }
                }
            }

            Card(colors = cardColors, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            stringResource(R.string.theme_color),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ThemeSeeds.forEach { seed ->
                            if (seed.id == "dynamic" && Build.VERSION.SDK_INT < 31) return@forEach
                            ColorSwatch(
                                seed = seed,
                                selected = Appearance.seed == seed.id,
                                wallpaper = seed.id == "dynamic",
                                onClick = { Appearance.applySeed(seed.id) },
                            )
                        }
                    }
                }
            }

            Card(colors = cardColors, shape = RoundedCornerShape(20.dp)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.theme_pure_black)) },
                    supportingContent = { Text(stringResource(R.string.theme_pure_black_summary)) },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Contrast,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = Appearance.pureBlack,
                            onCheckedChange = Appearance::applyPureBlack,
                            enabled = Appearance.mode != "light",
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
}

@Composable
private fun ModeChip(
    modifier: Modifier,
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg)
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun ColorSwatch(
    seed: ThemeSeed,
    selected: Boolean,
    wallpaper: Boolean,
    onClick: () -> Unit,
) {
    val outline = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
        ) {
            val w = size.width
            val h = size.height
            val halfW = w / 2f
            val halfH = h / 2f
            drawRect(seed.swatch, topLeft = Offset.Zero, size = Size(halfW, halfH))
            drawRect(seed.secondary, topLeft = Offset(halfW, 0f), size = Size(halfW, halfH))
            drawRect(seed.tertiary, topLeft = Offset(0f, halfH), size = Size(halfW, halfH))
            drawRect(
                Color.White.copy(alpha = 0.28f),
                topLeft = Offset(halfW, halfH),
                size = Size(halfW, halfH),
            )
        }
        when {
            selected -> Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White)
            wallpaper -> Icon(
                Icons.Outlined.Wallpaper,
                contentDescription = stringResource(R.string.theme_color_dynamic),
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
