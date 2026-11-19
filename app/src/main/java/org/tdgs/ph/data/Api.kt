package org.tdgs.ph.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ApiException(message: String, val status: Int? = null) : IOException(message)

/** Read-only client for the hub's /api/v1 endpoints (bearer API token, summary:read + history:read). */
class Api(private val client: OkHttpClient = defaultClient) {

    suspend fun dashboard(cfg: HubConfig, days: Int = 42): String = get(cfg, "dashboard") { addQueryParameter("days", days.toString()) }

    suspend fun daily(cfg: HubConfig, from: LocalDate, to: LocalDate): String =
        get(cfg, "daily") { addQueryParameter("from", from.toString()); addQueryParameter("to", to.toString()) }

    suspend fun intraday(cfg: HubConfig, date: LocalDate): String = get(cfg, "intraday") { addQueryParameter("date", date.toString()) }

    private suspend fun get(cfg: HubConfig, path: String, query: HttpUrl.Builder.() -> Unit): String = withContext(Dispatchers.IO) {
        val base = normalize(cfg.baseUrl) ?: throw ApiException("Invalid hub address")
        val url = base.newBuilder().addPathSegments("api/v1/$path").apply(query).build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${cfg.token}").header("Accept", "application/json").build()
        client.newCall(req).execute().use { r ->
            val body = r.body.string()
            when {
                r.isSuccessful -> body
                r.code == 401 || r.code == 403 -> throw ApiException("The hub rejected the API token", r.code)
                else -> throw ApiException("Hub returned HTTP ${r.code}", r.code)
            }
        }
    }

    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        /** HTTPS only (Android blocks cleartext), no credentials, query or fragment in the address. */
        fun normalize(input: String): HttpUrl? {
            val text = input.trim().let { if ("://" in it) it else "https://$it" }.trimEnd('/') + "/"
            val url = text.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https") return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
            return url
        }
    }
}
