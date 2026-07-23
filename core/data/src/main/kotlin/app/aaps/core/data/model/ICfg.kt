package app.aaps.core.data.model

import app.aaps.core.data.iob.Iob
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Insulin configuration holds info about insulin
 */
data class ICfg(
    /**
     * Insulin name
     */
    var insulinLabel: String,
    /**
     * Aka DIA before in milliseconds
     */
    var insulinEndTime: Long,
    /**
     * Peak time from start in milliseconds
     */
    var insulinPeakTime: Long,
    /**
     * Insulin concentration (0.2 for U20, 2.0 for U200 insulin)
     */
    var concentration: Double = 1.0
) {

    constructor(insulinLabel: String, peak: Int, dia: Double, concentration: Double)
        : this(insulinLabel = insulinLabel, insulinEndTime = (dia * 3600 * 1000).toLong(), insulinPeakTime = (peak * 60000).toLong(), concentration = concentration)
    /**
    * Used in InsulinPlugin (insulin editor)
    */
    fun isEqual(iCfg: ICfg?): Boolean {
        iCfg?.let { iCfg ->
            if (insulinLabel != iCfg.insulinLabel)
                return false
            if (insulinEndTime != iCfg.insulinEndTime)
                return false
            if (insulinPeakTime != iCfg.insulinPeakTime)
                return false
            if (concentration != iCfg.concentration)
                return false
            return true
        }
        return false
    }
    /**
     * DIA (insulinEndTime) in hours rounded to 1 decimal place
     */
    val dia: Double
        get() = (insulinEndTime / 3600.0 / 100.0).roundToInt() / 10.0

    /**
     * Peak time in minutes
     */
    val peak: Int
        get() = (insulinPeakTime / 60000).toInt()

    /**
     * Set insulinEndTime aka DIA
     * @param hours duration in hours
     */
    fun setDia(hours: Double) {
        insulinEndTime = (hours * 3600 * 1000).toLong()
    }

    /**
     * Set insulinPeakTime aka peak
     * @param minutes peak tme in minutes
     */
    fun setPeak(minutes: Int) {
        insulinPeakTime = (minutes * 60000).toLong()
    }

    /**
     * insulinNickname is only used in insulin editor
     */
    var insulinNickname: String = ""

    /**
     * deepClone is only used in insulin editor
     */
    fun deepClone(): ICfg = ICfg(insulinLabel, insulinEndTime, insulinPeakTime, concentration).also { it.insulinNickname = insulinNickname }

    fun pdModelIobCalculation(bolus: BS, t: Double): Iob {
        //MP Model for estimation of PD-based peak time: (a0 + a1*X)/(1+b1*X), where X = bolus size
        val a0 = 61.33 //MP Units = min
        val a1 = 12.27
        val b1 = 0.05185
        val result = Iob()
        val tp: Double = (a0 + a1 */* concentration * */bolus.amount)/(1 + b1 */* concentration * */bolus.amount) //MP Units = min; support for different insulin concentrations by extrapolating from U100 --> currently not in use as AAPS appears to already account for this
        val tpModel = tp.pow(2.0) * 2 //MP The peak time in the model is defined as half of the square root of this variable - thus the tp entered into the model must be transformed first
        /**
         *
         * MP - UAM Tsunami PD model U100 vs U200
         *
         * Insulin Activity calculation below: The same formula is used for both, U100 and U200
         * insulin as the concentration effect is already included in the peak time calculation.
         * If peak time is kept constant and only the dose is doubled, the general shape of the
         * curve doesn't change and hence the equation does not need adjusting. Unless a global
         * U200 mode is introduced where ISF between U100 and U200 has the same value (i.e.: When
         * ISF doubling and basal halving is done in AAPS' calculations and not by the user), the
         * equation doesn't need any changing.
         * The user must keep in mind that the displayed IOB is only half of the actual IOB.
         *
         */
        result.activityContrib = (2 * bolus.amount / tpModel) * t * exp(-t.pow(2.0) / tpModel)

        //MP New IOB formula - integrated version of the above activity curve
        val lowerLimit = t //MP lower integration limit, in min
        val upperLimit = 8.0 * 60 //MP upper integration limit, in min
        result.iobContrib = bolus.amount * (exp(-lowerLimit.pow(2.0)/tpModel) - exp(-upperLimit.pow(2.0)/tpModel))

        return result
    }
    fun iobCalcForTreatment(bolus: BS, time: Long): Iob {
        assert(insulinEndTime != 0L)
        assert(insulinPeakTime != 0L)
        val result = Iob()
        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0
            if (/*t < 8 * 60 && */insulinLabel.contains("(PD)")) { //MP: use pharmacodynamic model if PD model is selected insulin (ID 105 or 205)
                val pdResult = pdModelIobCalculation(bolus, t)
                result.iobContrib = pdResult.iobContrib
                result.activityContrib = pdResult.activityContrib
            } else { // MP: If the pharmacodynamic models are not used (IDs 105 & 205), use the traditional PK-based insulin model instead;
                // Defensive clamp: the bilinear IOB model is only well-defined for 0 < tp < td/2. Corrupt
                // iCfg (the v33 migration sentinel -1 before its repair, or a malformed NS-imported insulin
                // config) would otherwise divide by zero, produce a negative tau, or — when td <= 0 — make
                // the `t < td` gate never fire and silently contribute ZERO IOB, all of which mislead the
                // loop into overdelivery. These bounds are MATH-validity floors only, NOT the medical limits
                // (which are enforced upstream): legitimate peaks below HardLimits.MIN_PEAK (e.g. 30 min) and
                // any dia >= MIN_DIA are preserved unchanged; only degenerate values are sanitized.
                val td = (dia * 60).coerceAtLeast(MIN_DIA_MINUTES)
                val tp = peak.toDouble().coerceIn(MIN_PEAK_MINUTES, td / 2.0 - 1.0)
                // force the IOB to 0 if over DIA hours have passed
                if (t < td) {
                    val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
                    val a = 2 * tau / td
                    val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
                    result.activityContrib = bolus.amount * (s / tau.pow(2.0)) * t * (1 - t / td) * exp(-t / tau)
                    result.iobContrib = bolus.amount * (1 - s * (1 - a) * ((t.pow(2.0) / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1))
                }
            }
        }
        return result
    }

    companion object {
        // Math-validity floors for iobCalcForTreatment. They only engage for corrupt/degenerate iCfg
        // and are no-ops for real configs; they are NOT the medical HardLimits.
        private const val MIN_DIA_MINUTES = 300.0 // 5 h (mirrors HardLimits.MIN_DIA); floors corrupt/sentinel DIA <= 0
        private const val MIN_PEAK_MINUTES = 1.0  // just keeps tp > 0; real peaks (incl. sub-MIN_PEAK like 30 min) pass through
    }
}