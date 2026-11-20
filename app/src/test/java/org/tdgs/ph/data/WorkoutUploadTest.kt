package org.tdgs.ph.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WorkoutUploadTest {
    private val config = HubConfig("https://hub.example/base/", "upload-token")
    private val payload = buildJsonObject {
        put("id", "example-id")
        put("start", 1_790_000_000)
    }

    @Test
    fun postsToWorkoutEndpointWithTheUploadToken() = runBlocking {
        val api = Api(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("https://hub.example/base/fitness/phone-workouts", request.url.toString())
            assertEquals("Bearer upload-token", request.header("Authorization"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            assertEquals(payload.toString(), buffer.readUtf8())
            reply(request, 201, """{"id":"phone:example-id"}""")
        }.build())
        api.uploadWorkout(config, payload)
        assertFalse(Api.defaultClient.followRedirects)
    }

    @Test
    fun requiresMatchingAcknowledgmentAndHandlesAuthorizationFailure() = runBlocking {
        val wrongId = Api(OkHttpClient.Builder().addInterceptor {
            reply(it.request(), 201, """{"id":"phone:other-id"}""")
        }.build())
        assertFailsWith<ApiException> { wrongId.uploadWorkout(config, payload) }

        val unauthorized = Api(OkHttpClient.Builder().addInterceptor {
            assertEquals("GET", it.request().method)
            assertEquals("/base/fitness/records", it.request().url.encodedPath)
            reply(it.request(), 401, "{}")
        }.build())
        assertEquals(401, assertFailsWith<ApiException> { unauthorized.checkWorkoutToken(config) }.status)
    }

    private fun reply(request: okhttp3.Request, status: Int, body: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Test")
            .code(status).body(body.toResponseBody("application/json".toMediaType())).build()
}
