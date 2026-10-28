package ai.localsplash.aida.handset

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
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
    private lateinit var settingsStore: SettingsStore
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
    private val spamLikelyCalls = mutableSetOf<String>()
    private val escalatedCalls = mutableSetOf<String>()
    private var isShowingSettings = false
    private var takeOverFlashingAnimator: ValueAnimator? = null

    // UI elements
    private lateinit var rootContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var callsTabs: LinearLayout
    private lateinit var tabsScroll: HorizontalScrollView
    private lateinit var detailContainer: LinearLayout
    private var settingsBtn: Button? = null
    private var transcriptScroll: ScrollView? = null
    private var transcriptView: TextView? = null
    private var transcriptStateView: TextView? = null
    private var gapNoticeView: TextView? = null
    private var jumpToLatestBtn: Button? = null
    private var takeOverBtn: Button? = null
    private var reportSpamBtn: Button? = null
    private var stateBannerView: TextView? = null
    private var chronometerView: Chronometer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureSessionStore(this)
        settingsStore = SettingsStore(this)
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
                openCall(target)
                if (shouldTakeover) {
                    executeTakeover(target)
                }
            } else {
                fetchAndOpenCall(callId, shouldTakeover)
            }
        }
    }

    private fun observeIncomingAlerts() {
        scope.launch {
            AlertingService.incomingAlertCall.collect { callId ->
                if (settingsStore.automaticMonitor || selectedCallId == null) {
                    if (selectedCallId != callId) {
                        fetchAndOpenCall(callId)
                    }
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
                    } else if (calls.isNotEmpty() && !isShowingSettings) {
                        if (settingsStore.automaticMonitor) {
                            openCall(calls.first())
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
        val targetCall = selectedCall ?: AlertingService.activeCalls.value.firstOrNull()
        if (targetCall != null) {
            AlertingService.dismissCallAlert(this)
            if (live.currentCallId != targetCall.id || !live.isConnected) {
                openCall(targetCall)
            } else {
                renderCallScreen(targetCall)
                updateTranscriptUI()
            }
        } else if (selectedCallId != null) {
            scope.launch {
                fetchAndOpenCall(selectedCallId!!)
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
        stopTakeoverFlashing()
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

        settingsBtn = Button(this).apply {
            text = "⚙ Settings"
            textSize = 13f
            setTextColor(Color.rgb(71, 85, 105))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            minHeight = dp(32)
            isAllCaps = false
            setOnClickListener {
                if (isShowingSettings) {
                    exitSettings()
                } else {
                    renderSettingsScreen()
                }
            }
        }
        header.addView(settingsBtn)
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
        settingsBtn?.visibility = View.VISIBLE
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
                        reAttachSilently(forceFresh = true)
                    }
                } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    private suspend fun reAttachSilently(forceFresh: Boolean = false): Boolean {
        if (!forceFresh) {
            val stored = store.read()
            if (stored != null && stored.token != session?.token) {
                session = stored
                api = PlatformApi(stored.serverUrl, stored.token)
                Log.i(TAG, "reAttachSilently: Adopted newer session token from SecureSessionStore")
                return true
            }
        }

        val s = session ?: store.read() ?: return false
        val localIps = DeviceIdentifier.getLocalIps()
        if (localIps.isEmpty()) return false
        return try {
            val resp = PlatformApi(s.serverUrl).attach(
                AttachRequest(
                    appInstanceId = store.appInstanceId,
                    localIps = localIps,
                    deviceModel = DeviceIdentifier.getDeviceModel(),
                    appVersion = BuildConfig.VERSION_NAME,
                    claimedMac = DeviceIdentifier.getClaimedMac()?.takeIf { it.isNotBlank() },
                )
            )
            val updated = s.copy(token = resp.token, expiresAt = resp.expiresAt, device = resp.device)
            store.save(updated)
            session = updated
            api = PlatformApi(updated.serverUrl, updated.token)
            Log.i(TAG, "reAttachSilently succeeded: fresh session token acquired")
            true
        } catch (e: Exception) {
            Log.e(TAG, "reAttachSilently failed: ${e.message}")
            false
        }
    }

    private suspend fun fetchCallDetail(callId: String): CallDetail? {
        var currentApi = api
        if (currentApi == null) {
            reAttachSilently()
            currentApi = api ?: return null
        }
        return try {
            currentApi.call(callId)
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) {
                Log.w(TAG, "Session invalid (HTTP ${e.status}) fetching call $callId; re-attaching silently...")
                val reattached = reAttachSilently()
                val retryApi = api
                if (retryApi != null && reattached) {
                    try {
                        retryApi.call(callId)
                    } catch (retryError: ApiException) {
                        if (retryError.status == 401 || retryError.status == 403) {
                            Log.w(TAG, "Retry failed with HTTP ${retryError.status}; forcing fresh attach...")
                            if (reAttachSilently(forceFresh = true)) {
                                runCatching { api?.call(callId) }.getOrNull()
                            } else null
                        } else {
                            Log.e(TAG, "Retry fetching call $callId failed: ${retryError.message}")
                            null
                        }
                    } catch (retryError: Exception) {
                        Log.e(TAG, "Retry fetching call $callId failed: ${retryError.message}")
                        null
                    }
                } else {
                    null
                }
            } else {
                Log.e(TAG, "fetchCallDetail $callId failed: ${e.message}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchCallDetail $callId failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchAndOpenCall(callId: String, shouldTakeover: Boolean = false) {
        try {
            val detail = fetchCallDetail(callId) ?: return
            openCall(detail.call)
            if (shouldTakeover) {
                executeTakeover(detail.call)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndOpenCall error: ${e.message}", e)
        }
    }

    private fun openCall(call: Call, reconnect: Boolean = false) {
        if (foreground) {
            AlertingService.dismissCallAlert(this)
        }
        val callChanged = selectedCallId != call.id
        if (callChanged) {
            stopTakeoverFlashing()
        }
        isShowingSettings = false
        selectedCallId = call.id
        selectedCall = call

        if (reducer == null || reducer?.callId != call.id) {
            reducer = TranscriptReducer(call.id, capacity = 200)
        } else if (reconnect) {
            reducer?.interrupted()
        }

        renderCallScreen(call)
        updateTranscriptUI()

        // If already connected to or actively loading this call, keep the existing session intact
        if (!reconnect && !callChanged && (live.currentCallId == call.id && (live.isConnected || loadingCall?.isActive == true))) {
            Log.i(TAG, "openCall: already connected or loading call ${call.id}, keeping existing session")
            return
        }

        loadingCall?.cancel()
        earlyJoinJob?.cancel()
        if (callChanged || reconnect) {
            live.close()
        }

        loadingCall = scope.launch {
            try {
                transcriptStateView?.text = "Connecting live transcript…"
                val detail = fetchCallDetail(call.id) ?: run {
                    transcriptStateView?.text = "Unable to connect transcript."
                    return@launch
                }
                selectedCall = detail.call
                updateCallState(detail.call)

                val livekit = detail.livekit
                if (livekit == null) {
                    transcriptStateView?.text = "Waiting for room credentials…"
                    return@launch
                }

                val initialSid = detail.effectiveAgentParticipantSid
                // Join room early
                live.connect(
                    callId = call.id,
                    session = livekit,
                    agentParticipantSid = initialSid,
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
                    onCallFunction = { func ->
                        if (selectedCallId == call.id) {
                            runOnUiThread {
                                handleLiveKitCallFunction(func)
                            }
                        }
                    },
                )

                // If agentParticipantSid is not yet known, poll call detail every 1s for up to 10s
                if (initialSid.isNullOrBlank()) {
                    earlyJoinJob = launch {
                        for (i in 1..10) {
                            delay(1000)
                            if (!isActive || selectedCallId != call.id) break
                            val refreshed = fetchCallDetail(call.id) ?: continue
                            val sid = refreshed.effectiveAgentParticipantSid
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
        isShowingSettings = false
        detailContainer.removeAllViews()
        detailContainer.orientation = LinearLayout.HORIZONTAL
        settingsBtn?.visibility = View.VISIBLE
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

        // Report Spam Button
        reportSpamBtn = Button(this).apply {
            text = "Report Spam"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            minHeight = dp(46)
            setBackgroundColor(Color.rgb(185, 28, 28))
            setTextColor(Color.WHITE)
            isAllCaps = false
            visibility = if (spamLikelyCalls.contains(call.id)) View.VISIBLE else View.GONE
            setOnClickListener { executeReportSpam(call) }
        }
        leftPanel.addView(reportSpamBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, 0) })

        if (escalatedCalls.contains(call.id)) {
            startTakeoverFlashing()
        } else {
            stopTakeoverFlashing()
        }

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
            setOnClickListener {
                autoScroll = true
                visibility = View.GONE
                transcriptScroll?.post {
                    val child = transcriptScroll?.getChildAt(0)
                    if (child != null && transcriptScroll != null) {
                        val targetY = (child.bottom - transcriptScroll!!.height).coerceAtLeast(0)
                        transcriptScroll?.scrollTo(0, targetY)
                    }
                }
            }
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
            setLineSpacing(0f, 1.25f)
            text = "Connecting to Aida…"
        }
        transcriptScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(245, 247, 250))
            addView(transcriptView, LinearLayout.LayoutParams(-1, -2))
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val child = getChildAt(0)
                if (child != null) {
                    val diff = child.bottom - (height + scrollY)
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
        if (state != "screening") {
            stopTakeoverFlashing()
        }
        stateBannerView?.text = "State: $state"
        when (state) {
            "screening" -> {
                if (escalatedCalls.contains(call.id)) {
                    stateBannerView?.text = "ESCALATION: Human assistance requested!"
                    stateBannerView?.setBackgroundColor(Color.rgb(254, 226, 226))
                    stateBannerView?.setTextColor(Color.rgb(185, 28, 28))
                    startTakeoverFlashing()
                } else if (spamLikelyCalls.contains(call.id)) {
                    stateBannerView?.text = "Suspected Spam Call"
                    stateBannerView?.setBackgroundColor(Color.rgb(254, 243, 199))
                    stateBannerView?.setTextColor(Color.rgb(180, 83, 9))
                } else {
                    stateBannerView?.setBackgroundColor(Color.rgb(230, 240, 255))
                    stateBannerView?.setTextColor(Color.rgb(30, 64, 175))
                }
                takeOverBtn?.isEnabled = commandJob?.isActive != true
                takeOverBtn?.setBackgroundColor(Color.rgb(46, 125, 50))
                takeOverBtn?.text = "Take over"
                reportSpamBtn?.visibility = if (spamLikelyCalls.contains(call.id)) View.VISIBLE else View.GONE
            }
            "ringing" -> {
                stateBannerView?.setBackgroundColor(Color.rgb(255, 245, 200))
                stateBannerView?.setTextColor(Color.rgb(30, 64, 175))
                stateBannerView?.text = "Ringing your phone…"
                takeOverBtn?.isEnabled = false
                takeOverBtn?.setBackgroundColor(Color.rgb(120, 120, 120))
                takeOverBtn?.text = "Ringing your phone…"
                reportSpamBtn?.visibility = View.GONE
            }
            "human-active" -> {
                stateBannerView?.setBackgroundColor(Color.rgb(220, 255, 220))
                stateBannerView?.setTextColor(Color.rgb(30, 64, 175))
                stateBannerView?.text = "Connected — pick up the handset"
                takeOverBtn?.isEnabled = false
                takeOverBtn?.setBackgroundColor(Color.rgb(120, 120, 120))
                takeOverBtn?.text = "Connected"
                reportSpamBtn?.visibility = View.GONE
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

    private suspend fun executeTakeoverApi(callId: String, request: TakeoverRequest): TakeoverResponse? {
        val currentApi = api ?: return null
        return try {
            currentApi.takeover(callId, request)
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) {
                Log.w(TAG, "Takeover returned HTTP ${e.status} for call $callId; re-attaching silently...")
                if (reAttachSilently(forceFresh = true)) {
                    api?.takeover(callId, request)
                } else throw e
            } else throw e
        }
    }

    private fun executeTakeover(call: Call) {
        stopTakeoverFlashing()
        AlertingService.dismissCallAlert(this)
        if (commandJob?.isActive == true) return
        val paired = session ?: return

        commandJob = scope.launch {
            takeOverBtn?.isEnabled = false
            takeOverBtn?.setBackgroundColor(Color.rgb(120, 120, 120))
            takeOverBtn?.text = "Requesting takeover…"
            try {
                // Refresh call version directly from the server to avoid sending a stale version from initial alert
                val currentCall = try {
                    val refreshed = fetchCallDetail(call.id)?.call
                    if (refreshed != null) {
                        selectedCall = refreshed
                        refreshed
                    } else {
                        call
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to refresh call before takeover, using cached call: ${e.message}")
                    call
                }

                if (currentCall.state != "screening") {
                    updateCallState(currentCall)
                    return@launch
                }

                val pending = TakeoverPolicy.prepare(currentCall, paired.pendingTakeover)
                val savedSession = paired.copy(pendingTakeover = pending)
                store.save(savedSession)
                session = savedSession

                Log.i(TAG, "Sending takeover request: callId=${currentCall.id}, version=${pending.expectedCallVersion}, key=${pending.idempotencyKey}")
                val resp = executeTakeoverApi(currentCall.id, TakeoverRequest(pending.idempotencyKey, pending.expectedCallVersion))
                store.updatePendingTakeover(null)
                session = store.read()

                stateBannerView?.text = "Ringing your phone…"
                takeOverBtn?.text = "Ringing your phone…"
            } catch (error: Exception) {
                Log.e(TAG, "Takeover request failed: ${error.message}", error)
                if (error is CancellationException) throw error
                if (error is ApiException && TakeoverPolicy.definitiveRejection(error.status)) {
                    store.updatePendingTakeover(null)
                    session = store.read()
                }

                // If 409 conflict, the call version likely incremented during conversation.
                // Re-fetch call: if still screening with a newer version, auto-retry immediately!
                if (error is ApiException && error.status == 409) {
                    try {
                        val refreshed = fetchCallDetail(call.id)?.call
                        if (refreshed != null && refreshed.state == "screening" && refreshed.version != call.version) {
                            Log.i(TAG, "Call version advanced from ${call.version} to ${refreshed.version}; auto-retrying takeover...")
                            selectedCall = refreshed
                            val retryPending = TakeoverPolicy.prepare(refreshed, null)
                            store.updatePendingTakeover(retryPending)
                            session = store.read()

                            val retryResp = executeTakeoverApi(refreshed.id, TakeoverRequest(retryPending.idempotencyKey, retryPending.expectedCallVersion))
                            store.updatePendingTakeover(null)
                            session = store.read()

                            stateBannerView?.text = "Ringing your phone…"
                            takeOverBtn?.text = "Ringing your phone…"
                            return@launch
                        }
                    } catch (retryError: Exception) {
                        Log.e(TAG, "Auto-retry takeover failed: ${retryError.message}", retryError)
                    }
                }

                val reason = when ((error as? ApiException)?.status) {
                    409 -> error.errorResponse?.message ?: error.errorResponse?.reason ?: "Takeover in progress or already taken."
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

    private fun executeReportSpam(call: Call) {
        reportSpamBtn?.isEnabled = false
        reportSpamBtn?.text = "Reporting spam…"
        takeOverBtn?.isEnabled = false
        stopTakeoverFlashing()
        stateBannerView?.text = "Spam reported · Ending call…"
        stateBannerView?.setBackgroundColor(Color.rgb(254, 226, 226))
        stateBannerView?.setTextColor(Color.rgb(185, 28, 28))

        scope.launch {
            try {
                api?.reportSpam(
                    call.id,
                    ReportSpamRequest(
                        callId = call.id,
                        callerNumber = call.callerNumber,
                        reason = "spam_likely",
                        terminateCall = true
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Report spam API error: ${e.message}")
            }
            delay(500)
            handleCallEnded()
        }
    }

    private fun handleCallEnded() {
        stopTakeoverFlashing()
        loadingCall?.cancel()
        earlyJoinJob?.cancel()
        live.close()
        chronometerView?.stop()
        selectedCall?.id?.let {
            callStartTimes.remove(it)
            spamLikelyCalls.remove(it)
            escalatedCalls.remove(it)
        }
        reportSpamBtn?.visibility = View.GONE
        stateBannerView?.text = "Call ended"
        stateBannerView?.setBackgroundColor(Color.rgb(240, 240, 240))
        stateBannerView?.setTextColor(Color.rgb(100, 116, 139))
        takeOverBtn?.isEnabled = false
        takeOverBtn?.setBackgroundColor(Color.rgb(180, 180, 180))
        takeOverBtn?.text = "Call ended"
        transcriptStateView?.text = "Transcript closed"
        AlertingService.dismissCallAlert(this)

        scope.launch {
            delay(2000)
            val remainingCalls = AlertingService.activeCalls.value.filter { it.id != selectedCall?.id }
            if (remainingCalls.isNotEmpty() && !isShowingSettings) {
                openCall(remainingCalls.first())
            } else if (!isShowingSettings) {
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
                val child = transcriptScroll?.getChildAt(0)
                if (child != null && transcriptScroll != null) {
                    val targetY = (child.bottom - transcriptScroll!!.height).coerceAtLeast(0)
                    transcriptScroll?.scrollTo(0, targetY)
                }
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

    private fun handleLiveKitCallFunction(func: String) {
        val call = selectedCall ?: return
        Log.i(TAG, "handleLiveKitCallFunction: func=$func, callId=${call.id}")
        when (func) {
            "human_escalate" -> {
                escalatedCalls.add(call.id)
                SoundAlert.playChime(this)
                startTakeoverFlashing()
                stateBannerView?.text = "ESCALATION: Human assistance requested!"
                stateBannerView?.setBackgroundColor(Color.rgb(254, 226, 226))
                stateBannerView?.setTextColor(Color.rgb(185, 28, 28))
            }
            "spam_likely" -> {
                spamLikelyCalls.add(call.id)
                reportSpamBtn?.visibility = View.VISIBLE
                stateBannerView?.text = "Suspected Spam Call"
                stateBannerView?.setBackgroundColor(Color.rgb(254, 243, 199))
                stateBannerView?.setTextColor(Color.rgb(180, 83, 9))
            }
        }
    }

    private fun startTakeoverFlashing() {
        runOnUiThread {
            if (takeOverFlashingAnimator != null) return@runOnUiThread
            val btn = takeOverBtn ?: return@runOnUiThread
            val normalGreen = Color.rgb(46, 125, 50)
            val alertRed = Color.rgb(220, 38, 38)
            takeOverFlashingAnimator = ValueAnimator.ofObject(ArgbEvaluator(), normalGreen, alertRed).apply {
                duration = 450
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { anim ->
                    btn.setBackgroundColor(anim.animatedValue as Int)
                }
                start()
            }
        }
    }

    private fun stopTakeoverFlashing() {
        runOnUiThread {
            takeOverFlashingAnimator?.cancel()
            takeOverFlashingAnimator = null
            takeOverBtn?.setBackgroundColor(Color.rgb(46, 125, 50))
        }
    }

    private fun renderSettingsScreen() {
        isShowingSettings = true
        detailContainer.removeAllViews()
        detailContainer.orientation = LinearLayout.VERTICAL

        val currentSession = session
        val currentDevice = currentSession?.device

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        // Top bar: Settings title + Back button
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(label("Settings", 22f).apply {
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(15, 23, 42))
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val backBtn = Button(this).apply {
            text = "← Back to Monitor"
            textSize = 13f
            isAllCaps = false
            setBackgroundColor(Color.rgb(226, 232, 240))
            setTextColor(Color.rgb(30, 41, 59))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minHeight = dp(36)
            setOnClickListener { exitSettings() }
        }
        topBar.addView(backBtn)
        content.addView(topBar)

        content.addView(createDivider())

        // Preferences Section
        content.addView(label("Call Monitoring", 16f).apply {
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(30, 64, 175))
        })

        val autoMonitorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val autoMonitorText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        autoMonitorText.addView(label("Automatic Monitor", 15f).apply {
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(15, 23, 42))
        })
        autoMonitorText.addView(label("Automatically open live transcription monitor on inbound calls rather than merely showing the notification banner.", 12f).apply {
            setTextColor(Color.rgb(100, 116, 139))
        })
        autoMonitorRow.addView(autoMonitorText, LinearLayout.LayoutParams(0, -2, 1f))

        val autoSwitch = android.widget.Switch(this).apply {
            isChecked = settingsStore.automaticMonitor
            setOnCheckedChangeListener { _, isChecked ->
                settingsStore.automaticMonitor = isChecked
                Log.i(TAG, "Automatic Monitor setting set to $isChecked")
            }
        }
        autoMonitorRow.addView(autoSwitch)
        content.addView(autoMonitorRow)

        content.addView(createDivider())

        // Phone Identification Section
        content.addView(label("Phone Identification", 16f).apply {
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(30, 64, 175))
        })

        val localIps = DeviceIdentifier.getLocalIps().joinToString(", ").ifEmpty { "None detected" }
        val claimedMac = DeviceIdentifier.getClaimedMac() ?: "Unavailable"
        val model = DeviceIdentifier.getDeviceModel()

        content.addView(createInfoRow("Extension", currentDevice?.extension ?: "Not paired"))
        content.addView(createInfoRow("Context", currentDevice?.context ?: "None"))
        content.addView(createInfoRow("PBX Instance ID", currentDevice?.pbxInstanceId ?: "None"))
        content.addView(createInfoRow("Endpoint ID", currentDevice?.endpointId ?: "None"))
        content.addView(createInfoRow("Device Model", model))
        content.addView(createInfoRow("Local IP(s)", localIps))
        content.addView(createInfoRow("Claimed MAC", claimedMac))
        content.addView(createInfoRow("Server URL", currentSession?.serverUrl ?: "Default"))
        content.addView(createInfoRow("App Instance ID", store.appInstanceId))
        content.addView(createInfoRow("App Version", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"))

        content.addView(createDivider())

        // Device Connection & Detach
        content.addView(label("Device Connection", 16f).apply {
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(30, 64, 175))
        })

        val unpairLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(12))
        }
        unpairLayout.addView(label("Unpairing this phone will disconnect Ext ${currentDevice?.extension ?: ""} and stop receiving call alerts until re-identified.", 12f).apply {
            setTextColor(Color.rgb(100, 116, 139))
        })

        val unpairBtn = Button(this).apply {
            text = "Unpair phone"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(185, 28, 28))
            setBackgroundColor(Color.rgb(254, 226, 226))
            isAllCaps = false
            minHeight = dp(44)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { showDetachConfirmation() }
        }
        unpairLayout.addView(unpairBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, dp(8), 0, 0) })
        content.addView(unpairLayout)

        scroll.addView(content)
        detailContainer.addView(scroll, LinearLayout.LayoutParams(-1, -1))
    }

    private fun exitSettings() {
        isShowingSettings = false
        val call = selectedCall
        if (call != null) {
            renderCallScreen(call)
            updateTranscriptUI()
        } else {
            renderIdleScreen()
        }
    }

    private fun createInfoRow(title: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            addView(label(title, 13f).apply {
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(71, 85, 105))
            }, LinearLayout.LayoutParams(dp(140), -2))
            addView(label(value, 13f).apply {
                setTextColor(Color.rgb(15, 23, 42))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.rgb(226, 232, 240))
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
                setMargins(0, dp(8), 0, dp(8))
            }
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
