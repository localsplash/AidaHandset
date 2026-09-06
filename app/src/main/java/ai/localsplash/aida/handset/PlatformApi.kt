package ai.localsplash.aida.handset

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val status: Int) : IOException(when (status) {
    401 -> "Enrollment expired or was revoked. Pair this handset again."
    403 -> "This handset no longer has access to that call."
    404 -> "The call is no longer available."
    409 -> "The call changed. Refresh before trying a new takeover."
    429 -> "Too many requests. Wait briefly and retry."
    else -> "The server could not complete the request (HTTP $status)."
})

class PlatformApi(
    serverUrl: String,
    private val token: String? = null,
    private val client: OkHttpClient = defaultClient,
    allowLocalTestHttp: Boolean = false,
) {
    private val base: HttpUrl = validateServer(serverUrl, allowLocalTestHttp)

    suspend fun enroll(code: String, deviceId: String): Enrollment =
        json.decodeFromString(request(listOf("devices", "enroll"), json.encodeToString(EnrollmentRequest(code.trim(), deviceId))))

    suspend fun calls(): List<Call> = json.decodeFromString<CallsResponse>(request(listOf("calls"))).calls

    suspend fun call(id: String): CallDetail = json.decodeFromString(request(listOf("calls", id)))

    suspend fun takeover(callId: String, command: Command) {
        require(command.commandType == "TAKEOVER")
        request(listOf("calls", callId, "commands"), json.encodeToString(command))
    }

    private suspend fun request(segments: List<String>, body: String? = null): String = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegment("v1").apply { segments.forEach(::addPathSegment) }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (token != null) header("Authorization", "Bearer $token")
            if (body != null) post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code)
            response.body?.string().orEmpty()
        }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            // Do not forward enrollment codes or device credentials to redirect destinations.
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

        fun validateServer(value: String, allowLocalTestHttp: Boolean = false): HttpUrl {
            val url = value.trim().toHttpUrl()
            require(url.isHttps || (allowLocalTestHttp && url.host in listOf("localhost", "127.0.0.1"))) {
                "Use an HTTPS OfficePulse server URL with a trusted certificate."
            }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") {
                "Enter only the server origin, for example https://officepulse.localsplash.dev."
            }
            return url
        }
    }
}
