---
type: Specification
title: "batsignal protocol v1"
description: BLE advertisement wire format for broadcasting an AT Protocol identity — marker UUID, extended identity frame, legacy companion frame.
resource: https://github.com/rektide/batsignal/blob/main/PROTOCOL.md
tags: [batsignal, ble, atproto, did, specification]
status: stable
generated: { by: agent:opencode-glm53, at: 2026-09-03 }
verified: { by: unverified }
stale_after: 2027-03-03
sources:
  - id: format-decision
    resource: /design/beacon-format/beacon-format.glm53.md
    title: Wire-format decision record (candidates, rationale)
  - id: abl-research
    resource: /doc/abl/research.md
    title: AltBeacon evaluation — transport capacity findings
---

# batsignal protocol v1

A batsignal transmitter broadcasts an [AT Protocol](https://atproto.com) identity —
a `did:plc:` / `did:web:` DID, an `at://` URI, or a bare handle — over Bluetooth LE
advertising, so nearby devices can discover who is present with no network in the
middle. This document is the normative wire format. Design rationale and the
alternatives considered live in
[`design/beacon-format/beacon-format.glm53.md`](/design/beacon-format/beacon-format.glm53.md);
if this spec and that document disagree, **this spec wins**.

The key words MUST, MUST NOT, SHOULD, and MAY are to be interpreted as described in
RFC 2119.

## Conformance summary

A transmitter:

- MUST advertise the **extended identity frame** (§3) whenever the controller supports
  LE extended advertising, and SHOULD also advertise the **legacy companion frame**
  (§4) so that legacy-only scanners can detect a batsignal's presence.
- MUST use the marker UUID bytes of §2 exactly in both frames.
- MUST NOT place identity text in the legacy companion frame.

A scanner:

- MUST filter on service data under the marker UUID (§2) to find batsignal frames.
- MUST perform **extended** (non-legacy) scanning to receive the identity frame —
  legacy scanners can never see it (§3).
- MUST NOT interpret the legacy companion payload as an identity (§4).

## 1. Terminology

| Term | Meaning |
|---|---|
| identity | The self-describing text being broadcast: `did:plc:…`, `did:web:…`, `at://…`, or a bare handle |
| marker UUID | The 128-bit service UUID that identifies all batsignal frames (§2) |
| extended frame | Non-legacy advertising set carrying the identity (§3) |
| companion frame | Legacy (31-byte) advertising set carrying only a tag (§4) |

## 2. Marker UUID

All batsignal Service Data rides under this 128-bit service UUID:

```
40 40 E2 98 8E 40 40 E2 98 8E 40 40 E2 98 8E 40   (big-endian, as on air)
```

- Canonical text: `4040e298-8e40-40e2-988e-4040e2988e40`
- The 16 bytes are exactly the UTF-8 encoding of `@@☎@@☎@@☎@` — `@` (U+0040, "at",
  as in handles) and `☎` (U+260E TELEPHONE, as in signal). A scanner dump of the UUID
  decodes in any UTF-8 terminal.
- The glyph alignment lands the RFC 9562 version nibble on 4 (random-shaped) and the
  variant bits on 10 (RFC 9562), so UUID-aware tooling sees an unremarkable v4 UUID.
  This is a property, not a requirement: transmitters MUST NOT alter the bytes.
- The UUID is deliberately far from the Bluetooth base UUID
  (`0000xxxx-0000-1000-8000-00805f9b34fb`), so it cannot alias SIG-assigned 16/32-bit
  service space.

## 3. Extended frame (identity)

One **non-legacy** (extended) advertising set, **non-connectable, non-scannable**:

```text
AD structure: Service Data — 128-bit UUID (AD type 0x21)
┌─────────────────────────────┬────────────────────────────────────┐
│ length (1 B)                │ 0x11 + payload length              │
│ type    (1 B)               │ 0x21                               │
│ marker  (16 B)              │ §2 bytes                           │
│ payload (N B)               │ identity string, UTF-8             │
└─────────────────────────────┴────────────────────────────────────┘
```

Identity payload rules:

- The payload is the identity string as **raw UTF-8**: no NUL, no padding, no length
  prefix, no terminator. The AD structure's length field delimits it.
- The payload MUST be non-empty and MUST NOT contain a `0x00` byte.
- Identities are self-describing by prefix; there is no type byte. Known forms today:
  `did:plc:` (32 ASCII bytes), `did:web:` (variable), `at://` URIs (variable), bare
  handles (variable). Scanners MUST treat unknown prefixes as opaque UTF-8 text.
- Payload size is bounded only by the controller's extended advertising limit
  (`BluetoothAdapter.getLeMaximumAdvertisingDataLength()` on Android; BT 5.0 spec max
  1650 B). The usable identity budget is **max − 18** (0x21 header + marker UUID).
  A transmitter MUST NOT start the extended set if the identity exceeds this budget.

Recommended transmit parameters (SHOULD; all tunable):

| Parameter | v1 value |
|---|---|
| legacy mode | `false` (extended) |
| connectable / scannable | `false` / `false` |
| primary / secondary PHY | LE 1M / LE 1M |
| TX power | medium |
| interval | ~1000 ms (presence beacon; lower for faster discovery) |
| TX power indication | included in the extended header (ACAD) |
| device name / other AD structures | omitted |

## 4. Legacy companion frame

One **legacy-mode** (BT 4.x, 31-byte) advertising set so every scanner — including
those that cannot do extended scanning — sees that a batsignal is nearby:

```text
AD structure: Flags (AD type 0x01)
  02 01 06                                          (3 B, added by the stack)

AD structure: Service Data — 128-bit UUID (AD type 0x21)
┌─────────────────────────────┬────────────────────────────────────┐
│ length (1 B)                │ 0x1B                               │
│ type    (1 B)               │ 0x21                               │
│ marker  (16 B)              │ §2 bytes                           │
│ payload (10 B)              │ "batsignal" (9 B) + version (1 B)  │
└─────────────────────────────┴────────────────────────────────────┘
```

- The tag is the ASCII bytes of `batsignal`; the final byte is the **format version**,
  `0x01` in v1.
- Flags + header + marker + payload fill the 31-byte legacy budget **exactly** (3 + 2
  + 16 + 10 = 31). Nothing else fits; transmitters MUST NOT add other AD structures.
- The companion says *what* is nearby, never *who*: identity fragments, truncated
  DIDs, or handles MUST NOT appear here. The identity is extended-frame-only, so a
  legacy scanner learns presence but not identity — by design.

Both frames use the same marker UUID, so a scanner filtering on the UUID finds the
companion even where it cannot see the extended frame.

## 5. Test vectors

### Legacy companion — full on-air AD structures (31 B)

```
02 01 06 1B 21 40 40 E2 98 8E 40 40 E2 98 8E 40 40 E2 98 8E 40
62 61 74 73 69 67 6E 61 6C 01
```

(Flags `02 01 06`, then Service Data: length `1B`, type `21`, marker UUID, tag
`batsignal`, version `01`.)

### Extended — Service Data AD structure, identity `did:plc:aaaaaaaaaaaaaaaaaaaaaaaa`

```
31 21 40 40 E2 98 8E 40 40 E2 98 8E 40 40 E2 98 8E 40
64 69 64 3A 70 6C 63 3A 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61
```

(Length `0x31` = 49 = 1 + 16 + 32; the payload is the 32 ASCII bytes of the DID. The
DID here is a placeholder — real `did:plc:` identifiers are 24 base32-sortable
characters, same length.)

### Extended — Service Data payload only, identity `did:web:example.com`

```
64 69 64 3A 77 65 62 3A 65 78 61 6D 70 6C 65 2E 63 6F 6D
```

(17 payload bytes; AD structure length would be `0x22`.)

## 6. Receiver implementation notes

- **Android**: filter with
  `ScanFilter.Builder().setServiceData(ParcelUuid(MARKER), ByteArray(0))` (prefix
  match), and set `ScanSettings.Builder().setLegacy(false)` — without it the extended
  frame is invisible. Receiving `AdvertisingSet`-based legacy companions works with
  ordinary scanning.
- **nRF Connect**: enable extended scanning to see the identity frame; the companion
  appears in the standard list.
- Payload after the marker UUID is: extended frame → identity UTF-8 (decode as text,
  display as-is); companion frame → compare the 10 bytes against the §4 vector,
  checking the version byte.
- Neither frame is connectable or scannable: there is no GATT server, no scan
  response, and no follow-up protocol in v1. Proximity can be estimated from the
  extended header's TX power field vs. RSSI.

## 7. Versioning

- The companion frame's trailing byte is the format version; v1 = `0x01`.
- A future v2 might change the companion tag bytes or add structure to either
  payload. Scanners SHOULD ignore unknown versions' payload semantics (presence is
  still signalable by UUID match alone) and SHOULD tolerate identity payloads with
  unknown prefixes.
- The marker UUID is fixed for the lifetime of the protocol.

## Cross-references

- [`design/beacon-format/beacon-format.glm53.md`](/design/beacon-format/beacon-format.glm53.md) —
  decision record: why plain UTF-8, why Service Data, the UUID candidate analysis.
- [`doc/abl/research.md`](/doc/abl/research.md) — why raw `AdvertisingSet` and not a
  beacon library; capacity tables; scanner visibility caveats.
- Reference implementation:
  [`app/src/main/java/io/github/rektide/batsignal/ble/BeaconFrames.kt`](/app/src/main/java/io/github/rektide/batsignal/ble/BeaconFrames.kt)
  (byte assembly) with tests in
  [`app/src/test/java/io/github/rektide/batsignal/ble/BeaconFramesTest.kt`](/app/src/test/java/io/github/rektide/batsignal/ble/BeaconFramesTest.kt).
- [`README.md`](/README.md) — app usage, dev setup, on-hardware verification.
