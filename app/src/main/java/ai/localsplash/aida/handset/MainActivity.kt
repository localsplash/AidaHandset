package ai.localsplash.aida.handset

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: SecureSessionStore
    private lateinit var live: LiveTranscript
    private var session: DeviceSession? = null
    private var api: PlatformApi? = null
    private var polling: Job? = null
    private var loadingCall: Job? = null
    private var commandJob: Job? = null
    private var selected: Call? = null
    private var reducer: TranscriptReducer? = null
    private var foreground = false
    private lateinit var status: TextView
    private lateinit var callsColumn: LinearLayout
    private lateinit var detailColumn: LinearLayout
    private var transcript: TextView? = null
    private var transcriptState: TextView? = null
    private var gap: TextView? = null
    private var takeOver: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = SecureSessionStore(this)
        live = LiveTranscript(this, scope)
        session = store.read()
        if (session == null) showEnrollment() else showWorkspace()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        startPolling()
        selected?.let { openCall(it, reconnect = true) }
    }

    override fun onStop() {
        foreground = false
        polling?.cancel()
        loadingCall?.cancel()
        live.close()
        reducer?.interrupted()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        live.close()
        super.onDestroy()
    }

    private fun showEnrollment(message: String? = null) {
        val root = column().apply { setPadding(dp(28), dp(24), dp(28), dp(24)) }
        root.addView(label(getString(R.string.app_name), 28f))
        root.addView(label("Pair this phone with one business extension using the one-time code from Aida Admin.", 18f))
        val server = EditText(this).apply {
            hint = "OfficePulse server URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(getString(R.string.default_server_url))
            setSingleLine()
        }
        val code = EditText(this).apply {
            hint = "One-time enrollment code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            isSaveEnabled = false
        }
        status = label(message ?: "The phone's built-in SIP app continues to handle audio.", 16f)
        root.addView(server)
        root.addView(code)
        val pair = button("Pair handset") {
            if (code.text.isBlank()) { status.text = "Enter the enrollment code from Aida Admin."; return@button }
            val endpoint = server.text.toString().trim()
            val enrollmentCode = code.text.toString()
            it.isEnabled = false
            status.text = "Pairing…"
            scope.launch {
                try {
                    val result = PlatformApi(endpoint).enroll(enrollmentCode, store.deviceId)
                    check(result.token.isNotBlank() && result.device.id.isNotBlank()) { "The server returned an invalid enrollment." }
                    val paired = DeviceSession(endpoint, result.token, result.device)
                    store.save(paired)
                    session = paired
                    code.text.clear()
                    showWorkspace()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    status.text = friendly(error)
                    it.isEnabled = true
                }
            }
        }
        root.addView(pair)
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showWorkspace() {
        val paired = session ?: return
        api = PlatformApi(paired.serverUrl, paired.token)
        val root = column().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("${getString(R.string.app_name)}  ·  Business ${paired.device.iTenantId}  ·  Ext ${paired.device.extensionId}", 20f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Unpair") { confirmUnpair() })
        header.addView(button("Pending request") {
            val pending = session?.pending
            AlertDialog.Builder(this).setTitle("Pending takeover")
                .setMessage(if (pending == null) "There is no pending takeover." else
                    "Call ${pending.callId} has an unconfirmed takeover. Open it to retry. Clear only after checking the phone; clearing does not cancel the requested handoff.")
                .setNegativeButton("Close", null).apply {
                    if (pending != null) setPositiveButton("Clear pending retry") { _, _ ->
                        persistPending(null)
                        takeOver?.text = "Take over"
                    }
                }.show()
        })
        root.addView(header)
        status = label("Loading authorized calls…", 16f)
        root.addView(status)
        val body = LinearLayout(this)
        callsColumn = column()
        detailColumn = column().apply { setPadding(dp(16), 0, 0, 0) }
        detailColumn.addView(label("Select a call to view its live transcript.", 22f))
        body.addView(ScrollView(this).apply { addView(callsColumn) }, LinearLayout.LayoutParams(0, -1, 1f))
        body.addView(detailColumn, LinearLayout.LayoutParams(0, -1, 2f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        startPolling()
    }

    private fun startPolling() {
        if (!foreground || session == null || polling?.isActive == true) return
        polling = scope.launch {
            while (isActive) {
                refreshCalls()
                delay(5000)
            }
        }
    }

    private suspend fun refreshCalls() {
        try {
            val calls = api?.calls() ?: return
            status.text = if (calls.isEmpty()) "No active calls for this extension." else "${calls.size} active call(s) · updates every 5 seconds"
            callsColumn.removeAllViews()
            for (call in calls) {
                callsColumn.addView(button("${call.caller ?: "Caller"}\n${call.status} · ${call.id.take(12)}") { openCall(call) })
            }
            val current = selected
            if (current != null) {
                val updated = calls.find { it.id == current.id }
                if (updated == null) {
                    loadingCall?.cancel()
                    live.close()
                    selected = null
                    takeOver?.isEnabled = false
                    transcriptState?.text = "Call ended or access removed."
                } else {
                    selected = updated
                }
            }
        } catch (error: Exception) { handle(error) }
    }

    private fun openCall(call: Call, reconnect: Boolean = false) {
        loadingCall?.cancel()
        live.close()
        if (selected?.id != call.id) reducer = TranscriptReducer(call.id) else if (reconnect) reducer?.interrupted()
        selected = call
        renderDetail(call)
        loadingCall = scope.launch {
            try {
                val detail = api?.call(call.id) ?: return@launch
                check(detail.call.id == call.id) { "The server returned the wrong call." }
                selected = detail.call
                val room = detail.livekit
                if (room == null) {
                    transcriptState?.text = "Live transcription is not available for this call yet. Tap Reconnect to retry."
                } else {
                    live.connect(room, onEvent = { event ->
                        if (selected?.id == call.id && reducer?.accept(event) == true) updateTranscript()
                    }, onState = { message, interrupted ->
                        if (selected?.id == call.id) {
                            transcriptState?.text = message
                            if (interrupted) reducer?.interrupted()
                            updateTranscript()
                        }
                    })
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                transcriptState?.text = "Live transcript unavailable. Tap Reconnect."
                handle(error)
            }
        }
    }

    private fun renderDetail(call: Call) {
        detailColumn.removeAllViews()
        detailColumn.addView(label(call.caller ?: "Active call", 26f))
        detailColumn.addView(label("${call.status} · ${call.startedAt ?: call.id}", 15f))
        val actions = LinearLayout(this)
        takeOver = button(if (session?.pending?.callId == call.id) "Retry pending takeover" else "Take over") { confirmTakeover() }
        takeOver?.isEnabled = commandJob?.isActive != true
        actions.addView(takeOver)
        actions.addView(button("Reconnect") { selected?.let { openCall(it, reconnect = true) } })
        detailColumn.addView(actions)
        transcriptState = label("Connecting live transcript…", 16f)
        detailColumn.addView(transcriptState)
        gap = label("Live text starts when you open this call. Earlier history is unavailable.", 14f).apply { setTextColor(Color.rgb(135, 77, 0)) }
        detailColumn.addView(gap)
        transcript = label("Waiting for speech…", 22f).apply { setTextIsSelectable(true) }
        detailColumn.addView(ScrollView(this).apply { addView(transcript) }, LinearLayout.LayoutParams(-1, 0, 1f))
        updateTranscript()
    }

    private fun updateTranscript() {
        val state = reducer ?: return
        gap?.text = state.gapNotice ?: "Live text starts when you open this call. Earlier history is unavailable."
        transcript?.text = state.lines.joinToString("\n\n") { line ->
            "${line.speaker?.let { "$it: " }.orEmpty()}${line.text}${if (line.isFinal) "" else " …"}"
        }.ifEmpty { "Waiting for speech…" }
    }

    private fun confirmTakeover() {
        if (selected == null || commandJob?.isActive == true) return
        AlertDialog.Builder(this).setTitle("Take over this call?")
            .setMessage("OfficePulse will request the handoff to this extension. Answer using the phone's SIP controls.")
            .setNegativeButton("Cancel", null).setPositiveButton("Take over") { _, _ -> executeTakeover() }.show()
    }

    private fun executeTakeover() {
        val call = selected ?: return
        val paired = session ?: return
        commandJob = scope.launch {
            takeOver?.isEnabled = false
            try {
                val pending = TakeoverPolicy.prepare(call, paired.pending)
                // Persist before sending: a timeout or process death must never create a second command.
                val saved = paired.copy(pending = pending)
                store.save(saved)
                session = saved
                api?.takeover(call.id, pending.command) ?: error("Handset is no longer paired.")
                persistPending(null)
                status.text = "Takeover request accepted. Follow the phone's SIP controls; call updates confirm progress."
                takeOver?.text = "Take over"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ApiException && TakeoverPolicy.definitiveRejection(error.status)) persistPending(null)
                handle(error)
                if (session?.pending != null) {
                    status.text = "Takeover outcome is unconfirmed. Retry reuses the saved request; ${friendly(error)}"
                    takeOver?.text = "Retry pending takeover"
                }
            } finally { takeOver?.isEnabled = selected != null }
        }
    }

    private fun persistPending(pending: PendingCommand?) {
        session?.copy(pending = pending)?.let { store.save(it); session = it }
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this).setTitle("Unpair this handset?")
            .setMessage("This removes local access and pending retries. Revoke the device in Aida Admin to invalidate its server token.")
            .setNegativeButton("Cancel", null).setPositiveButton("Unpair") { _, _ -> unpair() }.show()
    }

    private fun unpair(message: String? = null) {
        polling?.cancel()
        loadingCall?.cancel()
        commandJob?.cancel()
        live.close()
        store.clear()
        session = null
        api = null
        selected = null
        reducer = null
        showEnrollment(message)
    }

    private fun handle(error: Exception) {
        if (error is CancellationException) throw error
        if (error is ApiException && error.status in listOf(401, 403)) unpair(error.message)
        else status.text = friendly(error)
    }

    private fun friendly(error: Exception): String = when (error) {
        is ApiException -> error.message ?: "Server request failed."
        is IllegalArgumentException -> error.message ?: "Check the server URL and enrollment code."
        is IOException -> "Cannot reach the server. Check this phone's network and the server certificate, then retry."
        else -> "Unable to complete this action. Check the server configuration and retry."
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(28, 41, 57)); setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(value: String, action: (View) -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; minHeight = dp(52); setOnClickListener(action)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
