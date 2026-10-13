package dev.atoll.app.compose

import android.content.Context
import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.core.WireState
import dev.atoll.app.files.FileRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Keep one VM per account/server world, then call setChannel on navigation.
 * Recreate when services.wire changes or the checked boot changes; never replay
 * a previous world's journal. For offline opening pass the last checked boot.
 */
fun createComposerViewModel(
    context: Context,
    services: WorkspaceServices,
    channelId: String,
    roster: List<ComposerActor> = emptyList(),
    writable: Boolean = false,
    boot: String = (services.wire.state.value as? WireState.Attached)?.receipt?.text("boot").orEmpty(),
): ComposerViewModel {
    require(boot.isNotEmpty()) { "Composer needs the server world validated by AppGraph" }
    val owner = JsonArray(listOf(services.server, services.principalId, boot).map(::JsonPrimitive)).toString()
    val database = ComposerDatabase.open(context)
    return ComposerViewModel(owner, services.wire, RoomOutboxRepository(owner, database),
        ChannelAttachmentUploader { id -> FileRepository(id, services.wire, services.httpClient, services.server, context.applicationContext.cacheDir) },
        releaseResources = database::close,
    ).also { it.setChannel(channelId, services.selfIds[channelId].orEmpty(), roster, writable) }
}
