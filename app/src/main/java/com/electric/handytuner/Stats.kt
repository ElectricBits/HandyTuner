// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import java.io.File

/**
 * Live readings for the overlay. Everything the app can read itself is read
 * directly (Diagnostics showed GPU, CPU, temperature and memory pressure all
 * are on the Odin 2 Portal); only FPS goes through PServer.
 *
 * FPS is SurfaceFlinger's page-flip counter (transaction 1013) taken twice:
 * read-only, so it never resets anything. Pulse's TimeStats method clears the
 * stats on each read, and two apps doing that would corrupt each other's FPS.
 */
class Stats {
    private var lastFlips: Long? = null
    private var lastFlipsAt = 0L
    private var lastCpu: Map<String, Pair<Long, Long>> = emptyMap()

    /** Call about once a second, off the main thread. */
    fun sample(): Bottleneck.Reading = Bottleneck.Reading(
        fps = fps(), gpuBusy = gpuBusy(), busiestCore = busiestCore(), cpuTempC = cpuTempSmoothed(), memPressure = memPressure(),
        throttling = throttling(),
    )

    /**
     * Whether the kernel is limiting the chip for heat right now, from its thermal cooling devices: each one's
     * `cur_state` is 0 until it starts slowing a CPU cluster, the GPU, or parking cores. (PULSE's own power caps
     * use `scaling_max_freq`, not these, so a power limit is never taken for heat.) null when none can be read.
     */
    fun throttling(): Boolean? {
        val paths = coolingPaths ?: File("/sys/class/thermal").listFiles { f -> f.name.startsWith("cooling_device") }.orEmpty()
            .filter { d -> read("${d.path}/type")?.let { COOLING.matches(it) } == true }
            .map { "${it.path}/cur_state" }.also { coolingPaths = it }
        val states = paths.mapNotNull { read(it)?.toIntOrNull() }
        val gpuLevel = read("/sys/class/kgsl/kgsl-3d0/thermal_pwrlevel")?.toIntOrNull()
        if (states.isEmpty() && gpuLevel == null) return null
        return throttled(states, gpuLevel)
    }

    private var coolingPaths: List<String>? = null

    private fun fps(): Int? {
        val out = PServer.run("service call SurfaceFlinger 1013") ?: return null
        val flips = Regex("Parcel\\(([0-9a-f]{8})").find(out)?.groupValues?.get(1)?.toLong(16) ?: return null
        val now = System.nanoTime()
        val prev = lastFlips
        val dt = (now - lastFlipsAt) / 1e9
        lastFlips = flips; lastFlipsAt = now
        return if (prev == null || dt <= 0 || flips < prev) null else ((flips - prev) / dt).toInt()
    }

    private fun gpuBusy() = read("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.filter(Char::isDigit)?.toIntOrNull()

    /** The hot cores (cpu-1-0/3/9/10 on the Odin 2 Portal, measured 2026-09-28), hottest wins. */
    fun cpuTemp() = listOf(35, 38, 44, 45)
        .mapNotNull { read("/sys/class/thermal/thermal_zone$it/temp")?.toLongOrNull() }
        .maxOrNull()?.let { (it / 1000).toInt() }

    private var tempEma: Double? = null

    /** One smoothing step; [prev] null starts the average. */
    fun ema(prev: Double?, raw: Int): Double {
        val e = prev ?: raw.toDouble()
        return e + (raw - e) * 0.2
    }

    /**
     * [cpuTemp], smoothed, for deciding anything. One sample is not a temperature: a single busy
     * second — a game loading, an install — can push a core past the heat line and make the HUD
     * claim the chip is hot while it sits at 40 °C. Pulse smooths its own thermal input the same way.
     */
    fun cpuTempSmoothed(): Int? {
        val raw = cpuTemp() ?: return null
        tempEma = ema(tempEma, raw)
        return tempEma!!.toInt()
    }

    private fun memPressure() = read("/proc/pressure/memory")?.let { Regex("avg10=([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

    /** Busiest single core since the last sample, from /proc/stat. */
    private fun busiestCore(): Int? {
        val now = File("/proc/stat").runCatching { readLines() }.getOrNull()
            ?.filter { Regex("^cpu[0-9]").containsMatchIn(it) }
            ?.associate { line ->
                val f = line.split(Regex("\\s+"))
                val v = f.drop(1).mapNotNull { it.toLongOrNull() }
                val idle = v.getOrElse(3) { 0 } + v.getOrElse(4) { 0 }
                f[0] to (v.sum() - idle to v.sum())
            } ?: return null
        val prev = lastCpu
        lastCpu = now
        if (prev.isEmpty()) return null
        return now.mapNotNull { (k, v) ->
            val p = prev[k] ?: return@mapNotNull null
            val total = v.second - p.second
            if (total <= 0) null else ((v.first - p.first) * 100 / total).toInt()
        }.maxOrNull()
    }

    /** MemAvailable from /proc/meminfo, in MB: what Android can still hand out before killing things. */
    fun freeRamMb(): Int? = read("/proc/meminfo")?.lineSequence()?.firstOrNull { it.startsWith("MemAvailable:") }
        ?.filter(Char::isDigit)?.toLongOrNull()?.let { (it / 1024).toInt() }

    private fun read(path: String) = runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        /** Cooling devices that slow the CPU or GPU or park cores for heat (named as on the Odin 2 Portal's kernel). */
        val COOLING = Regex("cpufreq-cpu\\d+|cpu-cluster\\d+|thermal-cluster.*|gpu|devfreq-.*kgsl.*|thermal-pause-.*|pause-cpu\\d+|cpu-hotplug\\d+")

        /** Throttling: any cooling device engaged, or the GPU held below its top level for heat. */
        fun throttled(states: List<Int>, gpuLevel: Int?): Boolean = states.any { it > 0 } || (gpuLevel ?: 0) > 0
    }
}
