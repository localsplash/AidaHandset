# AidaHandset

Android 11+ data and control app for a Grandstream GXV3450 office handset. The phone's native SIP client owns call audio; this app pairs to an extension, lists its active calls, displays LiveKit transcript data, and requests a takeover through OfficePulse.

## Responsibilities

- Pair to one business extension using an administrator-issued, one-time enrollment code.
- Poll authorized calls every five seconds while visible; select any simultaneous call for live text.
- Fetch call-specific LiveKit credentials from OfficePulse. No provider, database or SIP credentials belong in this app.
- Replace partial text with final segments, ignore duplicate/stale events, order segments by stream sequence, and visibly mark missed events and agent restarts.
- Persist a pending takeover request before sending it. A retry after an unknown outcome reuses its original idempotency key and call version, including after process death.

## Stack

Kotlin, native Android views, Android Keystore, OkHttp, LiveKit Android SDK, GitHub Actions. AGP 8.7.3, Gradle 8.9, Kotlin 2.1.10 and LiveKit 2.20.3 are pinned. The Gradle wrapper verifies the official distribution checksum; JitPack is restricted to LiveKit's pinned AudioSwitch dependency.

## Build and install

Install JDK 17 and Android SDK 35, accept the SDK licenses, and set `ANDROID_HOME` (or create a local `local.properties` with `sdk.dir=...`). Then:

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is for local POC testing. Configure your own signing key for a distributable release; signing keys must stay outside Git. CI produces the debug APK as a workflow artifact.

Open the app and enter the HTTPS **OfficePulse origin**, such as `https://officepulse.localsplash.dev`, followed by a one-time enrollment code created for the desired business extension in Aida Admin. Nginx Proxy Manager must forward that origin to OfficePulse and serve a certificate the Android device trusts. There is no web container to deploy for this Android app.

The app uses the server's assigned tenant and extension. It has no tenant override or SUPER ADMIN mode; central Identity manages the humans using Aida Admin, and OfficePulse authorizes the resulting scoped device token. Revoke enrolled devices in Aida Admin. Local Unpair removes the local encrypted session but does not revoke the server token.

## OfficePulse contract

All routes are relative to the configured origin. Enrollment is the only unauthenticated route; all other requests carry `Authorization: Bearer <device token>`. The server must enforce device revocation, tenant/extension access, optimistic concurrency, and command idempotency.

| Request | Body / response |
| --- | --- |
| `POST /v1/devices/enroll` | Send `{enrollmentToken, deviceId}`; receive `{token, device:{id, iTenantId, extensionId}}`. `iTenantId` is an integer; IDs and extension ID are strings. |
| `GET /v1/calls` | Receive `{calls:[{id,status,version,caller?,startedAt?,extensionId?}]}` scoped to the enrolled extension. |
| `GET /v1/calls/:id` | Receive `{call:{id,status,version,caller?,startedAt?,extensionId?},livekit?:{url,token}}`. LiveKit URL uses `wss://`. |
| `POST /v1/calls/:id/commands` | Send `{commandType:"TAKEOVER",idempotencyKey,expectedCallVersion}`; any successful HTTP response means the request was accepted, not that the SIP handoff completed. |

LiveKit reliable data packets use topic `transcript` (a missing topic is accepted for compatibility):

```json
{
  "type": "transcript",
  "callId": "call-id",
  "eventId": "unique-event-id",
  "streamId": "agent-connection-id",
  "sequence": 1,
  "segmentId": "utterance-id",
  "text": "How can I help?",
  "isFinal": false,
  "timestamp": "2026-09-06T12:00:00Z",
  "speaker": "Assistant"
}
```

Sequences start at one and increase per stream. A new agent connection must use a new `streamId`. Finals are immutable; partials use the same `segmentId` until finalization. The handset rejects packets for other calls. OfficePulse/LiveKit must ensure only authorized server agents can publish into a call room and issue handset tokens with publishing disabled.

## Recovery and security

- Tokens and pending commands are AES-GCM encrypted using an Android Keystore key and private preferences. Backups and screenshots are disabled. Enrollment codes are neither persisted nor logged.
- Only HTTPS origins without userinfo, paths, query strings or fragments are accepted. Redirects and automatic HTTP retries are disabled; credentials must never follow a redirect. Tokens are never placed in URLs by this app.
- `401` or `403` disconnects LiveKit, clears the transcript and encrypted local session, and asks for fresh enrollment. A stale call (`409`) requires a deliberate retry after refreshing; timeouts and server failures retain the pending request. The Pending request button can explicitly clear an unresolved retry after checking the phone, including when its call has ended.
- LiveKit uses `NoAudioHandler`, disables its silent-audio workaround, and connects with audio, video and automatic track subscriptions disabled. Microphone and camera permissions are removed from the merged manifest.
- Polling and LiveKit stop while the activity is hidden. Returning reconnects and marks a possible transcript gap. Transcript text is held in memory only and limited to the latest 500 segments.

There is **no transcript history/replay** in this initial contract. Opening a call late, losing connection, restarting an agent, or restarting the app can leave gaps; the UI says so. Pusher/background notifications, a kiosk/device-management policy, and verified end-to-end SIP handoff on a physical GXV3450 are follow-up work. A successful unit/build check does not establish PBX or hardware integration.

## White labeling

Change `app/src/main/res/values/strings.xml` for the displayed app name and default server URL. Set your own `applicationId` in `app/build.gradle.kts` when distributing an independently branded APK. The runtime server URL remains configurable in the enrollment screen.

## SDK references

- [Android Gradle Plugin 8.7 compatibility](https://developer.android.com/build/releases/agp-8-7-0-release-notes)
- [Pinned LiveKit connection options](https://github.com/livekit/client-sdk-android/blob/v2.20.3/livekit-android-sdk/src/main/java/io/livekit/android/ConnectOptions.kt)
- [Pinned LiveKit audio overrides](https://github.com/livekit/client-sdk-android/blob/v2.20.3/livekit-android-sdk/src/main/java/io/livekit/android/LiveKitOverrides.kt)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

## System specification

[Canonical Aida Voice Platform specification](https://github.com/localsplash/AidaInfrastructureSetupInstructions/blob/main/docs/AIDA_VOICE_PLATFORM_TECHNICAL_SPECIFICATION.md)

## Project invariant

The handset never stores or transmits the Asterisk SIP password.
