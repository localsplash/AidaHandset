package ai.localsplash.aida.handset

import org.junit.Assert.*
import org.junit.Test

class TranscriptReducerTest {
    private fun event(sequence: Long = 1, segment: String = "segment-1", final: Boolean = false,
        text: String = "hello", stream: String = "stream-1", id: String = "$stream-$sequence") =
        TranscriptEvent("transcript", "call-1", id, stream, sequence, segment, text, final, "2026-09-06T12:00:00Z", "Caller")

    @Test fun partialIsReplacedByFinalAndLatePartialCannotRegressIt() {
        val reducer = TranscriptReducer("call-1")
        assertTrue(reducer.accept(event()))
        assertTrue(reducer.accept(event(2, final = true, text = "hello world")))
        assertFalse(reducer.accept(event(3, text = "hello")))
        assertEquals("hello world", reducer.lines.single().text)
        assertTrue(reducer.lines.single().isFinal)
    }

    @Test fun duplicateEventIsIgnoredEvenIfPayloadChanges() {
        val reducer = TranscriptReducer("call-1")
        reducer.accept(event())
        assertFalse(reducer.accept(event(text = "changed")))
        assertEquals("hello", reducer.lines.single().text)
    }

    @Test fun outOfOrderSegmentsAreSortedWithoutOverwritingNewerText() {
        val reducer = TranscriptReducer("call-1")
        reducer.accept(event(3, segment = "third"))
        reducer.accept(event(1, segment = "first"))
        reducer.accept(event(2, segment = "second"))
        reducer.accept(event(5, segment = "second", text = "updated"))
        assertFalse(reducer.accept(event(4, segment = "second", text = "stale")))
        assertEquals(listOf("first", "second", "third"), reducer.lines.map { it.segmentId })
        assertEquals("updated", reducer.lines[1].text)
        assertNotNull(reducer.gapNotice)
    }

    @Test fun streamRestartClearsPartialsAndIgnoresRetiredStreamPackets() {
        val reducer = TranscriptReducer("call-1")
        reducer.accept(event(final = true))
        reducer.accept(event(2, segment = "partial"))
        reducer.accept(event(stream = "stream-2", text = "new stream"))
        assertEquals(2, reducer.lines.size)
        assertFalse(reducer.lines.any { it.segmentId == "partial" })
        assertFalse(reducer.accept(event(3, text = "old stream")))
        assertNotNull(reducer.gapNotice)
    }

    @Test fun disconnectRetainsFinalsAndMarksAnUnrecoverableGap() {
        val reducer = TranscriptReducer("call-1")
        reducer.accept(event(final = true))
        reducer.accept(event(2, segment = "partial"))
        reducer.interrupted()
        assertEquals(1, reducer.lines.size)
        assertTrue(reducer.gapNotice!!.contains("not replayed"))
    }

    @Test fun rejectsCrossCallMalformedAndOversizedPackets() {
        val reducer = TranscriptReducer("call-1")
        assertFalse(reducer.accept(event().copy(callId = "another-business-call")))
        assertFalse(reducer.accept(event().copy(type = "command")))
        assertFalse(reducer.accept(event(sequence = 0)))
        assertFalse(reducer.accept(event().copy(streamId = "")))
        assertFalse(reducer.accept(event(text = "x".repeat(8193))))
        assertTrue(reducer.lines.isEmpty())
    }

    @Test fun limitsLongCallMemoryAndShowsRetentionNotice() {
        val reducer = TranscriptReducer("call-1", capacity = 2)
        (1L..3).forEach { reducer.accept(event(it, segment = "segment-$it", final = true)) }
        assertEquals(listOf("segment-2", "segment-3"), reducer.lines.map { it.segmentId })
        assertTrue(reducer.gapNotice!!.contains("latest 2"))
    }
}
