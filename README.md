# AidaHandset

Android 11+ companion and call takeover app for the Grandstream GXV3450 office handset. The phone's native SIP client owns call audio; this app identifies its extension from the phone's live SIP registration, listens for queue alerts via Pusher through a persistent foreground service, displays LiveKit transcript data, and initiates one-tap call takeovers with auto-answer through OfficePulse.

## Responsibilities

- **Automatic attach:** On first run, reads its local IPv4 addresses and identifies its extension by matching Asterisk's live SIP registration (`asterisk.ps_contacts`). No pairing codes or manual MAC entry required.
- **Always-on alerting:** A foreground service starts at boot and maintains a persistent Pusher connection to queue channels. Incoming calls in `screening` state trigger a full-screen alert bringing the app to the front.
- **Live transcripts:** Early-join data connection to the call's LiveKit room (`autoSubscribe=false`, `NoAudioHandler`), buffering early speech until the agent SID is resolved and streaming caller and Aida utterances.
- **One-tap takeover:** Single tap on **Take over** requests an auto-answered transfer to the handset's extension. Retries reuse a Keystore-persisted idempotency key.
- **Simultaneous calls:** Displays active calls across queues with per-call transcript buffers capped at 200 segments.

## Stack

Kotlin, native Android views, Android Keystore, OkHttp, LiveKit Android SDK (`2.20.3`), Pusher Java Client (`2.4.4`), Gradle 8.9, AGP 8.7.3, Kotlin 2.1.10.

## How the phone is recognised

The phone registers to Asterisk over SIP, so Asterisk knows the link between the device's LAN IP and its extension:

```
asterisk.ps_contacts: endpoint = 411
  uri      = sip:411@172.116.149.216:39314;transport=TLS;x-ast-orig-host=192.168.6.97:5060
  via_addr = 192.168.6.97
```

On first run, the app calls `POST /v1/handset/attach` with its local IPv4 addresses (`NetworkInterface.getInetAddresses`). OfficePulse matches them against active registrations:
- The HTTP request's public IP matches the registration IP.
- One of the app's reported LAN addresses matches the contact's `via_addr`.
- Exactly one unexpired contact matches.
The matched endpoint becomes the handset's extension, and OfficePulse issues a device token stored in the Android Keystore.

## GXV3450 auto-answer configuration

Takeovers use an Asterisk originate with a dedicated `Call-Info: <sip:127.0.0.1>;answer-after=0` header. The Grandstream GXV3450 must be provisioned to auto-answer only INVITEs bearing this header:

In the device web UI:
**Account 1 → Call Settings → Auto Answer = "Intercom/Paging Only"**

In the device provisioning template (`/var/www/provisioning/cfg<MAC>.xml`):

| P-value | Parameter | Setting | Description |
| --- | --- | --- | --- |
| `P2981` | Auto Answer | `1` | Enable auto answer |
| `P2862` | Auto Answer Mode | `3` | Speakerphone |
| `P2863` | Mute on Intercom Auto Answer | `0` | Unmuted two-way audio immediately upon answer |
| `P2983` | Auto Answer Call Waiting | `0` | Do not barge in if phone is already on a call |
| `P2860` | Intercom Barging | `0` | Prevent takeover barge-in |

## OfficePulse contract

All routes are relative to `https://officepulse-api.localsplash.dev`. Every request except `attach` sends `Authorization: Bearer <device token>`.

| Route | Method | Description |
| --- | --- | --- |
| `/v1/handset/attach` | POST | `{appInstanceId, localIps:[...], deviceModel, claimedMac?}`. Returns `{token, expiresAt, device}`. |
| `/v1/handset/me` | GET | Returns `{device, queues:[{name, channel}], pusher:{key, cluster}}`. |
| `/v1/handset/calls` | GET | Active calls on the handset's queues: `{calls:[{id, state, version, queue, callerNumber?, startedAt}]}`. |
| `/v1/handset/calls/{id}` | GET | `{call, agentParticipantSid?, takeover?, livekit:{url, token, expiresIn}}`. |
| `/v1/handset/calls/{id}/takeover` | POST | `{idempotencyKey, expectedCallVersion}`. Returns 202 `{status:"ringing"}`. |
| `/v1/handset/logout` | POST | Revokes current device token. |

### Pusher alerts
The app subscribes to public queue channels (`aida;{pbxInstanceId};{context};{queue}`) and listens for the `call` event:
```json
{"v":1,"eventId":"...","callSessionId":"...","state":"screening","occurredAt":"..."}
```

### LiveKit transcripts
Reliable data on topic `transcript`:
```json
{"type":"transcript","callId":"...","eventId":"...","streamId":"...","sequence":1,"segmentId":"...","text":"...","isFinal":false,"timestamp":"...","speaker":"caller"}
```
- `speaker` is `caller` or `assistant` (lowercase).
- Row key is `streamId:speaker:segmentId`. Higher `sequence` replaces partial, and final is immutable.
- Transcripts are held in memory only, capped at 200 segments, and never written to disk, logs or notifications.

## Build and install

Install JDK 17 and Android SDK 35 (API 30 minimum).

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To connect via ADB to the GXV3450 desk phone over LAN:
1. On the phone or web UI, navigate to **Settings → System Security → Developer Mode** and enable it.
2. Run `adb connect <phone-ip>:5555`.
3. Tap **OK** on the phone prompt (*"Allow USB debugging?"*) and check *"Always allow from this computer"*.
4. Run `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
