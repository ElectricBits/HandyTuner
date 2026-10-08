// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the Quick Menu does to the device. PServer calls block, so call these
 * off the main thread. Every system setting goes through [put], which records
 * the original first ([Originals]).
 *
 * Never touched: CPU/GPU clocks and the fan nodes (Pulse's). While Pulse runs,
 * performance mode, fan mode and refresh rate are Pulse's too, and the menu
 * only shows them.
 */
class Actions(private val ctx: Context) {
    private fun put(key: String, value: String) = Originals.put(ctx, key, value)

    private fun get(key: String) = Settings.System.getString(ctx.contentResolver, key)

    // --- Pulse -------------------------------------------------------------
    fun pulseInstalled() = runCatching { ctx.packageManager.getPackageInfo(PULSE, 0) }.isSuccess
    fun pulseRunning() = !PServer.run("pidof $PULSE:pulse").isNullOrBlank()   // the engine's own process
    /** PULSE's settings are on HandyTuner's Tweaks page now. CLEAR_TOP re-creates the activity, so it reads the page. */
    fun openPulse() = ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.PAGE, "TWEAKS")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))

    // --- Performance and fan (AYN's own modes; only when Pulse isn't running) ----
    fun perfMode() = get("performance_mode")?.toIntOrNull()
    fun fanMode() = get("fan_mode")?.toIntOrNull()

    /** Performance first, then the fan: performance_mode 0 switches the fan off (seen by Portal Tuner). */
    fun setPerf(perf: Int, fan: Int = fanMode() ?: FAN_SMART) {
        put("performance_mode", perf.toString())
        put("fan_mode", fan.toString())
    }
    fun setFan(fan: Int) = put("fan_mode", fan.toString())

    // --- Odin Assistant extras ------------------------------------------------
    /** "Speed up": closes cached background apps. Returns MB of RAM freed (MemAvailable after − before). */
    fun speedUp(): Int {
        val before = Stats().freeRamMb() ?: 0
        // Said out loud rather than swallowed: a refused cleanup frees nothing, and "0 MB freed"
        // on its own would look like the device just didn't have anything to close.
        if (!closeBackground()) Notifier.nothingClosed(ctx)
        Thread.sleep(700)
        return ((Stats().freeRamMb() ?: before) - before).coerceAtLeast(0)
    }

    /**
     * Closes cached apps (oom_score_adj ≥ 900, what the low-memory killer would take first), but never
     * the last game: `am kill-all` closed a paused Minecraft on the Odin, and its unsaved progress with it.
     *
     * Returns whether it actually ran. A *paused* game isn't in front, so the script's own
     * `visible=true` check can't save it — `last_game` is the only record of it. So when that file
     * exists but can't be read, nothing is closed: keeping nothing ("none.") would keep the very game
     * this rule exists to protect. A missing file means no game has been played yet, so there is
     * nothing to protect and closing cached apps is fine.
     */
    fun closeBackground(): Boolean {
        val file = java.io.File(ctx.filesDir, "last_game")
        val raw = if (file.exists()) runCatching { file.readLines().getOrNull(2) }.getOrNull() else null
        val last = raw?.substringBefore(':')?.takeIf { it.matches(Regex("[A-Za-z0-9._]+")) }
        if (file.exists() && last == null) { Log.w(TAG, "speed up: can't read last_game, closing nothing"); return false }
        // ponytail: the removeTask binder code is per Android release; only used where it was checked
        // (AYN, Android 13 = 111). Elsewhere Speed Up still closes the apps, the cards just stay.
        val cards = if (android.os.Build.VERSION.SDK_INT == 33 && android.os.Build.MANUFACTURER == "AYN") 1 else 0
        return PServer.ok("sh $killScript ${last ?: "none."} $cards")
    }

    private val killScript by lazy {
        java.io.File(ctx.filesDir, "kill.sh").apply {
            writeText(killScriptText(ctx.packageName))
            setReadable(true, false); ctx.filesDir.setExecutable(true, false)
        }.absolutePath
    }

    /** A/B layout: 1 = Xbox (A bottom), 0 = swapped. Checked on the Odin with AYN's Key Test. */
    fun buttonLayout() = get("flip_button_layout")?.toIntOrNull() ?: 1
    fun setButtonLayout(v: Int) = put("flip_button_layout", v.toString())

    /**
     * Google Play services off/on, like Odin Assistant's switch: frees memory and background work
     * while gaming, but notifications, sign-ins and the Play Store stop until it's back on. Remembered
     * in a file, so "Reset everything to stock" turns it back on.
     */
    fun playServicesOn() = runCatching { ctx.packageManager.getApplicationInfo(GMS, 0).enabled }.getOrDefault(true)
    fun setPlayServices(on: Boolean) {
        val flag = java.io.File(ctx.filesDir, "gms_off")
        if (on) { PServer.run("pm enable $GMS"); flag.delete() }
        else { flag.writeText("1"); PServer.run("pm disable-user --user 0 $GMS") }
    }

    /** The fan's duty as a percent of its PWM period (read-only: the fan is Pulse's and AYN's). */
    fun fanPct(): Int? {
        val d = PServer.run("cat /sys/class/gpio5_pwm2/duty")?.toLongOrNull() ?: return null
        val p = PServer.run("cat /sys/class/gpio5_pwm2/period")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (d * 100 / p).toInt()
    }

    // --- Screen and sound ---------------------------------------------------
    fun brightnessPct() = (Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255)
    fun setBrightnessPct(pct: Int) {
        put("screen_brightness_mode", "0")                                  // manual, or auto undoes it
        put("screen_brightness", (pct.coerceIn(1, 100) * 255 / 100).toString())
    }

    private val audio get() = ctx.getSystemService(AudioManager::class.java)
    fun volumePct() = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun setVolumePct(pct: Int) = audio.setStreamVolume(AudioManager.STREAM_MUSIC,
        pct.coerceIn(0, 100) * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 100, 0)

    fun refreshHz() = get("peak_refresh_rate")?.toFloatOrNull()?.toInt()
    fun setRefreshHz(hz: Int) { put("peak_refresh_rate", "$hz.0"); put("min_refresh_rate", "$hz.0") }

    // --- Wi-Fi low latency (used by the Low Ping profile, see ProfileRunner) -----------
    /** Low Ping: Wi-Fi low-latency mode, held while on. Released if the overlay process dies. */
    private var lowLatency: WifiManager.WifiLock? = null
    fun lowPing(on: Boolean) {
        if (on && lowLatency == null) {
            lowLatency = ctx.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "HandyTuner:lowping").apply { acquire() }
        } else if (!on) { lowLatency?.release(); lowLatency = null }
    }
    val lowPingOn get() = lowLatency != null

    // --- Capture -------------------------------------------------------------
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Returns the saved path, or null. Call with the menu already hidden. */
    fun screenshot(): String? {
        val path = "/sdcard/Pictures/HandyTuner/HT_${stamp()}.png"
        PServer.run("mkdir -p /sdcard/Pictures/HandyTuner")
        if (!PServer.ok("screencap ${tvTarget("-d")}-p $path")) return null
        MediaScannerConnection.scanFile(ctx, arrayOf(path), null) { _, uri -> Notifier.screenshot(ctx, uri, path) }
        return path
    }

    var recordingPath: String? = null; private set

    /**
     * Docked to a TV, screenshots and recordings are of the TV, where the game is: screencap and screenrecord
     * default to the Odin's own screen. They take SurfaceFlinger's physical display id, not Android's display id.
     */
    private fun tvTarget(flag: String): String {
        if (Resolution.tv(ctx) == null) return ""
        val ids = PServer.run("dumpsys SurfaceFlinger --display-id | tr '\\n' ';'") ?: return ""
        return externalDisplayId(ids)?.let { "$flag $it " } ?: ""
    }

    /** Up to 3 minutes (screenrecord's own limit); detached so PServer's call returns at once. */
    fun startRecording() {
        val path = "/sdcard/Movies/HandyTuner/HT_${stamp()}.mp4"
        PServer.run("mkdir -p /sdcard/Movies/HandyTuner")
        PServer.run("setsid screenrecord ${tvTarget("--display-id")}--time-limit 180 $path </dev/null >/dev/null 2>&1 &")
        recordingPath = path
        Notifier.recording(ctx)
        // screenrecord stops itself at 3 minutes: finish up then, if Stop wasn't pressed first.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (recordingPath == path) Thread { stopRecording() }.start()
        }, 182_000)
    }

    /** SIGINT lets screenrecord finish the file. -x matches the process name only, never a
     *  command line: a pkill -f once matched pservice's own shell and killed it. */
    fun stopRecording() {
        PServer.run("pkill -INT -x screenrecord")
        recordingPath?.let { p ->
            Thread.sleep(800)
            MediaScannerConnection.scanFile(ctx, arrayOf(p), null) { _, uri -> Notifier.recordingSaved(ctx, uri) }
        }
        recordingPath = null
    }

    companion object {
        /** The first non-built-in display in `dumpsys SurfaceFlinger --display-id`: "Display 46… (HWC display 1): …". */
        fun externalDisplayId(out: String): String? =
            Regex("""Display (\d+) \(HWC display [1-9]\d*\)""").find(out)?.groupValues?.get(1)

        /** PULSE's engine is built into this app (merge-plan.md), in the `$PULSE:pulse` process. */
        const val PULSE = "com.electric.handytuner"
        private const val TAG = "HandyTuner"
        /** IActivityManager.Stub.TRANSACTION_removeTask on Android 13 (read from the Odin's framework.jar). */
        const val REMOVE_TASK = 111
        const val GMS = "com.google.android.gms"
        const val FAN_QUIET = 1; const val FAN_SMART = 4; const val FAN_SPORT = 5; const val FAN_CUSTOM = 6
        val PERF_NAMES = mapOf(0 to "Standard", 1 to "Balanced", 2 to "Max")
        val FAN_NAMES = mapOf(FAN_QUIET to "Quiet", FAN_SMART to "Smart", FAN_SPORT to "Sport", FAN_CUSTOM to "Hold temp")   // 6: PULSE holds the Tuning page's target temperature

        /**
         * kill.sh, as a function of [pkg] so a test can read it.
         *
         * Only `sh <path>` goes through [PServer.run], so [PServer.unsafeIn] never sees this body — the
         * most dangerous thing HandyTuner runs is its one command that skips the guard, which is why
         * CommandSafetyTest asserts on this text directly.
         *
         * Kills only cached apps: `oom_score_adj >= 900`, the ones the low-memory killer would take
         * next, with a dot in the name to skip system processes. The last game, Pulse and HandyTuner
         * are named explicitly, since a *paused* game isn't visible and the oom score can't save it.
         */
        internal fun killScriptText(pkg: String) =
            "for p in /proc/[0-9]*; do a=\$(cat \$p/oom_score_adj 2>/dev/null); [ \"\${a:-0}\" -ge 900 ] || continue\n" +
            " n=\$(tr '\\0' '\\n' < \$p/cmdline 2>/dev/null | head -1); n=\${n%%:*}\n" +
            " case \"\$n\" in *.*) ;; *) continue;; esac; case \"\$n\" in /*|*' '*|\"\$1\"|$pkg) continue;; esac\n" +
            " kill -9 \${p#/proc/}; done\n" +
            // Recents cards too (the owner saw nothing change when only processes closed): every app
            // card that isn't on screen, the last game, Pulse or HandyTuner. `am stack remove` left the
            // cards in Recents; IActivityManager.removeTask does what swiping away does.
            "[ \"\$2\" = 1 ] || exit 0\n" +
            "v=\$(am stack list | grep visible=true | grep -o \"taskId=[0-9]*\" | cut -d= -f2 | tr \"\\n\" \" \")\n" +
            "dumpsys activity recents | grep \"Recent #\" | grep type=standard | while read l; do\n" +
            " id=\$(echo \"\$l\" | grep -o \" #[0-9]* type\" | tr -dc 0-9)\n" +
            " case \"\$l\" in *\"\$1\"*|*$PULSE*|*$pkg*) continue;; esac\n" +
            " case \" \$v \" in *\" \$id \"*) continue;; esac\n" +
            " service call activity $REMOVE_TASK i32 \$id > /dev/null; done\n"
    }
}
