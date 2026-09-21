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

class ApiException(
    val status: Int,
    val errorResponse: AttachErrorResponse? = null,
    val rawBody: String? = null,
    override val message: String = errorResponse?.message ?: errorResponse?.reason ?: defaultMessage(status),
) : IOException(message) {
    companion object {
        private fun defaultMessage(status: Int): String = when (status) {
            401 -> "Session expired or was revoked. Re-attaching handset…"
            403 -> "Handset not recognized. Extension or network mismatch."
            404 -> "The call is no longer available."
            409 -> "Conflict occurred. Call version or takeover state changed."
            429 -> "Too many requests. Wait briefly and retry."
            503 -> "OfficePulse service unavailable."
            else -> "The server could not complete the request (HTTP $status)."
        }
    }
}

class PlatformApi(
    serverUrl: String,
    private val token: String? = null,
    private val client: OkHttpClient = defaultClient,
    allowLocalTestHttp: Boolean = false,
) {
    private val base: HttpUrl = validateServer(serverUrl, allowLocalTestHttp)

    suspend fun attach(request: AttachRequest): AttachResponse =
        json.decodeFromString(request(listOf("handset", "attach"), method = "POST", body = json.encodeToString(request), useToken = false))

    suspend fun me(): HandsetMeResponse =
        json.decodeFromString(request(listOf("handset", "me"), method = "GET"))

    suspend fun calls(): List<Call> =
        json.decodeFromString<CallsResponse>(request(listOf("handset", "calls"), method = "GET")).calls

    suspend fun call(id: String): CallDetail =
        json.decodeFromString(request(listOf("handset", "calls", id), method = "GET"))

    suspend fun takeover(callId: String, takeoverRequest: TakeoverRequest): TakeoverResponse {
        val response = request(listOf("handset", "calls", callId, "takeover"), method = "POST", body = json.encodeToString(takeoverRequest))
        return if (response.isBlank() || response == "{}") TakeoverResponse("ringing") else json.decodeFromString(response)
    }

    suspend fun logout() {
        request(listOf("handset", "logout"), method = "POST", body = "{}")
    }

    private suspend fun request(
        segments: List<String>,
        method: String = "GET",
        body: String? = null,
        useToken: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegment("v1").apply { segments.forEach(::addPathSegment) }.build()
        val requestBuilder = Request.Builder().url(url).header("Accept", "application/json")
        if (useToken && token != null) {
            requestBuilder.header("Authorization", "Bearer $token")
        }
        if (method == "POST") {
            val payload = (body ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
            requestBuilder.post(payload)
        } else {
            requestBuilder.get()
        }
        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val parsedError = runCatching { json.decodeFromString<AttachErrorResponse>(bodyStr) }.getOrNull()
                throw ApiException(response.code, parsedError, bodyStr)
            }
            bodyStr
        }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            // Do not forward device credentials to redirect destinations.
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

        fun validateServer(value: String, allowLocalTestHttp: Boolean = false): HttpUrl {
            val url = value.trim().toHttpUrl()
            require(url.isHttps || allowLocalTestHttp) {
                "Use an HTTPS OfficePulse server URL with a trusted certificate."
            }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") {
                "Enter only the server origin, for example https://officepulse-api.localsplash.dev."
            }
            return url
        }
    }
}

