/**
 *  Tuya TS0044 4-Button Zigbee Scene Switch (Reliable)
 *
 *  VERSION: 1.1.0  (ENHANCEMENTS)
 *
 *  STATUS:
 *   - Production-ready
 *   - Deployed for long-term use
 *   - No known reliability issues
 *
 *  IMPORTANT:
 *   This driver is intentionally conservative and defensive.
 *   Do NOT refactor, "simplify", or optimise timing/state logic
 *   unless you have a specific, observed failure to address.
 *
 *  HARDWARE CONFIRMED (by live logs):
 *   - ZHA profile 0x0104
 *   - clusterId 0x0006 (On/Off)
 *   - Tuya command 0xFD
 *   - Button identified by sourceEndpoint (01..04)
 *   - Action encoded by data[0]:
 *
 *        0x00 = pushed
 *        0x01 = doubleTapped   (device-level; no accompanying pushed frames)
 *        0x02 = released
 *
 *  CAPABILITIES:
 *   - pushed
 *   - held            (timing-inferred, sequence-guarded)
 *   - released
 *   - doubleTapped    (device-level, explicit, reliable on this variant)
 *
 *  RELIABILITY HARDENING:
 *   - Per-button + per-event debounce
 *   - Sequence tokens to invalidate stale scheduled tasks
 *   - Down-state timeout to prevent stuck "down"
 *   - Opportunistic self-heal on every incoming message
 *   - Defensive cleanup on double-tap
 *
 *  DESIGN PHILOSOPHY:
 *   - Correctness > cleverness
 *   - Recover safely from dropped Zigbee frames
 *   - Never emit duplicate or conflicting button events
 *   - Prefer explicit device signals over inference
 *
 *  CHANGE CONTROL:
 *   - v1.0.0 was the initial long-term "frozen" baseline
 *   - v1.1.0 adds factual health/diagnostic attributes (for SiMon/BatMan) and
 *     human-centric UI attributes, without changing the core button semantics
 *   - Any future behaviour-changing updates should increment MAJOR version and
 *     be justified by real-world evidence
 *
 *  Author: E.M.Chavits
 *  Based on empirical testing of _TZ3000_wkai4ga5 / TS0044
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

@Field static final String DRIVER_NAME = "Tuya TS0044 4-Button Scene Switch (Reliable)"
@Field static final String DRIVER_VER  = "1.1.0"

@Field static final Integer BUTTON_COUNT = 4

@Field static final String CLUSTER_ONOFF = "0006"
@Field static final String CMD_TUYA_FD   = "FD"

@Field static final int ACT_PUSHED       = 0x00
@Field static final int ACT_DOUBLETAP    = 0x01
@Field static final int ACT_RELEASED     = 0x02

@Field static final String HOLDMODE_PUSHED_THEN_HELD = "pushedThenHeld"
@Field static final String HOLDMODE_TAP_OR_HOLD      = "tapOrHold"

metadata {
    definition(
        name: DRIVER_NAME,
        namespace: "EMC",
        author: "E.M.Chavits"
    ) {
        capability "Actuator"
        capability "PushableButton"
        capability "HoldableButton"
        capability "ReleasableButton"
        capability "DoubleTapableButton"
        capability "Configuration"
        capability "Refresh"
        capability "Initialize"
        capability "Battery"

        // NOTE: "SignalStrength" intentionally not declared.
        // This driver does not currently emit RSSI/LQI events, and BORINGLY-RELIABLE
        // prefers truthful interfaces over aspirational capabilities.

        // Existing
        attribute "lastCheckin", "string"
        attribute "driverVersion", "string"

        // Canonical (apps): factual, stable keys
        attribute "lastRx", "number"                   // epoch ms
        attribute "lastEvent", "number"                // epoch ms
        attribute "rxCount", "number"
        attribute "eventCount", "number"
        attribute "parseErrorCount", "number"
        attribute "lastParseError", "string"
        attribute "stuckDownClearCount", "number"
        attribute "lastRecovery", "number"             // epoch ms
        attribute "lastButton", "number"
        attribute "lastAction", "string"
        attribute "lastEndpoint", "string"
        attribute "lastZcl", "string"

        // Human-centric (UI): formatting only; do not use for automation logic
        attribute "uiLastMessageReceived", "string"
        attribute "uiLastButtonActivity", "string"
        attribute "uiMessagesReceived", "string"
        attribute "uiButtonEventsEmitted", "string"
        attribute "uiLastInteraction", "string"
        attribute "uiLastZigbeeDetail", "string"
        attribute "uiIssuesSummary", "string"
        attribute "uiRecoverySummary", "string"

        fingerprint profileId: "0104",
                manufacturer: "_TZ3000_wkai4ga5",
                model: "TS0044",
                deviceJoinName: "Tuya TS0044 4-Button Scene Switch"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logFrames", type: "bool", title: "Log decoded button frames (debug)", defaultValue: false

        input name: "debounceMs", type: "number", title: "Debounce (ms) per button+event", defaultValue: 350, range: "0..2000"

        input name: "emitHeld", type: "bool", title: "Emit 'held' events (inferred by timing)", defaultValue: true
        input name: "holdThresholdMs", type: "number", title: "Hold threshold (ms)", defaultValue: 800, range: "250..5000"
        input name: "holdMode", type: "enum", title: "Hold mode",
                options: [
                        (HOLDMODE_PUSHED_THEN_HELD): "Emit pushed immediately, then held if still down (most responsive)",
                        (HOLDMODE_TAP_OR_HOLD):      "Tap=push only, Hold=held only (no pushed for holds)"
                ],
                defaultValue: HOLDMODE_PUSHED_THEN_HELD

        input name: "emitReleased", type: "bool", title: "Emit 'released' events", defaultValue: true

        input name: "downTimeoutMs", type: "number", title: "Down-state timeout (ms) to prevent 'stuck down'", defaultValue: 10000, range: "0..60000"

        // v0.5 extra #1: self-heal stuck-down state opportunistically on each parse
        input name: "selfHealOnParse", type: "bool", title: "Self-heal stuck-down state on each message", defaultValue: true

        // v0.5 extra #2: safe-mode toggle for configure() binds/reporting
        input name: "enableConfigureBinds", type: "bool", title: "Configure: send Zigbee binds/reporting commands", defaultValue: true

        input name: "layout", type: "enum", title: "Button layout mapping (optional)",
                options: [
                        "default": "Default (ep1..4 => btn1..4)",
                        "swapLR":  "Swap Left/Right (1<->3, 2<->4)",
                        "swapTB":  "Swap Top/Bottom (1<->2, 3<->4)",
                        "rot180":  "Rotate 180° (1<->4, 2<->3)"
                ],
                defaultValue: "default"
    }
}

/* ------------------------- Lifecycle ------------------------- */

def installed() {
    logInfo("${DRIVER_NAME} v${DRIVER_VER} installed")
    initialize()
}

def updated() {
    logInfo("${DRIVER_NAME} v${DRIVER_VER} updated")
    initialize()
    if (logEnable) runIn(1800, "logsOff")
}

def initialize() {
    sendEvent(name: "driverVersion", value: DRIVER_VER, displayed: false)

    sendEvent(name: "numberOfButtons", value: BUTTON_COUNT, displayed: false)
    List<String> supported = ["pushed", "doubleTapped"]
    if (emitHeld != false) supported << "held"
    if (emitReleased != false) supported << "released"
    sendEvent(name: "supportedButtonValues", value: supported, isStateChange: true, displayed: false)

    // Initialise canonical counters if missing. Monotonic thereafter.
    if (state.rxCount == null) state.rxCount = 0L
    if (state.eventCount == null) state.eventCount = 0L
    if (state.parseErrorCount == null) state.parseErrorCount = 0L
    if (state.stuckDownClearCount == null) state.stuckDownClearCount = 0L

    // Mirror canonical counters into attributes for apps/UI.
    sendEvent(name: "rxCount", value: (state.rxCount as Long), displayed: false)
    sendEvent(name: "eventCount", value: (state.eventCount as Long), displayed: false)
    sendEvent(name: "parseErrorCount", value: (state.parseErrorCount as Long), displayed: false)
    sendEvent(name: "stuckDownClearCount", value: (state.stuckDownClearCount as Long), displayed: false)

    // UI summaries (formatting only)
    refreshUiSummaries()

    (1..BUTTON_COUNT).each { Integer b ->
        clearDownState(b)
        clearHoldSchedule(b)
        clearHeldEmitted(b)
        clearDownSince(b)

        state.remove(debounceKey(b, "pushed"))
        state.remove(debounceKey(b, "held"))
        state.remove(debounceKey(b, "released"))
        state.remove(debounceKey(b, "doubleTapped"))
    }

    touchCheckin()
}

/* ------------------------- Commands ------------------------- */

def configure() {
    logInfo("configure()")
    if (enableConfigureBinds == false) {
        logInfo("configure() skipped: enableConfigureBinds is false")
        return []
    }

    List<String> cmds = []
    // Many TS0044 variants are command-only and may ignore these; generally harmless.
    cmds += zigbee.bind(0x0006)
    cmds += zigbee.bind(0x0001)
    cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 30, 21600, 0x01)
    return cmds
}

def refresh() {
    logInfo("refresh()")
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, 0x0004)
    cmds += zigbee.readAttribute(0x0000, 0x0005)
    return cmds
}

/* ------------------------- Parsing ------------------------- */

def parse(String description) {
    if (logEnable) log.debug "parse: ${description}"
    touchCheckin()

    Map descMap = safeDescMap(description)
    if (!descMap) {
        noteParseError("Unable to parse Zigbee description")
        return
    }

    noteRx(descMap)

    // Change 1/3: Run self-heal after successful parse/noteRx (reduces “surprise clears”)
    if (selfHealOnParse != false) {
        selfHealStuckDown()
    }

    if (handleBattery(descMap)) return
    if (handleTs0044ButtonFrame(descMap)) return
}

private void selfHealStuckDown() {
    // Only clear if timed out; keep it bounded and cheap.
    (1..BUTTON_COUNT).each { Integer b ->
        if (isDownTimedOut(b)) {
            hardClearDownState(b, "selfHealOnParse")
        }
    }
}

/**
 * TS0044 button frame handler.
 */
private boolean handleTs0044ButtonFrame(Map m) {
    String clusterId = (m.clusterId ?: "").toString().toLowerCase()
    String cmd       = (m.command ?: "").toString().toUpperCase()

    boolean isOnOffCluster = (clusterId == CLUSTER_ONOFF) || (m.clusterInt == 0x0006)
    boolean isFdCommand    = (cmd == CMD_TUYA_FD)

    if (!isOnOffCluster || !isFdCommand) return false

    Integer srcButton = safeHexToInt(m.sourceEndpoint?.toString())
    if (!(srcButton in 1..BUTTON_COUNT)) {
        logDebug("Ignoring TS0044 frame: unexpected sourceEndpoint=${m.sourceEndpoint}")
        return true
    }

    Integer button = mapButton(srcButton)

    List data = (m.data instanceof List) ? (List)m.data : []
    if (!data || data.size() < 1) {
        logDebug("Ignoring TS0044 frame: missing data payload (ep=${srcButton})")
        return true
    }

    Integer action = safeHexToInt(data[0]?.toString())

    if (logFrames && logEnable) {
        log.debug "TS0044 frame decoded: ep=${srcButton}=>btn=${button} actionByte=0x${toHex2(action)} data=${data}"
    }

    switch (action) {
        case ACT_PUSHED:
            onPhysicalPress(button)
            return true
        case ACT_DOUBLETAP:
            onPhysicalDoubleTap(button)
            return true
        case ACT_RELEASED:
            onPhysicalRelease(button)
            return true
        default:
            logDebug("TS0044 unknown action byte 0x${toHex2(action)} for button ${button} (srcEp=${srcButton})")
            return true
    }
}

private boolean handleBattery(Map m) {
    if (m.clusterInt == 0x0001 && m.attrInt == 0x0021 && m.value) {
        // Change 2/3: Zigbee 0x0021 is commonly in 0.5% units (200 = 100%)
        Integer raw = safeHexToInt(m.value?.toString())
        Integer pct = Math.round(raw / 2.0f) as Integer
        pct = clamp(pct, 1, 100)
        maybeSendBattery(pct)
        return true
    }
    return false
}

/* ------------------------- Button semantics ------------------------- */

private void onPhysicalPress(Integer button) {
    setDownState(button, true)

    Long seq = nextSeq(button)
    setDownSince(button, now())

    if (!emitHeld) {
        emitButtonEvent(button, "pushed")
        scheduleDownTimeout(button, seq)
        return
    }

    Integer threshold = clamp(((holdThresholdMs ?: 800) as Integer), 250, 5000)

    if ((holdMode ?: HOLDMODE_PUSHED_THEN_HELD) == HOLDMODE_TAP_OR_HOLD) {
        scheduleHoldCheck(button, threshold, seq)
    } else {
        emitButtonEvent(button, "pushed")
        scheduleHoldCheck(button, threshold, seq)
    }

    scheduleDownTimeout(button, seq)
}

private void onPhysicalRelease(Integer button) {
    boolean wasDown = isDown(button)
    setDownState(button, false)
    clearDownSince(button)

    bumpSeq(button)

    if (emitHeld && (holdMode == HOLDMODE_TAP_OR_HOLD) && wasDown) {
        if (!heldEmitted(button)) {
            emitButtonEvent(button, "pushed")
        }
    }

    if (emitReleased) {
        emitButtonEvent(button, "released")
    }

    clearHoldSchedule(button)
    clearHeldEmitted(button)
}

/**
 * Double-tap is device-level (0x01) on your variant; no timing inference required.
 */
private void onPhysicalDoubleTap(Integer button) {
    // Defensive clean-up of any press/hold state
    setDownState(button, false)
    clearDownSince(button)
    clearHoldSchedule(button)
    clearHeldEmitted(button)
    bumpSeq(button)

    emitButtonEvent(button, "doubleTapped")
}

/**
 * Scheduled hold check. Emits held if still down AND sequence token matches.
 */
def holdCheck(Map data) {
    Integer button = data?.button as Integer
    Long seq = (data?.seq ?: 0L) as Long
    if (!(button in 1..BUTTON_COUNT)) return

    clearHoldSchedule(button)

    if (!emitHeld) return
    if (!seqMatches(button, seq)) return

    if (isDownTimedOut(button)) {
        hardClearDownState(button, "holdCheck")
        return
    }

    if (isDown(button) && !heldEmitted(button)) {
        emitButtonEvent(button, "held")
        setHeldEmitted(button, true)
    }
}

/**
 * Scheduled down-timeout safety net. Clears stuck-down state if release never arrives.
 */
def downTimeoutCheck(Map data) {
    Integer button = data?.button as Integer
    Long seq = (data?.seq ?: 0L) as Long
    if (!(button in 1..BUTTON_COUNT)) return

    if (!seqMatches(button, seq)) return

    if (isDownTimedOut(button)) {
        hardClearDownState(button, "downTimeout")
    }
}

/* ------------------------- Hardening helpers ------------------------- */

private boolean isDownTimedOut(Integer button) {
    Integer timeout = (downTimeoutMs ?: 10000) as Integer
    if (timeout <= 0) return false

    Long since = (state[downSinceKey(button)] ?: 0L) as Long
    if (since <= 0L) return false

    return (isDown(button) && (now() - since) >= (timeout as Long))
}

private void hardClearDownState(Integer button, String reason) {
    logDebug("Clearing stuck down state for button ${button} (reason=${reason})")
    setDownState(button, false)
    clearDownSince(button)
    clearHoldSchedule(button)
    clearHeldEmitted(button)
    bumpSeq(button)

    noteRecovery("Cleared stuck down state (btn ${button}, reason=${reason})")
}

/* ------------------------- Event emission + debounce ------------------------- */

private void emitButtonEvent(Integer button, String action) {
    Long nowMs = now()
    Long last  = (state[debounceKey(button, action)] ?: 0L) as Long
    Long db    = ((debounceMs ?: 0) as Long)

    if (db > 0 && (nowMs - last) < db) {
        logDebug("Debounced ${action} for button ${button} (${nowMs - last}ms < ${db}ms)")
        return
    }
    state[debounceKey(button, action)] = nowMs

    logInfo("${device.displayName} button ${button} ${action}")
    sendEvent(name: action, value: button, isStateChange: true, type: "physical")

    noteEvent(button, action)
}

/* ------------------------- Scheduling ------------------------- */

private void scheduleHoldCheck(Integer button, Integer thresholdMs, Long seq) {
    setHoldScheduled(button, true)
    try {
        runInMillis(thresholdMs, "holdCheck", [data: [button: button, seq: seq]])
    } catch (Throwable t) {
        Integer secs = Math.max(1, Math.round(thresholdMs / 1000.0f) as Integer)
        runIn(secs, "holdCheck", [data: [button: button, seq: seq]])
        logDebug("runInMillis unavailable; hold timing rounded to seconds (~${secs}s)")
    }
}

private void scheduleDownTimeout(Integer button, Long seq) {
    Integer timeout = (downTimeoutMs ?: 10000) as Integer
    if (timeout <= 0) return

    Integer delayMs = clamp(timeout + 250, 250, 60000)
    try {
        runInMillis(delayMs, "downTimeoutCheck", [data: [button: button, seq: seq]])
    } catch (Throwable t) {
        Integer secs = Math.max(1, Math.round(delayMs / 1000.0f) as Integer)
        runIn(secs, "downTimeoutCheck", [data: [button: button, seq: seq]])
    }
}

/* ------------------------- Mapping ------------------------- */

private Integer mapButton(Integer b) {
    String l = (layout ?: "default").toString()
    switch (l) {
        case "swapLR":
            if (b == 1) return 3
            if (b == 3) return 1
            if (b == 2) return 4
            if (b == 4) return 2
            return b
        case "swapTB":
            if (b == 1) return 2
            if (b == 2) return 1
            if (b == 3) return 4
            if (b == 4) return 3
            return b
        case "rot180":
            if (b == 1) return 4
            if (b == 4) return 1
            if (b == 2) return 3
            if (b == 3) return 2
            return b
        default:
            return b
    }
}

/* ------------------------- Health / visibility ------------------------- */

private void touchCheckin() {
    String ts = formatNowIso()
    sendEvent(name: "lastCheckin", value: ts, displayed: false)
}

// Canonical: message received (any successfully parsed Zigbee description)
private void noteRx(Map m) {
    Long t = now()
    state.rxCount = ((state.rxCount ?: 0L) as Long) + 1L
    sendEvent(name: "lastRx", value: t, displayed: false)
    sendEvent(name: "rxCount", value: (state.rxCount as Long), displayed: false)

    String ep = (m?.sourceEndpoint != null) ? m.sourceEndpoint.toString() : null
    String cl = (m?.clusterId != null) ? m.clusterId.toString() : (m?.clusterInt != null ? String.format("%04X", (m.clusterInt as Integer)) : null)
    String cmd = (m?.command != null) ? m.command.toString() : null

    if (ep) sendEvent(name: "lastEndpoint", value: ep, displayed: false)
    if (cl || cmd) {
        String detail = "cluster=${cl ?: "?"} cmd=${cmd ?: "?"}"
        sendEvent(name: "lastZcl", value: detail, displayed: false)
    }

    // UI formatting only
    sendEvent(name: "uiLastMessageReceived", value: formatIso(t), displayed: false)
    sendEvent(name: "uiMessagesReceived", value: "${state.rxCount as Long}", displayed: false)
    if (ep || cl || cmd) {
        String uiDetail = "ep ${ep ?: "?"} | cluster ${cl ?: "?"} | cmd ${cmd ?: "?"}"
        sendEvent(name: "uiLastZigbeeDetail", value: uiDetail, displayed: false)
    }

    refreshUiSummaries()
}

// Canonical: button event emitted
private void noteEvent(Integer button, String action) {
    Long t = now()
    state.eventCount = ((state.eventCount ?: 0L) as Long) + 1L

    sendEvent(name: "lastEvent", value: t, displayed: false)
    sendEvent(name: "eventCount", value: (state.eventCount as Long), displayed: false)
    sendEvent(name: "lastButton", value: (button ?: 0) as Integer, displayed: false)
    sendEvent(name: "lastAction", value: (action ?: ""), displayed: false)

    // UI formatting only
    sendEvent(name: "uiLastButtonActivity", value: formatIso(t), displayed: false)
    sendEvent(name: "uiButtonEventsEmitted", value: "${state.eventCount as Long}", displayed: false)
    sendEvent(name: "uiLastInteraction", value: "Button ${button} — ${action}", displayed: false)

    refreshUiSummaries()
}

// Canonical: parse issues
private void noteParseError(String msg) {
    state.parseErrorCount = ((state.parseErrorCount ?: 0L) as Long) + 1L
    sendEvent(name: "parseErrorCount", value: (state.parseErrorCount as Long), displayed: false)
    if (msg) sendEvent(name: "lastParseError", value: msg.take(200), displayed: false)
    refreshUiSummaries()
}

// Canonical: recovery actions (bounded self-heal / timeout clearing)
private void noteRecovery(String reason) {
    Long t = now()
    state.stuckDownClearCount = ((state.stuckDownClearCount ?: 0L) as Long) + 1L
    sendEvent(name: "stuckDownClearCount", value: (state.stuckDownClearCount as Long), displayed: false)
    sendEvent(name: "lastRecovery", value: t, displayed: false)

    // UI formatting only
    sendEvent(name: "uiRecoverySummary", value: "${state.stuckDownClearCount as Long} (last: ${formatIso(t)})", displayed: false)
    if (reason) {
        refreshUiSummaries(reason.take(120))
    } else {
        refreshUiSummaries()
    }
}

// UI summaries are formatting only and may change without breaking app contracts.
private void refreshUiSummaries(String extraNote = null) {
    Long pe = (state.parseErrorCount ?: 0L) as Long
    Long rc = (state.stuckDownClearCount ?: 0L) as Long
    String lastErr = device.currentValue("lastParseError") as String

    List<String> parts = []
    if (pe > 0) parts << "parse issues: ${pe}" + (lastErr ? " (last: ${lastErr})" : "")
    if (rc > 0) parts << "recoveries: ${rc}"
    if (extraNote) parts << extraNote

    String issues = parts ? parts.join(" | ") : "none recorded"
    sendEvent(name: "uiIssuesSummary", value: issues, displayed: false)

    // Keep recovery summary populated even when zero.
    if (rc <= 0L) {
        sendEvent(name: "uiRecoverySummary", value: "0", displayed: false)
    }
}

// Change 3/3: Timezone fallback to UTC if location.timeZone is null
private java.util.TimeZone tzOrUtc() {
    return location?.timeZone ?: java.util.TimeZone.getTimeZone("UTC")
}

private String formatIso(Long ms) {
    if (ms == null || ms <= 0L) return ""
    try {
        return new Date(ms).format("yyyy-MM-dd HH:mm:ss", tzOrUtc())
    } catch (Throwable ignored) {
        return new Date(ms).toString()
    }
}

private String formatNowIso() {
    try {
        return new Date().format("yyyy-MM-dd HH:mm:ss", tzOrUtc())
    } catch (Throwable ignored) {
        return new Date().toString()
    }
}

/* ------------------------- Battery ------------------------- */

private void maybeSendBattery(Integer pct) {
    Integer last = device.currentValue("battery") as Integer
    if (last == null || Math.abs(pct - last) >= 2) {
        logInfo("${device.displayName} battery ${pct}%")
        sendEvent(name: "battery", value: pct, unit: "%")
    } else {
        logDebug("Battery ${pct}% ignored (last ${last}%)")
    }
}

/* ------------------------- Sequence token (hardening) ------------------------- */

private String seqKey(Integer b) { return "seq_${b}" }

private Long currentSeq(Integer b) {
    return (state[seqKey(b)] ?: 0L) as Long
}

private Long nextSeq(Integer b) {
    Long s = currentSeq(b) + 1L
    state[seqKey(b)] = s
    return s
}

private void bumpSeq(Integer b) {
    nextSeq(b)
}

private boolean seqMatches(Integer b, Long seq) {
    return currentSeq(b) == (seq ?: 0L)
}

/* ------------------------- State helpers ------------------------- */

private String downKey(Integer b)                    { return "isDown_${b}" }
private String heldKey(Integer b)                    { return "heldEmitted_${b}" }
private String holdScheduledKey(Integer b)           { return "holdScheduled_${b}" }
private String debounceKey(Integer b, String action) { return "db_${b}_${action}" }
private String downSinceKey(Integer b)               { return "downSinceMs_${b}" }

private boolean isDown(Integer b) { return (state[downKey(b)] == true) }
private void setDownState(Integer b, boolean v) { state[downKey(b)] = v }

private boolean heldEmitted(Integer b) { return (state[heldKey(b)] == true) }
private void setHeldEmitted(Integer b, boolean v) { state[heldKey(b)] = v }
private void clearHeldEmitted(Integer b) { state.remove(heldKey(b)) }

private void setHoldScheduled(Integer b, boolean v) { state[holdScheduledKey(b)] = v }
private void clearHoldSchedule(Integer b) { state.remove(holdScheduledKey(b)) }

private void setDownSince(Integer b, Long ms) { state[downSinceKey(b)] = (ms ?: 0L) }

private void clearDownState(Integer b) { state.remove(downKey(b)) }
private void clearDownSince(Integer b) { state.remove(downSinceKey(b)) }

/* ------------------------- Parsing utilities ------------------------- */

private Map safeDescMap(String description) {
    try {
        return zigbee.parseDescriptionAsMap(description)
    } catch (Throwable t) {
        logDebug("parseDescriptionAsMap failed: ${t}")
        return null
    }
}

private Integer safeHexToInt(String hex) {
    if (hex == null) return 0
    String h = hex.toString().trim().replace("0x", "")
    if (h.length() == 0) return 0
    try {
        return Integer.parseInt(h, 16)
    } catch (Throwable ignored) {
        return 0
    }
}

private Integer clamp(Integer v, Integer min, Integer max) {
    return Math.max(min, Math.min(max, v))
}

private String toHex2(Integer v) {
    int x = (v == null) ? 0 : v.intValue()
    return String.format("%02X", (x & 0xFF))
}

/* ------------------------- Logging helpers ------------------------- */

private void logInfo(String msg) {
    if (txtEnable) log.info msg
}

private void logDebug(String msg) {
    if (logEnable) log.debug msg
}

def logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.warn "${device.displayName} debug logging disabled"
}

