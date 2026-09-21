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

    @Test fun attachSuccessSendsLocalIpsAndDeviceModel() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"token":"device-secret","expiresAt":"2026-09-21T12:00:00Z","device":{"id":"dev-1","pbxInstanceId":"pbx-1","context":"localsplash","endpointId":"411","extension":"411"}}"""
            )
        )
        val attachResp = api().attach(
            AttachRequest(
                appInstanceId = "app-123",
                localIps = listOf("192.168.6.97"),
                deviceModel = "Grandstream GXV3450",
                appVersion = "0.1.0",
                claimedMac = "ec:74:d7:c9:27:18",
            )
        )
        assertEquals("device-secret", attachResp.token)
        assertEquals("411", attachResp.device.extension)
        assertEquals("localsplash", attachResp.device.context)

        val request = server.takeRequest()
        assertEquals("/v1/handset/attach", request.path)
        assertEquals("POST", request.method)
        assertNull(request.getHeader("Authorization"))
        val body = PlatformApi.json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("app-123", body["appInstanceId"]!!.jsonPrimitive.content)
        assertEquals("Grandstream GXV3450", body["deviceModel"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", body["appVersion"]!!.jsonPrimitive.content)
        assertEquals("ec:74:d7:c9:27:18", body["claimedMac"]!!.jsonPrimitive.content)
    }

    @Test fun attachOmitsClaimedMacWhenNullOrEmpty() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"token":"device-secret","expiresAt":"2026-09-21T12:00:00Z","device":{"id":"dev-1","pbxInstanceId":"pbx-1","context":"localsplash","endpointId":"411","extension":"411"}}"""
            )
        )
        api().attach(
            AttachRequest(
                appInstanceId = "app-123",
                localIps = listOf("192.168.6.97"),
                deviceModel = "Grandstream GXV3450",
                appVersion = "0.1.0",
                claimedMac = null,
            )
        )
        val request = server.takeRequest()
        val rawBody = request.body.readUtf8()
        val body = PlatformApi.json.parseToJsonElement(rawBody).jsonObject
        assertFalse("claimedMac must be omitted from JSON when null", body.containsKey("claimedMac"))
        assertEquals("0.1.0", body["appVersion"]!!.jsonPrimitive.content)
    }

    @Test fun attachNotRecognizedCarriesDiagnosticAddresses() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":"handset_not_recognized","message":"No registered contact matched","sentIps":["192.168.6.97"],"publicIpSeen":"172.116.149.216","reason":"unmatched_via_addr"}"""
            )
        )
        try {
            api().attach(AttachRequest("app-1", listOf("192.168.6.97"), "GXV3450"))
            fail("Expected 403 ApiException")
        } catch (error: ApiException) {
            assertEquals(403, error.status)
            assertNotNull(error.errorResponse)
            assertEquals("handset_not_recognized", error.errorResponse?.error)
            assertEquals("172.116.149.216", error.errorResponse?.publicIpSeen)
            assertEquals("unmatched_via_addr", error.errorResponse?.reason)
        }
    }

    @Test fun attachAmbiguousCarriesComparisonDetails() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"error":"handset_ambiguous","message":"Multiple contacts matched","sentIps":["192.168.6.97"],"reason":"multiple_contacts"}"""
            )
        )
        try {
            api().attach(AttachRequest("app-1", listOf("192.168.6.97"), "GXV3450"))
            fail("Expected 409 ApiException")
        } catch (error: ApiException) {
            assertEquals(409, error.status)
            assertEquals("multiple_contacts", error.errorResponse?.reason)
        }
    }

    @Test fun callsAndCallDetailUseBearerToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"calls":[{"id":"call-1","state":"screening","version":2,"queue":"support","callerNumber":"+15551234567"}]}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"call":{"id":"call-1","state":"screening","version":2,"queue":"support"},"agentParticipantSid":"PA_123","livekit":{"url":"wss://livekit.example.test","token":"jwt-token"}}"""
            )
        )
        val api = api("secret-token")
        val calls = api.calls()
        assertEquals(1, calls.size)
        assertEquals("screening", calls[0].state)
        assertEquals("+15551234567", calls[0].callerNumber)

        val detail = api.call("call-1")
        assertEquals("PA_123", detail.agentParticipantSid)
        assertEquals("wss://livekit.example.test", detail.livekit?.url)

        val req1 = server.takeRequest()
        assertEquals("/v1/handset/calls", req1.path)
        assertEquals("Bearer secret-token", req1.getHeader("Authorization"))

        val req2 = server.takeRequest()
        assertEquals("/v1/handset/calls/call-1", req2.path)
        assertEquals("Bearer secret-token", req2.getHeader("Authorization"))
    }

    @Test fun takeoverRetryKeepsExactIdempotencyKeyAndVersion() = runBlocking {
        val call = Call("call-1", "screening", 3, "support")
        val pending = TakeoverPolicy.prepare(call, null)
        server.enqueue(MockResponse().setResponseCode(503).setBody("internal server error"))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"ringing"}"""))

        val api = api("secret-token")
        try {
            api.takeover(call.id, TakeoverRequest(pending.idempotencyKey, pending.expectedCallVersion))
            fail("Expected 503")
        } catch (e: ApiException) {
            assertEquals(503, e.status)
        }

        // Retry with same pending after process recovery
        val retry = TakeoverPolicy.prepare(call.copy(version = 9), pending)
        val resp = api.takeover(call.id, TakeoverRequest(retry.idempotencyKey, retry.expectedCallVersion))
        assertEquals("ringing", resp.status)

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
        assertEquals("/v1/handset/calls/call-1/takeover", first.path)
        assertEquals(3L, retry.expectedCallVersion)
    }

    @Test fun commandConflictIsDefinitiveAndDoesNotAutoRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))
        try {
            api().takeover("call-1", TakeoverRequest("key-1", 1))
            fail("Expected conflict")
        } catch (error: ApiException) {
            assertTrue(TakeoverPolicy.definitiveRejection(error.status))
        }
    }

    @Test fun redirectsAreRejectedInsteadOfForwardingSecrets() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/unexpected")))
        try {
            api().attach(AttachRequest("app-1", listOf("192.168.6.97"), "GXV3450"))
            fail("Expected redirect rejection")
        } catch (error: ApiException) {
            assertEquals(307, error.status)
        }
    }

    @Test fun refusesNonHttpsOrCredentialBearingServerOrigins() {
        listOf(
            "http://officepulse.example.test",
            "https://user:password@example.test",
            "https://example.test/path",
            "https://example.test?tenant=7",
            "https://example.test#fragment",
        ).forEach { origin ->
            assertThrows(IllegalArgumentException::class.java) { PlatformApi.validateServer(origin) }
        }
    }

    @Test fun unresolvedCommandForAnotherCallCannotBeOverwritten() {
        val pending = TakeoverPolicy.prepare(Call("call-1", "screening", 1, "support"), null)
        assertThrows(IllegalArgumentException::class.java) {
            TakeoverPolicy.prepare(Call("call-2", "screening", 1, "support"), pending)
        }
    }
}
