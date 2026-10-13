package dev.atoll.app.files

import dev.atoll.app.net.MemoryKeyValueStore
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FileModelsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    @Test fun pathsBreadcrumbsLinkLinesAndUploadCollisions() {
        assertEquals("b/中文/", FilePaths.normalize("a/../b\\中文/"))
        assertEquals("b/", FilePaths.parent("b/中文/"))
        assertEquals("daemon://local-device/c0.dev.web/%E4%B8%AD%E6%96%87/", FilePaths.prefix("local-device", "c0.dev.web", "中文/"))
        assertEquals("file-3.md", FilePaths.availableName("file.md", listOf("FILE.md", "file-2.md")))
        assertEquals("a+b.txt", FilePaths.decode("a+b.txt"))
        assertEquals(123, FilePaths.fromLink("c", "/path/to/%E4%B8%AD%E6%96%87.kt:123")!!.line)
        assertNull(FilePaths.fromLink("c", "https://example.com/file"))
        assertNull(FilePaths.fromLink("c", "//example.com/file"))
    }
    @Test fun resourceListingsIncludeDirectoriesMetadataAndPageCursor() {
        val page = projectFilePage(obj("""{"items":[{"id":"daemon://d/c/folder","meta":{"node_type":"directory"}},{"id":"daemon://d/c/%E4%B8%AD%E6%96%87.kt","meta":{"node_type":"regular","media_type":"text/plain","size":42,"modified_at":123}},{"id":"daemon://d/c/nested/x","meta":{}}],"next":"cursor"}"""), "id", "daemon://d/c/", "")
        assertEquals(2, page.entries.size); assertEquals("cursor", page.next)
        assertEquals("folder/", page.entries[0].directory)
        assertEquals("中文.kt", page.entries[1].reference.name); assertEquals(42L, page.entries[1].reference.size); assertEquals(123L, page.entries[1].modifiedAt)
        assertEquals(page.entries[0], sortedFiles(page.entries, FileSort.NAME, true).first())
        val numbers = listOf("file10", "file2").map { FileEntry(FileReference("c", it, it)) }
        assertEquals(listOf("file2", "file10"), sortedFiles(numbers, FileSort.NAME, false).map { it.reference.name })
        val devices = projectDevices(obj("""{"items":[{"key":"local-device","declared":{"name":"local-device","default":true},"actual":{"measures":[{"name":"online","value":true,"unknown":false}]}}]}"""))
        assertTrue(devices.single().default); assertEquals(true, devices.single().online)
    }
    @Test fun typesFallbackToExtensionsAndUnknownTextIsStrictlySniffed() {
        assertEquals(PreviewKind.IMAGE, previewKind("a.png", "application/octet-stream"))
        assertEquals(PreviewKind.HTML, previewKind("a.html", "text/plain"))
        assertEquals(PreviewKind.PDF, previewKind("a", "application/pdf"))
        assertEquals(PreviewKind.MARKDOWN, previewKind("a.md", "application/octet-stream"))
        assertEquals(PreviewKind.TEXT, previewKind("a.kt", "application/octet-stream"))
        assertEquals("中文", sniffText("中文".toByteArray()))
        assertNull(sniffText(byteArrayOf(0, 1))); assertNull(sniffText(byteArrayOf(-1, -2)))
    }
    @Test fun nameSortMatchesWebNumericBaseSensitivityAndStableResourceKeys() {
        // Captured files-failed.png names, compared with web's Intl.Collator.
        val names=listOf("0c73007a98","04a3b571a2","5cca8d786e","302b13f891","a2c939ab4d","c4367dd627","d2a6a25667","db29e47a0d")
            .map { "android-e2e-$it.txt" }
        val entries=names.reversed().map { FileEntry(FileReference("c",it,it)) }
        assertEquals(names,sortedFiles(entries,FileSort.NAME,false).map { it.reference.name })
        val folder=FileEntry(FileReference("c","directory:z","z-directory"),directory="z-directory/")
        assertSame(folder,sortedFiles(entries+folder,FileSort.NAME,false).first())
        assertSame(folder,sortedFiles(entries+folder,FileSort.NAME,true).first())
        val ties=listOf(FileEntry(FileReference("c","z","Á")),FileEntry(FileReference("c","a","a")))
        assertEquals(listOf("a","z"),sortedFiles(ties,FileSort.NAME,false).map { it.reference.resourceId })
        assertEquals(listOf("a","z"),sortedFiles(ties,FileSort.NAME,true).map { it.reference.resourceId })
        val case=listOf(FileEntry(FileReference("c","z","B")),FileEntry(FileReference("c","a","b")))
        assertEquals(listOf("a","z"),sortedFiles(case,FileSort.NAME,false).map { it.reference.resourceId })
    }
    @Test fun readingHistoryAndStacksAreBoundedPersistedAndWorldScoped() {
        val store = MemoryKeyValueStore()
        val history = FileHistory(store, "node", "principal", "world")
        (1..30).forEach { history.opened(FileReference("c", "f$it", "file$it")) }
        assertEquals(24, history.recent.value.size); assertEquals(20, history.stack("c").size)
        assertEquals("f29", history.back("c")!!.resourceId)
        val loaded = FileHistory(store, "node", "principal", "world")
        assertEquals("f30", loaded.recent.value.first().resourceId); assertEquals("f29", loaded.stack("c").last().resourceId)
        assertTrue(FileHistory(store, "node", "principal", "new-world").recent.value.isEmpty())
        loaded.close("c"); assertTrue(loaded.stack("c").isEmpty())
    }
    @Test fun storageAddressesRejectTraversalEncodedSlashesAndForeignAuthoritySyntax() {
        assertEquals("folder/中文.pdf", StorageAddress.parse("oss://c0.storage/c0.dev/folder/%E4%B8%AD%E6%96%87.pdf")!!.path)
        for (s in listOf("oss://s/c/../f", "oss://s/c/a%2Fb", "oss://s/c/a?x", "oss://s/c/a#x", "oss://s/c/", "oss://s/c/a b")) assertNull(s, StorageAddress.parse(s))
    }
}
