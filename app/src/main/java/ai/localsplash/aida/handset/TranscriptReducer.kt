package ai.localsplash.aida.handset

data class TranscriptLine(
    val streamId: String,
    val segmentId: String,
    val firstSequence: Long,
    val latestSequence: Long,
    val text: String,
    val isFinal: Boolean,
    val speaker: String?,
) {
    val speakerLabel: String
        get() = when (speaker?.lowercase()) {
            "assistant", "aida" -> "Aida"
            "caller" -> "Caller"
            else -> speaker?.replaceFirstChar { it.uppercase() } ?: "Unknown"
        }
}

/** In-memory live display, bounded for long calls. Sequence numbers are per agent stream. */
class TranscriptReducer(val callId: String, private val capacity: Int = 200) {
    private val segments = linkedMapOf<String, TranscriptLine>()
    private val seen = linkedSetOf<String>()
    private val retiredStreams = mutableSetOf<String>()
    private val streamOrder = mutableMapOf<String, Int>()
    private var currentStream: String? = null
    private var highestSequence = 0L
    var gapNotice: String? = null
        private set

    val lines: List<TranscriptLine>
        get() = segments.values.sortedWith(compareBy({ streamOrder[it.streamId] ?: 0 }, { it.firstSequence }))

    fun interrupted() {
        segments.entries.removeAll { !it.value.isFinal }
        gapNotice = "Connection interrupted. Some words may be missing; transcript history is not replayed."
    }

    private fun logDebug(msg: String) {
        try {
            android.util.Log.d("TranscriptReducer", msg)
        } catch (_: Throwable) {}
    }

    fun accept(event: TranscriptEvent): Boolean {
        if (event.type != "transcript") {
            logDebug("Rejected: type '${event.type}' != 'transcript'")
            return false
        }
        if (event.callId != callId && event.callId.isNotBlank()) {
            logDebug("Rejected: callId '${event.callId}' != '$callId'")
            return false
        }
        if (event.sequence < 1) {
            logDebug("Rejected: sequence ${event.sequence} < 1")
            return false
        }
        if (event.eventId.isBlank() || event.segmentId.isBlank() || event.streamId.isBlank() || event.text.length > 8192) {
            logDebug("Rejected: blank ids or length > 8192")
            return false
        }
        if (event.streamId in retiredStreams) {
            logDebug("Rejected: retired stream '${event.streamId}'")
            return false
        }
        val eventKey = "${event.streamId}:${event.eventId}"
        if (eventKey in seen) {
            logDebug("Rejected: duplicate eventKey '$eventKey'")
            return false
        }
        seen.add(eventKey)
        if (seen.size > 2048) seen.remove(seen.first())
        if (event.streamId != currentStream) {
            currentStream?.let {
                retiredStreams.add(it)
                segments.entries.removeAll { entry -> !entry.value.isFinal }
                gapNotice = "Transcription restarted. Some words may be missing; earlier partial text was cleared."
            }
            currentStream = event.streamId
            streamOrder[event.streamId] = streamOrder.size
            highestSequence = 0
        }
        if (event.sequence > highestSequence + 1) {
            gapNotice = "Some transcript events were missed. History is not replayed."
        }
        highestSequence = maxOf(highestSequence, event.sequence)
        val speaker = event.speaker?.lowercase()?.trim()
        val key = "${event.streamId}:$speaker:${event.segmentId}"
        val previous = segments[key]
        // A finalized segment is immutable; late partials and duplicate finals cannot regress it.
        if (previous != null && (previous.isFinal || event.sequence <= previous.latestSequence)) {
            logDebug("Rejected: regressed or finalized segment '$key'")
            return false
        }
        segments[key] = TranscriptLine(
            event.streamId,
            event.segmentId,
            previous?.firstSequence ?: event.sequence,
            event.sequence,
            event.text,
            event.isFinal,
            speaker,
        )
        if (segments.size > capacity) {
            val oldest = lines.firstOrNull()
            if (oldest != null) {
                val oldestKey = "${oldest.streamId}:${oldest.speaker?.lowercase()?.trim()}:${oldest.segmentId}"
                if (segments.remove(oldestKey) == null) {
                    val firstKey = segments.keys.first()
                    segments.remove(firstKey)
                }
            }
            gapNotice = "Showing the latest $capacity transcript segments. Earlier text has been cleared from this handset."
        }
        logDebug("Accepted segment '$key' (${if (event.isFinal) "FINAL" else "PARTIAL"}): '${event.text}'")
        return true
    }
}

