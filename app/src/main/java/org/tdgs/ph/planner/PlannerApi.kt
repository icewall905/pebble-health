package org.tdgs.ph.planner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.tdgs.ph.data.Settings
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class PlannerLoginRequired : IOException("Sign in to your Pebble Health dashboard account.")

internal class PlannerApi(private val base: HttpUrl, private val settings: Settings) {
    private val origin = base.newBuilder().encodedPath("/").query(null).fragment(null).build()
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(100, TimeUnit.SECONDS)
        .callTimeout(105, TimeUnit.SECONDS)
        .build()

    suspend fun load() = JSONObject(request("api/planner"))
    suspend fun history(month: String) = JSONObject(request("api/planner/history", month = month))
    suspend fun calendar() = request("api/planner/calendar.ics")
    suspend fun mutate(payload: JSONObject) = JSONObject(request("api/planner", payload))

    suspend fun login(username: String, password: String) {
        request("auth/login", JSONObject(mapOf("username" to username.trim().lowercase(), "password" to password)))
    }

    suspend fun logout() {
        try {
            request("auth/logout", JSONObject())
        } catch (_: PlannerLoginRequired) {
            // The local copy still needs to be cleared after a server-side expiry.
        } finally {
            settings.savePlannerCookie(origin.toString(), null)
        }
    }

    private suspend fun request(path: String, body: JSONObject? = null, month: String? = null): String =
        withContext(Dispatchers.IO) {
            val url = origin.newBuilder().addPathSegments(path).apply {
                if (month != null) addQueryParameter("month", month)
            }.build()
            val builder = Request.Builder().url(url)
            if (path != "auth/login") {
                settings.plannerCookie(origin.toString())?.let { builder.header("Cookie", it) }
            }
            if (body != null) {
                builder.header("X-PH-Request", "1").post(body.toString().toRequestBody("application/json".toMediaType()))
            }
            client.newCall(builder.build()).execute().use { response ->
                val result = response.body.string()
                if (response.code == 401) {
                    settings.savePlannerCookie(origin.toString(), null)
                    if (path == "auth/login") throw IOException("Invalid username or password.")
                    throw PlannerLoginRequired()
                }
                if (!response.isSuccessful) {
                    val error = runCatching { JSONObject(result).optString("error") }.getOrNull()
                    throw IOException(error?.takeIf { it.isNotBlank() } ?: "Planner request failed (HTTP ${response.code}).")
                }
                val cookie = Cookie.parseAll(url, response.headers).firstOrNull { it.name == "ph_session" }
                if (cookie != null && cookie.expiresAt > System.currentTimeMillis()) {
                    settings.savePlannerCookie(origin.toString(), "${cookie.name}=${cookie.value}")
                }
                result
            }
        }
}

internal fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
    List(array.length()) { array.optJSONObject(it) }.filterNotNull()
}.orEmpty()
