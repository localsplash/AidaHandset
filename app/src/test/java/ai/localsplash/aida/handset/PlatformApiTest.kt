package ai.localsplash.aida.handset

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlatformApiTest {
    private val server = MockWebServer()
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }
    private fun api(token: String? = null) = PlatformApi(server.url("/").toString(), token, allowLocalTestHttp = true)

    @Test fun enrollmentSendsCodeAndInstallationIdWithoutTenantOverride() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"device-secret","device":{"id":"device-1","iTenantId":7,"extensionId":"101"}}"""))
        val enrollment = api().enroll(" code ", "installation-1")
        assertEquals(7L, enrollment.device.iTenantId)
        val request = server.takeRequest()
        assertEquals("/v1/devices/enroll", request.path)
        assertEquals("POST", request.method)
        assertNull(request.getHeader("Authorization"))
        val body = PlatformApi.json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("enrollmentToken", "deviceId"), body.keys)
        assertEquals("code", body["enrollmentToken"]!!.jsonPrimitive.content)
    }

    @Test fun scopedCallListUsesDeviceBearerAndNoClientTenantParameter() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"calls":[{"id":"call-1","status":"AI_ACTIVE","version":3}]}"""))
        assertEquals("call-1", api("secret").calls().single().id)
        val request = server.takeRequest()
        assertEquals("/v1/calls", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
    }

    @Test fun parsesDataOnlyCallDetail() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"call":{"id":"call-1","status":"AI_ACTIVE","version":3},"livekit":{"url":"wss://livekit.example.test","token":"room-secret"}}"""))
        val detail = api("secret").call("call-1")
        assertEquals(3L, detail.call.version)
        assertEquals("room-secret", detail.livekit!!.token)
        assertEquals("/v1/calls/call-1", server.takeRequest().path)
    }

    @Test fun ambiguousCommandRetryKeepsExactIdempotencyAndVersion() = runBlocking {
        val call = Call("call-1", "AI_ACTIVE", 3)
        val pending = TakeoverPolicy.prepare(call, null)
        server.enqueue(MockResponse().setResponseCode(503).setBody("sensitive internal server error"))
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        try { api("secret").takeover(call.id, pending.command); fail("Expected an API error") }
        catch (error: ApiException) { assertEquals(503, error.status); assertFalse(error.message!!.contains("sensitive")) }
        val retry = TakeoverPolicy.prepare(call.copy(version = 9), pending)
        api("secret").takeover(call.id, retry.command)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
        assertEquals("POST", first.method)
        assertEquals("/v1/calls/call-1/commands", first.path)
        assertEquals(3L, retry.command.expectedCallVersion)
        assertEquals("TAKEOVER", retry.command.commandType)
    }

    @Test fun commandConflictIsDefinitiveAndDoesNotAutoRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))
        try { api().takeover("call-1", Command(idempotencyKey = "one-key", expectedCallVersion = 1)); fail("Expected conflict") }
        catch (error: ApiException) { assertTrue(TakeoverPolicy.definitiveRejection(error.status)) }
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectsAreRejectedInsteadOfForwardingEnrollmentSecrets() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/unexpected")))
        try { api().enroll("one-time-secret", "device-1"); fail("Expected redirect rejection") }
        catch (error: ApiException) { assertEquals(307, error.status) }
        assertEquals(1, server.requestCount)
    }

    @Test fun refusesNonHttpsOrCredentialBearingServerOrigins() {
        listOf("http://officepulse.example.test", "https://user:password@example.test", "https://example.test/path",
            "https://example.test?tenant=7", "https://example.test#fragment").forEach { origin ->
            assertThrows(IllegalArgumentException::class.java) { PlatformApi.validateServer(origin) }
        }
    }

    @Test fun unresolvedCommandForAnotherCallCannotBeOverwritten() {
        val pending = TakeoverPolicy.prepare(Call("call-1", "AI_ACTIVE", 1), null)
        assertThrows(IllegalArgumentException::class.java) { TakeoverPolicy.prepare(Call("call-2", "AI_ACTIVE", 1), pending) }
    }
}
