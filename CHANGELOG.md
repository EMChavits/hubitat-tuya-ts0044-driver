# Changelog

All notable changes to this project are documented here.

This project follows a **boringly reliable** change policy:
- Behavioural stability is prioritised over feature growth
- Non-behavioural enhancements may be released as minor versions
- Any change to button semantics requires a major version bump

---

## [1.1.0] — Enhancements (non-behavioural)

### Added
- Canonical, factual health attributes for monitoring tools (e.g. SiMon, BatMan):
  - `lastRx`, `rxCount`
  - `lastEvent`, `eventCount`
  - `parseErrorCount`, `lastParseError`
  - `stuckDownClearCount`, `lastRecovery`
  - `lastButton`, `lastAction`
  - `lastEndpoint`, `lastZcl`
- Human-readable UI attributes (formatting only):
  - `uiLastMessageReceived`
  - `uiLastButtonActivity`
  - `uiMessagesReceived`
  - `uiButtonEventsEmitted`
  - `uiLastInteraction`
  - `uiLastZigbeeDetail`
  - `uiIssuesSummary`
  - `uiRecoverySummary`

### Changed
- Battery reporting corrected to handle Zigbee 0.5% units (cluster `0x0001`, attribute `0x0021`)
- Self-heal logic now runs after successful message parsing (reduces “surprise clears”)
- Time formatting hardened with UTC fallback when hub timezone is unavailable
- `supportedButtonValues` now reflects enabled features accurately

### Removed
- `SignalStrength` capability (not implemented; interface now strictly truthful)

### Behaviour
- **No changes** to button semantics, timing, or event generation
- v1.0.0 behaviour remains the reference baseline

---

## [1.0.0] — Initial stable release

- Frozen, long-term deployment baseline
- Defensive handling of TS0044 button frames
- Sequence-guarded timing inference for `held`
- Explicit device-level double-tap support
- Conservative recovery from Zigbee delivery issues
