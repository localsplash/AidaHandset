package ai.localsplash.aida.handset

import kotlinx.serialization.Serializable

@Serializable
data class AttachRequest(
    val appInstanceId: String,
    val localIps: List<String>,
    val deviceModel: String,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val claimedMac: String? = null,
) {
    fun normalized(): AttachRequest = copy(
        claimedMac = claimedMac?.trim()?.lowercase()?.ifEmpty { null }
    )
}

@Serializable
data class Device(
    val id: String,
    val pbxInstanceId: String,
    val context: String,
    val endpointId: String,
    val extension: String,
    val label: String? = null,
)

@Serializable
data class AttachResponse(
    val token: String,
    val expiresAt: String? = null,
    val device: Device,
)

@Serializable
data class AttachErrorResponse(
    val error: String,
    val message: String? = null,
    val sentIps: List<String> = emptyList(),
    val localIps: List<String> = emptyList(),
    val publicIp: String? = null,
    val publicIpSeen: String? = null,
    val reason: String? = null,
) {
    val displayPublicIp: String? get() = publicIp ?: publicIpSeen
    val displayLocalIps: List<String> get() = if (localIps.isNotEmpty()) localIps else sentIps
}

@Serializable
data class QueueInfo(
    val name: String,
    val channel: String,
)

@Serializable
data class PusherConfig(
    val key: String,
    val cluster: String,
)

@Serializable
data class HandsetMeResponse(
    val device: Device,
    val queues: List<QueueInfo> = emptyList(),
    val pusher: PusherConfig? = null,
)

@Serializable
data class Call(
    val id: String,
    val state: String,
    val version: Long,
    val queue: String,
    val callerNumber: String? = null,
    val startedAt: String? = null,
)

@Serializable
data class CallsResponse(
    val calls: List<Call> = emptyList(),
)

@Serializable
data class TakeoverInfo(
    val status: String,
    val reason: String? = null,
    val mine: Boolean = false,
)

@Serializable
data class LiveKitSession(
    val url: String,
    val token: String,
    val expiresIn: Long? = null,
)

@Serializable
data class CallDetail(
    val call: Call,
    val agentParticipantSid: String? = null,
    val takeover: TakeoverInfo? = null,
    val livekit: LiveKitSession? = null,
)

@Serializable
data class TakeoverRequest(
    val idempotencyKey: String,
    val expectedCallVersion: Long,
)

@Serializable
data class TakeoverResponse(
    val status: String,
)

@Serializable
data class PendingTakeover(
    val callId: String,
    val idempotencyKey: String,
    val expectedCallVersion: Long,
)

@Serializable
data class DeviceSession(
    val serverUrl: String,
    val token: String,
    val expiresAt: String? = null,
    val device: Device,
    val pendingTakeover: PendingTakeover? = null,
)

@Serializable
data class PusherCallEvent(
    val v: Int = 1,
    val eventId: String? = null,
    val callSessionId: String,
    val state: String,
    val occurredAt: String? = null,
)

@Serializable
data class TranscriptEvent(
    val type: String,
    val callId: String,
    val eventId: String,
    val streamId: String,
    val sequence: Long,
    val segmentId: String,
    val text: String,
    val isFinal: Boolean,
    val timestamp: String,
    val speaker: String? = null,
)
