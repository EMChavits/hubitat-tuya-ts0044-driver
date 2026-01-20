# Tuya TS0044 4-Button Zigbee Scene Switch (Reliable) - Hubitat Driver

- **Version:** 1.1.0  
- **Platform:** Hubitat Elevation  
- **Namespace:** EMC
- **Latest release:** v1.1.0  
  https://github.com/EMChavits/hubitat-tuya-ts0044-driver/releases/latest

A reliability-focused custom driver for **Tuya TS0044-based 4-button Zigbee scene switches**, developed and validated through real-world use rather than optimistic assumptions.

This driver prioritises **correctness, predictability, and long-term stability** over feature breadth.

---

## Status and change policy

- **Production-ready**
- **Deployed for long-term use**
- **No known reliability issues**

### Versioning intent
- **v1.0.0** — frozen behavioural baseline  
- **v1.1.0** — *non-behavioural enhancements only*:
  - factual health & diagnostic attributes
  - human-readable UI attributes
  - minor correctness hardening (battery reporting, timezone handling)

Core button semantics are **unchanged** from v1.0.0.

Any future release that alters button behaviour will require a **major version bump** and explicit justification.

See CHANGELOG.md for the full change history.

---

## Why this driver exists

Many TS0044 drivers rely on:
- optimistic timing assumptions,
- minimal debounce,
- or inference-based gesture detection.

This driver was built to:
- tolerate dropped or delayed Zigbee frames,
- avoid duplicate or conflicting button events,
- recover safely from edge-case device behaviour,
- make failures **observable rather than silent**.

The implementation reflects **empirical testing on actual hardware**.

---

## Hardware tested

This driver is **confirmed to work** with:

- **Model:** TS0044  
- **Manufacturer:** `_TZ3000_wkai4ga5`  
- **Profile:** ZHA (0x0104)

Other TS0044 variants *may* work but are **not guaranteed** to behave identically.

---

## How the device actually reports button events

Based on live Zigbee logs:

- All button activity arrives on:
  - **Cluster:** `0x0006` (On/Off)
  - **Command:** `0xFD` (Tuya-specific)
- **Button identity** is derived from `sourceEndpoint`:
  - `01` → Button 1  
  - `02` → Button 2  
  - `03` → Button 3  
  - `04` → Button 4  
- **Action** is encoded in the first data byte:
  - `0x00` → pushed  
  - `0x01` → doubleTapped *(device-level, explicit)*  
  - `0x02` → released  

No standard Zigbee button clusters are used.

---

## Supported button actions

The following actions are emitted as **physical events**:

| Action          | Source  | Notes |
|-----------------|---------|------|
| `pushed`        | Device  | Immediate, debounced |
| `doubleTapped`  | Device  | Explicit `0x01` frame on tested hardware |
| `released`      | Device  | Optional (preference-controlled) |
| `held`          | Driver  | Timing-inferred, sequence-guarded |

### About `held`
The TS0044 does **not** explicitly report a “held” gesture.

When enabled, `held` is:
- inferred by timing,
- protected by sequence tokens,
- bounded by down-state timeouts,
- fully optional.

If you prefer a **pure signal-only model**, `held` can be disabled entirely.

---

## Reliability hardening

Key defensive mechanisms built into the driver:

- Per-button, per-event debounce
- Sequence tokens to invalidate stale scheduled tasks
- Down-state timeout to prevent stuck “pressed” states
- Opportunistic self-healing (bounded and observable)
- Defensive cleanup when a device-level double-tap is received
- Optional safe-mode to disable Zigbee bind/reporting commands

The design goal is **graceful recovery**, not perfect delivery.

---

## Health & diagnostic attributes (new in v1.1.0)

Version **1.1.0** introduces **factual, low-risk attributes** intended for monitoring tools such as *SiMon* and *BatMan*.

### Canonical attributes (for apps / automation)

Stable keys intended for programmatic use:

- `lastRx` — timestamp of last Zigbee message (epoch ms)
- `rxCount` — total Zigbee messages received
- `lastEvent` — timestamp of last button event
- `eventCount` — total button events emitted
- `parseErrorCount`
- `lastParseError`
- `stuckDownClearCount`
- `lastRecovery`
- `lastButton`
- `lastAction`
- `lastEndpoint`
- `lastZcl`

These are **pure facts**.  
No interpretation, inference, or “health judgement” is performed by the driver.

### Human-readable UI attributes

Formatting-only attributes for visibility in the Hubitat UI:

- `uiLastMessageReceived`
- `uiLastButtonActivity`
- `uiMessagesReceived`
- `uiButtonEventsEmitted`
- `uiLastInteraction`
- `uiLastZigbeeDetail`
- `uiIssuesSummary`
- `uiRecoverySummary`

> ⚠️ Automation apps should **not** depend on `ui*` attributes.

---

## Battery reporting

If the device reports battery via cluster `0x0001 / attr 0x0021`, the driver:

- correctly interprets Zigbee **0.5% units**
- emits updates conservatively (≥2% change)

No synthetic battery values are generated.

---

## Installation

1. Copy the driver `.groovy` file into **Drivers Code**
2. Save the driver
3. Assign it to your TS0044 device
4. Click **Save Preferences**
5. Click **Initialize**

No re-pairing is required.

**Quick start:**  
After assignment, one **Save Preferences** followed by **Initialize** is sufficient.

---

## Configuration

Default settings are recommended for most users:

- Debounce: **350 ms**
- Hold threshold: **800 ms**
- Hold mode: *Emit pushed immediately, then held if still down*
- Down-state timeout: **10 seconds**
- Self-heal on parse: **Enabled**

Debug logging should be disabled after verification.

---

## Known limitations

- Tested only with `_TZ3000_wkai4ga5`
- Gesture behaviour may differ on other TS0044 variants
- No inference-based double-tap detection
- Conservative behaviour by design

---

## Support expectations

This driver is provided **as-is**.

### What this project is
- A stable, defensive driver for known hardware
- A reference implementation of **boringly reliable** Hubitat design

### What this project is not
- A universal TS0044 compatibility layer
- A feature-driven or experimental project
- A promise of ongoing feature development

Bug reports **with logs and hardware details** are welcome.  
Feature requests or behavioural changes may be declined to preserve stability.

---

## License

MIT License.

You are free to use, modify, and fork.  
If you redistribute modified versions, please clearly distinguish them from the original behaviour.

---

## Final note

This driver intentionally prefers **boring, predictable behaviour** over clever tricks.

If it appears more complex than expected, that complexity exists to handle  
real-world Zigbee failure modes **quietly and deterministically**.
