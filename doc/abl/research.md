---
type: Research
title: "AltBeacon android-beacon-library (ABL) evaluation for batsignal"
description: Does ABL's BeaconTransmitter support extended-length (AdvertisingSet) BLE advertising for raw at:// and did:plc: payloads? Spoiler: no.
resource: https://github.com/rektide/batsignal/blob/main/doc/abl/research.md
tags: [batsignal, ble, android, atproto, advertising]
status: stable
generated: { by: agent:opencode-glm53, at: 2026-09-02 }
verified: { by: unverified }
stale_after: 2027-03-02
sources:
  - id: abl-archive
    resource: file:///home/rektide/archive/altbeacon/android-beacon-library
    title: Local checkout at 2.21.1-16-gaca69f9 (2026-01-16)
  - id: abl-maven
    resource: https://repo1.maven.org/maven2/org/altbeacon/android-beacon-library/
    title: Maven Central coordinate org.altbeacon:android-beacon-library
    last_modified: 2026-01-16
---

# AltBeacon `android-beacon-library` (ABL) evaluation for batsignal

## TL;DR verdict

**Do not use ABL for batsignal's transmitter.** Its `BeaconTransmitter` is a thin, legacy-only wrapper: it calls `BluetoothLeAdvertiser.startAdvertising()` (the Android 5.0 API whose `AdvertiseData` is capped at 31 bytes) and contains **zero code** touching the Android 8.0+ `AdvertisingSet` / `startAdvertisingSet()` extended-advertising path. A raw `did:plc:` DID is 32 ASCII bytes — it does not fit in the 31-byte legacy budget *before any frame header*, and ABL's only coping mechanism is silently truncating over-long identifiers. There is no maintainer interest in extended advertising on either TX or RX side (the single related issue, #1125, is scan-side and has sat open since January 2023).

For a TX-only, extended-length identity beacon, write ~100 lines against the raw `android.bluetooth.le.AdvertisingSet` API inside our own foreground service. Revisit ABL only if we later want a beacon *scanner* — and even then note its scanner can't see extended advertisements either (#1125).

Evaluation performed against the local archive checkout at tag `2.21.1` + 16 commits (HEAD `aca69f9`, 2026-01-16), cross-checked with Maven Central and the GitHub issue tracker. Latest release at time of writing: **2.21.2 (2026-01-15)** — actively maintained, but maintained *as a scanner*.

---

## 1. Transmission support

Yes, ABL can transmit — barely. `lib/src/main/java/org/altbeacon/beacon/BeaconTransmitter.java` (whole file, 379 lines) is the entire TX surface:

- Constructed with `(Context, BeaconParser)`; `startAdvertising(Beacon)` serializes via `mBeaconParser.getBeaconAdvertisementData(mBeacon)` (line 183) and hands the bytes to the OS advertiser (line 234, see §2).
- **Formats**: it transmits whatever a `BeaconParser` layout describes. Built-in layout constants in `lib/src/main/java/org/altbeacon/beacon/BeaconParser.java`:
  - `ALTBEACON_LAYOUT` (line 42) — AltBeacon frame (default parser, `BeaconManager.java:476`)
  - `EDDYSTONE_TLM_LAYOUT`, `EDDYSTONE_UID_LAYOUT`, `EDDYSTONE_URL_LAYOUT` (lines 43–45) — predefined but *layouts only*; Eddystone-URL's byte-coding compression is not implemented on the TX side, you'd pre-encode yourself
  - iBeacon via the well-known custom layout string `"m:2-3=0211,i:4-19,i:20-21,i:22-23,p:24-24"` (see `lib/src/test/java/org/altbeacon/beacon/service/scanner/ScanFilterUtilsTest.java:103`)
- **Placement**: serialized bytes go out as manufacturer-specific data (default, line 225) or as service data under a 16/32/128-bit service UUID when the layout is `s:`-prefixed (lines 197–223). A 128-bit service-UUID layout can't also carry the UUID in the same legacy packet (comment at line 213: "no room").
- **Arbitrary byte payloads**: build a custom layout with a variable-length identifier (`i:4-21v` style, flag parsed at `BeaconParser.java:190-191`) and stuff bytes via `Beacon.Builder.setIdentifiers(...)` / `Identifier.fromBytes(byte[], start, end, littleEndian)` (`Identifier.java:172`). Note `setDataFields(List<Long>)` (`Beacon.java:986`) is numeric-only — Longs — so free text must ride in identifiers, not data fields.
- `checkTransmissionSupported(Context)` (lines 269–295) checks SDK level and hardware features only — no permission check (see §5).
- TX-path maturity nits: `mBeaconParser` is dereferenced at lines 169–177 *before* the null check at line 179 (NPE instead of the friendly message); `stopAdvertising()` is a no-op unless `onStartSuccess` already fired (`mStarted`, lines 245–249), so a quick start/stop can leak the advertisement. Harmless for demo apps, tells you how little traffic this path gets.

## 2. Extended-length advertising (the critical question)

**Legacy-only. Disqualifying for us.** Evidence:

- `BeaconTransmitter.java` imports only `AdvertiseCallback`, `AdvertiseData`, `AdvertiseSettings`, `BluetoothLeAdvertiser` (lines 6–9) and calls the legacy API at line 234:

  ```java
  mBluetoothLeAdvertiser.startAdvertising(settingsBuilder.build(), dataBuilder.build(), getAdvertiseCallback());
  ```

- Repo-wide grep for `AdvertisingSet|startAdvertisingSet|1650|247` across all Java sources: **0 matches**.
- GitHub issue tracker: search for `AdvertisingSet` in issues returns **0 results**; the only extended-advertising discussion is [issue #1125](https://github.com/AltBeacon/android-beacon-library/issues/1125) ("Add support to the library for detecting layouts in an extended advertisement", open since 2023-01-19) — that's the *scanner* side. The maintainer's entire response asks the reporter to propose a format; nothing has happened since.
- `AdvertiseData` payloads are hard-capped at 31 bytes of AD structures by the legacy (BT 4.x) advertising PDU format. ABL's own framing (2-byte manufacturer ID + type code + power byte) makes the real budget ~25 bytes. Over-long identifiers are silently truncated (`BeaconParser.java:828-837`: "Truncated identifier because it is too long").

Since a `did:plc:` DID alone is 32 bytes (see sizing below), the legacy path cannot carry our payload under any honest framing. Making it fit would require compression or chunking gymnastics we've explicitly ruled out.

**One correction to our working notes**: Android has no "INTERMEDIATE / LONG" payload modes — those aren't API constants. The actual knobs (from AOSP `AdvertisingSetParameters.java`, `packages/modules/Bluetooth/framework/java/android/bluetooth/le/`):

- `AdvertisingSetParameters.Builder.setLegacyMode(boolean)` — "When set to true, advertising set will advertise 4.x Spec compliant advertisements" (i.e. 31-byte legacy PDUs). The builder **defaults to `false` = extended**.
- Payload ceiling is device-dependent, queried at runtime via `BluetoothAdapter.getLeMaximumAdvertisingDataLength()` (API 26+); BT 5.0 spec allows up to **1650 bytes** total via chained AUX PDUs, and 1650 is what typical Android controllers report.
- "Long range" on Android is `setPrimaryPhy/setSecondaryPhy(PHY_LE_CODED)` (LE Coded PHY — more range, lower rate), independent of payload size. `INTERVAL_LOW/MEDIUM/HIGH` are advertising *frequency* presets (160/400/1600 × 0.625 ms).
- Capability gating: `BluetoothAdapter.isLeExtendedAdvertisingSupported()` (API 26+).

## 3. Maintenance status

- **Latest release**: 2.21.2, 2026-01-15 (`CHANGELOG.md` head; confirmed on [Maven Central](https://repo1.maven.org/maven2/org/altbeacon/android-beacon-library/maven-metadata.xml), `lastUpdated 20260116`). The GitHub *Releases* page stops at 2.19 (2021) — releases are published straight to Maven Central now, so don't use that page as the signal.
- **Actively maintained** by David G. Young (`davidgyoung`), one primary maintainer, frequent patch releases (2.20.x went to .7; 2.21.x to .2). 2.9k stars, 827 forks, 148 open issues (observed 2026-09-02).
- **SDK levels**: `compileSdk 34`, `targetSdk 34`, `minSdk 14` (`lib/build.gradle`).
- **Android 12+ permissions**: the library manifest declares only legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` — it does **not** declare `BLUETOOTH_ADVERTISE`/`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`; the app must add and request them. `BluetoothMedic` (optional helper) can *detect* `BLUETOOTH_ADVERTISE` denial (`lib/src/main/java/org/altbeacon/bluetooth/BluetoothMedic.java:524-530`), and 2.21.1 added warnings about missing permission declarations (CHANGELOG, #1229). `BeaconTransmitter` itself never checks: a missing permission surfaces as a caught-and-logged exception (`BeaconTransmitter.java:237-239`) — silent failure, no callback fired.
- **Android 14/15**: 2.21.2 "Fix broken start foreground service on Android 15 (#1247)" — but that's the *scan* `BeaconService`, which is `foregroundServiceType="location"`. Nothing Android 14+ specific exists for TX. No signs of API 35/36 work yet; nothing here blocks a modern app targetSdk since the app controls its own target.

## 4. Scanner-side weight (cost as a TX-only dependency)

ABL is a scanner that ships a transmitter. Pulling it in for TX only drags along:

- **AAR size**: 276,620 bytes for 2.21.2 ([Maven Central listing](https://repo1.maven.org/maven2/org/altbeacon/android-beacon-library/2.21.2/)), ~270 KB.
- **Transitive runtime deps** (`lib/build.gradle`): `androidx.appcompat:appcompat:1.2.0`, `androidx.core:core-ktx:1.12.0`, `androidx.lifecycle:lifecycle-process:2.6.2`, Kotlin stdlib.
- **Manifest merges into our app** (`lib/src/main/AndroidManifest.xml`), whether we use them or not:
  - permissions: `BLUETOOTH`, `BLUETOOTH_ADMIN`, **`FOREGROUND_SERVICE_LOCATION`**, `RECEIVE_BOOT_COMPLETED`, **`ACCESS_COARSE_LOCATION`** (no `maxSdkVersion` guard — a location-permission line in a beacon app's Play data-safety declaration, for a TX-only app that never scans)
  - services: `BeaconService` (foreground, type *location*), `BeaconIntentProcessor`, `ScanJob` (JobScheduler, hardcoded job IDs 208352939/208352940), `BluetoothTestJob`
  - receiver: `StartupBroadcastReceiver` for `BOOT_COMPLETED` / `POWER_CONNECTED` / `POWER_DISCONNECTED`
- 78 Java/Kotlin files in `lib/src/main` — the whole ranging/monitoring/powersave/distance-model machinery ships in the dex with us.

`BeaconTransmitter` uses none of those services (it's a standalone object talking to `BluetoothLeAdvertiser`), so for TX-only the dependency is 100% dead weight plus manifest noise a reviewer will ask about.

## 5. Background/foreground operation

Nothing. `BeaconTransmitter` is a plain object with no lifecycle: no service wrapper, no foreground-service guidance, no restart-on-adapter-off logic (Bluetooth-off during `stopAdvertising` is caught at line 255; during start it's the generic catch at 237).

Everything Android 12+–through–16 demands for our use case is on the app:

- runtime request `BLUETOOTH_ADVERTISE` before advertising (Android 12+),
- run the advertiser from a foreground service with `android:foregroundServiceType="bluetooth"`, the `FOREGROUND_SERVICE_BLUETOOTH` permission (Android 14+), and a persistent notification — otherwise advertising dies with the process when the app backgrounds,
- handle adapter state (register `BluetoothAdapter.ACTION_STATE_CHANGED` and re-start the set when BT comes back).

ABL's one foreground service is type `location` and exists for scanning. If we borrowed its `BluetoothMedic` for watchdog purposes we'd be adopting its scan-test assumptions too. Simplest to own this ourselves — it's a `Service` subclass, a `startForeground()` call, and a broadcast receiver.

## 6. Verdict & alternatives

### Recommendation

**Raw `android.bluetooth.le` `AdvertisingSet` API, no library.** The TX we need is one API surface, and it's the *only* one with the payload ceiling we need:

```kotlin
val params = AdvertisingSetParameters.Builder()
    .setLegacyMode(false)            // extended advertising (the default!)
    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
    .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)  // 1 Hz, power-friendly presence beacon
    .build()
val data = AdvertiseData.Builder()
    .addServiceData(ParcelUuid(BATSIGNAL_SERVICE_UUID), identityBytes)
    .setIncludeDeviceName(false)
    .build()
advertiser.startAdvertisingSet(params, data, null, null, null, callback)
```

(API 26+; gate on `isLeExtendedAdvertisingSupported()` and read `getLeMaximumAdvertisingDataLength()` to size the payload. Coded PHY for extra range is `setSecondaryPhy(PHY_LE_CODED)` if we ever want it — but note slower on-air rate and that some scanners don't enable coded-PHY scanning.)

**Caveat to plan around (real, not hypothetical):** non-legacy extended advertisements are invisible to pure-legacy scanners, and Android *scanners* must also opt in (scan with legacy mode off) to receive them — which is exactly the open gap in ABL's own scanner (#1125). Our receiver story — whether the scanner is our own future Android app, iOS CoreBluetooth, or a Linux box — should be validated on target hardware early. If broad legacy-scanner compatibility ever became a hard requirement, the fallback would be a *companion* short legacy frame (e.g. manufacturer frame with a 4-byte "batsignal marker" + nothing else, resolving full identity via the extended frame) — a dual-frame AdvertisingSet, still no ABL needed.

### Alternatives considered

| Option | Extended adv? | Fit |
|---|---|---|
| ABL `BeaconTransmitter` | **No** — legacy `startAdvertising` only, 31 B | Disqualified (32 B payload) |
| Raw `AdvertisingSet` (`android.bluetooth.le`) | Yes — up to controller max (typ. 1650 B) | **Chosen** — no deps, full control |
| Nordic [`android-ble-library`](https://github.com/NordicSemiconductor/Android-BLE-Library) / [`Kotlin BLE Library`](https://github.com/NordicSemiconductor/Kotlin-BLE-Library) | GATT client/server oriented; no beacon advertiser abstraction | Wrong tool — they'd still hand us the same `AdvertisingSet` call |
| `AdvertiseData` legacy API directly | No — 31 B | Only if we abandoned DID-in-advertisement |
| No other maintained Android beacon-TX library surfaced; ABL is the mainstream one, and its TX is legacy-only | | |

Revisit trigger for ABL: a future "scan for nearby batsignals" in-app feature. Even then, §2/#1125 means we'd be writing a raw `BluetoothLeScanner` with non-legacy scan settings anyway — so likely never.

---

## Payload capacity & batsignal sizing

### Advertising path capacities

| Path | Android API level | Max payload | Sees it? |
|---|---|---|---|
| Legacy advertisement (`AdvertiseData`) | 21+ | **31 B** all AD structures combined | Everything |
| Legacy adv + scan response | 21+ | 31 + 31 = 62 B | Active scanners only |
| Extended advertising set (`AdvertisingSet`, `setLegacyMode(false)`) | 26+ (BT 5.0 hw) | `BluetoothAdapter.getLeMaximumAdvertisingDataLength()` — spec max **1650 B**, typical controllers report 1650 | BT5 scanners doing extended scanning only |

ABL lives entirely in row 1. batsignal needs row 3.

### Our payloads (ASCII bytes, before any AD-structure headers)

| Payload | Bytes | Fits legacy 31 B? |
|---|---|---|
| `did:plc:<24-char id>` | 8 + 24 = **32** | **No — 1 byte over before any header** |
| `did:web:example.com` | 8 + 11 = 19 | Barely (≤ ~25 B after AD headers), domain-length-fragile |
| `did:web:` + real domain + optional port/path | 20–60+ | Fragile to no |
| `at://<did:plc:…>` | 4 + 32 = **36** | No |
| `at://<did:plc:…>/<nsid>/<rkey>` | ~66–90 | No |
| `at://<handle>` e.g. `at://rektide.tech` | 17 | Yes for short handles — but Phase 1 must accept DIDs, so this doesn't save us |

Adding any real frame header (AD length/type byte pair, 16- or 128-bit service UUID or manufacturer ID) makes the legacy fit strictly worse. The `did:plc:` row is the decision: raw-text DIDs require extended advertising, full stop.

## Prior art (footnote)

- **Eddystone-URL / Physical Web** — Google's URI-over-BLE beacon format, the canonical "text in an advertisement" precedent. It fit URLs into the 31-byte legacy budget with a byte-coding compression scheme (e.g. `0x00`⇒`http://www.`, `0x01`⇒`https://www.`, and ~14 suffix codes like `.com/`, `.org/`), leaving ~17 URL bytes — mirrored in ABL's `EDDYSTONE_URL_LAYOUT` (`i:4-21v`, `BeaconParser.java:45`). Instructive, and instructively dead: Google killed Physical Web/Nearby notifications and let Eddystone sunset. We are not inventing a sub-protocol inside a dead frame format, and we are not compressing DIDs — the 32 > 31 math means legacy is out regardless of cleverness, and BT 5 extended advertising removes the constraint the compression existed to satisfy.
- **BeaconBits** ([`blue.beaconbits.location`](https://github.com/beaconbits/lexicons)) — an ATProto lexicon for location records. Related ecosystem signal (ATProto + physical presence) but record-based: data lives in PDS records, not in BLE payloads. No bearing on our advertisement format.

## References

Follow-up: the wire-format decision this research unblocked lives in
[`design/beacon-format/beacon-format.glm53.md`](/design/beacon-format/beacon-format.glm53.md).

- Archive checkout: `~/archive/altbeacon/android-beacon-library` @ `aca69f9` (2.21.1+16, 2026-01-16)
  - `lib/src/main/java/org/altbeacon/beacon/BeaconTransmitter.java` — whole file; esp. lines 6–9 (imports), 234 (legacy startAdvertising), 237–239 (exception swallow), 245–259 (stop), 269–295 (support check)
  - `lib/src/main/java/org/altbeacon/beacon/BeaconParser.java` — 42–46 (layouts), 190–191 (variable-length flag), 751–867 (`getBeaconAdvertisementData`, truncation at 828–837)
  - `lib/src/main/java/org/altbeacon/beacon/Beacon.java` — 822+ (Builder), 875–1047 (`setIdentifiers`, `setDataFields(List<Long>)`, …)
  - `lib/src/main/java/org/altbeacon/beacon/Identifier.java` — 172 (`fromBytes`)
  - `lib/src/main/java/org/altbeacon/bluetooth/BluetoothMedic.java` — 524–530 (Android S permission-denial checks)
  - `lib/src/main/AndroidManifest.xml` — permissions & merged services
  - `lib/build.gradle` — SDK levels, deps; `CHANGELOG.md` — 2.21.2 / 2026-01-15
- Web:
  - [Maven Central maven-metadata.xml](https://repo1.maven.org/maven2/org/altbeacon/android-beacon-library/maven-metadata.xml) (latest 2.21.2), [2.21.2 directory listing](https://repo1.maven.org/maven2/org/altbeacon/android-beacon-library/2.21.2/) (AAR 276,620 B)
  - [Issue #1125 — extended advertisement scanning](https://github.com/AltBeacon/android-beacon-library/issues/1125) (open, 2023-01-19) + [maintainer comment](https://github.com/AltBeacon/android-beacon-library/issues/1125#issuecomment-1398313970)
  - [GitHub issue search: `AdvertisingSet` — 0 results](https://github.com/search?q=repo%3AAltBeacon%2Fandroid-beacon-library+AdvertisingSet&type=issues)
  - AOSP `AdvertisingSetParameters.java` ([framework/java/android/bluetooth/le](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/le/AdvertisingSetParameters.java)) — `setLegacyMode`, PHY constants, interval/TX constants; no payload-size constants (those are spec-level)
  - Android reference: [`BluetoothLeAdvertiser.startAdvertisingSet`](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser#startAdvertisingSet\(android.bluetooth.le.AdvertisingSetParameters,%20android.bluetooth.le.AdvertiseData,%20android.bluetooth.le.AdvertiseData,%20android.bluetooth.le.PeriodicAdvertisingParameters,%20android.bluetooth.le.AdvertiseData,%20android.bluetooth.le.AdvertisingSetCallback\)), [`BluetoothAdapter.getLeMaximumAdvertisingDataLength()`](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getLeMaximumAdvertisingDataLength\(\)), [`BluetoothAdapter.isLeExtendedAdvertisingSupported()`](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#isLeExtendedAdvertisingSupported\(\)), [`AdvertisingSetParameters`](https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetParameters)
