# batsignal

> A Bluetooth+ATproto presence signaller

batsignal is an Android app that advertises an [AT Protocol](https://atproto.com)
identity — a handle, `at://` URI, or `did:plc:` / `did:web:` DID — over
Bluetooth LE beacons, so nearby devices can discover who you are without any
network in the middle.

**Current scope (phase 1):** a single screen where you type the identity to
broadcast, and Start/Stop controls that drive a stub foreground service
([`BeaconService`](app/src/main/java/io/github/rektide/batsignal/service/BeaconService.kt)).
The service holds the identity, shows a persistent notification with a Stop
action, and exposes the `startAdvertising()` / `stopAdvertising()` TODO hooks
where real BLE advertising will land (a parallel effort is evaluating AltBeacon
vs. raw `AdvertisingSet` with extended payloads). Sign-in and resolution of
handles to DIDs come later.

The manifest plumbing is already banked: runtime requests for
`POST_NOTIFICATIONS` (Android 13+) and `BLUETOOTH_ADVERTISE` (Android 12+) are
asked before the service starts, and the service is declared with the
`connectedDevice` foreground-service type (the correct type for BLE
advertising — there is no `bluetooth` FGS type) paired with
`FOREGROUND_SERVICE_CONNECTED_DEVICE`.

## Dev setup

Toolchain is pinned with [mise](https://mise.jdx.dev/) — JDK (Temurin 21 LTS)
and Gradle 8.14.5:

```sh
mise install
```

The Android SDK is system-installed (Debian layout) at
`/usr/lib/android-sdk` — build-tools 36.0.0, platform `android-36` — and wired
up via git-ignored `local.properties`. If it's missing:

```sh
echo "sdk.dir=/usr/lib/android-sdk" > local.properties
```

Build:

```sh
mise exec -- ./gradlew assembleDebug
```

Prefer `mise exec --` over a bare `./gradlew` unless your environment already
resolves `java` to the pinned JDK: the wrapper picks up whatever `java` is
first on `PATH`, and a too-new JDK (e.g. 26) breaks AGP 8.13 with a cryptic
version-number-only error.

## Install on device

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
```
