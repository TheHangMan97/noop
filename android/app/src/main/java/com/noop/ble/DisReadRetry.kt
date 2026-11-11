package com.noop.ble

/**
 * Re-issuing a Device Information Service read that Android did not accept.
 *
 * `BluetoothGatt.readCharacteristic` returns false without calling back when it does not take the
 * request, most commonly because another GATT operation is still outstanding. The DIS chain used to drop
 * that `false`: the serial read is posted a few seconds after the 5/MG handshake, which is exactly when
 * the history offload starts writing, so on a busy connect the read was simply never issued. `disRead`
 * was already latched, so nothing asked again on that link, no `DIS:` line was logged at all, and the
 * hardware variant stayed UNKNOWN. On an MG that keeps every MG-only control (the ECG capture) disabled
 * while the strap reads as connected and paired, with nothing in the log to say why.
 *
 * The remedy is a bounded retry on the same link: every [DIS_READ_RETRY_DELAY_MS] for up to
 * [DIS_READ_ISSUE_ATTEMPTS] attempts, about a minute, which outlasts an ordinary offload burst without
 * retrying forever. CoreBluetooth queues reads itself, so the Apple client has no twin of this.
 */
internal const val DIS_READ_ISSUE_ATTEMPTS = 30

/** Spacing between attempts to issue one DIS read the stack did not accept. */
internal const val DIS_READ_RETRY_DELAY_MS = 2_000L

/**
 * Delay before the next attempt after [attemptsMade] attempts that the stack did not accept, or null once
 * [DIS_READ_ISSUE_ATTEMPTS] are spent and the read should be given up on this link.
 */
internal fun disReadRetryDelayMs(attemptsMade: Int): Long? =
    if (attemptsMade < DIS_READ_ISSUE_ATTEMPTS) DIS_READ_RETRY_DELAY_MS else null

/**
 * Whether to log after [attemptsMade] unaccepted attempts: the first one (so the log shows the read was
 * tried and not taken) and the last one (so it shows the give-up). Nothing in between, so a busy minute
 * costs two lines rather than thirty.
 */
internal fun shouldLogDisReadNotAccepted(attemptsMade: Int): Boolean =
    attemptsMade == 1 || disReadRetryDelayMs(attemptsMade) == null

/**
 * The log line for a DIS read Android did not accept. States only what was observed, a false return from
 * `readCharacteristic`, and not why: the stack gives no reason, and "busy" is the common cause rather
 * than an attributed one.
 */
internal fun disReadNotAcceptedLine(label: String, attemptsMade: Int): String =
    if (disReadRetryDelayMs(attemptsMade) != null) {
        "DIS: $label read not accepted by Android (readCharacteristic returned false, attempt $attemptsMade);" +
            " retrying every ${DIS_READ_RETRY_DELAY_MS / 1000} s, up to $DIS_READ_ISSUE_ATTEMPTS attempts"
    } else {
        "DIS: $label read still not accepted after $attemptsMade attempts; giving up on this link." +
            " The hardware variant stays unknown until the next connect"
    }
