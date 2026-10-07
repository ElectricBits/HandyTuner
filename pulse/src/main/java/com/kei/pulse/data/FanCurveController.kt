package com.kei.pulse.data

import com.kei.pulse.model.FanCurve
import com.kei.pulse.root.RootSupport
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Drives the Odin 3 fan from a [FanCurve] as a CONTINUOUS slew so the fan ramps smoothly instead of
 * audibly stepping. Two cadences:
 *  - [setTarget] (slow, ~1s telemetry tick) picks the curve's % for the live SoC temp — the goal.
 *  - [slew] (fast, ~300ms loop) eases the applied % toward that goal by [slewPerSecond] × dt and WRITES the
 *    duty every pass. That write is also the re-assert that beats the vendor fan service (it re-pins the 20%
 *    floor every ~2–4s); a tight loop with tiny increments holds the fan steady AND sounds smooth.
 *
 * Safety: at/above [FanCurve.THERMAL_OVERRIDE_C] the slew SNAPS straight to 100% (never ramps into an
 * overheat). The applied % can never write below the vendor-safe floor ([FanCurve.percentToDuty] clamps).
 * Device I/O is injected so the slew logic is unit-testable; the companion supplies the real sysfs impls.
 * The caller assigns the *effective* curve (any Cooler/Quieter bias already folded in) and decides when to
 * drive — see [FanController.customFanAvailable]; stopping (just stop calling [slew]) hands the fan back.
 */
class FanCurveController(
    private val writeDuty: (Int) -> Unit = Companion::writeDutyToDevice,
    private val readRpm: () -> Int? = Companion::readRpmFromDevice,
) {
    var curve: FanCurve = FanCurve.DEFAULT
    var slewPerSecond: Int = FanCurve.DEFAULT_SLEW
    var period: Int = DEFAULT_PERIOD

    private var appliedF: Float = FanCurve.MIN_PERCENT.toFloat()
    private var targetPercent: Int = FanCurve.MIN_PERCENT
    private var forceFull: Boolean = false
    @Volatile private var lastWrittenDuty: Int = -1 // -1 = nothing written yet → force the first write

    val appliedPercent: Int get() = appliedF.roundToInt()

    /** Pick the goal % for [tempC] from the curve. At/above the thermal trip, latch a snap-to-full. */
    fun setTarget(tempC: Int) {
        forceFull = tempC >= FanCurve.THERMAL_OVERRIDE_C
        targetPercent = if (forceFull) 100 else curve.percentFor(tempC)
    }

    /** Set the goal duty % directly (the closed-loop [FanTempController] path, which bypasses the curve). The
     *  caller already applied its own thermal-trip safety, so [percent] == 100 just slews to full normally. */
    fun setTargetPercent(percent: Int) {
        forceFull = false
        targetPercent = percent.coerceIn(FanCurve.MIN_PERCENT, 100)
    }

    /**
     * Ease the applied % toward the target by at most [slewPerSecond] × [dtMillis], then write the duty —
     * but ONLY when it actually changed. In manual mode the vendor never resets the duty, so re-writing the
     * same value every tick is needless AND it corrupts the gpio5_pwm2 RPM tach (it reads 0 mid-write). Writing
     * on change keeps the ramp smooth while letting the tach settle so RPM reads cleanly when the fan holds.
     */
    fun slew(dtMillis: Long) {
        if (forceFull) {
            appliedF = 100f // safety: jump to full at the thermal trip, don't ramp into an overheat
        } else {
            val maxStep = max(1f, slewPerSecond * dtMillis / 1000f)
            val target = targetPercent.toFloat()
            appliedF = when {
                target > appliedF -> (appliedF + maxStep).coerceAtMost(target)
                target < appliedF -> (appliedF - maxStep).coerceAtLeast(target)
                else -> appliedF
            }
        }
        val duty = FanCurve.percentToDuty(appliedF.roundToInt(), period)
        if (duty != lastWrittenDuty) {
            writeDuty(duty)
            lastWrittenDuty = duty
        }
    }

    /**
     * Reconcile against the live duty node. The Odin 3 vendor fan daemon RE-PINS the duty even in manual
     * passthrough (`fan_mode=6`) — observed live: PULSE commanded 20% but the node read 50%, and [slew]'s
     * write-on-change never corrected it (`lastWrittenDuty` still matched our intended value, so it skipped
     * the write while the vendor's value sat on the hardware). If [actualDuty] no longer matches what we last
     * wrote, force the next [slew] to re-assert our duty and return true. A null read (unreadable) or a
     * matching value is a no-op returning false — RP6/Thor leave the duty alone, so this never writes there
     * and can't cause oscillation.
     */
    fun reconcileActualDuty(actualDuty: Int?): Boolean {
        if (actualDuty != null && actualDuty != lastWrittenDuty) {
            lastWrittenDuty = -1 // something changed the node underneath us → re-write our value next slew
            return true
        }
        return false
    }

    /**
     * Immediately re-write the CURRENT applied duty (no ramp advance). Used right after [reconcileActualDuty]
     * flags that something stole the node, so we re-pin our value within the fast re-check cadence instead of
     * waiting for the next (slower) [slew] — the difference between an inaudible correction and a brief rev.
     */
    fun reassertCurrentDuty() {
        val duty = FanCurve.percentToDuty(appliedF.roundToInt(), period)
        writeDuty(duty)
        lastWrittenDuty = duty
    }

    /** Live fan RPM from the tach node, or null if unreadable. */
    fun rpm(): Int? = readRpm()

    /** Forget the eased state so a new session re-slews from the floor (and forces the next write). */
    fun reset() {
        appliedF = FanCurve.MIN_PERCENT.toFloat()
        targetPercent = FanCurve.MIN_PERCENT
        forceFull = false
        lastWrittenDuty = -1
    }

    companion object {
        const val DEFAULT_PERIOD = 50000

        /**
         * Set true while a one-shot external driver (the autocalibrate sweep) is writing the fan PWM duty
         * directly. The foreground service's fan paths no-op while it's set so the two don't fight over the
         * duty node. Same-process @Volatile (the service runs in the app process). The sweep MUST clear it in
         * a `finally` so a crashed/cancelled sweep can never strand the fan out of PULSE's control.
         */
        @Volatile var externalControlActive: Boolean = false

        fun writeDutyToDevice(duty: Int) {
            RootSupport.runRootCommand("echo $duty > ${FanController.FAN_DUTY_PATH}")
        }

        fun readRpmFromDevice(): Int? =
            RootSupport.runRootCommand("cat ${FanController.FAN_SPEED_PATH} 2>/dev/null")?.trim()?.toIntOrNull()

        /** The live PWM duty value (0..period) — reliable, unlike the [readRpmFromDevice] tach. */
        fun readDutyFromDevice(): Int? =
            RootSupport.runRootCommand("cat ${FanController.FAN_DUTY_PATH} 2>/dev/null")?.trim()?.toIntOrNull()

        fun readPeriodFromDevice(): Int? =
            RootSupport.runRootCommand("cat ${FanController.FAN_PERIOD_PATH} 2>/dev/null")?.trim()?.toIntOrNull()
    }
}
