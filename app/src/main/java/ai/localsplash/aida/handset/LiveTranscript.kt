package ai.localsplash.aida.handset

import android.content.Context
import android.util.Log
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@OptIn(io.livekit.android.annotations.Beta::class)
class LiveTranscript(private val context: Context, private val scope: CoroutineScope) {
    private var room: Room? = null
    private var events: Job? = null
    @Volatile private var expectedCallId: String? = null
    @Volatile private var boundAgentSid: String? = null
    @Volatile private var isRoomConnected = false
    val isConnected: Boolean
        get() = isRoomConnected && room != null
    val currentCallId: String?
        get() = expectedCallId
    private val pendingBuffer = Collections.synchronizedList(mutableListOf<Pair<String?, TranscriptEvent>>())
    private val streamSequenceCounters = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private var onEventCallback: ((TranscriptEvent) -> Unit)? = null

    private fun nextSequence(streamId: String): Long {
        return synchronized(streamSequenceCounters) {
            val current = streamSequenceCounters[streamId] ?: 0L
            val next = current + 1L
            streamSequenceCounters[streamId] = next
            next
        }
    }

    suspend fun connect(
        callId: String,
        session: LiveKitSession,
        agentParticipantSid: String? = null,
        onEvent: (TranscriptEvent) -> Unit,
        onState: (String, Boolean) -> Unit,
    ) {
        val wsUrl = when {
            session.url.startsWith("https://") -> "wss://" + session.url.removePrefix("https://")
            session.url.startsWith("http://") -> "ws://" + session.url.removePrefix("http://")
            else -> session.url
        }
        require(wsUrl.startsWith("wss://") || wsUrl.startsWith("ws://")) { "LiveKit URL must be a ws:// or wss:// URL: ${session.url}" }
        if (expectedCallId == callId && isConnected) {
            Log.i(TAG, "Already connected to callId=$callId, updating callbacks")
            onEventCallback = onEvent
            onState("Live transcript connected", false)
            return
        }
        close()
        expectedCallId = callId
        boundAgentSid = agentParticipantSid
        onEventCallback = onEvent
        pendingBuffer.clear()
        streamSequenceCounters.clear()

        Log.i(TAG, "Connecting to LiveKit: url=$wsUrl (orig=${session.url}), callId=$callId, boundAgentSid=$agentParticipantSid")

        val joined = LiveKit.create(
            context.applicationContext,
            overrides = LiveKitOverrides(
                audioOptions = AudioOptions(audioHandler = NoAudioHandler(), disableCommunicationModeWorkaround = true),
            ),
        )
        room = joined
        events = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            joined.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        isRoomConnected = true
                        Log.i(TAG, "Room Connected: ${joined.name}")
                        onState("Live transcript connected", false)
                    }
                    is RoomEvent.Reconnecting -> {
                        Log.w(TAG, "Room Reconnecting: ${joined.name}")
                        onState("Reconnecting transcript…", true)
                    }
                    is RoomEvent.Reconnected -> {
                        isRoomConnected = true
                        Log.i(TAG, "Room Reconnected: ${joined.name}")
                        onState("Live transcript reconnected", true)
                    }
                    is RoomEvent.Disconnected -> {
                        isRoomConnected = false
                        Log.w(TAG, "Room Disconnected: ${joined.name}, error: ${event.error}")
                        onState("Transcript disconnected.", true)
                    }
                    is RoomEvent.FailedToConnect -> {
                        isRoomConnected = false
                        val msg = event.error?.message ?: "Connection failed"
                        Log.e(TAG, "Room FailedToConnect: ${joined.name}, error: $msg", event.error)
                        onState("Transcript unavailable: $msg", true)
                    }
                    is RoomEvent.TranscriptionReceived -> {
                        val participant = event.participant
                        val identity = participant?.identity?.value.orEmpty()
                        Log.i(TAG, "TranscriptionReceived from $identity: ${event.transcriptionSegments.size} segments")
                        val speakerName = if (identity.contains("agent", ignoreCase = true) || identity.contains("assistant", ignoreCase = true)) {
                            "assistant"
                        } else if (identity.contains("caller", ignoreCase = true) || identity.contains("sip", ignoreCase = true)) {
                            "caller"
                        } else {
                            identity.ifEmpty { participant?.name ?: "assistant" }
                        }
                        val streamId = expectedCallId?.ifBlank { "stream-0" } ?: "stream-0"
                        for (seg in event.transcriptionSegments) {
                            val seq = nextSequence(streamId)
                            val ev = TranscriptEvent(
                                type = "transcript",
                                callId = expectedCallId.orEmpty(),
                                eventId = "$streamId-${seg.id}-$seq",
                                streamId = streamId,
                                sequence = seq,
                                segmentId = seg.id,
                                text = seg.text,
                                isFinal = seg.final,
                                timestamp = seg.lastReceivedTime.toString(),
                                speaker = speakerName,
                            )
                            Log.i(TAG, "TranscriptionSegment from $speakerName ($identity) [final=${seg.final}, seq=$seq]: ${seg.text}")
                            handleDataPacket(participant?.sid?.value, ev)
                        }
                    }
                    is RoomEvent.DataReceived -> if (event.data.size <= 65536) {
                        val rawStr = event.data.toString(Charsets.UTF_8)
                        val senderSid = event.participant?.sid?.value
                        Log.i(TAG, "DataReceived from $senderSid topic=${event.topic}: $rawStr")
                        val parsed = parseDataPacket(rawStr, expectedCallId)
                        if (parsed != null) {
                            handleDataPacket(senderSid, parsed)
                        } else {
                            Log.w(TAG, "Could not parse data packet: $rawStr")
                        }
                    }
                    else -> Unit
                }
            }
        }
        // No local tracks; no automatic remote media subscriptions. The phone's SIP client owns audio.
        try {
            joined.connect(wsUrl, session.token, ConnectOptions(autoSubscribe = false, audio = false, video = false))
        } catch (e: Exception) {
            Log.e(TAG, "Exception during joined.connect: ${e.message}", e)
            onState("Transcript connection error: ${e.message}", true)
        }
    }

    fun updateAgentParticipantSid(sid: String) {
        boundAgentSid = sid
        val targetCallback = onEventCallback ?: return
        val matchingPackets = synchronized(pendingBuffer) {
            val matching = pendingBuffer.filter { it.first == sid }.map { it.second }
            pendingBuffer.clear()
            matching
        }
        matchingPackets.forEach(targetCallback)
    }

    private fun handleDataPacket(senderSid: String?, event: TranscriptEvent) {
        val targetCallback = onEventCallback ?: run {
            Log.w(TAG, "No callback registered for event: ${event.text}")
            return
        }
        val currentSid = boundAgentSid
        Log.d(TAG, "handleDataPacket: senderSid=$senderSid, boundAgentSid=$currentSid, speaker=${event.speaker}, text='${event.text}'")
        // If an explicit agent SID is configured, filter by it; otherwise deliver immediately!
        if (currentSid == null || senderSid == null || senderSid == currentSid) {
            targetCallback(event)
        } else {
            // Buffer in case SID binding is updated
            synchronized(pendingBuffer) {
                if (pendingBuffer.size < 100) {
                    pendingBuffer.add(senderSid to event)
                }
            }
        }
    }

    private fun parseDataPacket(raw: String, expectedCallId: String?): TranscriptEvent? {
        val strict = runCatching { PlatformApi.json.decodeFromString<TranscriptEvent>(raw) }.getOrNull()
        if (strict != null && strict.text.isNotBlank()) {
            val sid = strict.streamId.ifBlank { "stream-0" }
            val seq = if (strict.sequence > 0) strict.sequence else nextSequence(sid)
            return strict.copy(
                callId = strict.callId.ifBlank { expectedCallId.orEmpty() },
                streamId = sid,
                sequence = seq,
                segmentId = strict.segmentId.ifBlank { "seg-$sid-$seq" },
                eventId = strict.eventId.ifBlank { "ev-$sid-$seq" },
            )
        }

        return try {
            val element = PlatformApi.json.parseToJsonElement(raw)
            val obj = (element as? JsonObject) ?: return null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type == "aida.event.agent_ready") {
                val sid = obj["agentParticipantSid"]?.jsonPrimitive?.contentOrNull
                if (!sid.isNullOrBlank()) {
                    updateAgentParticipantSid(sid)
                }
                return null
            }
            val text = obj["text"]?.jsonPrimitive?.contentOrNull
                ?: obj["transcript"]?.jsonPrimitive?.contentOrNull
                ?: obj["content"]?.jsonPrimitive?.contentOrNull
                ?: return null
            if (text.isBlank()) return null

            val speaker = obj["speaker"]?.jsonPrimitive?.contentOrNull
                ?: obj["role"]?.jsonPrimitive?.contentOrNull
                ?: obj["participant"]?.jsonPrimitive?.contentOrNull
            val isFinal = obj["isFinal"]?.jsonPrimitive?.booleanOrNull
                ?: obj["is_final"]?.jsonPrimitive?.booleanOrNull
                ?: obj["final"]?.jsonPrimitive?.booleanOrNull
                ?: false
            val streamId = obj["streamId"]?.jsonPrimitive?.contentOrNull
                ?: obj["stream_id"]?.jsonPrimitive?.contentOrNull
                ?: "stream-0"
            val rawSeq = obj["sequence"]?.jsonPrimitive?.longOrNull ?: 0L
            val seq = if (rawSeq > 0) rawSeq else nextSequence(streamId)
            val segmentId = obj["segmentId"]?.jsonPrimitive?.contentOrNull
                ?: obj["segment_id"]?.jsonPrimitive?.contentOrNull
                ?: obj["id"]?.jsonPrimitive?.contentOrNull
                ?: "seg-$streamId-$seq"
            val callId = obj["callId"]?.jsonPrimitive?.contentOrNull
                ?: obj["call_id"]?.jsonPrimitive?.contentOrNull
                ?: expectedCallId.orEmpty()
            val eventId = obj["eventId"]?.jsonPrimitive?.contentOrNull
                ?: obj["event_id"]?.jsonPrimitive?.contentOrNull
                ?: "ev-$streamId-$seq"

            TranscriptEvent(
                type = "transcript",
                callId = callId,
                eventId = eventId,
                streamId = streamId,
                sequence = seq,
                segmentId = segmentId,
                text = text,
                isFinal = isFinal,
                timestamp = System.currentTimeMillis().toString(),
                speaker = speaker,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        events?.cancel()
        events = null
        try {
            room?.disconnect()
            room?.release()
        } catch (_: Exception) {}
        room = null
        isRoomConnected = false
        expectedCallId = null
        boundAgentSid = null
        onEventCallback = null
        pendingBuffer.clear()
        streamSequenceCounters.clear()
    }

    companion object {
        private const val TAG = "LiveTranscript"
    }
}

