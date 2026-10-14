package dev.atoll.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollColors

/** MessageActions artboard: 24dp top corners, 52dp rows, 20dp icons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionsSheet(onDismiss: () -> Unit, title: String = "消息操作", content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AtollColors.Surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        scrimColor = AtollColors.Ink.copy(alpha = .32f),
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 8.dp).size(36.dp, 4.dp)
            .clip(RoundedCornerShape(2.dp)).background(AtollColors.ControlLine)) }) {
        Column(Modifier.fillMaxWidth().semantics { paneTitle = title; contentDescription = title }
            .testTag("atoll.message.actions").verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
            content = content)
    }
}

@Composable
internal fun MessageActionRow(label: String, icon: ImageVector, enabled: Boolean = true,
    danger: Boolean = false, detail: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val color = if (!enabled) AtollColors.Faint else if (danger) AtollColors.Danger else AtollColors.Ink
        Icon(icon, null, Modifier.size(20.dp), tint = if (!enabled || danger) color else AtollColors.Muted)
        Text(label, Modifier.weight(1f), fontSize = 15.sp, color = color)
        if (detail.isNotEmpty()) Text(detail, fontSize = 13.sp, color = AtollColors.Muted)
    }
}

/** Selection uses the same complete, unabridged text as whole-message copy. */
@Composable
internal fun MessageTextSelectionSheet(text: String, onDismiss: () -> Unit) {
    MessageActionsSheet(onDismiss, title = "选择文本") {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("选择文本", Modifier.weight(1f), fontSize = 15.sp, color = AtollColors.Ink)
            IconButton(onDismiss, Modifier.size(44.dp)) { Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp)) }
        }
        SelectionContainer(Modifier.padding(14.dp).testTag("atoll.message.selection")) {
            Text(text, fontSize = 15.sp, lineHeight = 23.sp, color = AtollColors.Body)
        }
    }
}
