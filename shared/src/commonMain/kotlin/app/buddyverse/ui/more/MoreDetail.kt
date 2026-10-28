package app.buddyverse.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.buddyverse.design.Icons
import app.buddyverse.i18n.S
import app.buddyverse.i18n.more
import app.buddyverse.ui.MorePage

/** Routes a pushed More page to its file. Scaffold-owned: pages are added here and in [MorePage] only by the integrator. */
@Composable
fun MoreDetail(page: MorePage) = when (page) {
    MorePage.Skills -> SkillsPage()
    MorePage.Tools -> ToolsPage()
    MorePage.Mcp -> McpPage()
    MorePage.Connectors -> ConnectorsPage()
    MorePage.Plugins -> PluginsPage()
    MorePage.Memory -> MemoryPage()
    MorePage.Projects -> ProjectsPage()
    MorePage.Models -> ModelsPage()
    MorePage.Messaging -> MessagingPage()
    MorePage.Kanban -> KanbanPage()
    MorePage.Webhooks -> WebhooksPage()
    MorePage.Processes -> ProcessesPage()
    MorePage.Files -> FilesPage()
    MorePage.Checkpoints -> CheckpointsPage()
    MorePage.Artifacts -> ArtifactsPage()
    MorePage.Insights -> InsightsPage()
    MorePage.Status -> StatusPage()
    MorePage.Settings -> SettingsPage()
    MorePage.Gallery -> GalleryPage()
}

/** Tile and header title of a page. */
val MorePage.label: String
    @Composable get() = when (this) {
        MorePage.Skills -> S.current.skills
        MorePage.Tools -> S.current.tools
        MorePage.Mcp -> S.current.mcp
        MorePage.Connectors -> S.current.connectors
        MorePage.Plugins -> S.current.plugins
        MorePage.Memory -> S.current.memory
        MorePage.Projects -> S.current.projects
        MorePage.Models -> S.current.models
        MorePage.Messaging -> S.current.messaging
        MorePage.Kanban -> S.current.kanban
        MorePage.Webhooks -> S.current.webhooks
        MorePage.Processes -> S.current.processes
        MorePage.Files -> S.more.files
        MorePage.Checkpoints -> S.current.checkpoints
        MorePage.Artifacts -> S.current.artifacts
        MorePage.Insights -> S.current.insights
        MorePage.Status -> S.current.gatewayStatus
        MorePage.Settings -> S.current.settings
        MorePage.Gallery -> S.current.buddyGallery
    }

/** Tile icon of a page (filled variant: `Icons.<name>Filled`). */
val MorePage.icon: ImageVector get() = when (this) {
    MorePage.Skills -> Icons.autoAwesome
    MorePage.Tools -> Icons.build
    MorePage.Mcp -> Icons.hub
    MorePage.Connectors -> Icons.cable
    MorePage.Plugins -> Icons.extension
    MorePage.Memory -> Icons.psychology
    MorePage.Projects -> Icons.folder
    MorePage.Models -> Icons.tune
    MorePage.Messaging -> Icons.chat
    MorePage.Kanban -> Icons.viewKanban
    MorePage.Webhooks -> Icons.link
    MorePage.Processes -> Icons.terminal
    MorePage.Files -> Icons.description
    MorePage.Checkpoints -> Icons.history
    MorePage.Artifacts -> Icons.inventory2
    MorePage.Insights -> Icons.insights
    MorePage.Status -> Icons.monitorHeart
    MorePage.Settings -> Icons.settings
    MorePage.Gallery -> Icons.palette
}
