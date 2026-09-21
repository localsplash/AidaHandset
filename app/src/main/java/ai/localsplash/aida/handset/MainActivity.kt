package ai.localsplash.aida.handset

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Chronometer
import android.widget.EditText
import android.widget.HorizontalScrollView
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
    private val live = LiveTranscript(this, scope)
    private var session: DeviceSession? = null
    private var api: PlatformApi? = null
    private var polling: Job? = null
    private var loadingCall: Job? = null
    private var earlyJoinJob: Job? = null
    private var commandJob: Job? = null
    private var selectedCallId: String? = null
    private var selectedCall: Call? = null
    private var reducer: TranscriptReducer? = null
    private var foreground = false
    private var autoScroll = true
    private val callStartTimes = mutableMapOf<String, Long>()

    // UI elements
    private lateinit var rootContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var callsTabs: LinearLayout
    private lateinit var tabsScroll: HorizontalScrollView
    private lateinit var detailContainer: LinearLayout
    private var detachBtn: Button? = null
    private var transcriptScroll: ScrollView? = null
    private var transcriptView: TextView? = null
    private var transcriptStateView: TextView? = null
    private var gapNoticeView: TextView? = null
    private var jumpToLatestBtn: Button? = null
    private var takeOverBtn: Button? = null
    private var stateBannerView: TextView? = null
    private var chronometerView: Chronometer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureSessionStore(this)
        AlertingService.start(this)

        session = store.read()
        if (session == null) {
            attemptAttach()
        } else {
            showWorkspace()
        }

        observeIncomingAlerts()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val callId = intent?.getStringExtra("EXTRA_CALL_ID") ?: return
        val shouldTakeover = intent.getBooleanExtra("EXTRA_ACTION_TAKEOVER", false)
        Log.i(TAG, "handleIntent: callId=$callId, shouldTakeover=$shouldTakeover, selectedCallId=$selectedCallId")
        scope.launch {
            AlertingService.dismissCallAlert(this@MainActivity)
            val calls = AlertingService.activeCalls.value
            val target = calls.find { it.id == callId }
            if (target != null) {
                if (selectedCallId != callId || selectedCall == null || live.currentCallId != callId || !live.isConnected) {
                    openCall(target)
                } else {
                    renderCallScreen(target)
                    updateTranscriptUI()
                }
                if (shouldTakeover) {
                    executeTakeover(target)
                }
            } else {
                if (selectedCallId != callId || selectedCall == null || live.currentCallId != callId || !live.isConnected) {
                    fetchAndOpenCall(callId, shouldTakeover)
                } else {
                    selectedCall?.let {
                        renderCallScreen(it)
                        updateTranscriptUI()
                        if (shouldTakeover) executeTakeover(it)
                    }
                }
            }
        }
    }

    private fun observeIncomingAlerts() {
        scope.launch {
            AlertingService.incomingAlertCall.collect { callId ->
                if (selectedCallId == null) {
                    selectedCallId = callId
                    fetchAndOpenCall(callId)
                }
            }
        }
        scope.launch {
            AlertingService.activeCalls.collect { calls ->
                if (session != null) {
                    renderCallTabs(calls)
                    val currentId = selectedCallId
                    if (currentId != null) {
                        val current = calls.find { it.id == currentId }
                        if (current != null) {
                            updateCallState(current)
                        } else if (selectedCall != null) {
                            handleCallEnded()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        startPolling()
        selectedCall?.let {
            if (live.currentCallId != it.id || !live.isConnected) {
                openCall(it)
            } else {
                renderCallScreen(it)
                updateTranscriptUI()
            }
        }
    }

    override fun onStop() {
        foreground = false
        polling?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        live.close()
        super.onDestroy()
    }

    private fun attemptAttach(customServerUrl: String? = null) {
        val serverUrl = customServerUrl ?: session?.serverUrl ?: getString(R.string.default_server_url)
        val root = column().apply { setPadding(dp(28), dp(24), dp(28), dp(24)) }
        root.addView(label(getString(R.string.app_name), 28f))
        val status = label("Identifying this phone…", 18f)
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })

        scope.launch {
            val localIps = DeviceIdentifier.getLocalIps()
            val claimedMac = DeviceIdentifier.getClaimedMac()
            val deviceModel = DeviceIdentifier.getDeviceModel()

            val attachApi = try {
                PlatformApi(serverUrl)
            } catch (e: Exception) {
                showAttachFailure(serverUrl, localIps, null, e.message ?: "Invalid server URL")
                return@launch
            }

            try {
                val resp = attachApi.attach(
                    AttachRequest(
                        appInstanceId = store.appInstanceId,
                        localIps = localIps,
                        deviceModel = deviceModel,
                        appVersion = BuildConfig.VERSION_NAME,
                        claimedMac = claimedMac?.takeIf { it.isNotBlank() },
                    )
                )
                val newSession = DeviceSession(
                    serverUrl = serverUrl,
                    token = resp.token,
                    expiresAt = resp.expiresAt,
                    device = resp.device,
                )
                store.save(newSession)
                session = newSession
                status.text = "Extension ${resp.device.extension} (${resp.device.context})"
                delay(600)
                showWorkspace()
                AlertingService.instance?.triggerRefresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val errResp = (error as? ApiException)?.errorResponse
                val reason = errResp?.reason ?: errResp?.message ?: errResp?.error ?: error.message ?: "Attach failed"
                val publicIpSeen = errResp?.displayPublicIp
                val sentIps = errResp?.displayLocalIps?.ifEmpty { null } ?: localIps
                showAttachFailure(serverUrl, sentIps, publicIpSeen, reason)
            }
        }
    }

    private fun showAttachFailure(serverUrl: String, sentIps: List<String>, publicIpSeen: String?, reason: String) {
        val root = column().apply { setPadding(dp(28), dp(24), dp(28), dp(24)) }
        root.addView(label(getString(R.string.app_name), 28f))
        root.addView(label("Handset identification failed", 22f).apply { setTextColor(Color.rgb(180, 40, 40)) })
        root.addView(label("Reason: $reason", 16f))
        root.addView(label("Local IPs sent: ${sentIps.joinToString(", ").ifEmpty { "None found" }}", 15f))
        if (publicIpSeen != null) {
            root.addView(label("Public IP seen by server: $publicIpSeen", 15f))
        }
        root.addView(label("Verify this phone is registered on the correct VLAN and extension.", 14f))

        val serverInput = EditText(this).apply {
            hint = "Server URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(serverUrl)
            setSingleLine()
        }
        root.addView(serverInput)

        val retryBtn = button("Retry identification") {
            val target = serverInput.text.toString().trim().ifEmpty { getString(R.string.default_server_url) }
            attemptAttach(target)
        }
        root.addView(retryBtn)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showWorkspace() {
        val currentSession = session ?: return
        api = PlatformApi(currentSession.serverUrl, currentSession.token)

        rootContainer = column().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val extLabel = "${getString(R.string.app_name)}  ·  Ext ${currentSession.device.extension} (${currentSession.device.context})"
        header.addView(label(extLabel, 18f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))

        detachBtn = Button(this).apply {
            text = "Unpair phone"
            textSize = 12f
            setTextColor(Color.rgb(130, 140, 150))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            minHeight = dp(32)
            isAllCaps = false
            setOnClickListener { showDetachConfirmation() }
        }
        header.addView(detachBtn)
        rootContainer.addView(header)

        statusText = label("Connected. Waiting for calls…", 13f).apply {
            setTextColor(Color.rgb(100, 116, 139))
        }
        rootContainer.addView(statusText)

        // Simultaneous calls tabs (only displayed when multiple calls exist)
        callsTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabsScroll = HorizontalScrollView(this).apply {
            visibility = View.GONE
            addView(callsTabs)
        }
        rootContainer.addView(tabsScroll, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, dp(4)) })

        // Detail area
        detailContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        renderIdleScreen()
        rootContainer.addView(detailContainer, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(rootContainer)
        startPolling()
    }

    private fun renderIdleScreen() {
        detailContainer.removeAllViews()
        detailContainer.orientation = LinearLayout.VERTICAL
        val idleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, 0)
        }
        idleLayout.addView(label("No active calls", 18f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(148, 163, 184))
            setTypeface(null, Typeface.BOLD)
        })
        idleLayout.addView(label("Monitoring queue for incoming calls…", 14f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(148, 163, 184))
        })
        detailContainer.addView(idleLayout, LinearLayout.LayoutParams(-1, -1))
        detachBtn?.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
    }

    private fun showDetachConfirmation() {
        val ext = session?.device?.extension ?: "this extension"
        AlertDialog.Builder(this)
            .setTitle("Unpair Extension?")
            .setMessage("Are you sure you want to unpair Ext $ext? This phone will stop receiving Aida call alerts until re-identified.")
            .setPositiveButton("Unpair") { _, _ -> confirmDetach() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderCallTabs(calls: List<Call>) {
        callsTabs.removeAllViews()
        if (calls.size <= 1) {
            tabsScroll.visibility = View.GONE
            if (calls.isEmpty() && selectedCall == null) {
                renderIdleScreen()
            }
            return
        }
        tabsScroll.visibility = View.VISIBLE
        for (call in calls) {
            val isSelected = call.id == selectedCallId
            val tabTitle = "${call.callerNumber ?: "Caller"}\n[${call.queue}] ${call.state}"
            val tabBtn = Button(this).apply {
                text = tabTitle
                isAllCaps = false
                textSize = 12f
                setBackgroundColor(if (isSelected) Color.rgb(200, 225, 255) else Color.rgb(240, 240, 240))
                setTextColor(Color.rgb(20, 20, 20))
                setOnClickListener {
                    if (selectedCallId != call.id) {
                        openCall(call)
                    }
                }
            }
            callsTabs.addView(tabBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(8), 0) })
        }
    }

    private fun startPolling() {
        if (!foreground || session == null || polling?.isActive == true) return
        polling = scope.launch {
            while (isActive) {
                try {
                    val calls = api?.calls() ?: emptyList()
                    AlertingService.activeCalls.value = calls
                    statusText.text = if (calls.isEmpty()) "Monitoring queue · No active calls" else "${calls.size} active call(s)"
                } catch (e: ApiException) {
                    if (e.status == 401 || e.status == 403) {
                        reAttachSilently()
                    }
                } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    private suspend fun reAttachSilently() {
        val s = session ?: return
        val localIps = DeviceIdentifier.getLocalIps()
        if (localIps.isEmpty()) return
        try {
            val resp = PlatformApi(s.serverUrl).attach(
                AttachRequest(
                    appInstanceId = store.appInstanceId,
                    localIps = localIps,
                    deviceModel = DeviceIdentifier.getDeviceModel(),
                    claimedMac = DeviceIdentifier.getClaimedMac(),
                )
            )
            val updated = s.copy(token = resp.token, expiresAt = resp.expiresAt, device = resp.device)
            store.save(updated)
            session = updated
            api = PlatformApi(updated.serverUrl, updated.token)
        } catch (_: Exception) {}
    }

    private suspend fun fetchAndOpenCall(callId: String, shouldTakeover: Boolean = false) {
        try {
            val detail = api?.call(callId) ?: return
            openCall(detail.call)
            if (shouldTakeover) {
                executeTakeover(detail.call)
            }
        } catch (_: Exception) {}
    }

    private fun openCall(call: Call, reconnect: Boolean = false) {
        AlertingService.dismissCallAlert(this)
        selectedCallId = call.id
        selectedCall = call
        loadingCall?.cancel()
        earlyJoinJob?.cancel()

        if (reducer == null || reducer?.callId != call.id) {
            reducer = TranscriptReducer(call.id, capacity = 200)
        } else if (reconnect) {
            reducer?.interrupted()
        }

        renderCallScreen(call)

        if (!reconnect && live.currentCallId == call.id && live.isConnected) {
            updateTranscriptUI()
            return
        }

        live.close()

        loadingCall = scope.launch {
            try {
                val detail = api?.call(call.id) ?: return@launch
                selectedCall = detail.call
                updateCallState(detail.call)

                val livekit = detail.livekit
                if (livekit == null) {
                    transcriptStateView?.text = "Waiting for room credentials…"
                    return@launch
                }

                // Join room early
                live.connect(
                    callId = call.id,
                    session = livekit,
                    agentParticipantSid = detail.agentParticipantSid,
                    onEvent = { event ->
                        Log.i(TAG, "onEvent received: speaker=${event.speaker} seq=${event.sequence} text='${event.text}'")
                        if (selectedCallId == call.id) {
                            val accepted = reducer?.accept(event) == true
                            Log.d(TAG, "reducer.accept: $accepted")
                            if (accepted) {
                                runOnUiThread { updateTranscriptUI() }
                            }
                        }
                    },
                    onState = { stateMsg, interrupted ->
                        Log.i(TAG, "onState: stateMsg='$stateMsg' interrupted=$interrupted")
                        if (selectedCallId == call.id) {
                            runOnUiThread {
                                transcriptStateView?.text = stateMsg
                                if (interrupted) reducer?.interrupted()
                                updateTranscriptUI()
                            }
                        }
                    },
                )

                // If agentParticipantSid is not yet known, poll call detail every 1s for up to 10s
                if (detail.agentParticipantSid.isNullOrBlank()) {
                    earlyJoinJob = launch {
                        for (i in 1..10) {
                            delay(1000)
                            if (!isActive || selectedCallId != call.id) break
                            val refreshed = runCatching { api?.call(call.id) }.getOrNull() ?: continue
                            val sid = refreshed.agentParticipantSid
                            if (!sid.isNullOrBlank()) {
                                live.updateAgentParticipantSid(sid)
                                break
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                transcriptStateView?.text = "Unable to connect transcript."
            }
        }
    }

    private fun renderCallScreen(call: Call) {
        detailContainer.removeAllViews()
        detailContainer.orientation = LinearLayout.HORIZONTAL
        detachBtn?.visibility = View.GONE
        statusText.visibility = View.GONE

        // Left panel: Call details & Takeover button (width: 240dp)
        val leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(12), 0)
        }

        val callerNum = call.callerNumber ?: "Caller"
        leftPanel.addView(label(callerNum, 22f).apply {
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(28, 41, 57))
        })

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metaRow.addView(label("Queue: ${call.queue}", 14f).apply {
            setTextColor(Color.rgb(100, 116, 139))
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val startTime = callStartTimes.getOrPut(call.id) { SystemClock.elapsedRealtime() }
        chronometerView = Chronometer(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(100, 116, 139))
            base = startTime
            start()
        }
        metaRow.addView(chronometerView)
        leftPanel.addView(metaRow)

        // State banner
        stateBannerView = TextView(this).apply {
            text = "State: ${call.state}"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(30, 64, 175))
            setBackgroundColor(Color.rgb(230, 240, 255))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            gravity = Gravity.CENTER
        }
        leftPanel.addView(stateBannerView, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })

        // Takeover Button - Green styling (#2E7D32)
        takeOverBtn = Button(this).apply {
            text = "Take over"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            minHeight = dp(52)
            setBackgroundColor(Color.rgb(46, 125, 50))
            setTextColor(Color.WHITE)
            isAllCaps = false
            setOnClickListener { executeTakeover(call) }
        }
        leftPanel.addView(takeOverBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, 0) })

        // Right panel: Live Transcript (weight: 1f, full available height)
        val rightPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val transcriptHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        transcriptStateView = label("Connecting live transcript…", 13f).apply {
            setTextColor(Color.rgb(100, 116, 139))
        }
        transcriptHeader.addView(transcriptStateView, LinearLayout.LayoutParams(0, -2, 1f))

        jumpToLatestBtn = Button(this).apply {
            text = "Jump to latest ↓"
            textSize = 12f
            isAllCaps = false
            visibility = View.GONE
            minHeight = dp(32)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { transcriptScroll?.fullScroll(View.FOCUS_DOWN) }
        }
        transcriptHeader.addView(jumpToLatestBtn)
        rightPanel.addView(transcriptHeader)

        gapNoticeView = label("", 12f).apply {
            setTextColor(Color.rgb(160, 80, 0))
            visibility = View.GONE
        }
        rightPanel.addView(gapNoticeView)

        // Transcript Scroll View & Content
        transcriptView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(28, 41, 57))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.25f)
            text = "Connecting to Aida…"
        }
        transcriptScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(245, 247, 250))
            addView(transcriptView, LinearLayout.LayoutParams(-1, -2))
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val child = getChildAt(0)
                if (child != null) {
                    val diff = (child.bottom - (height + scrollY))
                    autoScroll = diff <= dp(30)
                    jumpToLatestBtn?.visibility = if (autoScroll) View.GONE else View.VISIBLE
                }
            }
        }
        rightPanel.addView(transcriptScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        detailContainer.addView(leftPanel, LinearLayout.LayoutParams(dp(240), -1))
        detailContainer.addView(rightPanel, LinearLayout.LayoutParams(0, -1, 1f))

        updateCallState(call)
        updateTranscriptUI()
    }

    private fun updateCallState(call: Call) {
        selectedCall = call
        val state = call.state
        stateBannerView?.text = "State: $state"
        when (state) {
            "screening" -> {
                stateBannerView?.setBackgroundColor(Color.rgb(230, 240, 255))
                takeOverBtn?.isEnabled = commandJob?.isActive != true
                takeOverBtn?.setBackgroundColor(Color.rgb(46, 125, 50))
                takeOverBtn?.text = "Take over"
            }
            "ringing" -> {
                stateBannerView?.setBackgroundColor(Color.rgb(255, 245, 200))
                stateBannerView?.text = "Ringing your phone…"
                takeOverBtn?.isEnabled = false
                takeOverBtn?.setBackgroundColor(Color.rgb(120, 120, 120))
                takeOverBtn?.text = "Ringing your phone…"
            }
            "human-active" -> {
                stateBannerView?.setBackgroundColor(Color.rgb(220, 255, 220))
                stateBannerView?.text = "Connected — pick up the handset"
                takeOverBtn?.isEnabled = false
                takeOverBtn?.setBackgroundColor(Color.rgb(120, 120, 120))
                takeOverBtn?.text = "Connected"
                scope.launch {
                    delay(3000)
                    handleCallEnded()
                }
            }
            "fallback", "ended" -> {
                handleCallEnded()
            }
        }
    }

    private fun executeTakeover(call: Call) {
        if (commandJob?.isActive == true) return
        val paired = session ?: return

        commandJob = scope.launch {
            takeOverBtn?.isEnabled = false
            takeOverBtn?.setBackgroundColor(Color.rgb(120, 120, 120))
            takeOverBtn?.text = "Requesting takeover…"
            try {
                val pending = TakeoverPolicy.prepare(call, paired.pendingTakeover)
                val savedSession = paired.copy(pendingTakeover = pending)
                store.save(savedSession)
                session = savedSession

                val resp = api?.takeover(call.id, TakeoverRequest(pending.idempotencyKey, pending.expectedCallVersion))
                store.updatePendingTakeover(null)
                session = store.read()

                stateBannerView?.text = "Ringing your phone…"
                takeOverBtn?.text = "Ringing your phone…"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ApiException && TakeoverPolicy.definitiveRejection(error.status)) {
                    store.updatePendingTakeover(null)
                    session = store.read()
                }
                val reason = when ((error as? ApiException)?.status) {
                    409 -> "Takeover in progress or already taken."
                    503 -> "Your phone is busy or unavailable."
                    else -> error.message ?: "Takeover request failed."
                }
                stateBannerView?.text = "Takeover failed: $reason"
                takeOverBtn?.text = "Take over"
                takeOverBtn?.setBackgroundColor(Color.rgb(46, 125, 50))
                takeOverBtn?.isEnabled = true
            }
        }
    }

    private fun handleCallEnded() {
        loadingCall?.cancel()
        earlyJoinJob?.cancel()
        live.close()
        chronometerView?.stop()
        selectedCall?.id?.let { callStartTimes.remove(it) }
        stateBannerView?.text = "Call ended"
        stateBannerView?.setBackgroundColor(Color.rgb(240, 240, 240))
        takeOverBtn?.isEnabled = false
        takeOverBtn?.setBackgroundColor(Color.rgb(180, 180, 180))
        takeOverBtn?.text = "Call ended"
        transcriptStateView?.text = "Transcript closed"

        scope.launch {
            delay(2000)
            if (selectedCallId == selectedCall?.id) {
                selectedCallId = null
                selectedCall = null
                reducer = null
                renderIdleScreen()
            }
        }
    }

    private fun updateTranscriptUI() {
        val r = reducer ?: return
        val lines = r.lines

        gapNoticeView?.text = r.gapNotice ?: ""
        gapNoticeView?.visibility = if (r.gapNotice != null) View.VISIBLE else View.GONE

        if (lines.isEmpty()) {
            transcriptView?.text = "Waiting for speech…"
        } else {
            val builder = android.text.SpannableStringBuilder()
            for (i in lines.indices) {
                val line = lines[i]
                if (i > 0) builder.append("\n\n")

                val speakerLabel = "${line.speakerLabel}: "
                val startSpeaker = builder.length
                builder.append(speakerLabel)
                val endSpeaker = builder.length

                val speakerColor = if (line.speakerLabel == "Aida") {
                    Color.rgb(21, 101, 192) // Distinct blue for Assistant
                } else {
                    Color.rgb(46, 125, 50)  // Distinct green for Caller
                }
                builder.setSpan(android.text.style.StyleSpan(Typeface.BOLD), startSpeaker, endSpeaker, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(android.text.style.ForegroundColorSpan(speakerColor), startSpeaker, endSpeaker, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                val textStart = builder.length
                val finalMark = if (line.isFinal) "" else " …"
                builder.append("${line.text}$finalMark")
                val textEnd = builder.length
                val textColor = if (line.isFinal) Color.rgb(28, 41, 57) else Color.rgb(100, 116, 139)
                builder.setSpan(android.text.style.ForegroundColorSpan(textColor), textStart, textEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            transcriptView?.text = builder
        }

        if (autoScroll) {
            transcriptScroll?.post {
                transcriptScroll?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun confirmDetach() {
        scope.launch {
            try {
                api?.logout()
            } catch (_: Exception) {}
            store.clear()
            session = null
            api = null
            selectedCallId = null
            selectedCall = null
            reducer = null
            live.close()
            attemptAttach()
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(28, 41, 57)); setPadding(0, dp(4), 0, dp(4))
    }
    private fun button(value: String, action: (View) -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; minHeight = dp(48); setOnClickListener(action)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MainActivity"
    }
}
