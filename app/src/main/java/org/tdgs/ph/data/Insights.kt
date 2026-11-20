package org.tdgs.ph.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class InsightCoverage(
    val minutePercent: Double?,
    val stepDays: Int?,
    val sleepNights: Int?,
    val trainingDays: Int?,
)

data class InsightMeasurement(val date: String, val value: Double)

data class InsightMetric(
    val key: String,
    val label: String,
    val unit: String,
    val recentDays: Int,
    val baselineDays: Int,
    val recentMedian: Double?,
    val baselineMedian: Double?,
    val baselineRange: Pair<Double, Double>?,
    val latest: InsightMeasurement?,
)

data class InsightDay(val date: String, val values: Map<String, Double>)

data class InsightAssociation(
    val predictor: String,
    val outcome: String,
    val lag: Int,
    val pairs: Int,
    val status: String,
    val correlation: Double?,
)

data class StepEstimate(val date: String, val status: String, val steps: Int?, val typicalError: Int?, val replayDays: Int)

data class IntensityPlan(val target: Int, val observed: Int, val remaining: Int, val daysLeft: Int, val minutesPerDay: Int)

data class InsightsReport(
    val through: String,
    val windowDays: Int,
    val coverage: InsightCoverage,
    val metrics: List<InsightMetric>,
    val days: List<InsightDay>,
    val associations: List<InsightAssociation>,
    val stepEstimate: StepEstimate?,
    val intensityPlan: IntensityPlan?,
    val actions: List<String>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): InsightsReport {
            val report = json.parseToJsonElement(text).jsonObject
            val through = requireNotNull(report.str("through")) { "The hub returned an incomplete insights report" }
            val metrics = report.obj("metrics").orEmpty().mapNotNull { (key, value) ->
                val label = value.str("label") ?: return@mapNotNull null
                val range = value.arr("baseline_middle_80_pct").mapNotNull { it.asDoubleOrNull()?.takeIf { value -> value.isFinite() } }
                val latest = value.obj("latest")
                InsightMetric(key, label, value.str("unit").orEmpty(),
                    value.int("recent_days") ?: 0, value.int("baseline_days") ?: 0,
                    value.dbl("recent_median"), value.dbl("baseline_median"),
                    range.takeIf { it.size == 2 }?.let { it[0] to it[1] },
                    latest.str("date")?.let { date -> latest.dbl("value")?.let { InsightMeasurement(date, it) } })
            }
            val coverage = report.obj("coverage_last_7_days")
            val forecast = report.obj("steps_today_estimate")
            val goal = report.obj("weekly_intensity_goal")
            return InsightsReport(
                through = through,
                windowDays = report.int("window_days") ?: 41,
                coverage = InsightCoverage(coverage.dbl("minute_coverage_pct"), coverage.int("usable_step_days"),
                    coverage.int("recorded_sleep_nights"), coverage.int("confirmed_load_days")),
                metrics = metrics,
                days = report.arr("days").mapNotNull { row ->
                    val date = row.str("date") ?: return@mapNotNull null
                    val readings = metrics.mapNotNull { metric -> row.dbl(metric.key)?.let { metric.key to it } }.toMap()
                    InsightDay(date, readings)
                },
                associations = report.arr("associations").mapNotNull { link ->
                    val predictor = link.str("predictor") ?: return@mapNotNull null
                    val outcome = link.str("outcome") ?: return@mapNotNull null
                    InsightAssociation(predictor, outcome, link.int("lag_days") ?: 0, link.int("pairs") ?: 0,
                        link.str("status").orEmpty(), link.dbl("rank_correlation"))
                },
                stepEstimate = forecast.str("date")?.let {
                    StepEstimate(it, forecast.str("status").orEmpty(), forecast.int("estimate"),
                        forecast.int("typical_error_steps"), forecast.int("evaluation_days") ?: 0)
                },
                intensityPlan = goal.int("target")?.let {
                    IntensityPlan(it, goal.int("observed") ?: return@let null, goal.int("remaining_to_record") ?: return@let null,
                        goal.int("days_left_including_today") ?: return@let null, goal.int("even_split_minutes") ?: return@let null)
                },
                actions = report.arr("suggested_actions").mapNotNull { item ->
                    (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                },
            )
        }
    }
}
