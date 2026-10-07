// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.kei.pulse.control

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.kei.pulse.appwatch.ForegroundAppMonitorService
import com.kei.pulse.data.FrameLimiter
import com.kei.pulse.data.SocDetector
import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PowerTier
import com.kei.pulse.overlay.QuickAccessAction
import com.kei.pulse.AppContainer
import com.kei.pulse.model.RgbStick
import com.kei.pulse.sleep.SleepProfileMonitorService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * The remote control HandyTuner binds to. Two locks: the manifest's signature permission (only apps
 * signed with this fork's key), and [caller], which also wants the caller to be HandyTuner. Commands
 * become the same [QuickAccessAction]s PULSE's own panel sends, so nothing here writes the device.
 */
class ControlService : Service() {
    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IPulseControl.Stub() {
        override fun version() = guarded { VERSION }

        override fun state(): String = guarded {
            val s = ForegroundAppMonitorService.instance?.let { runBlocking { it.remoteState() } } ?: return@guarded ""
            JSONObject().put("frameCap", s.game?.let { caps.getInt(it, 0) } ?: 0).put("game", s.game).put("binding", s.binding).put("tier", s.tier).put("autoTdp", s.autoTdp)
                .put("fps", s.fps).put("fanMode", s.fanMode).put("overlay", s.overlay).put("autoTdpDefault", s.autoTdpDefault).put("defaultFps", s.defaultFps)
                .put("fpsOptions", org.json.JSONArray(s.fpsOptions)).toString()
        }

        override fun setTier(tier: Int) = guarded {
            PowerTier.entries.getOrNull(tier)?.let { send(QuickAccessAction.SetTier(it)) } ?: false
        }

        override fun setAutoTdp(on: Boolean, fps: Int) = guarded {
            if (fps !in fpsTargets) return@guarded false
            val svc = ForegroundAppMonitorService.instance ?: return@guarded false
            val st = runBlocking { svc.remoteState() }
            val now = st.autoTdp
            // AutoTDP paces the frames itself, so a HandyTuner cap on this game would fight it: drop it.
            if (on) st.game?.let { clearCap(it) }
            // PULSE's panel only has a toggle, so toggle only when the state differs.
            send(*listOfNotNull(QuickAccessAction.SetFpsTarget(fps), QuickAccessAction.ToggleAutoTdp.takeIf { now != on }).toTypedArray())
        }

        override fun setFanMode(mode: Int) = guarded { mode in FAN_MODES && send(QuickAccessAction.SetFanMode(mode)) }

        override fun setOverlayEnabled(enabled: Boolean) = guarded { send(QuickAccessAction.SetOverlayEnabled(enabled)) }

        override fun stats(): String = guarded { ForegroundAppMonitorService.instance?.remoteStats().orEmpty() }

        override fun setFrameCap(fps: Int) = guarded {
            if (fps != 0 && fps !in CAPS) return@guarded false
            val st = ForegroundAppMonitorService.instance?.let { runBlocking { it.remoteState() } } ?: return@guarded false
            val game = st.game ?: return@guarded false
            if (fps == 0) { clearCap(game); return@guarded true }
            if (st.autoTdp) return@guarded false               // AutoTDP owns the frame rate for this game
            Thread { FrameLimiter.setCapOverride(game, fps) }.start()
            caps.edit().putInt(game, fps).apply()
            true
        }

        override fun setDefaultTier(tier: Int) = guarded {
            val t = PowerTier.entries.getOrNull(tier) ?: return@guarded false
            val svc = ForegroundAppMonitorService.instance ?: return@guarded false
            svc.remoteGlobal(QuickAccessAction.SetTier(t)); true
        }

        override fun setDefaultAutoTdp(on: Boolean, fps: Int) = guarded {
            if (fps !in fpsTargets) return@guarded false
            val svc = ForegroundAppMonitorService.instance ?: return@guarded false
            val now = runBlocking { svc.remoteState() }.autoTdpDefault
            svc.remoteGlobal(*listOfNotNull(QuickAccessAction.SetFpsTarget(fps), QuickAccessAction.ToggleAutoTdp.takeIf { now != on }).toTypedArray())
            true
        }

        override fun engineSettings(): String = guarded {
            val s = runBlocking { container.settingsStorage.settings.first() }
            JSONObject().put("fanTargetC", s.fanTargetTempC).put("autoTdpBias", s.autoTdpBias.name)
                .put("sleep", s.sleepProfileEnabled && s.sleepProfileId != null).put("rgbMode", s.rgbMode.name)
                .put("rgbColor", "%06X".format(s.rgbManualLeftColor and 0xFFFFFF))
                .put("rgbBrightness", (s.rgbManualLeftBrightness * 100).roundToInt()).toString()
        }

        override fun setEngineSetting(key: String, value: String) = guarded {
            val change = EngineSettings.parse(key, value) ?: return@guarded false
            runBlocking { apply(change) }
        }

        override fun clearFrameCaps() = guarded {
            val all = caps.all.keys.toList()
            Thread { all.forEach { FrameLimiter.clearOverride(it) } }.start()
            caps.edit().clear().apply()
            true
        }
    }

    private val container by lazy { AppContainer(this) }

    /** The same storage calls PULSE's own panel made (ui/TunerViewModel.kt); the watcher reads them each tick. */
    private suspend fun apply(change: EngineChange): Boolean {
        val store = container.settingsStorage
        when (change) {
            is EngineChange.FanTarget -> { store.persistFanSmartEnabled(true); store.persistFanTargetTemp(change.celsius) }
            is EngineChange.Bias -> store.persistAutoTdpBias(change.bias)
            is EngineChange.Lights -> store.persistRgbMode(change.mode)
            is EngineChange.LightColor -> store.persistRgbManualStick(RgbStick.BOTH, change.argb, change.brightness / 100f)
            is EngineChange.Sleep -> if (change.on) {
                val id = sleepProfileId() ?: return false
                store.persistSleepProfile(true, id)
                SleepProfileMonitorService.start(this)
            } else {
                store.persistSleepProfileEnabled(false)
                SleepProfileMonitorService.stop(this)
            }
        }
        return true
    }

    /**
     * The profile the screen-off underclock applies: PULSE's own Power Saving limits, saved once as a profile
     * (this build ships no bundled profiles). null when the clusters can't be read, so sleep stays off.
     */
    private suspend fun sleepProfileId(): String? {
        val repo = container.repository
        repo.observeState().first().displayProfiles.firstOrNull { it.name == SLEEP_PROFILE }?.let { return it.id }
        val policies = repo.observeState().first().policies.ifEmpty { return null }
        repo.createUserProfile(SLEEP_PROFILE, repo.tierFrequencies(PowerTier.POWER_SAVING, policies))
        return repo.observeState().first().displayProfiles.firstOrNull { it.name == SLEEP_PROFILE }?.id
    }

    private val fpsTargets by lazy { PerAppConfig.fpsTargetsFor(SocDetector().detectSocModel()) }

    /** Games HandyTuner capped (package → fps): a cap outlives restarts, so this is what Reset clears. */
    private val caps by lazy { getSharedPreferences("handytuner_frame_caps", MODE_PRIVATE) }

    private fun clearCap(game: String) {
        if (!caps.contains(game)) return
        caps.edit().remove(game).apply()
        Thread { FrameLimiter.clearOverride(game) }.start()
    }

    private fun send(vararg actions: QuickAccessAction): Boolean {
        val svc = ForegroundAppMonitorService.instance ?: return false
        svc.remote(*actions)
        return true
    }

    private inline fun <T> guarded(block: () -> T): T {
        val uid = Binder.getCallingUid()
        if (uid != android.os.Process.myUid()) throw SecurityException("not HandyTuner (uid $uid)")
        return block()
    }

    companion object {
        const val VERSION = 5
        const val SLEEP_PROFILE = "HandyTuner sleep"
        const val CALLER = "com.electric.handytuner"
        val FAN_MODES = setOf(1, 4, 5, 6)
        /**
         * Only caps MEASURED working on the Odin 2 Portal (120 Hz panel): 30 → 29.9, 40 → 39.1, 60. Android also
         * accepts 45/90 but the panel ignores them, and a 24 fps override hung the whole device (2026-09-30), so
         * nothing untested gets through. Add a value only after measuring it on the device.
         */
        val CAPS = setOf(30, 40, 60)
    }
}
