package dev.atoll.app.stars

import android.content.Context
import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.core.WireState
import dev.atoll.app.core.string
import dev.atoll.app.net.AndroidKeyValueStore

/** Recreate on principal/checked-world changes. Reconnects in the same world retain bookmarks. */
fun createStarredViewModel(context: Context, services: WorkspaceServices,
    world: String = services.boot.ifEmpty { (services.wire.state.value as? WireState.Attached)?.receipt?.get("boot").string() },
): StarredViewModel = StarredViewModel(StarScope(services.server, services.principalId, world),
    StarredStore(AndroidKeyValueStore(context.applicationContext.getSharedPreferences("atoll.stars.v1", Context.MODE_PRIVATE))))
