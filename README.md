# Tuya TS0044 4-Button Zigbee Scene Switch (Reliable) — Hubitat Driver

- **Version:** 1.0.0 (frozen)
- **Platform:** Hubitat Elevation
- **Namespace:** EMC

A reliability-focused custom driver for **Tuya TS0044-based 4-button Zigbee scene switches**, developed and tested through real-world use rather than theoretical assumptions.

This driver prioritises **correctness, predictability, and long-term stability** over feature breadth.

---

## Why this driver exists

Many TS0044 drivers rely on:
- timing inference for gestures,
- minimal debounce,
- or optimistic assumptions about Zigbee delivery.

This driver was built to:
- handle dropped or delayed Zigbee frames safely,
- avoid duplicate or conflicting button events,
- recover automatically from edge-case device behaviour.

The implementation reflects empirical testing on actual hardware.

---

## Hardware tested

This driver is **confirmed to work** with:

- **Model:** TS0044
- **Manufacturer:** `_TZ3000_wkai4ga5`

Other TS0044 variants *may* work, but are **not guaranteed** to behave identically.

---

## Supported button actions

The following actions are emitted as **physical events**:

| Action          | Notes                                                       |
|-----------------|-------------------------------------------------------------|
| `pushed`        | Immediate, debounced                                        |
| `held`          | Timing-inferred, sequence-guarded                           |
| `released`      | Optional (preference-controlled)                            |
| `doubleTapped`  | **Device-level** (explicit `0x01` frame on tested hardware) |

> ⚠️ Double-tap support relies on **explicit device signalling**. 
> This driver does **not** attempt to infer double-taps from multiple pushes.

---

## Reliability features

Key defensive mechanisms built into the driver:

- Per-button, per-event debounce
- Sequence tokens to invalidate stale scheduled tasks
- Down-state timeout to prevent stuck “pressed” states
- Opportunistic self-healing on every incoming Zigbee message
- Defensive cleanup when double-tap is received
- Optional safe-mode to disable Zigbee bind/reporting commands

The design goal is **graceful recovery**, not perfect delivery.

---

## Installation

1. Copy the driver `.groovy` file into **Drivers Code** in Hubitat
2. Save the driver
3. Assign it to your TS0044 device
4. Click **Save Preferences**
5. Click **Initialize**

No re-pairing is required.

**Quick start:** 
After assignment, one **Save Preferences** followed by **Initialize** is sufficient. 
Button events should be available immediately.

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
- Behaviour is intentionally conservative to preserve stability

---

## Support expectations

This driver is **provided as-is**.

- It is **frozen at v1.0.0** for long-term deployment
- It is maintained primarily for the author’s hardware
- Behaviour differences on other TS0044 variants are expected

### What this project is
- A stable, defensive driver that works reliably on tested hardware
- A reference implementation for cautious Hubitat automation

### What this project is not
- A universal TS0044 compatibility layer
- A fast-moving feature project
- A promise of ongoing support or customisation

Bug reports supported by **logs and device details** are welcome. 
Feature requests or behavioural changes may be declined to preserve stability.

---

## License

Use, modify, and fork freely under the MIT License. 
If you share changes publicly, please make it clear how they differ from v1.0 behaviour.

---

## Final note

This driver intentionally prefers **boring reliability** over clever tricks.

If it looks more complex than expected, that complexity exists to handle 
real-world failure modes quietly and predictably.
