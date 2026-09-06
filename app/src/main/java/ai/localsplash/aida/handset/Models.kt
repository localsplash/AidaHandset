package ai.localsplash.aida.handset

import kotlinx.serialization.Serializable

@Serializable data class Device(val id: String, val iTenantId: Long, val extensionId: String)
@Serializable data class Enrollment(val token: String, val device: Device)
@Serializable data class EnrollmentRequest(val enrollmentToken: String, val deviceId: String)
@Serializable data class Call(
    val id: String,
    val status: String,
    val version: Long,
    val caller: String? = null,
    val startedAt: String? = null,
    val extensionId: String? = null,
)
@Serializable data class CallsResponse(val calls: List<Call>)
@Serializable data class LiveKitSession(val url: String, val token: String)
@Serializable data class CallDetail(val call: Call, val livekit: LiveKitSession? = null)
@Serializable data class Command(
    val commandType: String = "TAKEOVER",
    val idempotencyKey: String,
    val expectedCallVersion: Long,
)
@Serializable data class PendingCommand(val callId: String, val command: Command)
@Serializable data class DeviceSession(
    val serverUrl: String,
    val token: String,
    val device: Device,
    val pending: PendingCommand? = null,
)
@Serializable data class TranscriptEvent(
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
