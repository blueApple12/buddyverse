package app.buddyverse.core.api

import app.buddyverse.core.AppStore
import kotlinx.coroutines.CancellationException

// Skills and MCP servers are global in the app (one shared list), but Hermes keeps their switches and the MCP map per profile.
// A write goes to the default profile first (it owns the page), then here to every other bot.

/** Runs [block] for every bot profile except [done]; returns the names of the bots it failed on (empty = all applied). */
suspend fun AppStore.forOtherBots(done: String, block: suspend (String) -> Unit): List<String> =
    bots.value.filter { it.profile != done }.mapNotNull { b ->
        try { block(b.profile); null } catch (e: CancellationException) { throw e } catch (_: Exception) { b.name }
    }
