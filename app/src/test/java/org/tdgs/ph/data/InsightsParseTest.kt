package org.tdgs.ph.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InsightsParseTest {
    @Test
    fun parsesQualifiedReadingsPlanningAndMissingDays() {
        val report = InsightsReport.parse("""
            {
              "through":"2026-11-19", "window_days":41,
              "coverage_last_7_days":{"minute_coverage_pct":83,"usable_step_days":5,"confirmed_load_days":0},
              "metrics":{"steps":{"label":"Recorded steps","unit":"steps","recent_days":5,"baseline_days":14,
                "recent_median":3500,"baseline_median":4100,"baseline_middle_80_pct":[1850,6000],
                "latest":{"date":"2026-11-19","value":0}}},
              "days":[{"date":"2026-11-18"},{"date":"2026-11-19","steps":0}],
              "associations":[{"predictor":"steps","outcome":"sleep_minutes","lag_days":1,"pairs":15,
                "status":"exploratory_pattern","rank_correlation":0.51}],
              "steps_today_estimate":{"date":"2026-11-20","status":"available","estimate":4300,
                "typical_error_steps":650,"evaluation_days":23},
              "weekly_intensity_goal":{"target":150,"observed":90,"remaining_to_record":60,
                "days_left_including_today":3,"even_split_minutes":20},
              "suggested_actions":["review_training_days"]
            }
        """)
        assertEquals(0, report.coverage.trainingDays)
        assertNull(report.coverage.sleepNights)
        assertEquals(1850.0 to 6000.0, report.metrics.single().baselineRange)
        assertNull(report.days[0].values["steps"])
        assertEquals(0.0, report.days[1].values["steps"])
        assertEquals(0.0, report.metrics.single().latest?.value)
        assertEquals(1, report.associations.single().lag)
        assertEquals(23, report.stepEstimate?.replayDays)
        assertEquals(20, report.intensityPlan?.minutesPerDay)
        assertEquals(listOf("review_training_days"), report.actions)
    }

    @Test
    fun keepsPatternsWithoutFillingMissingBriefingsOrForecasts() {
        val report = InsightsReport.parse("""
            {"through":"2026-11-19","weekly_intensity_goal":null,
             "steps_today_estimate":{"date":"2026-11-20","status":"insufficient_history","estimate":null}}
        """)
        assertNull(report.stepEstimate?.steps)
        assertNull(report.intensityPlan)
        val dashboard = Dashboard.parse("""
            {"briefing":{"day":"2026-11-20","requires_refresh":true,"grounding":{"patterns_status":"ok"},
             "pages":{"today":{"headline":"","summary":""},"patterns":{"headline":"Your baseline changed","focus":"Review the goal"}}}}
        """)
        assertTrue(dashboard.briefingNeedsRefresh)
        assertEquals("ok", dashboard.patternsBriefingStatus)
        assertEquals("patterns", dashboard.briefing.single().key)
        assertEquals("Review the goal", dashboard.briefing.single().focus)
        assertFalse(Dashboard.parse("{}").briefingNeedsRefresh)
    }
}
