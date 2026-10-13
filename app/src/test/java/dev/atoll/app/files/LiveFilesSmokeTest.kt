package dev.atoll.app.files

import dev.atoll.app.core.WireState
import dev.atoll.app.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt-in fixture check; temporary upload/folder are removed in finally. No credential logging. */
class LiveFilesSmokeTest {
    @Test fun liveBrowsePreviewDownloadAndUploadRoundTrip() = runBlocking<Unit> {
        assumeTrue(System.getenv("ATOLL_FILES_SMOKE") == "1")
        val base = System.getenv("ATOLL_URL").orEmpty()
        val token = System.getenv("ATOLL_TOKEN").orEmpty()
        assumeTrue(base.isNotEmpty() && token.isNotEmpty())
        val channel = System.getenv("ATOLL_FILES_CHANNEL") ?: "ba676224-ff3c-4975-904b-6473e4dba788"
        val http = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build()) }.build()
        val cache = java.nio.file.Files.createTempDirectory("atoll-live-files").toFile()
        val wire = Wire(base, http, "Android files JVM smoke")
        try {
            val feeds = launch(start = CoroutineStart.UNDISPATCHED) { wire.frames.collect {} }
            wire.start()
            withTimeout(15_000) { wire.state.filterIsInstance<WireState.Attached>().first() }
            val repository = FileRepository(channel, wire, http, base, cache)
            val device = repository.initialize().first { it.default }
            val page = repository.list(device, "android-files-smoke/")
            assertTrue(page.entries.any { it.reference.name == "sample.png" })
            for ((name, kind) in listOf("sample.txt" to PreviewKind.TEXT, "sample.png" to PreviewKind.IMAGE, "sample.pdf" to PreviewKind.PDF, "sample.wav" to PreviewKind.AUDIO, "sample.mp4" to PreviewKind.VIDEO)) {
                val loaded = repository.preview(page.entries.first { it.reference.name == name }.reference)
                assertEquals(kind, loaded.kind); assertTrue(loaded.file.length() > 0)
            }
            val large = page.entries.first { it.reference.name == "large-64mb.bin" }.reference
            val target = java.io.File(cache, "download.bin")
            repository.download(large, { target.outputStream() })
            assertEquals(64L * 1024 * 1024, target.length())
            val folderName = "test-${UUID.randomUUID()}"
            val folderPath = "android-files-smoke/$folderName/"
            val folder = FileReference(channel, FilePaths.prefix(device.name, repository.channelName, "android-files-smoke/") + folderName, folderName)
            var uploaded: FileReference? = null
            repository.mkdir(device, "android-files-smoke/", folderName)
            try {
                uploaded = repository.upload(device, folderPath, "proof.txt", emptyList(), { "upload round-trip 中文".toByteArray().inputStream() }, "upload round-trip 中文".toByteArray().size.toLong(), "text/plain")
                val loaded = repository.preview(uploaded)
                assertEquals("upload round-trip 中文", loaded.text)
            } finally {
                uploaded?.let { repository.delete(it) }
                repository.delete(folder)
            }
            feeds.cancel()
        } finally { wire.close(); cache.deleteRecursively() }
    }
}
