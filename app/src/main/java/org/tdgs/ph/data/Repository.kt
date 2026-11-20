package org.tdgs.ph.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate

data class UiState(
    val dashboard: Dashboard? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fetchedAt: Long? = null,
)

data class InsightsState(
    val report: InsightsReport? = null,
    val error: String? = null,
)

/**
 * Single source of the dashboard model. The last good response is cached in
 * app-private storage so the app and widget open instantly and work offline.
 */
class Repository(context: Context, val settings: Settings, private val api: Api = Api()) {
    private val cacheFile = File(context.filesDir, "dashboard.json")
    private val mutex = Mutex()
    private val insightsMutex = Mutex()
    private val insightsFile = File(context.filesDir, "insights.json")
    private val _insights = MutableStateFlow(InsightsState())
    val insights: StateFlow<InsightsState> = _insights.asStateFlow()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val intradayCache = HashMap<String, Intraday>()
    private val dailyCache = HashMap<String, List<DaySummary>>()

    fun loadCached() {
        if (_state.value.dashboard != null || !cacheFile.exists()) return
        runCatching { Dashboard.parse(cacheFile.readText()) }.onSuccess {
            _state.value = _state.value.copy(dashboard = it, fetchedAt = cacheFile.lastModified() / 1000)
        }
    }

    suspend fun refresh(): Result<Dashboard> = mutex.withLock {
        val cfg = settings.current() ?: return Result.failure(ApiException("Hub not configured"))
        _state.value = _state.value.copy(loading = true, error = null)
        val result = runCatching {
            val text = api.dashboard(cfg, 42)
            val parsed = Dashboard.parse(text)
            cacheFile.writeText(text)
            parsed
        }
        _state.value = result.fold(
            { UiState(it, false, null, System.currentTimeMillis() / 1000) },
            { _state.value.copy(loading = false, error = it.message ?: "Refresh failed") },
        )
        if (result.isSuccess) {
            intradayCache.clear()
            dailyCache.clear()
        }
        result
    }

    suspend fun refreshInsights() = insightsMutex.withLock {
        val cfg = settings.current() ?: return@withLock
        if (_insights.value.report == null && insightsFile.exists()) {
            runCatching { InsightsReport.parse(insightsFile.readText()) }.onSuccess {
                _insights.value = InsightsState(report = it)
            }
        }
        _insights.value = _insights.value.copy(error = null)
        try {
            val text = api.insights(cfg)
            val parsed = InsightsReport.parse(text)
            if (settings.current() != cfg) return@withLock
            runCatching { insightsFile.writeText(text) }
            _insights.value = InsightsState(report = parsed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (settings.current() != cfg) return@withLock
            val message = when ((error as? ApiException)?.status) {
                404 -> "Update your hub to use personal patterns."
                401, 403 -> "Personal patterns require a hub API token with history:read access."
                else -> "Could not refresh personal patterns."
            }
            _insights.value = _insights.value.copy(error = message)
        }
    }

    suspend fun intraday(date: LocalDate): Result<Intraday> {
        intradayCache[date.toString()]?.let { return Result.success(it) }
        val cfg = settings.current() ?: return Result.failure(ApiException("Hub not configured"))
        return runCatching { Intraday.parse(api.intraday(cfg, date)) }.onSuccess { intradayCache[date.toString()] = it }
    }

    suspend fun daily(from: LocalDate, to: LocalDate): Result<List<DaySummary>> {
        val key = "$from..$to"
        dailyCache[key]?.let { return Result.success(it) }
        val cfg = settings.current() ?: return Result.failure(ApiException("Hub not configured"))
        return runCatching { parseDaily(api.daily(cfg, from, to)) }.onSuccess { dailyCache[key] = it }
    }

    suspend fun test(cfg: HubConfig): Result<Dashboard> = runCatching { Dashboard.parse(api.dashboard(cfg, 7)) }

    fun forget() {
        cacheFile.delete()
        insightsFile.delete()
        _insights.value = InsightsState()
        intradayCache.clear()
        dailyCache.clear()
        _state.value = UiState()
    }
}
