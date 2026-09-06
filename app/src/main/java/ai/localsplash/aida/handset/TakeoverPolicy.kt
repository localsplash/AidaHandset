package ai.localsplash.aida.handset

import java.util.UUID

object TakeoverPolicy {
    fun prepare(call: Call, pending: PendingCommand?): PendingCommand {
        if (pending != null) {
            require(pending.callId == call.id) { "Resolve the pending takeover for call ${pending.callId} first." }
            return pending
        }
        return PendingCommand(call.id, Command(idempotencyKey = UUID.randomUUID().toString(), expectedCallVersion = call.version))
    }

    fun definitiveRejection(status: Int): Boolean = status in listOf(400, 401, 403, 404, 409, 422)
}
