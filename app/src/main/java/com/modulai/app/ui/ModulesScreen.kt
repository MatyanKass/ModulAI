package com.modulai.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.modulai.app.R

private data class ModuleCard(
    @StringRes val title: Int,
    @StringRes val desc: Int,
    val icon: ImageVector,
    /** Navigation route; null until the module exists. */
    val route: String? = null,
)

private val modules = listOf(
    ModuleCard(R.string.mod_chat, R.string.mod_chat_desc, Icons.Outlined.Chat, CHAT_ROUTE),
    ModuleCard(R.string.mod_rp, R.string.mod_rp_desc, Icons.Outlined.TheaterComedy, RP_ROUTE),
    ModuleCard(R.string.mod_research, R.string.mod_research_desc, Icons.Outlined.TravelExplore, RESEARCH_ROUTE),
    ModuleCard(R.string.mod_code, R.string.mod_code_desc, Icons.Outlined.Code),
    ModuleCard(R.string.mod_image, R.string.mod_image_desc, Icons.Outlined.Image),
    ModuleCard(R.string.mod_audio, R.string.mod_audio_desc, Icons.Outlined.GraphicEq),
    ModuleCard(R.string.mod_video, R.string.mod_video_desc, Icons.Outlined.Movie),
    ModuleCard(R.string.mod_lab, R.string.mod_lab_desc, Icons.Outlined.Science),
)

@Composable
fun ModulesScreen(onOpen: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(modules) { module ->
            val route = module.route
            ElevatedCard(
                modifier = if (route != null) Modifier.clickable { onOpen(route) } else Modifier.alpha(0.6f),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Icon(module.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(module.title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(module.desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (route == null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.soon),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}
