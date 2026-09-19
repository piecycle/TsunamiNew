package app.aaps.plugins.aps.tsunami

import app.aaps.core.interfaces.aps.GlucoseStatus
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * Result of the Tsunami/Wave safety evaluation.
 *
 * @property blocked True if a hard stop applies. Tsunami/Wave dosing must be disabled (fall back to oref1).
 * @property allowance Fraction (0.0 - 1.0, in steps of 0.25) of the calculated insulin requirement that may be dosed.
 * @property log Human readable explanation, one line per layer.
 */
data class TsunamiSafetyResult(
    val blocked: Boolean,
    val allowance: Double,
    val log: List<String>
)

/** Legitimacy of a glucose rise. Doubtful rises are minor climbs (small snack, secondary meal peak); confirmed rises are large and sustained. */
enum class RiseClass(val allowance: Double) {
    CONFIRMED(1.0),
    PROBABLE(0.5),
    DOUBTFUL(0.25)
}

/**
 * Safety module for Tsunami and Wave mode. Works without carbohydrate input and without glucose predictions.
 *
 * Layer 1: hard stop below an absolute BG floor.
 * Layer 2: BG risk attenuation (Kovatchev risk function) and a post-hypoglycemia lockout.
 * Layer 3: classification of the current rise as confirmed, probable or doubtful.
 *
 * The layers are combined by taking the minimum (not the product) together with an IOB headroom cap and a
 * mode ceiling. The result is snapped down to steps of 25 %.
 */
object TsunamiSafety {

    // --- Layer 1 ---
    private const val HARD_FLOOR_BG = 80.0 // mg/dL, no Tsunami/Wave dosing below this

    // --- Layer 2 ---
    private const val LOW_BG = 70.0 // mg/dL, definition of a low
    private const val KOVATCHEV_ZERO_RISK_BG = 112.5 // mg/dL, zero risk point of the Kovatchev risk function
    private const val LOCKOUT_MIN_MINUTES = 30.0 // lockout after a shallow low (nadir just below LOW_BG)
    private const val LOCKOUT_MAX_MINUTES = 90.0 // lockout ceiling, reached at a nadir of 50 mg/dL or lower
    private const val LOCKOUT_MINUTES_PER_MGDL = 3.0 // additional lockout minutes per mg/dL of nadir below LOW_BG
    private const val RESCUE_CARB_SIGNATURE_MINUTES = 30.0 // rise after a nadir shorter than this is attributed to rescue carbs (includes sensor lag)
    private const val LOCKOUT_RELEASE_ABOVE_TARGET = 20.0 // mg/dL above target at which the lockout may be released
    private const val LOCKOUT_BREAKOUT_BG = 160.0 // mg/dL, above this the lockout ends immediately, even within the rescue-carb signature
    private const val LOCKOUT_ALLOWANCE = 0.25// allowance while locked out, after the rescue-carb signature has passed

    // --- Layer 3 ---
    private const val EXCURSION_WINDOW_MINUTES = 90.0 // look-back for the local minimum
    private const val SMALL_EXCURSION = 60.0 // mg/dL, below this a rise counts as minor
    private const val MIN_DELTA_FOR_RISE = 1.0 // mg/dL per 5 min, below this there is no rise
    private const val MOMENTUM_MIN_PREVIOUS_DELTA = 4.0 // deceleration is only assessed if the previous delta was significant
    private const val MOMENTUM_STRONG_DECELERATION = 0.5 // delta / previous delta below this is a strong deceleration
    private const val TIMER_EXPIRING_MINUTES = 15.0 // tsunami with less time than this left is considered expiring
    private const val GROSS_DELTA_DOUBTFUL_BELOW = 4.0 // mg/dL per 5 min, gross delta below this is drift/decaying insulin, not a true rise
    private const val GROSS_DELTA_CONFIRMED_AT_LEAST = 10.0 // mg/dL per 5 min, gross delta needed to confirm a rise

    // --- IOB headroom ---
    private const val IOB_START_REDUCING = 0.5 // fraction of max IOB
    private const val IOB_FULL_REDUCTION = 1.2 // fraction of max IOB
    private const val IOB_MIN_FACTOR = 0.6

    private const val ALLOWANCE_STEP = 0.25
    private const val MINUTE_MS = 60_000.0

    /**
     * @param history Recent glucose (newest first, at least 150 min for the full lockout logic).
     * @param rawHistory Raw glucose (newest first), used for early deceleration detection.
     * @param nowMs Time of the newest glucose reading.
     * @param modeEndTimeMs Planned end of the active Tsunami, null for Wave or if unknown.
     * @param grossDelta Delta the glucose would have without insulin activity (net delta + activity * ISF). Separates
     *        true rises (high gross delta) from a net rise that only exists because insulin action is fading (gross delta close to net delta).
     */
    fun evaluate(
        bg: Double,
        delta: Double,
        grossDelta: Double,
        targetBg: Double,
        iob: Double,
        maxIob: Double,
        history: List<GlucoseStatus>,
        rawHistory: List<GlucoseStatus>,
        nowMs: Long,
        modeEndTimeMs: Long?
    ): TsunamiSafetyResult {
        val log = mutableListOf("--- Tsunami Safety ---")

        // Layer 1: hard stop
        if (bg < HARD_FLOOR_BG) {
            log.add("L1 BG floor: BG $bg < ${HARD_FLOOR_BG.toInt()}. Blocked, using oref1.")
            return TsunamiSafetyResult(blocked = true, allowance = 0.0, log = log)
        }

        // Layer 2a: BG risk factor
        val bgFactor = bgRiskFactor(bg)
        log.add("L2 BG risk factor: ${format(bgFactor)}")

        // Layer 2b: post-hypoglycemia lockout
        val lockoutCap = lockoutCap(bg, targetBg, history, nowMs, log)

        // Layer 3: rise legitimacy, downgrades take effect immediately, upgrades need two consecutive cycles
        // No gross delta history exists, so the current gross delta is used for the previous cycle as well
        val riseNow = classifyRise(bg, delta, grossDelta, history, rawHistory, nowMs, modeEndTimeMs)
        val previousHistory = history.drop(1)
        val previousRaw = rawHistory.drop(1)
        val risePrevious = if (previousHistory.size >= 2) {
            classifyRise(previousHistory[0].glucose, previousHistory[0].delta, grossDelta, previousHistory, previousRaw, previousHistory[0].date, modeEndTimeMs)
        } else riseNow
        val rise = if (riseNow.allowance <= risePrevious.allowance) riseNow else risePrevious
        log.add("L3 Rise: $rise (now $riseNow, previous $risePrevious; net delta ${format(delta)}, gross delta ${format(grossDelta)})")

        // IOB headroom: gently reduces dosing as IOB nears its maximum
        val iobFactor = iobHeadroomFactor(iob, maxIob)
        log.add("IOB headroom factor: ${format(iobFactor)}")

        val allowance = snapDown(minOf(bgFactor, lockoutCap, rise.allowance, iobFactor, 1.0))
        log.add("Allowance: ${format(allowance)}")
        return TsunamiSafetyResult(blocked = false, allowance = allowance, log = log)
    }

    /** Kovatchev risk function: zero at 112.5 mg/dL, rising steeply towards hypoglycemia. */
    private fun kovatchevRisk(bg: Double): Double = (1.509 * (ln(bg).pow(1.0804) - 5.381)).pow(2)

    /** 1.0 at or above the zero-risk BG, falling to 0.0 at the hard floor following the Kovatchev risk curve. */
    private fun bgRiskFactor(bg: Double): Double {
        if (bg >= KOVATCHEV_ZERO_RISK_BG) return 1.0
        return (1.0 - kovatchevRisk(bg) / kovatchevRisk(HARD_FLOOR_BG)).coerceIn(0.0, 1.0)
    }

    private fun lockoutCap(bg: Double, targetBg: Double, history: List<GlucoseStatus>, nowMs: Long, log: MutableList<String>): Double {
        val lows = history.filter { it.glucose < LOW_BG }
        if (lows.isEmpty()) return 1.0

        val nadir = lows.minByOrNull { it.glucose } ?: return 1.0 // first minimum = most recent one
        val lastLow = lows.first()
        val lockoutMinutes = (LOCKOUT_MIN_MINUTES + (LOW_BG - nadir.glucose) * LOCKOUT_MINUTES_PER_MGDL).coerceIn(LOCKOUT_MIN_MINUTES, LOCKOUT_MAX_MINUTES)
        val minutesSinceLastLow = (nowMs - lastLow.date) / MINUTE_MS
        if (minutesSinceLastLow >= lockoutMinutes) return 1.0

        // A high BG is no rescue-carb rebound anymore, regardless of how recent the low was
        if (bg > LOCKOUT_BREAKOUT_BG) {
            log.add("L2 Post-low lockout broken: BG $bg > ${LOCKOUT_BREAKOUT_BG.toInt()}.")
            return 1.0
        }

        val minutesSinceNadir = (nowMs - nadir.date) / MINUTE_MS
        if (minutesSinceNadir >= RESCUE_CARB_SIGNATURE_MINUTES && bg >= targetBg + LOCKOUT_RELEASE_ABOVE_TARGET) {
            log.add("L2 Post-low lockout released: BG $bg >= target + ${LOCKOUT_RELEASE_ABOVE_TARGET.toInt()} and rise lasted ${format(minutesSinceNadir)} min.")
            return 1.0
        }
        val cap = if (minutesSinceNadir < RESCUE_CARB_SIGNATURE_MINUTES) 0.0 else LOCKOUT_ALLOWANCE
        log.add(
            "L2 Post-low lockout: nadir ${nadir.glucose.toInt()} ${format(minutesSinceNadir)} min ago, lockout ${format(lockoutMinutes)} min " +
                "(${format(lockoutMinutes - minutesSinceLastLow)} min left). Cap ${format(cap)}."
        )
        return cap
    }

    private fun classifyRise(
        bg: Double,
        delta: Double,
        grossDelta: Double,
        history: List<GlucoseStatus>,
        rawHistory: List<GlucoseStatus>,
        nowMs: Long,
        modeEndTimeMs: Long?
    ): RiseClass {
        val minGlucose = history
            .filter { (nowMs - it.date) / MINUTE_MS <= EXCURSION_WINDOW_MINUTES }
            .minOfOrNull { it.glucose } ?: bg
        val excursion = bg - min(minGlucose, bg)

        val previousDelta = history.getOrNull(1)?.delta ?: delta
        val rawDelta = rawHistory.getOrNull(0)?.delta ?: delta
        val rawPreviousDelta = rawHistory.getOrNull(1)?.delta ?: rawDelta
        // Raw data is quicker to catch deceleration, smoothed data is more robust. Use the stronger deceleration.
        val momentum = listOfNotNull(momentumRatio(delta, previousDelta), momentumRatio(rawDelta, rawPreviousDelta)).minOrNull() ?: 1.0

        val timerExpiring = modeEndTimeMs != null && (modeEndTimeMs - nowMs) / MINUTE_MS < TIMER_EXPIRING_MINUTES
        val smallExcursion = excursion < SMALL_EXCURSION

        return when {
            delta < MIN_DELTA_FOR_RISE -> RiseClass.DOUBTFUL
            // Net rise without a real gross rise: insulin action is fading, nothing is pushing glucose up
            grossDelta < GROSS_DELTA_DOUBTFUL_BELOW -> RiseClass.DOUBTFUL
            smallExcursion && (momentum < MOMENTUM_STRONG_DECELERATION || timerExpiring) -> RiseClass.DOUBTFUL
            !smallExcursion && momentum >= MOMENTUM_STRONG_DECELERATION && grossDelta >= GROSS_DELTA_CONFIRMED_AT_LEAST -> RiseClass.CONFIRMED
            else -> RiseClass.PROBABLE
        }
    }

    /** Proportion of the previous delta that remains, or null if the previous delta was too small to judge. */
    private fun momentumRatio(delta: Double, previousDelta: Double): Double? =
        if (previousDelta > MOMENTUM_MIN_PREVIOUS_DELTA && delta < previousDelta) delta / previousDelta else null

    private fun iobHeadroomFactor(iob: Double, maxIob: Double): Double {
        val start = maxIob * IOB_START_REDUCING
        val full = maxIob * IOB_FULL_REDUCTION
        val range = full - start
        if (range <= 0) return 1.0
        return (1.0 - (iob - start) / range).coerceIn(IOB_MIN_FACTOR, 1.0)
    }

    private fun snapDown(value: Double): Double = floor(value / ALLOWANCE_STEP + 1e-9) * ALLOWANCE_STEP

    private fun format(value: Double): String = (Math.round(value * 100) / 100.0).toString()
}
