---
type: Decision
title: "batsignal beacon wire format"
description: How an AT Protocol identity rides in a BLE extended advertisement — marker UUID, frames, and parameters.
resource: https://github.com/rektide/batsignal/blob/main/design/beacon-format/beacon-format.glm53.md
tags: [batsignal, ble, atproto, did, advertising]
status: stable # UUID candidate D confirmed by user 2026-09-02
generated: { by: agent:opencode-glm53, at: 2026-09-02 }
verified: { by: human:rektide, at: 2026-09-02 }
stale_after: 2027-03-02
sources:
  - id: abl-research
    resource: /doc/abl/research.md
    title: AltBeacon evaluation — capacity tables and AdvertisingSet findings
  - id: rfc9562
    resource: https://www.rfc-editor.org/rfc/rfc9562.html
    title: UUIDs and their URNs (version/variant field definitions)
  - id: css
    resource: https://www.bluetooth.com/specifications/assigned-numbers/
    title: Bluetooth CSS assigned numbers (AD types 0x16 / 0x21, base UUID)
---

# batsignal beacon wire format

## Situation

batsignal phase 1: an Android app where the user types an AT Protocol identity and a
foreground service advertises it over BLE. The AltBeacon evaluation
([`doc/abl/research.md`](/doc/abl/research.md)) settled the transport: **raw
`AdvertisingSet` (`setLegacyMode(false)`) in an app-owned foreground service** — no
library. ABL is legacy-`startAdvertising`-only, and a raw `did:plc:` string is 32 bytes,
one over the entire 31-byte legacy budget before any header. Eddystone is dead and its
compression buys us nothing; BT's own extended advertising removes the constraint the
compression existed to satisfy.

User decisions already made (2026-09-02):

1. **Payload = plain UTF-8 text.** Identity strings are self-describing
   (`did:plc:…`, `did:web:…`, `at://…`, or a bare handle). No kind byte, no packing.
2. **Carrier = Service Data with a custom 128-bit UUID** — the "atproto riff" marker.
3. **Run a legacy-mode companion advertisement** alongside the extended one, carrying
   only a marker tag, so legacy scanners see that something batsignal-ish is nearby.

This doc fixes the exact bytes.

## Marker UUID: the `@☎` riff

The marker is a 128-bit service UUID built from repeating the glyphs `@` (U+0040,
"at", as in handles) and `☎` (U+260E TELEPHONE, as in signal). Candidates:

| Cand. | String (glyphs) | Bytes (hex) | UUID text | ver nibble | variant | Notes |
|---|---|---|---|---|---|---|
| **A** | `@☎@☎@☎@☎` (utf-8) | `40 e2 98 8e` ×4 | `40e2988e-40e2-988e-40e2-98e240e2988e` | 9 (reserved) | 01 (NCS) | purest rhythm; lands on reserved interpretation |
| B | `@☎@☎@☎@☎` (utf-16be) | `00 40 26 0e` ×4 | `0040260e-0040-260e-0040-260e0040260e` | 2 (defined) | 00 (NCS) | only renders under utf-16be |
| C | `☎@☎@☎@☎@` (utf-8) | `e2 98 8e 40` ×4 | `e2988e40-e298-8e40-e298-8e40e2988e40` | 8 (vendor!) | 11 (reserved-future) | version 8 is poetic, variant is the worst slot |
| **D** | `@@☎@@☎@@☎@` (utf-8) | `40 40 e2 98 8e 40` ×2 + `40 e2 98 8e` interleave — see below | `4040e298-8e40-40e2-988e-4040e2988e40` | **4 (standard)** | **10 (RFC 9562)** | pure glyphs AND fully kosher shape |

D's full bytes: `40 40 e2 98 8e 40 40 e2 98 8e 40 40 e2 98 8e 40`.

### Reserved space — what we actually have to dodge

You guessed right that there's reserved space; here is the complete list for a BLE
service UUID:

1. **Bluetooth base UUID aliasing (the hard rule).** Any 128-bit UUID of the form
   `0000xxxx-0000-1000-8000-00805f9b34fb` aliases to SIG-assigned 16-bit/32-bit space
   (battery, heart-rate, …). All four candidates are nowhere near this shape — no
   dodge needed.
2. **RFC 9562 version nibble** (high nibble of byte 6) and **variant bits** (top bits
   of byte 8). BLE stacks do not require RFC 9562 shape — `UUID.fromString()` and every
   scanner accept any 16 bytes — so landing on `9`/NCS (candidate A) is *harmless in
   practice*. But it means our UUID renders as "reserved" to any UUID-aware tooling.
   Candidate **D** is the sweet spot: because `@` = `0x40` and `☎`'s middle byte is
   `0x98`, aligning the pattern so byte 6 is an `@` and byte 8 falls inside a `☎`
   yields **version 4 + variant 10** — an unremarkable random-shaped UUID — while the
   byte string is still nothing but `@` and `☎` glyphs. Collision odds with real random
   v4 UUIDs are nil (a random v4 would have to literally be these glyph bytes).

**Chosen: D**, `4040e298-8e40-40e2-988e-4040e2988e40`, decodable in any UTF-8
terminal as `@@☎@@☎@@☎@` (user-confirmed 2026-09-02). A remains documented as the
considered fallback if pure `@☎` rhythm ever matters more than the reserved-nibble
cosmetics.

## Frame layouts

### Extended frame (the identity)

One advertising set, `setLegacyMode(false)`, non-connectable, non-scannable:

```
AD structure: Service Data - 128-bit UUID (AD type 0x21)
  ┌──────────────────────┬────────────────────────────────────┐
  │ marker UUID (16 B)   │ identity string, UTF-8 (no NUL)    │
  │ 4040e298-8e40-40e2-… │ e.g. "did:plc:xxxxxxxxxxxxxxxxxxxx"│
  └──────────────────────┴────────────────────────────────────┘
```

- Budget: `getLeMaximumAdvertisingDataLength()` − ~19 (0x21 header + UUID) →
  typically 1630+ bytes. A `did:plc:` (32), `did:web:` (variable), or full `at://`
  record URI (36+) fits with enormous headroom.
- No length prefix: the AD structure's length field delimits the payload.
- No trailing NUL, no padding. The identity is the raw text the user entered
  (post-resolution, per [`batsignal-handle-resolution`]).

### Legacy companion frame (the marker)

Second advertising set, legacy mode (visible to all BT 4.x+ scanners):

```
AD structure: Flags (0x01) = 0x06                       — 3 B
AD structure: Service Data - 128-bit UUID (0x21)
  ┌──────────────────────┬─────────────────────┐
  │ marker UUID (16 B)   │ "batsignal" + 0x01  │
  └──────────────────────┴─────────────────────┘
```

- Budget math: 31 − 3 (flags) − 2 (0x21 length+type) − 16 (UUID) = **10 payload
  bytes**. `"batsignal"` (9) + one version byte `0x01` fills it exactly at 31.
- The companion says *what* is nearby, never *who* — no truncated identities, no
  fragments that could be mistaken for a DID. The full identity is extended-only.
- Both frames use the **same** marker UUID, so a scanner that filters on the UUID finds
  the companion even where it can't see the extended frame.

## AdvertisingSet parameters (boring defaults)

| Parameter | Value | Rationale |
|---|---|---|
| `setLegacyMode` | `false` (extended) / companion set `true` | identity frame needs >31 B |
| connectable / scannable | `false` / `false` | pure broadcast; no GATT server phase 1 |
| primary PHY | `LE_1M` | every extended-capable scanner supports it |
| secondary PHY | `LE_1M` | `LE_CODED` (long range) is slower and rarer; revisit if range matters |
| TX power | `TX_POWER_MEDIUM` | bump to `ULTRA_HIGH` if the receiver test comes up short |
| interval | `INTERVAL_HIGH` (~1000 ms) | presence beacon; battery-friendliest. drop to ~250 ms if discovery feels sluggish |
| include TX power | yes | free bytes, lets receivers estimate proximity |

## Receiver notes (for verification and future scanner work)

- **Android**: `ScanFilter.Builder().setServiceData(ParcelUuid(MARKER), ByteArray(0))`
  prefix-matches the UUID; and the gotcha from the research doc — scanners must set
  `ScanSettings.Builder().setLegacy(false)` or they will never see the extended frame.
- **nRF Connect**: extended advertisements appear only with extended/active scanning
  enabled; the legacy companion shows up everywhere and is the quick "is it on" check.
- **iOS**: CoreBluetooth surfaces extended advertisements on modern hardware; treat
  iOS RX as untested until we try it.

## Open questions

- None. UUID candidate D confirmed by the user 2026-09-02. TX power / interval are
  tunable knobs, not decisions.

## Cross-references

- [`doc/abl/research.md`](/doc/abl/research.md) — why raw `AdvertisingSet`, capacity
  tables, and the legacy-scanner-invisibility caveat that motivated the companion frame.
- [`/README.md`](/README.md) — phase-1 scope and dev setup.
- beads: `batsignal-payload-format` (this decision), `batsignal-advertiser-service`
  (implementation), `batsignal-handle-resolution` (what text goes in the frame later).
