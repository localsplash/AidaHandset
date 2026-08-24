# AidaHandset

Provide the Grandstream GXV3450 data-and-control interface.

## Responsibilities

- Pair to one HostedPulse extension using a one-time code
- Receive private Pusher call notifications
- Fetch authorized call details and a data-only LiveKit token
- Display simultaneous calls and live transcripts
- Issue idempotent Take over and recover after interruption

## Stack

Kotlin, Android 11, LiveKit Android SDK, Pusher, GitHub Actions

## System specification

[Canonical Aida Voice Platform specification](https://github.com/localsplash/AidaInfrastructureSetupInstructions/blob/main/docs/AIDA_VOICE_PLATFORM_TECHNICAL_SPECIFICATION.md)

## Project invariant

The handset never stores or transmits the Asterisk SIP password.
