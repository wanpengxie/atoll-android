package dev.atoll.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.atoll.app.ui.components.ChatSheetHeader
import dev.atoll.app.ui.components.ChatSheetShape
import dev.atoll.app.ui.theme.ChatColors

internal class MessageAction(val label: String, val icon: ImageVector, val enabled: Boolean = true, val onClick: () -> Unit)

private const val PER_ROW = 5

/**
 * MessageActions board: the WeChat dark popover above the pressed message (below it when there is no
 * room), white 22dp icons with 12sp labels, five per row, an arrow pointing at the message.
 */
@Composable
internal fun MessageActionPopover(actions: List<MessageAction>, above: Boolean, onDismiss: () -> Unit) {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.roundToPx() }
    Popup(popupPositionProvider = remember(above, gap) { MessagePopoverPosition(above, gap) }, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        Column(Modifier.semantics { paneTitle = "消息操作"; contentDescription = "消息操作" }.testTag("atoll.message.actions"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (!above) Arrow(up = true)
            Column(Modifier.clip(RoundedCornerShape(8.dp)).background(ChatColors.Popover).padding(6.dp)) {
                actions.chunked(PER_ROW).forEach { row ->
                    Row {
                        row.forEach { action ->
                            Column(Modifier.size(width = 60.dp, height = 64.dp).clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                val tint = if (action.enabled) Color.White else Color.White.copy(alpha = .35f)
                                Icon(action.icon, null, Modifier.size(22.dp), tint = tint)
                                Spacer(Modifier.height(6.dp))
                                Text(action.label, fontSize = 12.sp, lineHeight = 16.sp, color = tint, maxLines = 1, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            if (above) Arrow(up = false)
        }
    }
}

@Composable
private fun Arrow(up: Boolean) {
    val shape = androidx.compose.runtime.remember(up) { GenericShape { size, _ ->
        if (up) { moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(0f, size.height) }
        else { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height) }
        close()
    } }
    Box(Modifier.size(14.dp, 7.dp).clip(shape).background(ChatColors.Popover))
}

private class MessagePopoverPosition(private val above: Boolean, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = ((anchorBounds.left + anchorBounds.right) / 2 - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = if (above) anchorBounds.top - popupContentSize.height - gap else anchorBounds.bottom + gap
        return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

/** Selection uses the same complete, unabridged text as whole-message copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageTextSelectionSheet(text: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ChatColors.Block, shape = ChatSheetShape, scrimColor = ChatColors.Scrim, dragHandle = null) {
        Column(Modifier.fillMaxWidth().semantics { paneTitle = "选择文本" }.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            ChatSheetHeader("选择文本", "关闭", onDismiss)
            SelectionContainer(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("atoll.message.selection")) {
                Text(text, fontSize = 16.sp, lineHeight = 25.sp, color = ChatColors.Body)
            }
        }
    }
}
