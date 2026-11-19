package org.tdgs.ph.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardParseTest {
    private val sample = """
    {"now": 1795115750, "tz": "Europe/Copenhagen", "wearer": "", "last_received": 1795115516,
     "current_activity": {"steps": 2881, "active_seconds": 420, "distance_meters": 2931.78, "active_kcal": 277.1, "resting_kcal": 1774.9},
     "watch_battery": {"percent": 98, "last_percent": 98, "observed_at": 1795115486, "status": "observed",
        "runtime_estimate": {"full_charge_hours": 268.5}, "series": [{"ts": 1795115486, "percent": 98}]},
     "recovery": {"today": "2026-11-19",
        "sleep": {"night": "2026-11-19", "score": 70, "sleep_s": 26940, "restful_s": 19560, "need_s": 28800,
                  "components": {"duration": 87, "regularity": 43}},
        "sleep_nights": [{"night": "2026-11-19", "sleep_s": 26940, "restful_s": 19560, "onset": 1795039260, "wake": 1795083780,
                          "sleep_intervals": [[1795039260, 1795062300], [1795079880, 1795083780]],
                          "restful_intervals": [[1795082220, 1795083840], [5, null]]}],
        "readiness": {"score": null, "status": "insufficient"}},
     "stock_estimates": {"readiness": {"score": null, "sleep_based_score": 61, "status": "insufficient",
        "components": {"sleep": {"score": 70}}}},
     "daytime_stress": {"score": null, "status": "unavailable", "points": [{"ts": 1795093800, "score": 31}], "daily": [{"date": "2026-11-19", "score": null}]},
     "oxygen": {"latest": {"ts": 1795090620, "percent": 96.0}, "readings": [], "daily": [{"date": "2026-11-19", "count": 8, "mean": 95.4, "min": 94.0, "max": 97.0}]},
     "weight": {"entries": [{"date": "2026-11-19", "kg": 114.5, "trend_kg": 114.85}], "change_7d_kg": 1.85},
     "daily": [{"date": "2026-11-18", "minutes": 1418, "steps": 2973, "hr_minutes": 24, "hr_min": 49, "hr_avg": 70.2, "hr_max": 105, "resting_hr": null},
               {"date": "2026-11-19", "minutes": 1172, "steps": 2881, "active_minutes": 7, "hr_minutes": 40, "hr_min": 45, "hr_avg": 67.8, "hr_max": 90}],
     "observations": [{"ts": 1795112880, "hr": 63, "steps": 0}, {"ts": 1795113480, "hr": null, "steps": 0}],
     "workouts": [{"id": "pebble:1794648480:walk", "start": 1794648480, "seconds": 600, "kind": "walk", "rpe": null, "hr_coverage": 0.4, "ignored": 0}],
     "briefing": {"day": "2026-11-19", "pages": {"today": {"headline": "H", "summary": "S", "highlights": [{"text": "a", "tone": "good"}], "focus": "F"}}},
     "fitness": {"hr_max": 176, "resting_hr": 41.0, "intensity": {"last_7_days": 12, "goal": 150,
        "days": [{"date": "2026-11-19", "moderate": 4, "vigorous": 1}]}, "vo2max": {"value": 36.4}},
     "collection_health": {"daily": [{"date": "2026-11-19", "hr_coverage_pct": 3.3}]},
     "training": {"latest": {"status": "warming", "acute": null}}}
    """.trimIndent()

    @Test
    fun parsesCoreFields() {
        val d = Dashboard.parse(sample)
        assertEquals("2026-11-19", d.today)
        assertEquals(2881, d.stepsToday)
        assertEquals(98, d.battery.percent)
        assertEquals(70, d.sleep.score)
        assertEquals(61, d.readiness.score, "falls back to the sleep-based stock score")
        assertEquals(63, d.latestHr)
        assertEquals(1795112880, d.latestHrAt)
        assertEquals(2, d.days.size)
        assertEquals(2881, d.todayDay?.steps)
        assertEquals(26940, d.lastNight?.sleepS)
        assertEquals(2, d.lastNight?.sleepIntervals?.size)
        assertEquals(1, d.lastNight?.restfulIntervals?.size, "malformed interval dropped")
        assertEquals(6.0, d.intensityDays.single().total)
        assertEquals("a", d.briefing.single().highlights.single())
        assertEquals(3.3, d.hrCoveragePct)
        assertNull(d.acuteLoad)
        assertTrue(d.workouts.single().kind == "walk")
    }

    @Test
    fun emptyObjectDoesNotCrash() {
        val d = Dashboard.parse("{}")
        assertNull(d.stepsToday)
        assertTrue(d.days.isEmpty() && d.nights.isEmpty())
    }

    @Test
    fun parsesIntradayAndDaily() {
        val i = Intraday.parse("""{"date":"2026-11-19","start":10,"end":20,"points":[{"ts":11,"hr":60,"steps":3,"active":0,"intensity":null}],
            "steps_15min":[{"ts":10,"steps":3}],"sleep":[{"start":1,"seconds":5,"kind":"sleep"}]}""")
        assertEquals(60, i.points.single().hr)
        assertEquals(3, i.steps15.single().second)
        val days = parseDaily("""{"items":[{"date":"2026-11-01","minutes":0,"steps":null}]}""")
        assertNull(days.single().steps)
    }
}
