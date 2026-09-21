package ai.localsplash.aida.handset

import java.util.Collections
import org.junit.Assert.*
import org.junit.Test

class AlertingAndEarlyJoinTest {

    @Test
    fun pusherCallEventParsesCorrectly() {
        val payload = """{"v":1,"eventId":"evt-456","callSessionId":"sess-789","state":"screening","occurredAt":"2026-09-20T12:00:00Z"}"""
        val event = PlatformApi.json.decodeFromString<PusherCallEvent>(payload)
        assertEquals(1, event.v)
        assertEquals("evt-456", event.eventId)
        assertEquals("sess-789", event.callSessionId)
        assertEquals("screening", event.state)
        assertEquals("2026-09-20T12:00:00Z", event.occurredAt)
    }

    @Test
    fun earlyJoinBuffersPacketsUntilAgentParticipantSidIsBound() {
        val received = mutableListOf<TranscriptEvent>()
        val pendingBuffer = Collections.synchronizedList(mutableListOf<Pair<String?, TranscriptEvent>>())
        var boundAgentSid: String? = null

        fun onEvent(event: TranscriptEvent) {
            received.add(event)
        }

        fun handlePacket(senderSid: String?, event: TranscriptEvent) {
            val currentSid = boundAgentSid
            if (currentSid == null) {
                pendingBuffer.add(senderSid to event)
            } else if (senderSid == currentSid) {
                onEvent(event)
            }
        }

        fun updateSid(sid: String) {
            boundAgentSid = sid
            val matching = synchronized(pendingBuffer) {
                val matches = pendingBuffer.filter { it.first == sid }.map { it.second }
                pendingBuffer.clear()
                matches
            }
            matching.forEach { onEvent(it) }
        }

        val eventAida1 = TranscriptEvent("transcript", "call-1", "e-1", "str-1", 1, "seg-1", "Hello from Aida", false, "2026-09-19T17:00:00Z", "assistant")
        val eventOther = TranscriptEvent("transcript", "call-1", "e-2", "str-1", 2, "seg-2", "Ignored speaker", false, "2026-09-19T17:00:00Z", "assistant")
        val eventAida2 = TranscriptEvent("transcript", "call-1", "e-3", "str-1", 3, "seg-1", "Hello from Aida greeting", true, "2026-09-19T17:00:01Z", "assistant")

        // Packets arrive before SID is bound
        handlePacket("PA_AIDA", eventAida1)
        handlePacket("PA_OTHER", eventOther)

        assertEquals(0, received.size)
        assertEquals(2, pendingBuffer.size)

        // Agent SID is discovered
        updateSid("PA_AIDA")

        assertEquals(1, received.size)
        assertEquals("Hello from Aida", received[0].text)
        assertEquals(0, pendingBuffer.size)

        // Subsequent packet from bound SID passes directly
        handlePacket("PA_AIDA", eventAida2)
        assertEquals(2, received.size)
        assertEquals("Hello from Aida greeting", received[1].text)

        // Subsequent packet from wrong SID is dropped
        handlePacket("PA_UNKNOWN", eventOther)
        assertEquals(2, received.size)
    }
}
