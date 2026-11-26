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
import com.modulai.app.ui.audio.AudioScreen
import com.modulai.app.ui.code.CodeScreen
import com.modulai.app.ui.image.ImageScreen
import com.modulai.app.ui.rp.RpScreen
import com.modulai.app.ui.skills.SkillsScreen
import com.modulai.app.ui.video.VideoScreen

const val CHAT_ROUTE = "chat"
const val RESEARCH_ROUTE = "research"
const val RP_ROUTE = "rp"
const val CODE_ROUTE = "code"
const val SKILLS_ROUTE = "skills"
const val IMAGE_ROUTE = "image"
const val AUDIO_ROUTE = "audio"
const val VIDEO_ROUTE = "video"

/** Screens that draw their own top bar and replace the tab bar. */
private val FULL_SCREEN = setOf(CHAT_ROUTE, RESEARCH_ROUTE, RP_ROUTE, CODE_ROUTE, SKILLS_ROUTE, IMAGE_ROUTE, AUDIO_ROUTE, VIDEO_ROUTE)

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
            if (route !in FULL_SCREEN) NavigationBar {
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
        val pickModel = {
            nav.popBackStack()
            openTab(Tab.MODELS)
        }
        // Full-screen routes draw their own bars, so they must not get the tab padding.
        NavHost(
            nav,
            startDestination = Tab.MODULES.route,
            modifier = if (route in FULL_SCREEN) Modifier else Modifier.padding(padding),
        ) {
            composable(Tab.MODULES.route) { ModulesScreen(onOpen = { nav.navigate(it) }) }
            composable(RP_ROUTE) {
                RpScreen(
                    onBack = { nav.popBackStack() },
                    onStartChat = { card, persona, greetingIndex ->
                        container.chat.startRoleplay(card, persona, greetingIndex)
                        nav.navigate(CHAT_ROUTE)
                    },
                )
            }
            composable(CODE_ROUTE) {
                CodeScreen(container, onBack = { nav.popBackStack() }, onPickModel = pickModel)
            }
            composable(SKILLS_ROUTE) { SkillsScreen(container, onBack = { nav.popBackStack() }) }
            composable(VIDEO_ROUTE) { VideoScreen(onBack = { nav.popBackStack() }) }
            composable(IMAGE_ROUTE) { ImageScreen(container, onBack = { nav.popBackStack() }) }
            composable(AUDIO_ROUTE) { AudioScreen(container, onBack = { nav.popBackStack() }) }
            composable(RESEARCH_ROUTE) {
                ResearchScreen(container, onBack = { nav.popBackStack() }, onPickModel = pickModel)
            }
            composable(Tab.MODELS.route) { ModelsScreen(container) }
            composable(Tab.DEVICE.route) { DeviceScreen(container) }
            composable(CHAT_ROUTE) {
                ChatScreen(
                    container,
                    onBack = { nav.popBackStack() },
                    onPickModel = pickModel,
                )
            }
        }
    }
}
