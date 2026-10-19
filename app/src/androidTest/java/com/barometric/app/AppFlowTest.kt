package com.barometric.app

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.barometric.app.data.LocationEntity
import com.barometric.app.data.SourceEntity
import com.barometric.app.data.PressureReading
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun ratingZeroCanBeReplacedWithDifferentRating() {
        val tracker = (rule.activity.application as BarometricApplication).container.tracker
        val date = LocalDate.now(LocationEntity.HYRUM.zone)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Symptoms").fetchSemanticsNodes().isNotEmpty() }
        if (rule.onAllNodesWithContentDescription("Open symptom form").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithContentDescription("Open symptom form").performScrollTo().performClick()
        val slider = rule.onNodeWithTag("symptom-HEADACHE")
        slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        rule.waitUntil(10_000) { runBlocking { tracker.dao.entry(date.toString())?.rating == 0 } }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        rule.waitUntil(10_000) { runBlocking { tracker.dao.entry(date.toString())?.rating == 2 } }
        rule.onNodeWithContentDescription("Dismiss symptom form").performClick()
        rule.onNodeWithTag("symptom-HEADACHE").assertDoesNotExist()
        rule.onNodeWithContentDescription("Open symptom form").performClick()
        slider.assertExists()

    }

    @Test fun calendarAllowsPastDateRatingAndRejectsFutureDates() {
        val tracker = (rule.activity.application as BarometricApplication).container.tracker
        val today = LocalDate.now(LocationEntity.HYRUM.zone)
        val yesterday = today.minusDays(1)
        rule.onNodeWithText("Calendar").performClick()
        if (yesterday.month != today.month) rule.onNodeWithContentDescription("Previous month").performClick()
        rule.onNodeWithTag("calendar-$yesterday", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithTag("symptom-HEADACHE").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        rule.waitUntil(10_000) { runBlocking { tracker.dao.entry(yesterday.toString())?.rating == 3 } }
        assertEquals("America/Denver", runBlocking { tracker.dao.entry(yesterday.toString())!!.timezone })
    }

    @Test fun insightsStayOffTodayAndSettingsExposeExport() {
        rule.onNodeWithText("Your patterns").assertDoesNotExist()
        rule.onNodeWithText("Insights").assertDoesNotExist()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Export JSON").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Syncs total steps for each calendar day when you open the app.").assertExists()
    }

    @Test fun verticalGraphSwipeScrollsThePage() {
        val tracker = (rule.activity.application as BarometricApplication).container.tracker
        val location = LocationEntity.HYRUM
        val date = LocalDate.now(location.zone)
        // Deterministic UI-only fixture. Device test installation is removed after the run.
        runBlocking {
            tracker.dao.saveSources(listOf(SourceEntity(location.id, "gesture-fixture", "TEST", "UI test fixture", null, location.latitude, location.longitude, 0.0, false, -1)))
            tracker.dao.saveReadings((0..5).map {
                PressureReading(location.id, "gesture-fixture", date.atStartOfDay(location.zone).toEpochSecond() + it * 600,
                    1000.0 + it, intervalMinutes = 10)
            })
        }
        val graph = rule.onNodeWithContentDescription("Pressure graph from $date to $date.", substring = true)
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Pressure graph from $date to $date.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        graph.performScrollTo()
        val before = graph.fetchSemanticsNode().positionInRoot.y
        graph.performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertTrue("Vertical graph gestures must allow page scrolling", graph.fetchSemanticsNode().positionInRoot.y < before - 20)
    }
}
