package dev.atoll.app.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollColors
import java.time.*
import java.time.format.DateTimeFormatter

enum class SchedulePreset(val label: String) {
    TenMinutes("10 分钟后"), OneHour("1 小时后"), TomorrowMorning("明天 09:00"), Custom("自定义…")
}

/** Relative presets start at submission; calendar presets use the device's local time zone. */
internal fun scheduledInstant(preset: SchedulePreset, now: Instant, zone: ZoneId, custom: LocalDateTime? = null): Instant = when (preset) {
    SchedulePreset.TenMinutes -> now.plusSeconds(600)
    SchedulePreset.OneHour -> now.plusSeconds(3600)
    SchedulePreset.TomorrowMorning -> now.atZone(zone).toLocalDate().plusDays(1).atTime(9, 0).atZone(zone).toInstant()
    SchedulePreset.Custom -> requireNotNull(custom) { "请选择日期与时间" }.atZone(zone).toInstant()
}
internal fun scheduleDelayMillis(preset: SchedulePreset, now: Instant, zone: ZoneId, custom: LocalDateTime? = null): Long =
    Duration.between(now, scheduledInstant(preset, now, zone, custom)).toMillis().also {
        require(it > 0) { "请选择将来的时间" }
    }
internal fun schedulePreview(preset: SchedulePreset, now: Instant, zone: ZoneId, custom: LocalDateTime?): String {
    if (preset == SchedulePreset.Custom && custom == null) return "请选择日期与时间"
    val target = scheduledInstant(preset, now, zone, custom)
    if (!target.isAfter(now)) return "请选择将来的时间"
    val local = target.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val day = when (local.toLocalDate()) {
        today -> "今天"; today.plusDays(1) -> "明天"
        else -> local.format(DateTimeFormatter.ofPattern("M月d日"))
    }
    return "将在$day ${local.format(DateTimeFormatter.ofPattern("HH:mm"))} 触发"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SchedulePresetSelector(preset: SchedulePreset, custom: LocalDateTime?, now: Instant, zone: ZoneId,
    onPreset: (SchedulePreset) -> Unit, onCustom: () -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("什么时候", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SchedulePreset.entries.forEach { choice ->
                val selected = preset == choice
                Box(Modifier.heightIn(min = 44.dp).selectable(selected, enabled = enabled, role = Role.RadioButton,
                    onClick = { onPreset(choice); if (choice == SchedulePreset.Custom) onCustom() }), contentAlignment = Alignment.Center) {
                    Box(Modifier.height(38.dp).clip(RoundedCornerShape(19.dp))
                        .background(if (selected) AtollColors.Ink else AtollColors.Surface)
                        .border(1.dp, if (selected) AtollColors.Ink else AtollColors.ControlLine, RoundedCornerShape(19.dp))
                        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(choice.label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) AtollColors.Surface else AtollColors.Ink)
                    }
                }
            }
        }
        if (preset == SchedulePreset.Custom) OutlinedButton(onCustom, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(custom?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "选择日期与时间")
        }
        Text(schedulePreview(preset, now, zone, custom), fontSize = 12.sp, color = AtollColors.Muted)
    }
}
