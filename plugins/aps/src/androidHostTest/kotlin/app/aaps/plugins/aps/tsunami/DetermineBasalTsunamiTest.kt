package app.aaps.plugins.aps.tsunami

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileTsunami
import app.aaps.core.interfaces.aps.RT
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Wave close to target: no dosing below target, temp basal only inside the SMB zone (target + SMB cap * ISF,
 * at most target + 60) during activity control, SMBs above it.
 *
 * Insulin activity values are taken from the hypo on 2026-10-03 (BG 86, delta +1, activity falling).
 */
class DetermineBasalTsunamiTest : TestBaseWithProfile() {

    private lateinit var sut: DetermineBasalTsunami

    private val currentTime = 1_790_000_000_000L
    private val stepMs = 5 * 60_000L

    @BeforeEach
    fun setup() {
        sut = DetermineBasalTsunami(profileUtil, fabricPrivacy)
    }

    /** Flat-ish history (newest first) ending at [bg], rising by [delta] per reading, 19 readings (90 min). */
    private fun history(bg: Double, delta: Double): List<GlucoseStatus> =
        List(19) { i -> GlucoseStatusSMB(glucose = bg - i * delta, delta = delta, shortAvgDelta = delta, longAvgDelta = delta, date = currentTime - i * stepMs) }

    // 48 ticks like IobCobCalculatorPlugin.calculateIobArrayForSMB, every tick needs iobWithZeroTemp
    private fun iobArray() = Array(48) { i ->
        val time = currentTime + i * stepMs
        IobTotal(
            time = time,
            iob = 1.5,
            activity = 0.025,
            lastBolusTime = currentTime - 3_600_000L,
            iobWithZeroTemp = IobTotal(time = time, iob = 1.5, activity = 0.025)
        )
    }

    private fun profile(target: Double, sens: Double, tempTarget: Boolean) = OapsProfileTsunami(
        dia = 9.0,
        min_5m_carbimpact = 0.0,
        max_iob = 7.0,
        max_daily_basal = 1.0,
        max_basal = 4.0,
        min_bg = target,
        max_bg = target,
        target_bg = target,
        carb_ratio = 14.0,
        sens = sens,
        autosens_adjust_targets = false,
        max_daily_safety_multiplier = 3.0,
        current_basal_safety_multiplier = 4.0,
        high_temptarget_raises_sensitivity = false,
        low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false,
        resistance_lowers_target = false,
        adv_target_adjustments = false,
        exercise_mode = false,
        half_basal_exercise_target = 160,
        maxCOB = 120,
        skip_neutral_temps = false,
        remainingCarbsCap = 90,
        enableUAM = true,
        A52_risk_enable = false,
        SMBInterval = 3,
        enableSMB_with_COB = true,
        enableSMB_with_temptarget = true,
        allowSMB_with_high_temptarget = true,
        enableSMB_always = true,
        enableSMB_after_carbs = true,
        maxSMBBasalMinutes = 30,
        maxUAMSMBBasalMinutes = 30,
        bolus_increment = 0.1,
        carbsReqThreshold = 1,
        current_basal = 1.0,
        temptargetSet = tempTarget,
        autosens_max = 1.2,
        out_units = "mg/dl",
        lgsThreshold = null,
        variable_sens = sens,
        insulinDivisor = 75,
        TDD = 40.0,
        tsunamiModeID = 1,
        tsunamiModeActivationTime = null,
        peakTime = 75.0,
        PDmodel = false,
        insConc = 1.0,
        percentage = 100,
        enableWaveMode = true,
        waveActiveHours = true,
        waveUseSMBCap = true,
        SMBcap = 0.8,
        insulinReqPCT = 1.0,
        activityTarget = 0.9,
        deltaReductionPCT = 0.5,
        futureActivity = 0.0515,
        activityPredTime = 75.0,
        sensorLagActivity = 0.152,
        historicActivity = 0.152,
        currentActivity = 0.131,
        lastBolus = 0.0
    )

    private fun run(bg: Double, delta: Double, target: Double = 100.0, sens: Double = 40.0, tempTarget: Boolean = false): RT {
        val history = history(bg, delta)
        return sut.determine_basal(
            glucose_status = history[0],
            currenttemp = CurrentTemp(0, 0.0, null),
            iob_data_array = iobArray(),
            profile = profile(target, sens, tempTarget),
            autosens_data = AutosensResult(ratio = 1.0),
            meal_data = MealData(lastBolusTime = currentTime - 3_600_000L),
            microBolusAllowed = true,
            currentTime = currentTime,
            flatBGsDetected = false,
            dynIsfMode = false,
            recentGlucoseHistory = history,
            rawGlucoseHistory = history
        )
    }

    private fun RT.console(): String = (consoleLog.orEmpty() + consoleError.orEmpty()).joinToString(" ")

    private fun RT.smb(): Double = units ?: 0.0

    @Test
    fun `incident replay - Wave does not dose below target`() {
        val rT = run(bg = 85.7, delta = 0.5)

        assertThat(rT.smb()).isEqualTo(0.0)
        assertThat(rT.console()).contains("Wave: glucose below target")
        assertThat(rT.console()).doesNotContain("WAVE STATUS")
    }

    @Test
    fun `inside the SMB zone Wave uses temp basal only`() {
        // zone top = 100 + 0.8 * 40 = 132
        val rT = run(bg = 120.0, delta = 1.0)

        assertThat(rT.console()).contains("WAVE STATUS")
        assertThat(rT.smb()).isEqualTo(0.0)
        assertThat(rT.reason.toString()).contains("temp basal only")
        assertThat(rT.rate!!).isGreaterThan(1.0)
    }

    @Test
    fun `above the SMB zone Wave gives SMBs`() {
        val rT = run(bg = 135.0, delta = 1.0)

        assertThat(rT.smb()).isGreaterThan(0.0)
        assertThat(rT.console()).contains("SMB allowed")
    }

    @Test
    fun `high ISF - zone is capped at target + 60`() {
        // 0.8 * 100 = 80 would put the zone top at 180, the cap brings it down to 160
        assertThat(run(bg = 155.0, delta = 1.0, sens = 100.0).smb()).isEqualTo(0.0)
        assertThat(run(bg = 165.0, delta = 1.0, sens = 100.0).smb()).isGreaterThan(0.0)
    }

    @Test
    fun `temp target moves the gate and the zone`() {
        // Temp target 140: below it Wave is off, the zone ends at 140 + 32 = 172
        assertThat(run(bg = 130.0, delta = 1.0, target = 140.0, tempTarget = true).console()).contains("Wave: glucose below target")
        val inZone = run(bg = 150.0, delta = 1.0, target = 140.0, tempTarget = true)
        assertThat(inZone.console()).contains("WAVE STATUS")
        assertThat(inZone.smb()).isEqualTo(0.0)
        assertThat(run(bg = 175.0, delta = 1.0, target = 140.0, tempTarget = true).smb()).isGreaterThan(0.0)
    }

    @Test
    fun `ramp-up mode is not limited by the SMB zone`() {
        val rT = run(bg = 110.0, delta = 5.0)

        assertThat(rT.console()).contains("Mode: Ramping up activity.")
        assertThat(rT.smb()).isGreaterThan(0.0)
    }

    @Test
    fun `zone top is one SMB deep, at most 60 above target`() {
        assertThat(TsunamiSafety.waveSmbZoneTop(targetBg = 100.0, smbCap = 0.8, sens = 40.0)).isWithin(1e-9).of(132.0)
        assertThat(TsunamiSafety.waveSmbZoneTop(targetBg = 100.0, smbCap = 0.3, sens = 40.0)).isWithin(1e-9).of(112.0)
        assertThat(TsunamiSafety.waveSmbZoneTop(targetBg = 100.0, smbCap = 0.8, sens = 100.0)).isWithin(1e-9).of(160.0)
        assertThat(TsunamiSafety.waveSmbZoneTop(targetBg = 140.0, smbCap = 0.8, sens = 40.0)).isWithin(1e-9).of(172.0)
    }
}
