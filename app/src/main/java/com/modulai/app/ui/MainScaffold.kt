package com.modulai.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.modulai.app.AppContainer
import com.modulai.app.R

private const val CHAT_ROUTE = "chat"

private enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    MODULES("modules", R.string.tab_modules, Icons.Outlined.Apps),
    MODELS("models", R.string.tab_models, Icons.Outlined.Inventory2),
    DEVICE("device", R.string.tab_device, Icons.Outlined.Memory),
}

@Composable
fun MainScaffold(container: AppContainer) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    Scaffold(
        bottomBar = {
            if (route != CHAT_ROUTE) NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.destination?.route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        fun openTab(tab: Tab) = nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        // The chat screen draws its own bars, so it must not get the tab padding.
        NavHost(
            nav,
            startDestination = Tab.MODULES.route,
            modifier = if (route == CHAT_ROUTE) Modifier else Modifier.padding(padding),
        ) {
            composable(Tab.MODULES.route) { ModulesScreen(onOpenChat = { nav.navigate(CHAT_ROUTE) }) }
            composable(Tab.MODELS.route) { ModelsScreen(container) }
            composable(Tab.DEVICE.route) { DeviceScreen(container) }
            composable(CHAT_ROUTE) {
                ChatScreen(
                    container,
                    onBack = { nav.popBackStack() },
                    onPickModel = {
                        nav.popBackStack()
                        openTab(Tab.MODELS)
                    },
                )
            }
        }
    }
}
