package org.tdgs.ph.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class DaySummary(
    val date: String,
    val minutes: Int,
    val steps: Int?,
    val activeMinutes: Int?,
    val activeKcal: Double?,
    val restingKcal: Double?,
    val distanceM: Double?,
    val hrMinutes: Int,
    val hrMin: Int?,
    val hrAvg: Double?,
    val hrMax: Int?,
    val restingHr: Int?,
)

data class Night(
    val night: String,
    val sleepS: Int,
    /** Null when the night was estimated from minute data and has no restful split. */
    val restfulS: Int?,
    val estimated: Boolean,
    val onset: Long?,
    val wake: Long?,
    val sleepIntervals: List<Pair<Long, Long>>,
    val restfulIntervals: List<Pair<Long, Long>>,
    /** Stretches inside the night with no recording; never treated as sleep or wake. */
    val gaps: List<Pair<Long, Long>> = emptyList(),
)

data class SleepScore(val night: String?, val score: Int?, val sleepS: Int?, val restfulS: Int?, val needS: Int?,
                      val duration: Int?, val regularity: Int?, val reason: String?)

data class Score(val score: Int?, val status: String?, val reason: String?)

/** One readiness input: its 0-100 score, the hub's plain-language detail and its configured weight. */
data class ReadinessComponent(val key: String, val score: Int?, val detail: String?, val weight: Double?, val provisional: Boolean)

/** Readiness as the hub serves it in recovery.readiness (HRV model, or the stock estimate when that cannot score). */
data class Readiness(
    val score: Int?,
    val sleepOnly: Boolean,
    val status: String?,
    val reason: String?,
    val provisional: Boolean,
    val stockEstimate: Boolean,
    val components: List<ReadinessComponent>,
    val missing: List<String>,
)

/** Weekly HRV against the personal usual range (recovery.hrv_status). */
data class HrvStatus(
    val status: String?,
    val weeklyMs: Double?,
    val lastNightMs: Double?,
    val lowMs: Double?,
    val highMs: Double?,
    val nightsThisWeek: Int?,
    val baselineNights: Int?,
    val requiredNights: Int?,
    val provisional: Boolean,
    val estimate: Boolean,
    val reason: String?,
)

data class HrvNight(val night: String, val rmssdMs: Double?, val restingHr: Double?)
data class NightValue(val night: String, val value: Double)
data class Observation(val ts: Long, val hr: Int?, val steps: Int?, val snapshot: Boolean)

data class Workout(val id: String, val start: Long, val seconds: Int, val kind: String, val rpe: Double?,
                   val hrCoverage: Double?, val load: Double?, val excluded: Boolean,
                   val activeSeconds: Int? = null, val distanceM: Double? = null,
                   val ignored: Boolean = false, val editRevision: Int? = null, val editFingerprint: String? = null,
                   /** Hub id of the phone recording this watch auto-detection duplicates; it is hidden then. */
                   val supersededBy: String? = null)

data class Battery(val percent: Int?, val lastPercent: Int?, val observedAt: Long?, val status: String?,
                   val runtimeHours: Double?, val series: List<Pair<Long, Int>>)

data class OxygenReading(val ts: Long, val percent: Double)
data class OxygenDay(val date: String, val mean: Double?, val min: Double?, val max: Double?, val count: Int)
data class WeightEntry(val date: String, val kg: Double, val trendKg: Double?)
data class StressPoint(val ts: Long, val score: Int?)
data class StressDay(val date: String, val score: Int?)
data class BriefingPage(
    val key: String,
    val headline: String?,
    val summary: String?,
    val focus: String?,
    val highlights: List<String>,
    val dataNotes: List<String>,
)
data class IntensityDay(val date: String, val moderate: Double?, val vigorous: Double?, val total: Double?)

data class Dashboard(
    val now: Long,
    val tz: String,
    val today: String,
    val wearer: String?,
    val lastReceived: Long?,
    val stepsToday: Int?,
    val distanceTodayM: Double?,
    val activeKcalToday: Double?,
    val restingKcalToday: Double?,
    val activeSecondsToday: Int?,
    val latestHr: Int?,
    val latestHrAt: Long?,
    val restingHr: Double?,
    val battery: Battery,
    val sleep: SleepScore,
    val nights: List<Night>,
    val readiness: Score,
    val readinessComponents: Map<String, Int?>,
    val readinessDetail: Readiness,
    val hrvStatus: HrvStatus,
    val hrvNights: List<HrvNight>,
    /** Stock-firmware overnight pulse estimates, one per night. */
    val overnightPulse: List<NightValue>,
    val stockMode: Boolean,
    val strainScore: Int?,
    val strainMeasuredAt: Long?,
    val sleepPulse: List<NightValue>,
    val sleepRegularitySdMin: Double?,
    val stressToday: Int?,
    val stressMeasuredAt: Long?,
    /** Minute readings for roughly the last 36 hours (the hub trims the rest). */
    val observations: List<Observation>,
    val stress: Score,
    val stressPoints: List<StressPoint>,
    val stressDays: List<StressDay>,
    val oxygenLatest: OxygenReading?,
    val oxygenReadings: List<OxygenReading>,
    val oxygenDays: List<OxygenDay>,
    val weight: List<WeightEntry>,
    val weightChange7d: Double?,
    val days: List<DaySummary>,
    val workouts: List<Workout>,
    val briefing: List<BriefingPage>,
    val briefingDay: String?,
    val briefingNeedsRefresh: Boolean,
    val patternsBriefingStatus: String?,
    val intensityWeek: Double?,
    val intensityGoal: Double?,
    val intensityDays: List<IntensityDay>,
    val vo2max: Double?,
    val hrMax: Int?,
    val hrCoveragePct: Double?,
    val trainingStatus: String?,
    val acuteLoad: Double?,
    val chronicLoad: Double?,
) {
    val todayDay: DaySummary? get() = days.lastOrNull { it.date == today }
    val lastNight: Night? get() = nights.lastOrNull()?.takeIf { it.night == today }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): Dashboard = from(json.parseToJsonElement(text).jsonObject)

        fun from(o: JsonObject): Dashboard {
            val now = o.lng("now") ?: (System.currentTimeMillis() / 1000)
            val rec = o.obj("recovery")
            val today = rec.str("today") ?: ""
            val act = o.obj("current_activity")
            val days = o.arr("daily").map(::day)
            val hr = latestHr(o)
            val stock = o.obj("stock_estimates")
            // recovery.readiness is already the effective score: the hub substitutes the
            // stock estimate there only when the HRV model cannot score.
            val ready = rec.obj("readiness")?.takeIf { it.int("score") != null || it.int("sleep_based_score") != null }
                ?: stock.obj("readiness") ?: rec.obj("readiness")
            val hs = rec.obj("hrv_status")
            val strain = stock.obj("strain")
            val stressObj = o.obj("daytime_stress")
            val ox = o.obj("oxygen")
            val w = o.obj("weight")
            val fit = o.obj("fitness")
            val intensity = fit.obj("intensity")
            val training = o.obj("training").obj("latest")
            val coverage = o.obj("collection_health").arr("daily").lastOrNull()
            return Dashboard(
                now = now,
                tz = o.str("tz") ?: "UTC",
                today = today,
                wearer = o.str("wearer")?.takeIf { it.isNotBlank() },
                lastReceived = o.lng("last_received"),
                stepsToday = act.int("steps"),
                distanceTodayM = act.dbl("distance_meters"),
                activeKcalToday = act.dbl("active_kcal"),
                restingKcalToday = act.dbl("resting_kcal"),
                activeSecondsToday = act.int("active_seconds"),
                latestHr = hr?.first,
                latestHrAt = hr?.second,
                restingHr = fit.dbl("resting_hr") ?: days.lastOrNull { it.restingHr != null }?.restingHr?.toDouble(),
                battery = battery(o.obj("watch_battery")),
                sleep = sleepScore(rec.obj("sleep")),
                nights = rec.arr("sleep_nights").map(::night),
                readiness = Score(ready.int("score") ?: ready.int("sleep_based_score"), ready.str("status"), ready.str("reason")),
                readinessComponents = ready.obj("components")?.mapValues { (_, v) -> v.int("score") } ?: emptyMap(),
                readinessDetail = readiness(ready),
                hrvStatus = HrvStatus(
                    status = hs.str("status"), weeklyMs = hs.dbl("weekly_rmssd_ms"), lastNightMs = hs.dbl("last_night_rmssd_ms"),
                    lowMs = hs.dbl("baseline_low_ms"), highMs = hs.dbl("baseline_high_ms"), nightsThisWeek = hs.int("nights_this_week"),
                    baselineNights = hs.int("baseline_nights"), requiredNights = hs.int("required_baseline_nights"),
                    provisional = hs.bool("provisional") == true,
                    estimate = hs.obj("comparable_series").str("metric") == "ppi_rmssd_estimate", reason = hs.str("reason"),
                ),
                hrvNights = rec.arr("nights").mapNotNull { n -> n.str("night")?.let { HrvNight(it, n.dbl("rmssd_ms"), n.dbl("resting_hr")) } },
                overnightPulse = stock.obj("pulse").arr("points").mapNotNull { p -> p.str("night")?.let { k -> p.dbl("pulse")?.let { NightValue(k, it) } } },
                stockMode = stock.str("mode") == "stock_firmware_estimates",
                strainScore = strain.int("score"),
                strainMeasuredAt = strain.lng("measured_at"),
                sleepPulse = o.arr("sleep_pulse").mapNotNull { p -> p.str("night")?.let { k -> p.dbl("pulse")?.let { NightValue(k, it) } } },
                sleepRegularitySdMin = rec.obj("sleep").dbl("regularity_sd_min"),
                stressToday = stressObj.obj("today").int("score"),
                stressMeasuredAt = stressObj.lng("measured_at"),
                observations = o.arr("observations").mapNotNull { p ->
                    p.lng("ts")?.let { Observation(it, p.int("hr"), p.int("steps"), p.bool("snapshot") == true) }
                },
                stress = Score(stressObj.int("score"), stressObj.str("status"), stressObj.str("reason")),
                stressPoints = stressObj.arr("points").mapNotNull { p -> p.lng("ts")?.let { StressPoint(it, p.int("score")) } },
                stressDays = stressObj.arr("daily").mapNotNull { d -> d.str("date")?.let { StressDay(it, d.int("score")) } },
                oxygenLatest = ox.obj("latest")?.let { l -> l.lng("ts")?.let { t -> l.dbl("percent")?.let { OxygenReading(t, it) } } },
                oxygenReadings = ox.arr("readings").mapNotNull { r -> r.lng("ts")?.let { t -> r.dbl("percent")?.let { OxygenReading(t, it) } } },
                oxygenDays = ox.arr("daily").mapNotNull { d -> d.str("date")?.let { OxygenDay(it, d.dbl("mean"), d.dbl("min"), d.dbl("max"), d.int("count") ?: 0) } },
                weight = w.arr("entries").mapNotNull { e -> e.str("date")?.let { d -> e.dbl("kg")?.let { WeightEntry(d, it, e.dbl("trend_kg")) } } },
                weightChange7d = w.dbl("change_7d_kg"),
                days = days,
                workouts = o.arr("workouts").mapNotNull(::workout).sortedByDescending { it.start },
                briefing = briefing(o.obj("briefing")),
                briefingDay = o.obj("briefing").str("day"),
                briefingNeedsRefresh = o.obj("briefing").bool("requires_refresh") == true,
                patternsBriefingStatus = o.obj("briefing").obj("grounding").str("patterns_status"),
                intensityWeek = intensity.dbl("last_7_days"),
                intensityGoal = intensity.dbl("goal"),
                intensityDays = intensity.arr("days").mapNotNull { d ->
                    d.str("date")?.let {
                        val m = d.dbl("moderate"); val v = d.dbl("vigorous")
                        // Vigorous minutes count double towards the weekly goal, as on the hub.
                        IntensityDay(it, m, v, if (m == null && v == null) null else (m ?: 0.0) + 2 * (v ?: 0.0))
                    }
                },
                vo2max = fit.obj("vo2max").dbl("value"),
                hrMax = fit.int("hr_max") ?: o.obj("profile").int("hr_max"),
                hrCoveragePct = coverage.dbl("hr_coverage_pct"),
                trainingStatus = training.str("status"),
                acuteLoad = training.dbl("acute"),
                chronicLoad = training.dbl("chronic"),
            )
        }

        fun day(e: JsonElement) = DaySummary(
            date = e.str("date") ?: "",
            minutes = e.int("minutes") ?: 0,
            steps = e.int("steps"),
            activeMinutes = e.int("active_minutes"),
            activeKcal = e.dbl("active_kcal"),
            restingKcal = e.dbl("resting_kcal"),
            distanceM = e.dbl("distance_m"),
            hrMinutes = e.int("hr_minutes") ?: 0,
            hrMin = e.int("hr_min"),
            hrAvg = e.dbl("hr_avg"),
            hrMax = e.int("hr_max"),
            restingHr = e.int("resting_hr"),
        )

        private fun intervals(list: List<JsonElement>): List<Pair<Long, Long>> = list.mapNotNull { pair ->
            val a = (pair as? kotlinx.serialization.json.JsonArray) ?: return@mapNotNull null
            val s = a.getOrNull(0)?.asDoubleOrNull()?.toLong()
            val t = a.getOrNull(1)?.asDoubleOrNull()?.toLong()
            if (s != null && t != null && t > s) s to t else null
        }

        private fun night(e: JsonElement) = Night(
            night = e.str("night") ?: "",
            sleepS = e.int("sleep_s") ?: 0,
            restfulS = e.int("restful_s"),
            estimated = e.str("source") == "estimated_minutes",
            onset = e.lng("onset"),
            wake = e.lng("wake"),
            sleepIntervals = intervals(e.arr("sleep_intervals")),
            restfulIntervals = intervals(e.arr("restful_intervals")),
            gaps = intervals(e.arr("recorded_gaps")),
        )

        private fun readiness(r: JsonObject?): Readiness {
            val weights = r.obj("weights")
            val comps = r.obj("components")
            val keys = (weights?.keys ?: comps?.keys ?: emptySet()).ifEmpty { setOf("hrv", "resting_hr", "sleep", "training_load") }
            val score = r.int("score")
            return Readiness(
                score = score ?: r.int("sleep_based_score"),
                sleepOnly = score == null && r.int("sleep_based_score") != null,
                status = r.str("status"), reason = r.str("reason"), provisional = r.bool("provisional") == true,
                stockEstimate = r.str("formula_version") == "readiness-stock-v1",
                components = keys.map { k ->
                    val c = comps.obj(k)
                    ReadinessComponent(k, c.int("score"), c.str("detail"), weights.dbl(k), c.bool("provisional") == true)
                },
                missing = r.arr("missing").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            )
        }

        private fun sleepScore(e: JsonObject?) = SleepScore(
            night = e.str("night"), score = e.int("score"), sleepS = e.int("sleep_s"), restfulS = e.int("restful_s"),
            needS = e.int("need_s"), duration = e.obj("components").int("duration"),
            regularity = e.obj("components").int("regularity"), reason = e.str("reason"),
        )

        private fun battery(e: JsonObject?) = Battery(
            percent = e.int("percent"), lastPercent = e.int("last_percent"), observedAt = e.lng("observed_at"),
            status = e.str("status"), runtimeHours = e.obj("runtime_estimate").dbl("full_charge_hours"),
            series = e.arr("series").mapNotNull { p -> p.lng("ts")?.let { t -> p.int("percent")?.let { t to it } } },
        )

        private fun workout(e: JsonElement): Workout? {
            val id = e.str("id") ?: return null
            val start = e.lng("start") ?: return null
            return Workout(id, start, e.int("seconds") ?: 0, e.str("kind") ?: "workout", e.dbl("rpe"),
                e.dbl("hr_coverage"), e.dbl("load"), e.bool("excluded") == true || (e.int("ignored") ?: 0) != 0,
                e.int("active_seconds"), e.dbl("distance_m"),
                e.bool("ignored") == true || (e.int("ignored") ?: 0) != 0, e.int("edit_revision"), e.str("edit_fingerprint"),
                e.str("superseded_by"))
        }

        private fun briefing(e: JsonObject?): List<BriefingPage> {
            val pages = e.obj("pages") ?: return emptyList()
            return listOf("today", "patterns", "sleep", "recovery", "training", "more").mapNotNull { key ->
                val p = pages.obj(key) ?: return@mapNotNull null
                val headline = p.str("headline")?.takeIf { it.isNotBlank() }
                val summary = p.str("summary")?.takeIf { it.isNotBlank() }
                if (headline == null && summary == null) return@mapNotNull null
                BriefingPage(key, headline, summary, p.str("focus")?.takeIf { it.isNotBlank() },
                    p.arr("highlights").mapNotNull { h -> h.str("text") ?: h.str("detail") ?: h.str("label") },
                    p.arr("data_notes").mapNotNull { note -> (note as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it.isString && it.content.isNotBlank() }?.content })
            }
        }

        /** Newest minute HR from the collection-health evidence, else the hourly daily rollup. */
        private fun latestHr(o: JsonObject): Pair<Int, Long>? {
            val measured = o.obj("collection_health").obj("measurements").obj("heart_rate").lng("measured_at")
            val obs = o.arr("observations").lastOrNull { it.int("hr") != null }
            val v = obs?.int("hr") ?: return null
            return v to (obs.lng("ts") ?: measured ?: return null)
        }
    }
}

data class IntradayPoint(val ts: Long, val hr: Int?, val steps: Int?, val active: Int?, val intensity: Double?,
                         val distanceM: Double? = null)

data class Intraday(
    val date: String,
    val start: Long,
    val end: Long,
    val points: List<IntradayPoint>,
    val steps15: List<Pair<Long, Int>>,
    val sleep: List<Triple<Long, Int, String>>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Intraday {
            val o = json.parseToJsonElement(text).jsonObject
            return Intraday(
                date = o.str("date") ?: "",
                start = o.lng("start") ?: 0,
                end = o.lng("end") ?: 0,
                points = o.arr("points").mapNotNull { p ->
                    p.lng("ts")?.let { IntradayPoint(it, p.int("hr"), p.int("steps"), p.int("active"), p.dbl("intensity"), p.dbl("distance_m")) }
                },
                steps15 = o.arr("steps_15min").mapNotNull { b -> b.lng("ts")?.let { t -> b.int("steps")?.let { t to it } } },
                sleep = o.arr("sleep").mapNotNull { s ->
                    s.lng("start")?.let { st -> Triple(st, s.int("seconds") ?: 0, s.str("kind") ?: "sleep") }
                },
            )
        }
    }
}

fun parseDaily(text: String): List<DaySummary> =
    Json.parseToJsonElement(text).jsonObject.arr("items").map(Dashboard::day)
