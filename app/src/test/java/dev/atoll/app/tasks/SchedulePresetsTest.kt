package dev.atoll.app.tasks

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class SchedulePresetsTest {
    private val singapore = ZoneId.of("Asia/Singapore")
    @Test fun relativePresetsRetainDurationAcrossMidnight() {
        val now = ZonedDateTime.of(2026, 10, 14, 23, 55, 0, 0, singapore).toInstant()
        assertEquals(600_000L, scheduleDelayMillis(SchedulePreset.TenMinutes, now, singapore))
        assertEquals(3_600_000L, scheduleDelayMillis(SchedulePreset.OneHour, now, singapore))
        assertEquals("将在明天 00:05 触发", schedulePreview(SchedulePreset.TenMinutes, now, singapore, null))
    }
    @Test fun tomorrowIsNextCalendarDayEvenBeforeNineAndUsesLocalZone() {
        val now = Instant.parse("2026-10-14T00:00:00Z")
        assertEquals(25 * 3_600_000L, scheduleDelayMillis(SchedulePreset.TomorrowMorning, now, singapore))
        val losAngeles = ZoneId.of("America/Los_Angeles")
        assertEquals(LocalDateTime.of(2026, 10, 15, 9, 0), scheduledInstant(SchedulePreset.TomorrowMorning, now, singapore).atZone(singapore).toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 10, 14, 9, 0), scheduledInstant(SchedulePreset.TomorrowMorning, now, losAngeles).atZone(losAngeles).toLocalDateTime())
    }
    @Test fun daylightSavingChangesActualDurationInsteadOfAssumingTwentyFourHours() {
        val zone = ZoneId.of("America/New_York")
        val spring = ZonedDateTime.of(2026, 3, 7, 23, 30, 0, 0, zone).toInstant()
        val autumn = ZonedDateTime.of(2026, 10, 31, 23, 30, 0, 0, zone).toInstant()
        assertEquals(8 * 3_600_000L + 1_800_000, scheduleDelayMillis(SchedulePreset.TomorrowMorning, spring, zone))
        assertEquals(10 * 3_600_000L + 1_800_000, scheduleDelayMillis(SchedulePreset.TomorrowMorning, autumn, zone))
    }
    @Test fun customTimeUsesSelectedLocalDateAndRejectsPastOrMissing() {
        val now = Instant.parse("2026-10-14T00:00:00Z")
        val custom = LocalDateTime.of(2026, 10, 16, 9, 0)
        assertEquals(custom.atZone(singapore).toInstant(), scheduledInstant(SchedulePreset.Custom, now, singapore, custom))
        assertEquals("将在10月16日 09:00 触发", schedulePreview(SchedulePreset.Custom, now, singapore, custom))
        for (date in listOf(null, LocalDateTime.of(2026, 10, 14, 8, 0), LocalDateTime.of(2026, 10, 13, 9, 0))) {
            try { scheduleDelayMillis(SchedulePreset.Custom, now, singapore, date); fail("invalid custom time") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
