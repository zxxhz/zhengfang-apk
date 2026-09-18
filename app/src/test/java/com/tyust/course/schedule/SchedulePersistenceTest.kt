package com.tyust.course.schedule

import com.tyust.course.manager.AppThemeMode
import com.tyust.course.manager.AppThemePreferences
import com.tyust.course.manager.ScheduleSettingsManager
import org.junit.Assert.*
import org.junit.Test

class SchedulePersistenceTest {
    @Test fun semesterStartDatePublishesOnlyChangesAndSurvivesReload() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        val initialRevision = manager.revision
        manager.semesterStartDate = 1_778_457_600_000L
        assertTrue(manager.revision > initialRevision)
        assertEquals(manager.semesterStartDate, ScheduleSettingsManager(prefs).semesterStartDate)
        val changedRevision = manager.revision
        manager.semesterStartDate = manager.semesterStartDate
        assertEquals(changedRevision, manager.revision)
    }

    @Test fun periodCountChangesPublishRevisionSoBackNavigationCannotLeaveAStaleGrid() {
        val manager = ScheduleSettingsManager(MemoryPreferences())
        val previous = manager.revision
        manager.periodCount = 16
        assertEquals(16, manager.periodCount)
        assertTrue(manager.revision > previous)
    }
    @Test fun themeRoundTripsIndependentlyFromWallpaperAndInvalidTypesFallBack() {
        val prefs = MemoryPreferences()
        prefs.edit().putString("wallpaper", "Aurora").putFloat("image_dim", 0.37f).apply()
        assertEquals(AppThemeMode.System, AppThemePreferences(prefs).read())
        AppThemeMode.entries.forEach {
            AppThemePreferences(prefs).write(it)
            assertEquals(it, AppThemePreferences(prefs).read())
        }
        prefs.edit().putString("theme_mode", "obsolete").apply()
        assertEquals(AppThemeMode.System, AppThemePreferences(prefs).read())
        prefs.edit().putBoolean("theme_mode", true).apply()
        assertEquals(AppThemeMode.System, AppThemePreferences(prefs).read())
        assertEquals("Aurora", prefs.getString("wallpaper", null))
        assertEquals(0.37f, prefs.getFloat("image_dim", 0f), 0f)
    }

    @Test fun editingDeletionUndoAndReloadKeepIdsAndAccountIsolation() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        val original = ScheduleSettingsManager.CustomCourse("existing-id", "数学", "A101", "", 1, 1, 2, "1-16周")
        manager.addCustomCourse(original, "account-a")
        manager.addCustomCourse(original.copy(name = "英语"), "account-b")
        manager.updateCustomCourse(original.copy(name = "高数", teacher = "老师"), "account-a")
        assertEquals("existing-id", manager.getCustomCourses("account-a").single().id)
        assertEquals("英语", manager.getCustomCourses("account-b").single().name)
        val deleted = manager.getCustomCourses("account-a").single()
        manager.removeCustomCourse(deleted.id, "account-a")
        assertTrue(manager.getCustomCourses("account-a").isEmpty())
        manager.updateCustomCourse(deleted, "account-a")
        assertEquals(deleted, ScheduleSettingsManager(prefs).getCustomCourses("account-a").single())
    }

    @Test fun legacyMissingAndDuplicateIdsAreRepairedOnlyOnce() {
        val prefs = MemoryPreferences()
        val legacy = """[{"id":"keep","name":"数学","day":1,"startPeriod":1,"endPeriod":2},
            {"id":"keep","name":"英语","day":2,"startPeriod":1,"endPeriod":2},
            {"name":"体育","day":3,"startPeriod":1,"endPeriod":2}]"""
        prefs.edit().putString("custom_courses", legacy).apply()
        val first = ScheduleSettingsManager(prefs).getCustomCourses("account-a")
        assertEquals("keep", first.first().id)
        assertEquals(3, first.map { it.id }.toSet().size)
        assertTrue(first.all { it.id.isNotBlank() })
        assertEquals(first, ScheduleSettingsManager(prefs).getCustomCourses("account-a"))
        assertTrue(ScheduleSettingsManager(prefs).getCustomCourses("account-b").isEmpty())
        assertFalse(prefs.contains("custom_courses"))
    }

    @Test fun semesterCalendarsAreIsolatedAndLegacyDateMigratesToOnlyOneCurrentTerm() {
        val prefs = MemoryPreferences()
        val store = ScheduleCalendarStore(prefs)
        val base = ScheduleTimeBase("2026-09-07", mapOf(1 to "08:00"))
        assertTrue(store.migrateLegacy("a", "2026-2027-1", base))
        assertFalse(ScheduleCalendarStore(prefs).migrateLegacy("a", "2026-2027-2", base))
        assertNull(store.read("a", "2026-2027-2"))
        assertNull(store.read("b", "2026-2027-1"))
        val next = base.copy(firstWeekDate = "2027-03-01")
        assertTrue(store.write("a", "2026-2027-2", next))
        assertEquals(base, store.read("a", "2026-2027-1"))
        assertEquals(next, ScheduleCalendarStore(prefs).read("a", "2026-2027-2"))
    }

    @Test fun testShufeZjPeriodTimesAndPeriodCount() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        val shufeTimes = manager.getShufeZjPeriodTimes()
        assertEquals(13, shufeTimes.size)
        assertEquals("08:00", shufeTimes[0].startTime)
        assertEquals("08:40", shufeTimes[0].endTime)
        assertEquals("08:50", shufeTimes[1].startTime)
        assertEquals("09:30", shufeTimes[1].endTime)
        assertEquals("09:40", shufeTimes[2].startTime)
        assertEquals("10:20", shufeTimes[2].endTime)
        assertEquals("10:30", shufeTimes[3].startTime)
        assertEquals("11:10", shufeTimes[3].endTime)
        assertEquals("11:20", shufeTimes[4].startTime)
        assertEquals("12:00", shufeTimes[4].endTime)
        assertEquals("14:00", shufeTimes[5].startTime)
        assertEquals("14:40", shufeTimes[5].endTime)
        assertEquals("14:50", shufeTimes[6].startTime)
        assertEquals("15:30", shufeTimes[6].endTime)
        assertEquals("15:40", shufeTimes[7].startTime)
        assertEquals("16:20", shufeTimes[7].endTime)
        assertEquals("16:30", shufeTimes[8].startTime)
        assertEquals("17:10", shufeTimes[8].endTime)
        assertEquals("18:30", shufeTimes[9].startTime)
        assertEquals("19:10", shufeTimes[9].endTime)
        assertEquals("19:20", shufeTimes[10].startTime)
        assertEquals("20:00", shufeTimes[10].endTime)
        assertEquals("20:10", shufeTimes[11].startTime)
        assertEquals("20:50", shufeTimes[11].endTime)
        assertEquals("21:00", shufeTimes[12].startTime)
        assertEquals("21:40", shufeTimes[12].endTime)
    }
}
