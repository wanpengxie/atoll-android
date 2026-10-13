package dev.atoll.app.app

internal fun createdChannelRecord(id: String, parentId: String, name: String, parent: ChannelRecord?): ChannelRecord =
    ChannelRecord(id, name, parentId, parent?.qualifiedName?.takeIf { it.isNotEmpty() }?.let { "$it.$name" } ?: name)
