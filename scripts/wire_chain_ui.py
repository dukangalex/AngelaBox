#!/usr/bin/env python3
from pathlib import Path

def main():
    nav = Path('app/src/main/java/io/nekohasekai/sfa/compose/navigation/Navigation.kt')
    t = nav.read_text()
    if 'ChainBuilderScreen' not in t:
        t = t.replace(
            'import io.nekohasekai.sfa.compose.screen.tools.OutboundPickerScreen',
            'import io.nekohasekai.sfa.compose.screen.tools.ChainBuilderScreen\nimport io.nekohasekai.sfa.compose.screen.tools.OutboundPickerScreen',
        )
    if 'tools/chain_builder' not in t:
        idx = t.find('route = "tools/stun_test"')
        if idx < 0:
            raise SystemExit('stun_test route not found')
        insert_at = t.find('route = "tools/usbip/{serverTag}"', idx)
        if insert_at < 0:
            insert_at = t.find('route = "tools/outbound_picker', idx)
        if insert_at < 0:
            raise SystemExit('insert point not found')
        insert_at = t.rfind('composable(', 0, insert_at)
        block = '''
        composable(
            route = "tools/chain_builder",
            enterTransition = slideInFromRight,
            exitTransition = slideOutToLeft,
            popEnterTransition = slideInFromLeft,
            popExitTransition = slideOutToRight,
        ) {
            ChainBuilderScreen(navController = navController)
        }

'''
        t = t[:insert_at] + block + t[insert_at:]
    nav.write_text(t)
    print('nav ok')

    tools = Path('app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ToolsScreen.kt')
    t = tools.read_text()
    if 'tools/chain_builder' not in t:
        idx = t.find('stringResource(R.string.network_quality)')
        if idx < 0:
            raise SystemExit('network_quality not found')
        card_start = t.rfind('Card(', 0, idx)
        insert = '''
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        "链式代理生成器",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(
                        "点选节点顺序，生成 chain 配置",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Route,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { navController.navigate("tools/chain_builder") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

'''
        t = t[:card_start] + insert + t[card_start:]
        tools.write_text(t)
    print('tools ok')

if __name__ == '__main__':
    main()
