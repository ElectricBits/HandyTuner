package com.kei.pulse.data

import android.content.Context
import android.util.Log
import com.kei.pulse.root.RootSupport

/**
 * Reads frame rate + frame-pacing for the foreground app via **SurfaceFlinger TimeStats**.
 *
 * TimeStats is the one source that works across every target here (Android 13 RP6/Thor and Android 15
 * Odin 3). Each sample dumps the busiest layer matching the foreground package, reduced **in-shell**
 * to one tiny line, then **`-clear`s** so the next dump only covers the frames since this one.
 *
 * The FPS we report is the **display's present cadence**, not any one app layer's submit rate. Many games
 * (and translation-layer/emulator surfaces) submit at one rate while the compositor *presents* at another
 * (frame pacing / smoothing); a per-layer `averageFPS` reads the submit rate (e.g. 63) while the screen is
 * actually presenting ~90 — which is what every comparison overlay shows. So we read the **global
 * `presentToPresent` histogram** (the legacy-stats block before any layer), take the weighted-mean present
 * interval → FPS (clamped to the panel's `displayRefreshRate`), and pull the **worst frametime** + a
 * **slow-frame count** (≥33 ms presents) from the same histogram — the frametime-stability signal AutoTDP
 * tunes against. The busiest layer's `averageFPS` is kept only as a fallback for firmware that doesn't
 * populate the global block.
 *
 * Robustness baked in (an earlier rewrite regressed on all three devices):
 *  - The reply MUST be a **single tiny line**: the dump is piped straight into awk (only awk's one line
 *    reaches stdout) and the trailing `-clear` is fully redirected, because large/multi-line replies
 *    from the PServer root shell corrupt.
 *
 * Cheap by construction: samples at most once per [MIN_SAMPLE_INTERVAL_MS] (caching between), never
 * leaves TimeStats recording ([stop] disables + clears it), and briefly holds the last good reading
 * across a momentary static/idle window so the overlay doesn't flicker. All shell goes through
 * [RootSupport].
 */
class FpsReader(private val context: Context) {

    data class FpsSample(
        val fps: Float,
        val frameTimeMs: Float,
        /** Approx 1% low (min fps across the recent window); null if unknown. */
        val onePercentLowFps: Float?,
        /** Rolling average across recent reads. */
        val avgFps: Float,
        /** Recent per-read fps for the trend mini-graph (oldest first). */
        val recentFps: List<Float>,
        /** Worst present-to-present interval in the last window (ms) — the stutter tail; null if unknown. */
        val worstFrameTimeMs: Float? = null,
        /** Frames presented ≥33 ms apart in the last window (genuine hitches). */
        val jankFrames: Int = 0,
    )

    /** Reduced TimeStats sample for one window: SF's averageFPS + frame-pacing summary. */
    data class TimeStatsSample(
        val avgFps: Float,
        val frames: Long,
        val worstFrameMs: Int,
        val slowFrames: Int,
    )

    private val recentFps = ArrayDeque<Float>()

    // Cadence cache: sample at most once per interval, returning the last sample in between.
    private var lastSample: FpsSample? = null
    private var lastSampleAtMs = 0L
    private var lastSamplePackage: String? = null

    // Last *real* reading, held briefly across momentary static/idle windows (loading screens, a
    // quick pause) so the overlay doesn't snap to "—" the instant rendering stalls.
    private var lastGoodSample: FpsSample? = null
    private var lastGoodAtMs = 0L

    @Volatile private var timestatsEnabled = false

    /** One sample for [packageName], or null when no usable frame data is available yet. */
    fun read(packageName: String?): FpsSample? {
        if (packageName.isNullOrBlank()) return null
        val now = System.currentTimeMillis()
        if (packageName == lastSamplePackage && now - lastSampleAtMs < MIN_SAMPLE_INTERVAL_MS) {
            return lastSample
        }
        if (packageName != lastSamplePackage) resetForPackage()
        lastSamplePackage = packageName
        lastSampleAtMs = now
        lastSample = sampleTimeStats(packageName)
        return lastSample
    }

    /** Stop measuring: disable TimeStats so SurfaceFlinger isn't left recording. Call when idle. */
    fun stop() {
        if (timestatsEnabled) {
            RootSupport.runGeneratedScript(
                context,
                SCRIPT_NAME,
                "dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1; " +
                    "dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1; echo OK",
            )
        }
        timestatsEnabled = false
        resetForPackage()
        lastSamplePackage = null
    }

    private fun resetForPackage() {
        recentFps.clear()
        lastSample = null
        lastGoodSample = null
        lastGoodAtMs = 0L
    }

    private fun sampleTimeStats(pkg: String): FpsSample? {
        if (!timestatsEnabled) {
            RootSupport.runGeneratedScript(
                context,
                SCRIPT_NAME,
                "dumpsys SurfaceFlinger --timestats -enable >/dev/null 2>&1; " +
                    "dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1; echo OK",
            )
            timestatsEnabled = true
            return null // window starts now; no FPS yet
        }
        val out = RootSupport.runGeneratedScript(context, SCRIPT_NAME, timestatsScript())
        if (DEBUG_LOG) Log.d(TAG, "pkg=$pkg raw=${out?.replace("\n", "\\n")}")
        val parsed = out?.let { parseTimestats(it) }
        // No usable layer / too few frames = static or bursty screen: hold the last good reading
        // briefly rather than blanking the overlay.
        if (parsed == null || parsed.frames < MIN_FRAMES) return heldSample()
        val fps = parsed.avgFps.takeIf { it in 1f..240f } ?: return heldSample()
        return statsFor(fps, parsed.worstFrameMs, parsed.slowFrames).also {
            lastGoodSample = it
            lastGoodAtMs = System.currentTimeMillis()
        }
    }

    private fun heldSample(): FpsSample? =
        lastGoodSample?.takeIf { System.currentTimeMillis() - lastGoodAtMs < HOLD_MS }

    /**
     * Dump TimeStats and reduce it in-shell to one line:
     * "FR <globalFrames> AF <globalFps> WORST <ms> SLOW <n> LF <layerFrames> LAF <layerFps>", then clear.
     *
     * `AF` is the **display present rate** from the global `presentToPresent` histogram (weighted-mean
     * interval, idle bins ≥100 ms dropped, clamped to `displayRefreshRate`). `LF`/`LAF` are the busiest
     * non-`none` layer's frames + `averageFPS` (the submit-rate fallback). Note: the GLOBAL histogram is
     * `presentToPresent` (camelCase); the per-layer one is `present2present` (digit) — we read the global.
     */
    private fun timestatsScript(): String =
        """
            dumpsys SurfaceFlinger --timestats -dump -maxlayers 16 2>/dev/null | awk '
              function commitLayer(){ if((lf+0)>(blf+0) && lname !~ /= none/){ blf=lf; blaf=laf } }
              BEGIN{ g=1 }
              /layerName =/ { commitLayer(); g=0; lf=0; laf=0; lname=${'$'}0 }
              g==1 && ${'$'}1=="totalFrames" { gf=${'$'}3 }
              g==1 && ${'$'}1=="displayRefreshRate" { rr=${'$'}3 }
              g==1 && /presentToPresent histogram/ { gh=1; next }
              g==1 && gh==1 { gh=0; for(i=1;i<=NF;i++){ split(${'$'}i,a,"="); ms=a[1]+0; c=a[2]+0; if(c>0 && ms<100){ sc+=c; sm+=ms*c; if(ms>worst)worst=ms; if(ms>=33)slow+=c } } }
              g==0 && ${'$'}1=="totalFrames" { lf=${'$'}3 }
              g==0 && ${'$'}1=="averageFPS" { laf=${'$'}3 }
              END{
                commitLayer();
                fps=(sm>0)?(1000.0*sc/sm):0;
                if(rr>0 && fps>rr) fps=rr;
                printf "FR %d AF %.2f WORST %d SLOW %d LF %d LAF %.2f\n", gf+0, fps, worst+0, slow+0, blf+0, blaf+0
              }
            '
            dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1
        """.trimIndent()

    private fun statsFor(fps: Float, worstFrameMs: Int, slowFrames: Int): FpsSample {
        recentFps.addLast(fps)
        while (recentFps.size > HISTORY) recentFps.removeFirst()
        return FpsSample(
            fps = fps,
            frameTimeMs = if (fps > 0f) 1000f / fps else 0f,
            onePercentLowFps = recentFps.minOrNull(),
            avgFps = recentFps.average().toFloat(),
            recentFps = recentFps.toList(),
            worstFrameTimeMs = worstFrameMs.takeIf { it > 0 }?.toFloat(),
            jankFrames = slowFrames,
        )
    }

    companion object {
        private const val TAG = "PulseFps"
        // Logs the raw reduced TimeStats line each sample — leave on while the FPS path is being
        // verified on-device (adb logcat -s PulseFps); cheap (one short line per ~second).
        private const val DEBUG_LOG = true
        private const val SCRIPT_NAME = "fps-probe.sh"
        private const val HISTORY = 40
        private const val MIN_SAMPLE_INTERVAL_MS = 500L
        private const val MIN_FRAMES = 5 // fewer than this in a window ⇒ idle/bursty, not reliable
        private const val HOLD_MS = 4_000L // keep showing the last good fps this long across a stall

        /**
         * Parses "FR <gFrames> AF <gFps> WORST <ms> SLOW <n> LF <lFrames> LAF <lFps>". Prefers the global
         * display present rate (`AF`); falls back to the busiest layer's submit rate (`LAF`) only when the
         * global block is empty. Returns null when neither is usable. Pure — unit-tested.
         */
        fun parseTimestats(out: String): TimeStatsSample? {
            val m = Regex(
                """FR\s+(\d+)\s+AF\s+([0-9.]+)\s+WORST\s+(\d+)\s+SLOW\s+(\d+)\s+LF\s+(\d+)\s+LAF\s+([0-9.]+)""",
            ).find(out) ?: return null
            val gFrames = m.groupValues[1].toLongOrNull() ?: 0L
            val gFps = m.groupValues[2].toFloatOrNull() ?: 0f
            val worst = m.groupValues[3].toIntOrNull() ?: 0
            val slow = m.groupValues[4].toIntOrNull() ?: 0
            val lFrames = m.groupValues[5].toLongOrNull() ?: 0L
            val lFps = m.groupValues[6].toFloatOrNull() ?: 0f
            return when {
                gFrames > 0L && gFps > 0f -> TimeStatsSample(gFps, gFrames, worst, slow)
                lFrames > 0L && lFps > 0f -> TimeStatsSample(lFps, lFrames, worst, slow)
                else -> null
            }
        }
    }
}
