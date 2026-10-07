package com.kei.pulse.data

import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.root.RootSupport
import kotlin.math.pow

private fun cat(path: String): String? {
    val escaped = path.replace("'", "'\\''")
    return RootSupport.runRootCommand("cat '$escaped' 2>/dev/null")?.trim()?.takeIf { it.isNotEmpty() }
}

/** Battery reads shared by the per-app draw tracker (outside the HUD's TelemetryReader). */
object BatteryReader {
    /** Live system draw in watts and whether the battery is actually discharging. */
    fun drawSnapshot(): Pair<Float?, Boolean> {
        val status = cat("/sys/class/power_supply/battery/status")
        val isDischarging = status == null || status.equals("Discharging", ignoreCase = true)
        val ua = cat("/sys/class/power_supply/battery/current_now")?.toLongOrNull()
        val uv = cat("/sys/class/power_supply/battery/voltage_now")?.toLongOrNull()
        val watts = if (ua != null && uv != null && uv > 0) {
            (kotlin.math.abs(ua) / 1_000_000.0 * (uv / 1_000_000.0)).toFloat().takeIf { it > 0f }
        } else {
            null
        }
        return watts to isDischarging
    }

    /** Full-battery energy in Wh: energy_full when exposed, else charge_full × 3.85 V nominal. */
    fun capacityWh(): Float? {
        cat("/sys/class/power_supply/battery/energy_full")?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { return it / 1_000_000f }
        val uah = cat("/sys/class/power_supply/battery/charge_full")?.toLongOrNull()
            ?: cat("/sys/class/power_supply/battery/charge_full_design")?.toLongOrNull()
        return uah?.takeIf { it > 0 }?.let { (it / 1_000_000f) * 3.85f }
    }
}

/** Per-cluster CPU governor control. Reversible (set back to Balanced). */
class GovernorController {
    fun readGovernor(policy: CpuPolicyInfo): String? = cat("${policy.policyPath}/scaling_governor")

    private fun availableGovernors(policy: CpuPolicyInfo): List<String> =
        cat("${policy.policyPath}/scaling_available_governors")
            ?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()

    /** Writes a raw kernel governor name to every CPU policy (used to restore a captured governor). */
    fun setGovernorRaw(policies: List<CpuPolicyInfo>, governor: String): Boolean {
        val cpu = policies.filterNot { it.isGpu }
        if (cpu.isEmpty() || governor.isBlank()) return false
        val cmd = cpu.joinToString("; ") { p ->
            val path = "${p.policyPath}/scaling_governor"
            "chmod 666 $path; echo $governor > $path; chmod 644 $path"
        }
        RootSupport.runRootCommand(cmd)
        return true
    }

    /** Applies the first supported candidate of [option] to every CPU policy; returns it. */
    fun setGovernor(policies: List<CpuPolicyInfo>, option: GovernorOption): String? {
        val cpu = policies.filterNot { it.isGpu }
        if (cpu.isEmpty()) return null
        val avail = availableGovernors(cpu.first())
        val chosen = option.candidates.firstOrNull { c -> avail.any { it.equals(c, true) } }
            ?: option.candidates.first()
        val cmd = cpu.joinToString("; ") { p ->
            val path = "${p.policyPath}/scaling_governor"
            "chmod 666 $path; echo $chosen > $path; chmod 644 $path"
        }
        RootSupport.runRootCommand(cmd)
        return chosen
    }

    companion object {
        // Each option lists candidate kernel governors in preference order. "walt" is the
        // Odin 3 stock balanced scaler, so it maps to Balanced (schedutil as fallback).
        val OPTIONS = listOf(
            GovernorOption("Performance", listOf("performance")),
            GovernorOption("Balanced", listOf("walt", "schedutil", "sched_pixel")),
            GovernorOption("Power Save", listOf("powersave")),
        )

        /** Which option a live governor name belongs to (so the right chip highlights). */
        fun optionForGovernor(governor: String?): GovernorOption? {
            if (governor.isNullOrBlank()) return null
            return OPTIONS.firstOrNull { o -> o.candidates.any { it.equals(governor, true) } }
        }
    }
}

data class GovernorOption(val label: String, val candidates: List<String>)

/** Display refresh-rate control via the platform refresh-rate settings. Reversible. */
class RefreshRateController {
    fun setRate(hz: Int): Boolean {
        RootSupport.runRootCommand(
            "settings put system peak_refresh_rate $hz; settings put system min_refresh_rate $hz",
        )
        return true
    }

    fun readPeak(): Int? =
        RootSupport.runRootCommand("settings get system peak_refresh_rate")?.trim()?.toFloatOrNull()?.toInt()

    companion object {
        val RATES = listOf(60, 90, 120)
    }
}

/**
 * GPU minimum-frequency floor via kgsl `min_pwrlevel`. Pairs with the max cap to pin the
 * GPU into a band. A higher index = slower clock, so the floor's index is >= the ceiling's,
 * which the kernel requires. Passing the slowest level clears the floor.
 */
class GpuFloorController {
    fun setFloorLevel(kgslRoot: String, floorLevel: Int): Boolean {
        val path = "$kgslRoot/min_pwrlevel"
        RootSupport.runRootCommand("chmod 666 $path; echo $floorLevel > $path; chmod 444 $path")
        return true
    }

    /**
     * Pins the GPU to its current ceiling by setting BOTH min_pwrlevel and max_pwrlevel to the
     * live max_pwrlevel. Returns the read-back min_pwrlevel so the caller can confirm it stuck.
     */
    fun lockToCurrentCap(kgslRoot: String): Int? {
        val maxP = "$kgslRoot/max_pwrlevel"
        val minP = "$kgslRoot/min_pwrlevel"
        val level = RootSupport.runRootCommand("cat $maxP")?.trim()?.toIntOrNull() ?: return null
        RootSupport.runRootCommand(
            "chmod 666 $maxP; chmod 666 $minP; " +
                "echo $level > $maxP; echo $level > $minP; " +
                "chmod 444 $maxP; chmod 444 $minP",
        )
        return RootSupport.runRootCommand("cat $minP")?.trim()?.toIntOrNull()
    }
}

/** Snapshot of live device telemetry for the HUD. Nulls render as "—". */
data class TelemetrySnapshot(
    val cpuClocksMhz: Map<Int, Int> = emptyMap(),
    /** All-core CPU load, capacity-weighted: busy time × current/boost-max clock per core. */
    val cpuLoadPercent: Int? = null,
    /** Per-logical-core busy %, ordered by core id — for the overlay's per-core histogram. */
    val cpuCoreLoadsPercent: List<Int> = emptyList(),
    /**
     * Aggregate CPU iowait % (time blocked on disk I/O). Logged as a diagnostic only — on this platform it
     * reads ~0 even under heavy loads (per-core iowait is unreliable on Android, and emulator loads are
     * container/GPU/network-bound, not disk-bound), so parking gates on continuous-render instead.
     */
    val cpuIowaitPercent: Int? = null,
    val gpuMhz: Int? = null,
    /**
     * The GPU's live ceiling (MHz) read back from `max_pwrlevel` — the fastest the GPU is ALLOWED to run
     * right now. Diagnostic for the media GPU-pin (Batch 4): if PULSE caps the GPU low but this reads back
     * near the top, the vendor daemon has reverted `max_pwrlevel` to level 0.
     */
    val gpuCeilingMhz: Int? = null,
    /** Raw `max_pwrlevel`/`min_pwrlevel` indices (fastest = 0) the device currently holds — daemon-revert proof. */
    val gpuMaxLevel: Int? = null,
    val gpuMinLevel: Int? = null,
    /** Raw kgsl busy %, relative to the CURRENT clock — used for the calibration gate. */
    val gpuBusyPercent: Int? = null,
    /** Busy % weighted by current/max clock — utilisation of full GPU capacity, for display. */
    val gpuLoadPercent: Int? = null,
    val batteryPercent: Int? = null,
    val cpuTempC: Int? = null,
    val gpuTempC: Int? = null,
    val batteryDrawMa: Int? = null,
    val batteryDrawW: Float? = null,
    /** False while charging/full — current_now then reads charger current, not system draw. */
    val isDischarging: Boolean = true,
    val ramUsedMb: Int? = null,
    val ramTotalMb: Int? = null,
    val ramUsedPercent: Int? = null,
)

/**
 * Reads live clocks / GPU load / battery / temps / draw. Best-effort: missing nodes read null.
 *
 * Every value is read with its own single `cat`, and the thermal-zone scan is the sequential
 * loop — both verbatim from the last build verified on-device. Do NOT batch these reads into
 * combined PServer commands or "optimise" the zone scan: both were tried and silently broke
 * CPU temperature on all three target devices (PServer's combined-stdout reply format is not
 * reliable on this firmware).
 */
class TelemetryReader {
    // Resolved once: which thermal zones report CPU / GPU temperature, found via the
    // single-value reads that work reliably (the shell chokes on multi-line loops).
    private var zonesResolved = false
    private var cpuZone: String? = null
    private var gpuZone: String? = null

    // Previous /proc/stat per-core (busy, total) jiffies; CPU load needs two samples to delta.
    private var prevCoreJiffies: Map<Int, Pair<Long, Long>> = emptyMap()
    private var prevIowaitJiffies = 0L
    private var prevTotalJiffies = 0L

    /**
     * Total CPU load as "% of the max it can do": each core's busy-time fraction (from
     * /proc/stat deltas between polls) weighted by its cluster's current clock over its
     * observed boost ceiling, averaged across cores. Busy cores at half clock read ~50%,
     * not 100 — so the number honestly reflects boost headroom and user caps alike.
     * Null on the first poll (a delta needs two samples).
     */
    /**
     * Fetches just the cpu lines of /proc/stat. Never reads the whole file through PServer —
     * its reply silently fails on large outputs and the full file (intr line) can be 10KB+.
     * Tries a direct file read first (free when SELinux allows), then small PServer commands.
     */
    private fun readProcStatCpuLines(): String? {
        runCatching { java.io.File("/proc/stat").readLines() }.getOrNull()
            ?.filter { it.startsWith("cpu") }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.joinToString("\n") }
        return RootSupport.runRootCommand("grep '^cpu' /proc/stat 2>/dev/null")
            ?.trim()?.takeIf { it.startsWith("cpu") }
            ?: RootSupport.runRootCommand("head -n 12 /proc/stat 2>/dev/null")
                ?.trim()?.takeIf { it.startsWith("cpu") }
    }

    /** Aggregate (capacity-weighted) CPU load, raw per-core busy% (overlay histogram), and iowait%. */
    private data class CpuLoadResult(val overall: Int?, val perCore: List<Int>, val iowaitPct: Int?)

    private fun readCpuLoad(policies: List<CpuPolicyInfo>, curMhzByPolicy: Map<Int, Int>): CpuLoadResult {
        val stat = readProcStatCpuLines() ?: return CpuLoadResult(null, emptyList(), null)
        val cores = mutableMapOf<Int, Pair<Long, Long>>()
        var iowaitSum = 0L
        var totalSum = 0L
        stat.lineSequence().forEach { line ->
            if (!line.startsWith("cpu") || line.length < 4 || !line[3].isDigit()) return@forEach
            val parts = line.trim().split(Regex("\\s+"))
            val id = parts[0].removePrefix("cpu").toIntOrNull() ?: return@forEach
            val fields = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (fields.size < 5) return@forEach
            val total = fields.sum()
            val idleAll = fields[3] + fields[4] // idle + iowait
            cores[id] = (total - idleAll) to total
            iowaitSum += fields[4]
            totalSum += total
        }
        if (cores.isEmpty()) return CpuLoadResult(null, emptyList(), null)
        val prev = prevCoreJiffies
        prevCoreJiffies = cores
        // Aggregate iowait% over the same window (null on the first sample, before we have a baseline).
        val prevIo = prevIowaitJiffies
        val prevTot = prevTotalJiffies
        prevIowaitJiffies = iowaitSum
        prevTotalJiffies = totalSum
        val iowaitPct = ((totalSum - prevTot).takeIf { it > 0 && prev.isNotEmpty() })
            ?.let { dTot -> (((iowaitSum - prevIo).toDouble() / dTot) * 100).toInt().coerceIn(0, 100) }
        if (prev.isEmpty()) return CpuLoadResult(null, emptyList(), null)

        var weighted = 0.0
        var counted = 0
        val perCore = mutableMapOf<Int, Int>()
        for ((id, now) in cores) {
            val before = prev[id] ?: continue
            val deltaTotal = now.second - before.second
            if (deltaTotal <= 0) continue
            val busyFrac = ((now.first - before.first).toDouble() / deltaTotal).coerceIn(0.0, 1.0)
            perCore[id] = (busyFrac * 100).toInt().coerceIn(0, 100)
            val policy = policies.firstOrNull { !it.isGpu && id in it.cpuIds }
            val clockFrac = policy?.let { p ->
                val curKhz = curMhzByPolicy[p.id]?.times(1000.0) ?: return@let null
                val maxKhz = p.observedMaxFreq.toDouble().takeIf { it > 0 } ?: return@let null
                (curKhz / maxKhz).coerceIn(0.0, 1.0)
            } ?: 1.0
            weighted += busyFrac * clockFrac
            counted++
        }
        if (counted == 0) return CpuLoadResult(null, emptyList(), iowaitPct)
        val overall = ((weighted / counted) * 100).toInt().coerceIn(0, 100)
        return CpuLoadResult(overall, perCore.toSortedMap().values.toList(), iowaitPct)
    }

    /** Used/total RAM (MB) and used %, from /proc/meminfo. Direct read; no PServer needed. */
    private fun readRam(): Triple<Int?, Int?, Int?> {
        val lines = runCatching { java.io.File("/proc/meminfo").readLines() }.getOrNull()
            ?: return Triple(null, null, null)
        fun kb(prefix: String): Long? = lines.firstOrNull { it.startsWith(prefix) }
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        val totalKb = kb("MemTotal:") ?: return Triple(null, null, null)
        val availKb = kb("MemAvailable:") ?: return Triple(null, null, null)
        if (totalKb <= 0) return Triple(null, null, null)
        val usedKb = (totalKb - availKb).coerceAtLeast(0)
        val usedMb = (usedKb / 1024).toInt()
        val totalMb = (totalKb / 1024).toInt()
        val pct = (usedKb * 100 / totalKb).toInt().coerceIn(0, 100)
        return Triple(usedMb, totalMb, pct)
    }

    private fun resolveZones() {
        if (zonesResolved) return
        var i = 0
        while (i <= 90 && (cpuZone == null || gpuZone == null)) {
            val base = "/sys/class/thermal/thermal_zone$i"
            val type = cat("$base/type")?.lowercase()
            if (type != null) {
                if (cpuZone == null && type.contains("cpu")) cpuZone = base
                if (gpuZone == null &&
                    (type.contains("gpu") || type.contains("kgsl") || type.contains("gfx"))
                ) {
                    gpuZone = base
                }
            }
            i++
        }
        zonesResolved = true
    }

    private fun readTemp(zone: String?): Int? {
        val raw = zone?.let { cat("$it/temp") }?.toLongOrNull() ?: return null
        val c = (if (raw > 1000) raw / 1000 else raw).toInt()
        return c.takeIf { it in 1..150 }
    }

    fun read(policies: List<CpuPolicyInfo>): TelemetrySnapshot {
        val cpu = policies.filterNot { it.isGpu }.associate { policy ->
            policy.id to ((cat("${policy.policyPath}/scaling_cur_freq")?.toIntOrNull() ?: 0) / 1000)
        }.filterValues { it > 0 }
        val cpuLoad = readCpuLoad(policies, cpu)
        val ram = readRam()

        val gpuPolicy = policies.firstOrNull { it.isGpu }
        val gpuRoot = gpuPolicy?.policyPath
        val gpuMhz = gpuRoot?.let {
            (cat("$it/gpuclk")?.toLongOrNull()?.takeIf { hz -> hz > 0L }
                ?: cat("$it/devfreq/cur_freq")?.toLongOrNull())
                ?.let { hz -> (hz / 1_000_000L).toInt() }
        }
        // Read back the live pwrlevel bounds (fastest = 0). The ceiling MHz comes from mapping max_pwrlevel
        // through the GPU's ascending freq table — same mapping the writer uses. Diagnostic for Batch 4.
        val gpuMaxLevel = gpuRoot?.let { cat("$it/max_pwrlevel")?.toIntOrNull() }
        val gpuMinLevel = gpuRoot?.let { cat("$it/min_pwrlevel")?.toIntOrNull() }
        val gpuCeilingMhz = gpuPolicy?.supportedFrequencies
            ?.takeIf { it.isNotEmpty() }
            ?.let { freqs ->
                val lvl = gpuMaxLevel ?: 0
                val idx = (freqs.size - 1 - lvl).coerceIn(0, freqs.lastIndex)
                freqs[idx] / 1000 // ascending kHz → MHz
            }
        val gpuBusy = gpuRoot?.let { root ->
            cat("$root/gpu_busy_percentage")?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        }
        // kgsl busy% is relative to the current (often idle-downclocked) frequency, so it reads
        // absurdly high at idle. Weight it by clock to show utilisation of full GPU capacity.
        val gpuMaxMhz = policies.firstOrNull { it.isGpu }?.selectableMaxFreq?.div(1000)
        val gpuLoad = if (gpuBusy != null && gpuMhz != null && gpuMaxMhz != null && gpuMaxMhz > 0) {
            (gpuBusy * gpuMhz / gpuMaxMhz).coerceIn(0, 100)
        } else {
            gpuBusy
        }
        val battery = cat("/sys/class/power_supply/battery/capacity")?.toIntOrNull()
        // Unknown/missing status defaults to discharging so behaviour matches pre-status builds.
        val status = cat("/sys/class/power_supply/battery/status")
        val isDischarging = status == null || status.equals("Discharging", ignoreCase = true)

        val currentUa = cat("/sys/class/power_supply/battery/current_now")?.toLongOrNull()
        val drawMa = currentUa?.let { (kotlin.math.abs(it) / 1000L).toInt() }?.takeIf { it > 0 }
        val voltageUv = cat("/sys/class/power_supply/battery/voltage_now")?.toLongOrNull()
        val drawW = if (currentUa != null && voltageUv != null && voltageUv > 0) {
            (kotlin.math.abs(currentUa) / 1_000_000.0 * (voltageUv / 1_000_000.0)).toFloat().takeIf { it > 0f }
        } else {
            null
        }

        resolveZones()
        return TelemetrySnapshot(
            cpuClocksMhz = cpu,
            cpuLoadPercent = cpuLoad.overall,
            cpuCoreLoadsPercent = cpuLoad.perCore,
            cpuIowaitPercent = cpuLoad.iowaitPct,
            gpuMhz = gpuMhz,
            gpuCeilingMhz = gpuCeilingMhz,
            gpuMaxLevel = gpuMaxLevel,
            gpuMinLevel = gpuMinLevel,
            gpuBusyPercent = gpuBusy,
            gpuLoadPercent = gpuLoad,
            batteryPercent = battery,
            cpuTempC = readTemp(cpuZone),
            gpuTempC = readTemp(gpuZone) ?: readTemp(gpuRoot),
            batteryDrawMa = drawMa,
            batteryDrawW = drawW,
            isDischarging = isDischarging,
            ramUsedMb = ram.first,
            ramTotalMb = ram.second,
            ramUsedPercent = ram.third,
        )
    }
}

/**
 * Rough estimate of peak system power draw (watts) at the *current ceilings*. Snapdragon exposes
 * no power-prediction API, so this is a heuristic: each CPU cluster contributes ∝ cores × cap^EXP
 * weighted by its clock class, the GPU a flat share, normalised so all-uncapped ≈ [DEFAULT_PEAK_W].
 * The superlinear EXP reflects voltage rising with frequency, so capping the top bins saves
 * disproportionate power. It's a directional figure, not a lab measurement — always label it "est".
 */
object PowerEstimator {
    private const val EXP = 2.2
    private const val GPU_WEIGHT = 9.0
    const val DEFAULT_PEAK_W = 20f

    /**
     * 0..1: how much of the device's full-power envelope the *current ceilings* allow (1.0 =
     * everything uncapped). Used both to scale the watt estimate and to back-calibrate the
     * nominal peak from a real measured draw at full load (nominal ≈ measuredDraw / index).
     */
    fun relativeIndex(policies: List<CpuPolicyInfo>, caps: Map<Int, Int>): Float? {
        if (policies.isEmpty()) return null
        var index = 0.0
        var maxIndex = 0.0
        for (p in policies) {
            val maxF = p.selectableMaxFreq.toDouble()
            if (maxF <= 0.0) continue
            val cap = (caps[p.id] ?: p.currentMaxFreq).toDouble().coerceIn(0.0, maxF)
            val weight = if (p.isGpu) GPU_WEIGHT else p.cpuIds.size.coerceAtLeast(1) * (maxF / 1_000_000.0)
            index += weight * (cap / maxF).pow(EXP)
            maxIndex += weight
        }
        if (maxIndex <= 0.0) return null
        return (index / maxIndex).toFloat()
    }

    fun estimatePeakWatts(
        policies: List<CpuPolicyInfo>,
        caps: Map<Int, Int>,
        nominalPeakW: Float = DEFAULT_PEAK_W,
    ): Float? = relativeIndex(policies, caps)?.let { nominalPeakW * it }
}

/** CPU minimum-frequency floor via per-policy `scaling_min_freq`. Reversible (0 = lowest OPP). */
class CpuFloorController {
    /**
     * Raise each non-prime cluster's `scaling_min` to a percentage of its max, or (on `percent <= 0`)
     * reset every cluster's min to its lowest OPP.
     *
     * Lock discipline — this is deliberately PRIME-EXCLUSIVE on a raise (approach A):
     *  - The PRIME cluster's `scaling_min` is owned exclusively by the cap apply path (it lowers the prime
     *    min and `chmod 444`-locks it so the vendor perflock daemon can't re-raise it and clamp the prime
     *    `scaling_max` cap back up). Writing the prime min here at writable-644 would RELEASE that 444 lock,
     *    and if the floor% exceeded the cap% it would raise the prime min above the prime cap → the kernel
     *    clamps the prime `scaling_max` back UP and the cap silently breaks. The prime can't be meaningfully
     *    frequency-floored on this SoC anyway (the HAL pins its min ~3 GHz mid-game), so we skip it entirely.
     *  - Every other cluster's floor target is clamped to `min(floorTarget, currentScalingMax)` — read live —
     *    so a floor can never raise that cluster's min above its own cap (which the kernel would otherwise
     *    reject / clamp the cap up to satisfy). We only touch each cluster's min (writable 644), never its
     *    max, so the perf cap keeps biting.
     *  - On reset (`percent <= 0`) every CPU cluster's min (prime included) goes back to its lowest OPP, as
     *    before — clearing a floor must hand min control fully back.
     */
    fun setFloor(policies: List<CpuPolicyInfo>, percent: Int): Boolean {
        val cpu = policies.filterNot { it.isGpu }
        if (cpu.isEmpty()) return false
        // Clearing the floor resets every cluster (incl. prime) to its lowest OPP — unchanged behaviour.
        if (percent <= 0) {
            val cmd = cpu.joinToString("; ") { p ->
                val path = "${p.policyPath}/scaling_min_freq"
                "chmod 666 $path; echo ${p.minFreq} > $path; chmod 644 $path"
            }
            RootSupport.runRootCommand(cmd)
            return true
        }
        // Raise: skip the PRIME (its min is the cap apply's 444-locked node) and clamp the rest to <= cap.
        val cmd = floorableClusters(cpu).joinToString("; ") { p ->
            // Never raise this cluster's min above its current cap (scaling_max) — that would make the
            // kernel clamp the cap back up. Live-read the cap; fall back to the requested floor if absent.
            val cap = cat("${p.policyPath}/scaling_max_freq")?.toIntOrNull()
            val target = floorTargetFor(p, percent, cap)
            val path = "${p.policyPath}/scaling_min_freq"
            "chmod 666 $path; echo $target > $path; chmod 644 $path"
        }
        if (cmd.isBlank()) return true // only a prime cluster exists (nothing safe to floor) — no-op success
        RootSupport.runRootCommand(cmd)
        return true
    }

    companion object {
        /** The CPU clusters a raise may floor: every cluster EXCEPT the prime (highest-freq policy). */
        internal fun floorableClusters(cpu: List<CpuPolicyInfo>): List<CpuPolicyInfo> {
            val primeId = cpu.maxByOrNull { it.selectableMaxFreq }?.id
            return cpu.filter { it.id != primeId }
        }

        /**
         * The `scaling_min` a cluster's floor should be written to: the nearest supported OPP to
         * `selectableMaxFreq * percent / 100`, then clamped to `<= currentCap` so the floor can never raise
         * the min above the cluster's own `scaling_max` cap. [currentCap] null ⇒ unknown ⇒ leave unclamped.
         */
        internal fun floorTargetFor(p: CpuPolicyInfo, percent: Int, currentCap: Int?): Int {
            val t = p.selectableMaxFreq * percent / 100
            val snapped = p.supportedFrequencies.minByOrNull { kotlin.math.abs(it - t) } ?: p.minFreq
            return if (currentCap != null) minOf(snapped, currentCap) else snapped
        }
    }
}
