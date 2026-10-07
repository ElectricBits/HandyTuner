// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.provider.Settings
import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The always-on part: reads the controller hotkey and shows the Neon HUD.
 *
 * Runs in its own small process (see the manifest) and uses plain Views, not
 * Compose, so the low-memory killer has less reason to pick it. Whether the
 * overlay was open is saved, so a restart after a kill puts it back: Pulse lost
 * its overlay on every restart, and that was the bug the owner saw.
 */
class OverlayService : AccessibilityService(), QuickMenu.Host {

    /**
     * Gap between a group's icon+label and its readings. The four labels are all four characters
     * and share one icon size, so a fixed gap puts every reading in the same column instead of
     * letting them start wherever its label happens to end.
     */
    private val GROUP_GAP = "  "

    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("stats").apply { start() }
    private val bg = Handler(worker.looper)
    private val stats = Stats()
    private val net by lazy { Net(this) }
    private val held = mutableSetOf<Int>()
    private var hud: LinearLayout? = null
    private var hudLp: WindowManager.LayoutParams? = null
    private var hudWidth = 0
    /** One TextView per group: PERF, NET, TEMP, SYS, then the verdict line. */
    private lateinit var linePerf: TextView
    private lateinit var lineNet: TextView
    private lateinit var lineTemp: TextView
    private lateinit var lineSys: TextView
    private lateinit var line3: TextView
    @Volatile private var style = HudStyle()
    private val nunito by lazy { runCatching { Typeface.create(resources.getFont(R.font.nunito), 800, false) }.getOrDefault(Typeface.DEFAULT_BOLD) }
    private val actions by lazy { Actions(this) }
    private val menu by lazy { QuickMenu(this, actions, bg, this, nunito, mono) }
    private val runner by lazy { ProfileRunner(this, actions) }
    private val front by lazy { Foreground(this) }
    override fun runner() = runner
    override fun currentGame() = session?.pkg

    // --- Key mapping: the scheme for the front game, and its editor ---
    private val keyPlayer by lazy { KeyMapPlayer(this) }
    private val editor by lazy { KeyMapEditor(this, nunito) { loadKeymap() } }
    @Volatile private var frontPkg: String? = null
    private var keymapStamp = -1L
    private val keyView by lazy { KeyMapView(this, nunito) }
    private fun loadKeymap() {
        val p = frontPkg
        val spots = if (KeyMapper.canMap(p)) KeyMapper.get(this, p!!) else emptyList()
        val set = KeyMapper.settings(this)
        keyPlayer.use(spots); keyPlayer.settings = set
        main.post { if (set.show && !editor.open) keyView.show(spots, set.opacity) else keyView.hide() }
    }
    override fun frontPkg() = frontPkg
    override fun pulse() = pulse
    override fun keyCount() = frontPkg?.let { KeyMapper.get(this, it).size } ?: 0
    override fun openKeyEditor() { frontPkg?.takeIf { KeyMapper.canMap(it) }?.let { p -> main.post { menu.hide(); keyView.hide(); editor.start(p) } } }

    // --- Game watch: profiles on, sessions recorded, every 3 s whether or not the HUD is up ---
    private data class Open(val pkg: String, val label: String, val start: Long, val bat: Int, val charging: Boolean)
    @Volatile private var session: Open? = null
    private var profileStamp = -1L
    private var candidate: String? = null
    private var resetStamp = -1L
    private var front0: String? = null            // the last confirmed front app or game

    private fun label(id: String) = GameId.label(id) ?: runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(id, 0)).toString()
    }.getOrDefault(id)

    private fun charging() = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }

    /** Charge limit + gaming bypass (ChargeRule), once the owner has set it on the Battery page. Writes only on change. */
    private fun chargeTick() {
        if (!ChargeRule.file(this).exists()) return
        val b = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return
        // Plugged, not "charging": once bypassed the battery reports not charging while the charger is in.
        val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val want = ChargeRule.decide(ChargeRule.load(this), plugged, level, session != null, bypassed == true) ?: return
        if (want == bypassed) return
        Originals.put(this, "system:${ChargeRule.KEY}", if (want) "1" else "0")
        bypassed = want
        Log.i(TAG, "charging: bypass=$want level=$level")
    }

    private fun endSession() {
        val o = session ?: return
        session = null
        Sessions.add(this, Sessions.Session(o.pkg, o.label, o.start, System.currentTimeMillis(), o.bat, battery(), o.charging || charging()))
        Log.i(TAG, "session end ${o.pkg}")
    }

    /**
     * A game's settings: HandyTuner's part now, and the Pulse part sent to the fork (D16), only what differs
     * from what Pulse already has for the game in front. Runs on the worker thread (binder calls).
     */
    private fun applyGame(id: String, g: GameSettings) {
        runner.apply(g.profile)
        val st = pulse.state() ?: return                         // no fork: the HandyTuner part is all there is
        // Pulse applies to the app IT sees in front; if that's not this game yet, don't touch another app's settings.
        if (st.optString("game") != id.substringBefore(':')) { pendingApply = id; Log.i(TAG, "pulse sees ${st.optString("game")}, not $id: next check"); return }
        pendingApply = null
        val binding = st.optString("binding")
        val p = g.pulse
        var accepted = true
        if (p.autoFps != null) {
            if (binding != "auto:tdp" || st.optInt("fps") != p.autoFps) accepted = pulse.call { it.setAutoTdp(true, p.autoFps) } ?: false
        } else {
            val tier = p.tier!!
            if (binding != "tier:" + TIER_KEYS[tier]) { accepted = pulse.call { it.setTier(tier) } ?: false; Thread.sleep(400) }   // the cap needs the tier in first
            // Asking a fork that predates caps would just fail the binder call, so don't ask; the version
            // then shows up in the report below as "caps need v3" instead of silence.
            if (st.optInt("frameCap") != p.cap && pulse.supports(Applied.Feature.FRAME_CAP))
                accepted = (pulse.call { it.setFrameCap(p.cap) } ?: false) && accepted
        }
        if (st.optInt("fanMode", -1) != p.fan) accepted = (pulse.call { it.setFanMode(p.fan) } ?: false) && accepted
        reportApply(g.preset.label, p, accepted)
    }

    /** The last thing we said wasn't applied, so a repeat of the same problem isn't announced twice. */
    @Volatile private var applyNote: String? = null

    /** One place to announce anything the fork didn't take, however it was caused. */
    private fun report(what: String, problems: List<Applied.Problem>) {
        val note = problems.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.message }
        if (note == null) { applyNote = null; return }
        Log.i(TAG, "not applied: $what — $note")
        if (note != applyNote) { applyNote = note; Notifier.applyProblem(this, what, note) }
    }

    /**
     * Compare what we just asked for with what the fork now says it has, so a refusal is said out loud
     * instead of leaving the game quietly unprofiled (docs/STATE_MODEL.md, docs/BUGS.md #4). The watcher
     * re-sends every few seconds, so a lasting problem reports once rather than every time.
     */
    private fun reportApply(what: String, want: PulsePart, accepted: Boolean) {
        // Settle before judging. The fork's setters are fire-and-forget — they hand an action to
        // Pulse's overlay and return true immediately — so one read straight afterwards can still see
        // the old value. On the Odin that reported "the fork refused" for a default it had just set.
        pulseSettles { Applied.problems(want, pulse.actual() ?: return@pulseSettles false, accepted).isEmpty() }
        val got = pulse.actual() ?: return
        report(what, Applied.problems(want, got, accepted))
    }

    /** Polls until [ok] holds or the budget runs out, for a change Pulse applies on its own time. */
    private fun pulseSettles(gapMs: Long = 250, tries: Int = 6, ok: () -> Boolean): Boolean {
        for (i in 1..tries) {
            if (ok()) return true
            if (i < tries) Thread.sleep(gapMs)
        }
        return false
    }

    /**
     * Outside games, Pulse runs Power Saving (D14). That is Pulse's all-games default, so the default it had
     * is kept first, once, for Reset; a default changed later in Pulse itself is left alone.
     */
    private fun outsideGamesDefault() {
        // Safe mode means read-only, and this writes. It reached the device once: Pulse reconnecting
        // after safe mode had already restored the default re-set it, because the restore deletes the
        // "what it was" file this checks.
        if (SafeMode.active(this)) return
        val orig = java.io.File(filesDir, PULSE_DEFAULT_ORIG)
        if (orig.exists()) return
        if (!pulse.supports(Applied.Feature.ALL_GAMES_DEFAULT)) {
            // v3 and older have no all-games default, so there is nothing to set and no refusal to
            // report — only an answer for a fork that is simply too old (docs/BUGS.md #11).
            report("Power Saving outside games", Applied.defaultProblems(2, null, pulse.actual() ?: return))
            return
        }
        val st = pulse.state() ?: return
        orig.writeAtomic(org.json.JSONObject().put("tier", st.optString("tier")).put("autoTdp", st.optBoolean("autoTdpDefault"))
            .put("fps", st.optInt("defaultFps", 60)).toString())
        if (pulse.call { it.setDefaultTier(2) } != true) orig.delete()
        else Log.i(TAG, "pulse default -> Power Saving (was ${orig.readText()})")
    }

    private fun restorePulseDefault() {
        val orig = java.io.File(filesDir, PULSE_DEFAULT_ORIG)
        val o = runCatching { org.json.JSONObject(orig.readText()) }.getOrNull() ?: return
        if (!pulse.supports(Applied.Feature.ALL_GAMES_DEFAULT)) { orig.delete(); return }   // nothing was ever set
        // Pulse's tier label follows the last tier used anywhere, so it only means "the default" when AutoTDP
        // wasn't; with AutoTDP on, put back Pulse's own starting label, Custom.
        val tier = if (o.optBoolean("autoTdp")) 3
            else listOf("AAA / Max", "Balanced", "Power Saving", "Custom").indexOf(o.optString("tier")).takeIf { it >= 0 } ?: 3
        pulse.call { it.setDefaultTier(tier) }
        if (o.optBoolean("autoTdp")) {
            val fps = o.optInt("fps", 60)
            Thread.sleep(400)
            pulse.call { it.setDefaultAutoTdp(true, fps) }
            // Only the AutoTDP half is checkable: `state().tier` is Pulse's active label, not the
            // default. And it needs settling, for the same reason reportApply does.
            val landed = pulseSettles { pulse.actual()?.autoTdpDefault == true }
            if (!landed) pulse.actual()?.let { report("the default for other apps", Applied.defaultProblems(3, fps, it)) }
        }
        orig.delete()
        Log.i(TAG, "pulse default restored: $o")
    }

    private var safeWas = false
    private var bypassed: Boolean? = null   // charging switch as last written; null = not written yet
    private var tweakLowLatency = false

    /**
     * The Tweaks page's "Low Latency, always on", held on top of whatever the game's preset does. Checked
     * every watcher tick (3 s), so a preset clearing its own part is topped back up at once.
     */
    private fun ensureTweaks() {
        val on = Tweaks.lowLatency(this)
        val scans = listOf("wifi_scan_always_enabled", "ble_scan_always_enabled")
        if (on) {
            if (!actions.lowPingOn) actions.lowPing(true)
            scans.forEach { k -> if (android.provider.Settings.Global.getInt(contentResolver, k, 0) != 0) Originals.put(this, "global:$k", "0") }
        } else if (tweakLowLatency && runner.active != Profile.LOW_PING) {
            actions.lowPing(false)
            // Battery Saver turns scans off too; only a game without a network part gets them back.
            if (runner.active == Profile.NORMAL) Originals.restore(this, scans.map { "global:$it" })
        }
        tweakLowLatency = on
    }

    @Volatile private var pendingApply: String? = null      // a game whose Pulse part waits for Pulse to see it

    /** For the debug receiver: run on the worker thread, where profile changes belong. */
    fun bgRun(work: () -> Unit) { bg.post(work) }

    override fun applyGameNow(id: String) { bg.post { GameStore.get(this, id)?.let { applyGame(id, it) } } }

    private val watch = object : Runnable {
        override fun run() {
            runCatching {
                // Safe mode: read only. The watcher is stopped rather than skipped a piece, because
                // every branch below it ends in an apply, and an overlay that crash-loops must not be
                // the thing that keeps writing to the device between the crashes.
                // Stepping aside over HandyTuner's own screens changes nothing on the device, so it runs in safe mode too.
                val front0Pkg = front.current()
                main.post { hud?.visibility = if (front0Pkg == packageName) View.GONE else View.VISIBLE }
                val safe = SafeMode.active(this@OverlayService)
                // Cleared in the app: take back what safe mode handed to Pulse (its bar, its all-games default).
                if (safeWas && !safe && pulse.connected) { Log.i(TAG, "safe mode cleared: resuming"); pulse.onConnected() }
                safeWas = safe
                if (safe) return@runCatching
                // "Reset everything to stock", pressed in the app: its settings are already back; drop the rest.
                val r = java.io.File(filesDir, "reset").lastModified()
                if (r != resetStamp) {
                    if (resetStamp != -1L) {
                        runner.forget(); Log.i(TAG, "reset")
                        Tweaks.setLowLatency(this@OverlayService, false)   // Reset means stock: the always-on tweak too
                        ChargeRule.file(this@OverlayService).delete(); bypassed = null   // the app already restored the switch
                        val off = java.io.File(filesDir, PULSE_HUD_OFF)
                        if (off.exists() && pulse.call { it.setOverlayEnabled(true) } == true) off.delete()
                        pulse.call { it.clearFrameCaps() }       // caps outlive restarts; Reset means stock
                        restorePulseDefault()
                        if (pulse.supports(Applied.Feature.ENGINE_SETTINGS)) TUNING_STOCK.forEach { (k, v) -> pulse.call { it.setEngineSetting(k, v) } }
                    }
                    resetStamp = r
                }
                val pkg = front0Pkg
                // Pulse, HandyTuner's own screens and the system UI don't end a game: you're only checking something.
                val neutral = pkg == null || pkg == packageName || pkg == Actions.PULSE || pkg == "com.android.systemui"
                // The game, not just the app: "app.gamenative:SlimeRancher.exe" once the .exe is running.
                val fg = if (neutral) pkg else GameId.of(this@OverlayService, pkg!!)
                val changedProfiles = GameStore.file(this@OverlayService).lastModified().let { m -> (m != profileStamp).also { profileStamp = m } }
                // A switch counts once it holds for two checks (6 s): loading screens flash other activities in
                // front, and GameNative did at launch, which split one session in two.
                val confirmed = if (!neutral && fg != front0) (fg == candidate).also { candidate = fg } else { candidate = null; false }
                if (confirmed) {
                    front0 = fg
                    frontPkg = pkg; loadKeymap()
                    Log.i(TAG, "front: $fg")
                    endSession()
                    if (GameStore.isGame(this@OverlayService, fg!!)) {
                        session = Open(fg, label(fg), System.currentTimeMillis(), battery(), charging())
                        // For the onboarding's "try it" step: which game was spotted, and when.
                        runCatching { java.io.File(filesDir, "last_game").writeText("${System.currentTimeMillis()}\n${label(fg)}\n$fg") }
                        // A new game starts on the default preset, and is saved so it shows on the Games page.
                        val g = GameStore.get(this@OverlayService, fg) ?: GameSettings.of(Preset.NEW_GAME).also { GameStore.set(this@OverlayService, fg, it) }
                        applyGame(fg, g)
                        if (g != GameSettings.of(Preset.NEW_GAME)) Notifier.preset(this@OverlayService, g, label(fg))
                        Log.i(TAG, "session start $fg, ${g.label}: ${g.describe()}")
                    } else runner.clear()
                } else if ((java.io.File(filesDir, "keymaps.json").lastModified() + java.io.File(filesDir, "keymap_settings.properties").lastModified())
                        .let { m -> (m != keymapStamp).also { keymapStamp = m } }) {
                    loadKeymap()
                } else if (pendingApply != null && pendingApply == session?.pkg) {
                    GameStore.get(this@OverlayService, pendingApply!!)?.let { applyGame(pendingApply!!, it) }
                } else if (changedProfiles) {
                    session?.let { o -> GameStore.get(this@OverlayService, o.pkg)?.let { applyGame(o.pkg, it) } }
                }
                ensureTweaks()
                chargeTick()
                Unit
            }.onFailure { Log.w(TAG, "watch: $it") }
            bg.postDelayed(this, 3_000)
        }
    }
    override fun hudShown() = hud != null
    override fun toggleHud() = toggle()

    // --- AFK: black screen, lowest brightness, every button held back until A is pressed 3 times ---
    private var afkView: View? = null
    private val aPresses = ArrayDeque<Long>()
    override fun afkOn() = afkView != null

    override fun startAfk() {
        if (afkView != null) return
        prefs.edit().putBoolean(KEY_AFK, true).apply()
        bg.post { actions.setBrightnessPct(1) }
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xFF000000.toInt())
            addView(TextView(this@OverlayService).apply {
                text = "AFK — press A three times to come back"; textSize = 18f; typeface = nunito; setTextColor(0xFF3A4A60.toInt())
            })
            setOnTouchListener { _, _ -> true }                     // touches are held back too
        }
        getSystemService(WindowManager::class.java).addView(v, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ))
        afkView = v
        Log.i(TAG, "afk on")
    }

    private fun endAfk() {
        afkView?.let { getSystemService(WindowManager::class.java).removeView(it) }
        afkView = null; aPresses.clear()
        prefs.edit().putBoolean(KEY_AFK, false).apply()
        bg.post { Originals.restore(this, listOf("screen_brightness_mode", "screen_brightness")) }
        Log.i(TAG, "afk off")
    }
    private val mono by lazy { runCatching { resources.getFont(R.font.jetbrains_mono_bold) }.getOrDefault(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)) }
    private var styleStamp = -1L

    private val prefs by lazy { getSharedPreferences("overlay", MODE_PRIVATE) }

    fun debugMenu() = main.post { menu.toggle() }
    fun speedUpFromNotification() = bg.post { Notifier.speedUp(this, actions.speedUp()) }
    fun stopRecordingFromNotification() = bg.post { if (actions.recordingPath != null) actions.stopRecording() }

    val pulse by lazy { PulseLink(this) }

    override fun onServiceConnected() {
        instance = this
        // Watchdog first: a service that keeps dying must stop writing before anything else happens,
        // and putting the device back needs the worker this hands the work to.
        val tripped = SafeMode.recordStart(this) && !SafeMode.active(this)
        if (tripped) SafeMode.set(this, true, "the overlay restarted 3 times in 10 minutes")
        // One overlay (D2): Pulse's own bar goes off while HandyTuner's HUD shows its stats. Reset puts it back.
        pulse.onConnected = { bg.post {
            if (SafeMode.active(this)) return@post            // read-only: Pulse keeps its own bar and default
            if (pulse.call { it.setOverlayEnabled(false) } == true) java.io.File(filesDir, PULSE_HUD_OFF).writeText("1")
            outsideGamesDefault()
            main.post { if (hud != null) { hide(); show() } }   // move up into the space Pulse's bar used
        } }
        pulse.connect()
        // A restart (the low-memory killer takes this process too) loses which profile was on, so
        // put back anything a profile left behind, then the watcher re-applies the right one.
        // The watcher runs in safe mode too (it only hides the HUD over HandyTuner's screens then), so that
        // clearing safe mode in the app is noticed without a restart.
        if (!tripped && !SafeMode.active(this) && Tweaks.assistantOff(this)) bg.post {
            val raw = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            Grants.withoutOdinAssistant(raw)?.let { kept ->
                // An empty value would also switch this service off, so only write a non-empty one.
                if (kept.isNotEmpty() && PServer.ok("settings put secure enabled_accessibility_services $kept"))
                    Log.i(TAG, "odin assistant: game watcher switched off")
            }
        }
        if (tripped) bg.post { enterSafeMode(); bg.post(watch) } else bg.post { Originals.restore(this, ProfileRunner.KEYS); bg.post(watch) }
        Log.i(TAG, "connected, pid ${android.os.Process.myPid()}")
        if (prefs.getBoolean(KEY_SHOWN, false)) show()
        Notifier.status(this, hud != null)
        // Killed while AFK: the black screen went with the process, the dim brightness didn't.
        if (!tripped && prefs.getBoolean(KEY_AFK, false)) {
            prefs.edit().putBoolean(KEY_AFK, false).apply()
            bg.post { Originals.restore(this, listOf("screen_brightness_mode", "screen_brightness")) }
        }
    }

    /**
     * Safe mode: undo everything and then change nothing. Runs on the worker.
     *
     * Each step is wrapped separately, because a watchdog that stops at the first failure leaves the
     * device in exactly the half-applied state it was called to avoid. The watcher is not restarted
     * afterwards — nothing is going to be applied again until the owner clears safe mode.
     */
    private fun enterSafeMode() {
        runCatching { actions.setPlayServices(true) }.onFailure { Log.w(TAG, "safemode: play services", it) }
        if (java.io.File(filesDir, "gms_off").exists()) java.io.File(filesDir, "gms_off").delete()
        runCatching { runner.clear() }.onFailure { Log.w(TAG, "safemode: profile", it) }
        runCatching { pulse.call { it.clearFrameCaps() } }.onFailure { Log.w(TAG, "safemode: caps", it) }
        runCatching { restorePulseDefault() }.onFailure { Log.w(TAG, "safemode: all-games default", it) }
        runCatching { Originals.restore(this) }.onFailure { Log.w(TAG, "safemode: settings", it) }
        val off = java.io.File(filesDir, PULSE_HUD_OFF)
        if (off.exists() && pulse.call { it.setOverlayEnabled(true) } == true) off.delete()
        java.io.File(filesDir, "reset").writeText(System.currentTimeMillis().toString())
        Log.w(TAG, "safe mode on: ${SafeMode.why(this)}; device put back, overlay read-only")
        Notifier.safeMode(this)
    }

    private var hudCombo = Hotkey.HUD.default
    private var menuCombo = Hotkey.MENU.default
    private var comboStamp = -1L

    /** Never consumes a key (the game must get every press, the combos included), except in AFK. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (editor.open) return editor.onKey(event)
        if (afkView != null) {
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A && event.action == KeyEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                aPresses.addLast(now); while (aPresses.size > 3) aPresses.removeFirst()
                if (aPresses.size == 3 && now - aPresses.first() < 2_000) main.post { endAfk() }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            // One stat per press: picks up a hotkey recorded in the app without any messaging.
            val m = Hotkey.HUD.file(this).lastModified() + Hotkey.MENU.file(this).lastModified()
            if (m != comboStamp) { comboStamp = m; hudCombo = Hotkey.HUD.load(this); menuCombo = Hotkey.MENU.load(this); held.clear() }
        }
        if (event.keyCode in hudCombo || event.keyCode in menuCombo) when (event.action) {
            // Fires on the press that completes a combo, once per hold.
            KeyEvent.ACTION_DOWN -> if (held.add(event.keyCode)) {
                if (event.keyCode in hudCombo && held.containsAll(hudCombo)) toggle()
                if (event.keyCode in menuCombo && held.containsAll(menuCombo)) main.post { menu.toggle() }
            }
            KeyEvent.ACTION_UP -> held.remove(event.keyCode)
        }
        // A mapped button becomes a tap on the screen, and the game doesn't see the button.
        return !menu.showing && keyPlayer.onKey(event)
    }

    private fun toggle() {
        if (hud == null) show() else hide()
        prefs.edit().putBoolean(KEY_SHOWN, hud != null).apply()
        Log.i(TAG, "combo -> overlay ${if (hud != null) "shown" else "hidden"}")
        Notifier.status(this, hud != null)
    }

    private fun neonText(size: Float) = TextView(this).apply {
        typeface = mono
        textSize = size * style.size.scale
        isSingleLine = true
        setShadowLayer(10f, 0f, 0f, style.accent)    // the glow
    }

    /**
     * Bumped by every show() and hide(). [Handler.removeCallbacks] cannot cancel a tick that is
     * already running, so a tick used to reach its `postDelayed` after the HUD had been hidden and
     * shown again and start a second chain — pressing the hotkey mid-sample doubled the polling
     * rate and then quadrupled it, which also halved the FPS reading. Each chain now carries the
     * generation it was started for and stops when that is stale, which removeCallbacks cannot do.
     */
    private var hudGeneration = 0

    private fun show() {
        if (hud != null) return
        val gen = ++hudGeneration
        style = HudStyle.load(this); styleStamp = HudStyle.file(this).lastModified()
        linePerf = neonText(15f)
        lineNet = neonText(15f)
        lineTemp = neonText(15f)
        lineSys = neonText(15f)
        line3 = neonText(12f)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 14, 28, 14)
            background = GradientDrawable().apply {
                setColor((style.opacity * 255 / 100 shl 24) or 0x050510); cornerRadius = 28f; setStroke(3, style.accent)
            }
            addView(linePerf); addView(lineNet); addView(lineTemp); addView(lineSys); addView(line3)
        }
        val top = style.corner == HudStyle.Corner.TOP_LEFT || style.corner == HudStyle.Corner.TOP_RIGHT
        val left = style.corner == HudStyle.Corner.TOP_LEFT || style.corner == HudStyle.Corner.BOTTOM_LEFT
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or (if (left) Gravity.START else Gravity.END)
            // 140: under stock Pulse's top bar. With the fork its bar is off, so the HUD goes up top.
            x = 24; y = if (top && !pulse.connected) 140 else 40
        }
        getSystemService(WindowManager::class.java).addView(box, lp)
        hud = box; hudLp = lp
        getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(displayListener, main)
        bg.post(tickFor(gen))
    }

    private fun hide() {
        hudGeneration++   // orphans the chain that may be mid-sample: it notices and does not re-post
        getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(displayListener)
        lastDrawn = null
        hud?.let { getSystemService(WindowManager::class.java).removeView(it) }
        hud = null; hudWidth = 0
    }

    private var lastDrawn: Pair<Frame, Bottleneck.Verdict>? = null

    /** The panel switching rate (Pulse's AutoTDP does it) redraws the HUD at once, not at the next tick. */
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayChanged(id: Int) { lastDrawn?.let { (f, v) -> draw(f, v) } }
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
    }

    private fun refreshHz() = getSystemService(android.hardware.display.DisplayManager::class.java)
        .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate?.let { Math.round(it) }

    private fun pulseStats() = pulse.stats()

    /** OLED burn-in: nudge the HUD 1–3 px every few minutes (owner's call, D8); too small to notice. */
    private fun drift(step: Int) {
        val box = hud ?: return; val lp = hudLp ?: return
        val (dx, dy) = DRIFT[step % DRIFT.size]
        lp.x = 24 + dx; lp.y = (if (lp.gravity and Gravity.TOP == Gravity.TOP && !pulse.connected) 140 else 40) + dy
        getSystemService(WindowManager::class.java).updateViewLayout(box, lp)
    }

    private fun battery() = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /**
     * Minutes of battery left. Android 14 has a real prediction API; this Portal is 13, so there the
     * estimate is derived from the charge counter and the present draw instead — µAh × % ÷ µA is
     * hours, so no battery capacity needs to be known.
     *
     * Null rather than a number it can't stand behind: no counter, a negative draw (charging), the
     * platform's own "unknown", or an answer outside 1 min–24 h. That last clamp is deliberate — a
     * charge counter reported ten times over yields a plausible-looking 40-hour estimate, and the
     * clamp is what stops it reaching the HUD.
     */
    private fun batteryLeftMin(): Int? {
        val mins = if (Build.VERSION.SDK_INT >= 34) {
            getSystemService(PowerManager::class.java).getBatteryDischargePrediction()?.toMinutes()
        } else {
            val bm = getSystemService(BatteryManager::class.java)
            val pct = battery()
            val chargeUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            // The sign of CURRENT_NOW is device convention, not platform: this Portal reports
            // -613418 µA while discharging. So take the magnitude and decide "charging" from the
            // status flag, where the charging or full estimate would be meaningless anyway.
            val drawUa = abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()).toInt()
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (!charging && pct > 0 && chargeUah > 0 && drawUa > 0) chargeUah.toLong() * pct / 100 * 60 / drawUa else null
        }
        return mins?.takeIf { it in 1..(24 * 60) }?.toInt()
    }

    /** What Pulse's bar doesn't show. GPU, CPU and temperature are still read, only to pick the verdict. */
    private data class Frame(
        val r: Bottleneck.Reading, val game: Net.Latency?, val net: Net.Latency?, val band: String?, val bars: Int,
        val ramMb: Int?, val batLeftMin: Int?,
        /** The Pulse fork's live stats (IPulseControl.stats); null without the fork or items. */
        val pulse: org.json.JSONObject?,
    )

    private var ticks = 0
    private var lastGame: Net.Latency? = null
    private var lastNet: Net.Latency? = null

    /** Once a second while shown: game server re-picked every 10 s and pinged every 2 s, the NET test
     *  every 60 s. Sampled off the main thread, drawn on it. */
    /** One polling chain per [hudGeneration]. It returns instead of re-posting when its generation
     *  is stale, which is the whole point: a chain whose HUD was hidden mid-sample must not restart. */
    private fun tickFor(gen: Int) = object : Runnable {
        override fun run() {
            if (gen != hudGeneration || hud == null) return
            // Settings changed in the app: rebuild the HUD with the new look (show() reposts the tick).
            if (HudStyle.file(this@OverlayService).lastModified() != styleStamp) { main.post { hide(); show() }; return }
            val r = stats.sample()
            val t = ticks++
            val want = style.shown
            // Hidden items cost nothing: no detection, no pings.
            if (style.compact || HudStyle.Item.GAME in want) { if (t % 10 == 0) net.detect(); if (t % 2 == 0) lastGame = net.pingGame() }
            if ((style.compact || HudStyle.Item.NET in want) && t % 60 == 0) lastNet = net.netTest()
            val wifi = getSystemService(android.net.wifi.WifiManager::class.java)
            @Suppress("DEPRECATION") val info = wifi.connectionInfo
            val band = when (info?.frequency ?: 0) { in 2400..2500 -> "2.4G"; in 4900..5900 -> "5G"; in 5925..7125 -> "6G"; else -> null }
            val bars = if (band == null) 0 else wifi.calculateSignalLevel(info.rssi) * 4 / wifi.maxSignalLevel.coerceAtLeast(1)
            val f = Frame(
                r, lastGame, lastNet, band, bars, stats.freeRamMb(), batteryLeftMin(),
                if (PULSE_ITEMS.any { it in want }) pulseStats() else null,
            )
            // Sampling above takes long enough for hide()/show() to have landed meanwhile. Re-check
            // before re-posting, or this chain survives the HUD that owned it and forks a second one.
            if (gen != hudGeneration || hud == null) return
            if (t % DRIFT_TICKS == 0) main.post { drift(t / DRIFT_TICKS) }
            main.post { draw(f, Bottleneck.of(r)) }
            bg.postDelayed(this, 1_000)
        }
    }

    private fun draw(f: Frame, verdict: Bottleneck.Verdict) {
        if (hud == null) return
        lastDrawn = f to verdict
        var s = SpannableStringBuilder()
        val want = style.shown
        fun part(label: String, value: String?, color: Int) {
            if (s.isNotEmpty()) s.append("   ")
            val a = s.length; s.append(label); s.setSpan(ForegroundColorSpan(DIM), a, s.length, 0)
            s.setSpan(RelativeSizeSpan(0.75f), a, s.length, 0)
            val b = s.length; s.append(value ?: "–"); s.setSpan(ForegroundColorSpan(if (value == null) DIM else color), b, s.length, 0)
        }
        /**
         * Appends a tinted drawable to [sb]. An [ImageSpan] over an object-replacement character is
         * the only way to get an image inside a TextView.
         *
         * ALIGN_CENTER, not the default ALIGN_BASELINE: baseline puts the icon's bottom edge on the
         * baseline, so it reads a touch low beside digits — level with their feet instead of their
         * middle. Centering puts every inline glyph at the same height within the line, which is what
         * keeps the four group icons and the battery one looking like a set. getVerticalOffset() would
         * allow a finer nudge but isn't in the public SDK, so the alignment constant is the lever.
         */
        fun iconInto(sb: SpannableStringBuilder, res: Int, tint: Int) {
            val at = sb.length
            sb.append('￼')
            androidx.core.content.ContextCompat.getDrawable(this@OverlayService, res)?.mutate()?.let { img ->
                img.setTint(tint)
                // Scaled with the text: neonText() multiplies the size by style.size.scale, so a
                // fixed icon would dwarf the line at Small and look undersized at Large.
                val px = (11 * style.size.scale * resources.displayMetrics.density).toInt()
                img.setBounds(0, 0, px, px)
                sb.setSpan(ImageSpan(img, ImageSpan.ALIGN_CENTER), at, at + 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        /** Like [part], but the value leads with an icon instead of a dim text label. */
        fun partIcon(res: Int, tint: Int, value: String?, color: Int) {
            if (s.isNotEmpty()) s.append("   ")
            iconInto(s, res, tint)
            s.append(' ')
            val b = s.length; s.append(value ?: "–")
            s.setSpan(ForegroundColorSpan(if (value == null) DIM else color), b, s.length, 0)
        }

        /**
         * A group line: its name, then its icon, then the readings — or nothing at all when the
         * group drew nothing, so an empty group hides itself instead of leaving a bare label.
         *
         * The icon rides in as an [ImageSpan] over an object-replacement character, which is the only
         * way to get a drawable inline in a TextView. Each group gets its own colour from the accent
         * palette, so the four lines are told apart before you read a single digit.
         */
        fun group(label: String, iconRes: Int, tint: Int, body: CharSequence): CharSequence {
            if (body.isEmpty()) return ""
            val g = SpannableStringBuilder()
            iconInto(g, iconRes, tint)
            g.append(' ')
            val a = g.length; g.append(label)
            g.setSpan(ForegroundColorSpan(DIM), a, g.length, 0)
            g.setSpan(RelativeSizeSpan(0.75f), a, g.length, 0)
            g.append(GROUP_GAP)
            return g.append(body)
        }
        val fps = f.r.fps
        if (style.compact) {
            part("FPS ", fps?.toString(), if ((fps ?: 0) >= 55) GOOD else if ((fps ?: 0) >= 30) WARN else BAD)
            val l = f.game ?: f.net
            part("", l?.ms?.let { "${it}ms" }, if ((l?.loss ?: 0) > 0 || (l?.ms ?: 0) >= 100) BAD else if ((l?.ms ?: 0) >= 50) WARN else GOOD)
            part("", f.band?.let { "$it " + "▂▄▆█".take(f.bars.coerceIn(1, 4)) }, if (f.bars >= 3) GOOD else if (f.bars == 2) WARN else BAD)
            linePerf.text = s; lineNet.text = ""; lineTemp.text = ""; lineSys.text = ""
            linePerf.visibility = android.view.View.VISIBLE; lineNet.visibility = android.view.View.GONE
            lineTemp.visibility = android.view.View.GONE; lineSys.visibility = android.view.View.GONE
            line3.visibility = android.view.View.GONE
            fit(); return
        }
        /**
         * A ping, led by an icon rather than a word. Two pings on one line both reading "42ms" and
         * "12ms" were the confusing part - not which was faster, but which was which. The group was
         * labelled NET and so was the item inside it, so the line read "NET GAME 42ms NET 12ms".
         *
         * Says [none] when there is nothing to measure: no server was ever found for the game.
         */
        fun ping(iconRes: Int, l: Net.Latency?, none: String) {
            if (l?.ms == null) { partIcon(iconRes, DIM, if (l == null) none else "no reply", DIM); return }
            partIcon(iconRes, DIM, "${l.ms}ms" + (l.jitter?.let { " ±$it" } ?: "") + (if (l.loss > 0) " ${l.loss}%lost" else ""),
                if (l.loss > 0 || l.ms >= 100) BAD else if (l.ms >= 50 || (l.jitter ?: 0) >= 15) WARN else GOOD)
        }

        // PERF: what the panel is doing.
        if (HudStyle.Item.FPS in want) part("FPS ", fps?.toString(), if ((fps ?: 0) >= 55) GOOD else if ((fps ?: 0) >= 30) WARN else BAD)
        // The panel's rate right now: Pulse's AutoTDP lowers it to hold its target on this chip (60 Hz for 60 fps).
        if (HudStyle.Item.HZ in want) refreshHz().let { hz -> part("", hz?.let { "${it}Hz" }, if ((hz ?: 0) >= 120) GOOD else if ((hz ?: 0) >= 60) WARN else BAD) }
        f.pulse?.let { p ->
            fun num(k: String) = if (p.has(k) && !p.isNull(k)) p.optDouble(k) else null
            // "12/34%" in a mono font reads as a fraction or a date, and never says which half is
            // which. Named, and each coloured on its own number so the loaded one is obvious.
            if (HudStyle.Item.LOAD in want) {
                fun load(v: Double?) = if ((v ?: 0.0) >= 90) BAD else if ((v ?: 0.0) >= 70) WARN else GOOD
                part("CPU ", num("cpuLoad")?.let { "${it.toInt()}%" }, load(num("cpuLoad")))
                part("GPU ", num("gpuLoad")?.let { "${it.toInt()}%" }, load(num("gpuLoad")))
            }
            if (HudStyle.Item.MODE in want) part("", p.optString("mode").ifEmpty { null }, style.accent)
        }
        linePerf.text = group("PERF ", R.drawable.ic_speed, 0xFF39FF14.toInt(), s)          // lime

        // NET: the two pings and the Wi-Fi under them.
        s = SpannableStringBuilder()
        if (HudStyle.Item.GAME in want) ping(R.drawable.ic_sports_esports, f.game, "no server")
        if (HudStyle.Item.NET in want) ping(R.drawable.ic_public, f.net, "testing…")
        if (HudStyle.Item.WIFI in want) part("", f.band?.let { "$it " + "▂▄▆█".take(f.bars.coerceIn(1, 4)) }, if (f.bars >= 3) GOOD else if (f.bars == 2) WARN else BAD)
        lineNet.text = group("NET  ", R.drawable.ic_wifi, 0xFF00E5FF.toInt(), s)           // cyan

        // TEMP: the readings, with draw next to them - it is what makes heat.
        s = SpannableStringBuilder()
        f.pulse?.let { p ->
            fun num(k: String) = if (p.has(k) && !p.isNull(k)) p.optDouble(k) else null
            fun temp(c: Double?) = if ((c ?: 0.0) >= 90) BAD else if ((c ?: 0.0) >= 80) WARN else GOOD   // red at 90 °C (safety spec)
            if (HudStyle.Item.TEMPS in want) {
                part("CPU ", num("cpuC")?.let { "${it.toInt()}°" }, temp(num("cpuC")))
                part("GPU ", num("gpuC")?.let { "${it.toInt()}°" }, temp(num("gpuC")))
            }
            // Pulse has no draw figure while plugged in, so say what's happening instead of a dash.
            if (HudStyle.Item.WATTS in want) part("", num("watts")?.let { "%.1fW".format(it) } ?: "charging".takeIf { p.optBoolean("charging") }, style.accent)
        }
        lineTemp.text = group("TEMP ", R.drawable.ic_thermostat, 0xFFFF8A00.toInt(), s)    // orange, for heat

        // SYS: the device itself.
        s = SpannableStringBuilder()
        val ram = f.ramMb
        if (HudStyle.Item.RAM in want) part("RAM ", ram?.let { if (it >= 1024) "%.1fG".format(it / 1024.0) else "${it}M" },
            if ((ram ?: 0) >= 1500) GOOD else if ((ram ?: 0) >= 700) WARN else BAD)
        if (HudStyle.Item.SESSION in want) {
            val m = f.batLeftMin
            partIcon(R.drawable.ic_battery_horiz_075, style.accent,
                m?.let { if (it >= 60) "${it / 60}h${if (it % 60 > 0) "${it % 60}m" else ""}" else "${it}m" }, style.accent)
        }
        if (HudStyle.Item.CLOCK in want) part("", java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), 0xFFFFFFFF.toInt())
        lineSys.text = group("SYS  ", R.drawable.ic_memory, 0xFFFF2BD6.toInt(), s)         // magenta

        // "Warm — holding cap" is a warning that is also good news, so it gets amber, not the alarm magenta
        // the real problems get. It fires whenever the smoothed chip temp passes the line, which is
        // often enough that painting it red every time trains you to ignore the red ones.
        val fine = verdict == Bottleneck.Verdict.NONE || verdict == Bottleneck.Verdict.CAP
        line3.text = (if (fine) "▸ " else "⚠ ") + verdict.text
        line3.setTextColor(if (fine) DIM else if (verdict == Bottleneck.Verdict.HOT_OK) WARN else MAGENTA)
        for (l in listOf(linePerf, lineNet, lineTemp, lineSys)) {
            l.visibility = if (l.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
        line3.visibility = if (HudStyle.Item.BOTTLENECK in want) android.view.View.VISIBLE else android.view.View.GONE
        fit()
    }

    /**
     * WRAP_CONTENT overlay windows are measured like dialogs: a narrow width is tried first, and
     * single-line text clips instead of asking for more, so the box stuck at ~320 dp. Size it
     * from the widest line instead.
     */
    private fun fit() {
        val box = hud ?: return
        val w = listOf(linePerf, lineNet, lineTemp, lineSys, line3).filter { it.visibility == android.view.View.VISIBLE }
            .maxOfOrNull { it.paint.measureText(it.text.toString()) }?.toInt()?.plus(box.paddingLeft + box.paddingRight + 8) ?: 0
        if (w != hudWidth) {
            hudWidth = w
            hudLp?.let { it.width = w; getSystemService(WindowManager::class.java).updateViewLayout(box, it) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        pulse.disconnect()
        hide(); menu.hide(); keyView.hide(); if (afkView != null) endAfk()
        bg.removeCallbacks(watch)
        // PServer and the battery are blocking binder calls — `runner.clear()` restores every saved
        // key through them, and `endSession()` reads the battery twice — so doing either here holds
        // the main thread for as long as pservice takes to answer, which is long enough to trip an
        // ANR. The worker is quit with quitSafely() just below, which runs what is already queued, so
        // both still happen, only off the main thread. A kill that never reaches onDestroy loses this
        // last session row, which it would have lost anyway.
        bg.post { endSession(); runner.clear() }
        worker.quitSafely()
        super.onDestroy()
    }

    companion object {
        /** What "Reset everything to stock" puts the Tuning page back to: the engine's own defaults. */
        val TUNING_STOCK = listOf("sleep" to "off", "rgbMode" to "OFF", "fanTargetC" to "78", "autoTdpBias" to "EFFICIENT")
        /** For the debug-build receiver only. */
        @Volatile var instance: OverlayService? = null
        private const val TAG = "HandyTuner"
        private val TIER_KEYS = listOf("MAX", "BALANCED", "POWER_SAVING")
        private const val PULSE_DEFAULT_ORIG = "pulse_default_orig"   // Pulse's all-games default before HandyTuner
        private const val PULSE_HUD_OFF = "pulse_hud_off"     // HandyTuner switched Pulse's bar off; Reset turns it back on
        private val PULSE_ITEMS = setOf(HudStyle.Item.TEMPS, HudStyle.Item.WATTS, HudStyle.Item.LOAD, HudStyle.Item.MODE)
        private const val DRIFT_TICKS = 180                     // ticks are 1 s: every 3 minutes
        private val DRIFT = listOf(0 to 0, 2 to 1, 3 to 3, 1 to 2, -1 to 3, -2 to 1)
        private const val KEY_SHOWN = "shown"
        private const val KEY_AFK = "afk"
        private const val MAGENTA = 0xFFFF2BD6.toInt()
        private const val GOOD = 0xFF39FF14.toInt()
        private const val WARN = 0xFFFFB300.toInt()
        private const val BAD = 0xFFFF3B3B.toInt()
        private const val DIM = 0xFF7A8BA0.toInt()
    }
}
