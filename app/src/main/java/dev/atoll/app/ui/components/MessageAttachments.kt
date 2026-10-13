package dev.atoll.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollColors
import java.util.Locale

internal fun attachmentName(value: MessageAttachmentViewState) = value.name.ifBlank { value.resourceId.ifBlank { "附件" } }
internal fun attachmentType(mime: String): String = when {
    mime.startsWith("image/", true) -> "图片"
    mime.equals("application/pdf", true) -> "PDF"
    mime.startsWith("audio/", true) -> "音频"
    mime.startsWith("video/", true) -> "视频"
    mime.startsWith("text/", true) -> "文本"
    else -> "文件"
}
internal fun attachmentSize(size: Long?): String? {
    if (size == null || size < 0) return null
    if (size < 1024) return "$size B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var number = size.toDouble() / 1024
    var unit = 0
    while (number >= 1024 && unit < units.lastIndex) { number /= 1024; unit++ }
    return String.format(Locale.ROOT, "%.1f %s", number, units[unit])
}

@Composable
fun MessageAttachments(values: List<MessageAttachmentViewState>, onLink: ((String) -> Unit)? = null,
    onAttachment: MessageAttachmentHandler? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { attachment ->
            val name = attachmentName(attachment)
            val type = attachmentType(attachment.mime)
            Surface(shape = RoundedCornerShape(8.dp), color = AtollColors.Input,
                border = BorderStroke(1.dp, AtollColors.ControlLine)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(onClick = {
                        if (onAttachment != null) onAttachment(attachment, MessageAttachmentAction.Preview)
                        else onLink?.invoke(attachment.uri)
                    }, enabled = onAttachment != null || (onLink != null && attachment.uri.isNotBlank()),
                        color = AtollColors.Input, modifier = Modifier.weight(1f)) {
                        Row(Modifier.heightIn(min = 44.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (type == "PDF") "PDF" else "◇", Modifier.width(28.dp), fontSize = 11.sp, color = AtollColors.Muted)
                            Column(Modifier.weight(1f)) {
                                Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(type, attachmentSize(attachment.size)).joinToString(" · "), fontSize = 10.sp, color = AtollColors.Muted)
                            }
                        }
                    }
                    if (onAttachment != null) IconButton({ onAttachment(attachment, MessageAttachmentAction.Download) }, Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Download, "下载 $name", Modifier.size(18.dp), tint = AtollColors.Muted)
                    }
                }
            }
        }
    }
}
