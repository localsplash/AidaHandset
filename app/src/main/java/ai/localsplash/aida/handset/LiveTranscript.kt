package ai.localsplash.aida.handset

import android.content.Context
import io.livekit.android.ConnectOptions
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LiveTranscript(private val context: Context, private val scope: CoroutineScope) {
    private var room: Room? = null
    private var events: Job? = null

    suspend fun connect(session: LiveKitSession, onEvent: (TranscriptEvent) -> Unit, onState: (String, Boolean) -> Unit) {
        require(session.url.startsWith("wss://")) { "LiveKit must use a secure wss:// URL." }
        close()
        val joined = LiveKit.create(context.applicationContext, overrides = LiveKitOverrides(
            audioOptions = AudioOptions(audioHandler = NoAudioHandler(), disableCommunicationModeWorkaround = true),
        ))
        room = joined
        events = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            joined.events.collect { event ->
                when (event) {
                    is RoomEvent.DataReceived -> if (event.data.size <= 65536 && (event.topic == "transcript" || event.topic == null)) {
                        val parsed = runCatching { PlatformApi.json.decodeFromString<TranscriptEvent>(event.data.toString(Charsets.UTF_8)) }.getOrNull()
                        if (parsed != null) onEvent(parsed)
                    }
                    is RoomEvent.Connected -> onState("Live transcript connected", false)
                    is RoomEvent.Reconnecting -> onState("Reconnecting transcript…", true)
                    is RoomEvent.Reconnected -> onState("Live transcript reconnected", true)
                    is RoomEvent.Disconnected -> onState("Transcript disconnected. Tap Reconnect.", true)
                    is RoomEvent.FailedToConnect -> onState("Transcript unavailable. Tap Reconnect.", true)
                    else -> Unit
                }
            }
        }
        // No local tracks; no automatic remote media subscriptions. The phone's SIP client owns audio.
        joined.connect(session.url, session.token, ConnectOptions(autoSubscribe = false, audio = false, video = false))
    }

    fun close() {
        events?.cancel()
        events = null
        room?.disconnect()
        room?.release()
        room = null
    }
}
