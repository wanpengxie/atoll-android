package dev.atoll.app.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.atoll.app.net.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Scoped-storage download: bytes go directly to public Downloads, never to a ByteArray. */
object PublicDownloads {
    suspend fun download(context: Context, repository: FileRepository, reference: FileReference, destination: Uri? = null, progress: (TransferProgress) -> Unit = {}): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val created = destination == null
        val uri = destination ?: if (Build.VERSION.SDK_INT >= 29) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, reference.name)
                put(MediaStore.Downloads.MIME_TYPE, reference.mediaType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Atoll")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }) ?: throw IOException("无法创建下载文件")
        } else throw IOException("请选择下载位置")
        try {
            val headers = repository.download(reference, { resolver.openOutputStream(uri, "w") ?: throw IOException("无法写入下载位置") }, progress)
            if (created && Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0); if (headers.mediaType.isNotEmpty()) put(MediaStore.Downloads.MIME_TYPE, headers.mediaType) }, null, null)
            uri
        } catch (e: Exception) {
            if (created) resolver.delete(uri, null, null)
            throw e
        }
    }
}
