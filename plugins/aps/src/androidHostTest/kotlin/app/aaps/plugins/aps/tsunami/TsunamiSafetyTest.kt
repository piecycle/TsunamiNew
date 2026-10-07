package app.aaps.plugins.aps.tsunami

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TsunamiSafetyTest {

    private val now = 10_000_000_000L
    private val stepMs = 5 * 60 * 1000L

    /** Builds a glucose history (newest first) from values given oldest first, one reading every 5 minutes, newest reading at [now]. */
    private fun history(oldestFirst: List<Double>): List<GlucoseStatus> {
        val newestFirst = oldestFirst.reversed()
        return newestFirst.mapIndexed { i, bg ->
            val older = newestFirst.getOrNull(i + 1)
            GlucoseStatusSMB(glucose = bg, date = now - i * stepMs, delta = if (older != null) bg - older else 0.0)
        }
    }

    private fun flat(bg: Double, readings: Int): List<Double> = List(readings) { bg }

    private fun evaluate(
        series: List<Double>,
        gross: Double = 12.0,
        target: Double = 100.0,
        iob: Double = 1.0,
        maxIob: Double = 10.0,
        modeEndTimeMs: Long? = null
    ): TsunamiSafetyResult {
        val history = history(series)
        return TsunamiSafety.evaluate(
            bg = history[0].glucose,
            delta = history[0].delta,
            grossDelta = gross,
            targetBg = target,
            iob = iob,
            maxIob = maxIob,
            history = history,
            rawHistory = history,
            nowMs = now,
            modeEndTimeMs = modeEndTimeMs
        )
    }

    private fun TsunamiSafetyResult.logText() = log.joinToString("\n")

    // Sustained meal-like rise, 110 mg/dL above the 90 minute minimum, steady 15 mg/dL per 5 min
    private val confirmedRise = flat(100.0, 12) + listOf(110.0, 120.0, 130.0, 140.0, 150.0, 165.0, 180.0, 195.0, 210.0)

    // ---------------------------------------------------------------- Layer 1

    @Test
    fun `bg below 80 blocks tsunami and falls back to oref1`() {
        val result = evaluate(flat(100.0, 20) + listOf(90.0, 85.0, 79.0))

        assertThat(result.blocked).isTrue()
        assertThat(result.allowance).isEqualTo(0.0)
        assertThat(result.logText()).contains("L1 BG floor")
    }

    @Test
    fun `bg of exactly 80 is not blocked but allows no dosing`() {
        val result = evaluate(flat(100.0, 20) + listOf(90.0, 85.0, 80.0))

        assertThat(result.blocked).isFalse()
        assertThat(result.allowance).isEqualTo(0.0)
    }

    // ---------------------------------------------------------------- Layer 2: BG risk

    @Test
    fun `bg just above floor is strongly attenuated`() {
        // Kovatchev factor at 85 mg/dL is about 0.3, which snaps down to 0.25
        val result = evaluate(flat(80.0, 20) + listOf(82.0, 84.0, 85.0))

        assertThat(result.blocked).isFalse()
        assertThat(result.logText()).contains("L2 BG risk factor: 0.3")
        assertThat(result.allowance).isEqualTo(0.25)
    }

    @Test
    fun `bg above Kovatchev zero risk point does not attenuate a confirmed rise`() {
        val result = evaluate(confirmedRise)

        assertThat(result.blocked).isFalse()
        assertThat(result.allowance).isEqualTo(1.0)
    }

    // ---------------------------------------------------------------- Layer 2: post-hypo lockout

    @Test
    fun `no dosing within rescue carb signature after a low`() {
        // Nadir 60 mg/dL 25 min ago, BG already back at 100
        val series = flat(110.0, 18) + listOf(100.0, 80.0, 65.0, 60.0, 62.0, 68.0, 76.0, 88.0, 100.0)
        val result = evaluate(series)

        assertThat(result.allowance).isEqualTo(0.0)
        assertThat(result.logText()).contains("lockout")
    }

    @Test
    fun `lockout is released after rescue carb signature once bg is above target plus 20`() {
        // Nadir 60 mg/dL 45 min ago, rising steadily to 128 (>= target + 20); excursion is large in the current and previous cycle
        val series = flat(100.0, 10) + listOf(65.0, 60.0, 66.0, 72.0, 80.0, 88.0, 96.0, 104.0, 112.0, 120.0, 128.0)
        val result = evaluate(series, target = 100.0)

        assertThat(result.logText()).contains("lockout released")
        assertThat(result.allowance).isEqualTo(1.0)
    }

    @Test
    fun `lockout stays at 25 percent after rescue carb signature while bg is below target plus 20`() {
        val series = flat(100.0, 10) + listOf(65.0, 60.0, 66.0, 72.0, 80.0, 88.0, 96.0, 104.0, 112.0, 120.0)
        val result = evaluate(series, target = 110.0)

        assertThat(result.allowance).isEqualTo(0.25)
        assertThat(result.logText()).contains("Post-low lockout:")
    }

    @Test
    fun `lockout is broken immediately when bg climbs above 160`() {
        // Nadir 60 mg/dL only 20 min ago, but BG shot up to 170
        val series = flat(110.0, 18) + listOf(100.0, 80.0, 65.0, 60.0, 80.0, 110.0, 140.0, 170.0)
        val result = evaluate(series)

        assertThat(result.logText()).contains("lockout broken")
        assertThat(result.logText()).doesNotContain("Post-low lockout:")
    }

    @Test
    fun `lockout is not broken at exactly 160 within the rescue carb signature`() {
        val series = flat(110.0, 18) + listOf(100.0, 80.0, 65.0, 60.0, 80.0, 110.0, 135.0, 160.0)
        val result = evaluate(series)

        assertThat(result.allowance).isEqualTo(0.0)
        assertThat(result.logText()).doesNotContain("lockout broken")
    }

    @Test
    fun `lockout for a deep low is capped at 90 minutes`() {
        // Nadir 40 mg/dL 85 min ago, last reading below 70 was 75 min ago
        val series = listOf(40.0, 50.0, 60.0, 70.0, 80.0, 85.0, 90.0, 95.0) + flat(100.0, 10)
        val result = evaluate(series)

        assertThat(result.logText()).contains("lockout 90.0 min")
        assertThat(result.allowance).isEqualTo(0.25)
    }

    @Test
    fun `shallow low lockout has expired after its shorter duration`() {
        // Nadir 65 mg/dL 65 min ago: lockout is 45 min, so it is over
        val series = listOf(65.0, 90.0, 105.0) + flat(110.0, 11)
        val result = evaluate(series)

        assertThat(result.logText()).doesNotContain("lockout")
    }

    @Test
    fun `lows older than the lockout do not matter`() {
        val series = listOf(55.0, 70.0, 90.0) + flat(120.0, 28)
        val result = evaluate(series)

        assertThat(result.logText()).doesNotContain("lockout")
    }

    // ---------------------------------------------------------------- Layer 3: rise legitimacy

    @Test
    fun `sustained rise with high gross delta is confirmed`() {
        val result = evaluate(confirmedRise, gross = 12.0)

        assertThat(result.logText()).contains("L3 Rise: CONFIRMED")
        assertThat(result.allowance).isEqualTo(1.0)
    }

    @Test
    fun `rise caused by fading insulin with low gross delta is doubtful even after a large excursion`() {
        val result = evaluate(confirmedRise, gross = 3.0)

        assertThat(result.logText()).contains("L3 Rise: DOUBTFUL")
        assertThat(result.allowance).isEqualTo(0.25)
    }

    @Test
    fun `large excursion with medium gross delta is only probable`() {
        val result = evaluate(confirmedRise, gross = 6.0)

        assertThat(result.logText()).contains("L3 Rise: PROBABLE")
        assertThat(result.allowance).isEqualTo(0.5)
    }

    @Test
    fun `small decelerating snack rise is doubtful`() {
        // 33 mg/dL excursion, delta drops from 6 to 2
        val series = flat(100.0, 20) + listOf(105.0, 115.0, 125.0, 131.0, 133.0)
        val result = evaluate(series, gross = 8.0)

        assertThat(result.logText()).contains("L3 Rise: DOUBTFUL")
        assertThat(result.allowance).isEqualTo(0.25)
    }

    @Test
    fun `small steady rise is probable`() {
        val series = flat(100.0, 20) + listOf(105.0, 110.0, 116.0, 122.0, 128.0, 134.0)
        val result = evaluate(series, gross = 8.0)

        assertThat(result.logText()).contains("L3 Rise: PROBABLE")
        assertThat(result.allowance).isEqualTo(0.5)
    }

    @Test
    fun `small rise near the end of the tsunami timer is doubtful`() {
        val series = flat(100.0, 20) + listOf(105.0, 110.0, 116.0, 122.0, 128.0, 134.0)

        assertThat(evaluate(series, gross = 8.0, modeEndTimeMs = now + 10 * 60_000L).allowance).isEqualTo(0.25)
        assertThat(evaluate(series, gross = 8.0, modeEndTimeMs = now + 60 * 60_000L).allowance).isEqualTo(0.5)
    }

    @Test
    fun `large excursion with strong deceleration is only probable`() {
        // Secondary peak: delta drops from 18 to 6 after a 114 mg/dL excursion
        val series = flat(100.0, 12) + listOf(115.0, 130.0, 150.0, 170.0, 190.0, 208.0, 214.0)
        val result = evaluate(series, gross = 12.0)

        assertThat(result.logText()).contains("L3 Rise: PROBABLE")
        assertThat(result.allowance).isEqualTo(0.5)
    }

    @Test
    fun `flat glucose is doubtful`() {
        val result = evaluate(flat(100.0, 30), gross = 12.0)

        assertThat(result.logText()).contains("L3 Rise: DOUBTFUL")
    }

    @Test
    fun `an upgrade needs two consecutive cycles`() {
        // Previous cycle: 55 mg/dL excursion (small). Current cycle: 65 mg/dL excursion (large).
        val series = flat(100.0, 12) + listOf(110.0, 120.0, 130.0, 140.0, 155.0, 165.0)
        val result = evaluate(series, gross = 12.0)

        assertThat(result.logText()).contains("now CONFIRMED")
        assertThat(result.logText()).contains("previous PROBABLE")
        assertThat(result.allowance).isEqualTo(0.5)
    }

    // ---------------------------------------------------------------- IOB headroom and combination

    @Test
    fun `iob below half of max iob does not reduce allowance`() {
        assertThat(evaluate(confirmedRise, iob = 4.0, maxIob = 10.0).allowance).isEqualTo(1.0)
    }

    @Test
    fun `iob near max iob reduces allowance`() {
        // Factor 0.857 at IOB 6 snaps to 0.75; factor is clamped to 0.6 at IOB 9 and snaps to 0.5
        assertThat(evaluate(confirmedRise, iob = 6.0, maxIob = 10.0).allowance).isEqualTo(0.75)
        assertThat(evaluate(confirmedRise, iob = 9.0, maxIob = 10.0).allowance).isEqualTo(0.5)
    }

    @Test
    fun `layers are combined by minimum not by product`() {
        // Probable rise (0.5) and IOB factor 0.857 (0.75): minimum is 0.5, a product would give 0.25
        val result = evaluate(confirmedRise, gross = 6.0, iob = 6.0, maxIob = 10.0)

        assertThat(result.allowance).isEqualTo(0.5)
    }

    @Test
    fun `allowance is always a multiple of 25 percent`() {
        val scenarios = listOf(
            evaluate(confirmedRise, iob = 5.5),
            evaluate(confirmedRise, gross = 6.0, iob = 7.0),
            evaluate(flat(88.0, 20) + listOf(90.0, 93.0, 96.0)),
            evaluate(flat(100.0, 20) + listOf(105.0, 115.0, 125.0, 131.0, 133.0), gross = 8.0)
        )

        scenarios.forEach { assertThat((it.allowance * 4) % 1.0).isEqualTo(0.0) }
    }
}
