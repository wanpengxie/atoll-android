package dev.atoll.app.tasks

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow

private val workTitleWhitespace=Regex("\\s+")
/** Only the visible prefix is formatted on main; full bodies remain in facts and details. */
internal fun compactWorkTitle(title: String): String = title.take(512).replace(workTitleWhitespace," ").trim()+if(title.length>512) "…" else ""

@Composable internal fun WorkTitle(title: String) {
    val compact=remember(title) { compactWorkTitle(title) }
    Text(compact,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
}
