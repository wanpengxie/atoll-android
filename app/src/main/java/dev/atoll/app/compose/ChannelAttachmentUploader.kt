package dev.atoll.app.compose

import dev.atoll.app.files.FileRepository
import dev.atoll.app.files.FileDevice
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Files owns paths, device discovery, collisions, ticket lifetime and byte transport. */
class ChannelAttachmentUploader(private val repository: (String) -> FileRepository) : AttachmentUploader {
    private val stores = mutableMapOf<String, Pair<FileRepository, FileDevice>>()
    private val serial = Mutex()
    override suspend fun upload(channelId: String, file: UploadFile): ComposerAttachment = serial.withLock {
        val (store, device) = stores[channelId] ?: repository(channelId).let { repo ->
            val target = repo.initialize().find { it.default && it.online != false } ?: error("频道的默认存储设备当前不可用")
            (repo to target).also { stores[channelId] = it }
        }
        store.upload(device, "", file.name, emptyList(), file.open, file.size, file.mediaType)
    }
}
