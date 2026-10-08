// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The Quick Menu, laid out as docs/design/board-1.png section 2: slides in from
 * the right over the game, D-pad + A to use, B to close. Plain Views, because it
 * lives in the small :overlay process.
 *
 * One "Open Pulse" button only (the Pulse Status tile), by the owner's call: the
 * board had a second one on Performance & Fan.
 */
class QuickMenu(
    private val svc: AccessibilityService,
    private val actions: Actions,
    private val bg: Handler,
    private val host: Host,
    private val nunito: Typeface,
    private val mono: Typeface,
) {
    interface Host {
        fun hudShown(): Boolean; fun toggleHud(); fun startAfk(); fun afkOn(): Boolean
        fun runner(): ProfileRunner; fun currentGame(): String?
        fun frontPkg(): String?; fun keyCount(): Int; fun openKeyEditor()
        fun pulse(): PulseLink
        /** Apply a game's saved settings now (on the worker thread, after anything already queued). */
        fun applyGameNow(id: String)
        /** The TV while docked to one, where the menu opens; null for the Odin's own screen. */
        fun tvScreen(): android.view.Display?
    }

    private val main = Handler(Looper.getMainLooper())
    /** The screen the open menu is on. */
    private var wm: WindowManager? = null
    private var root: View? = null
    val showing get() = root != null

    private data class State(
        val pulseInstalled: Boolean, val pulseRunning: Boolean, val perf: Int?, val fan: Int?,
        val brightness: Int, val volume: Int, val hz: Int?, val cpuC: Int?, val fanPct: Int?, val layout: Int,
        /** The Pulse fork's live state; null without the fork (stock Pulse, or none). */
        val fork: org.json.JSONObject?,
    )

    fun toggle() = if (showing) hide() else open()

    /** Reads everything off the main thread first, then builds. */
    fun open() {
        if (showing) return
        bg.post {
            // Pulse's CPU temp, not our own: it is the same number the HUD shows and the one Pulse's
            // thermal policy actually throttles on (AutoTuneController reads cpuss-0). Stats.cpuTemp()
            // is the hottest of four core zones instead, so the two moved independently and the menu
            // looked like it was contradicting the HUD. Ours is the fallback for no fork.
            val pulseC = host.pulse().stats()?.optInt("cpuC", -1)?.takeIf { it > 0 }
            val cpuC = pulseC ?: Stats().cpuTemp()
            Log.i("HandyTuner", "menu cpuC=$cpuC from=${if (pulseC != null) "pulse" else "stats"}")
            val s = State(actions.pulseInstalled(), actions.pulseInstalled() && actions.pulseRunning(), actions.perfMode(), actions.fanMode(),
                actions.brightnessPct(), actions.volumePct(), actions.refreshHz(), cpuC, actions.fanPct(), actions.buttonLayout(),
                host.pulse().state())
            main.post { if (!showing) build(s) }
        }
    }

    fun hide() {
        root?.let { v -> runCatching { wm?.removeView(v) } }   // the TV may be gone already
        root = null; wm = null
    }

    // --- building blocks ---------------------------------------------------------

    /** 0.8: the whole menu scaled to fit one 1080p screen, like the board. */
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v * 0.8f, svc.resources.displayMetrics).toInt()

    /** How far the glow reaches outside a shape. Paddings add it so content sits inside the outline. */
    private val g by lazy { dp(3) }

    private fun shape(fill: Int, stroke: Int, width: Int, radius: Int = 12, glowAlpha: Int = 110) =
        GlowDrawable(fill, stroke, width.toFloat(), dp(radius).toFloat(), g.toFloat(), glowAlpha)

    /** Focus glow: a bright 3 dp outline and a strong halo on what the D-pad is on, a soft one otherwise. */
    private fun focusBg(fill: Int = TILE, rest: Int = BLUE_DIM, focused: Int = BLUE, radius: Int = 12) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(fill, focused, dp(3), radius, 255))
        addState(intArrayOf(android.R.attr.state_pressed), shape(fill, focused, dp(3), radius, 255))
        addState(intArrayOf(), shape(fill, rest, dp(1), radius))
    }

    private fun text(s: String, size: Float = 15f, color: Int = WHITE, bold: Boolean = true) = TextView(svc).apply {
        text = s; textSize = size * 0.86f; setTextColor(color); typeface = if (bold) nunito else Typeface.create(nunito, 600, false)
        if (bold) setShadowLayer(10f, 0f, 0f, 0x660389FB)       // the board's faint glow on titles
    }

    /** GlowDrawable blurs, and blur needs a software layer. [color]/[elev] are kept for call-site readability. */
    @Suppress("UNUSED_PARAMETER")
    private fun <T : View> T.glow(color: Int = BLUE, elev: Int = 8): T = apply { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }

    private fun icon(res: Int, size: Int = 26, tint: Int = BLUE) = ImageView(svc).apply {
        setImageResource(res); setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    private fun hbox(vararg v: View, gap: Int = 2) = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, c -> addView(c); if (i > 0) (c.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(gap) }
    }

    private fun vbox(vararg v: View) = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; v.forEach { addView(it) } }

    /** A tile: navy glass with the blue outline. Focusable and clickable when it does something. */
    private fun tile(onClick: (() -> Unit)?, vararg body: View) = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12) + g, dp(6) + g, dp(12) + g, dp(6) + g)
        background = if (onClick != null) focusBg() else shape(TILE, BLUE_DIM, dp(1))
        if (onClick != null) { isFocusable = true; isClickable = true; setOnClickListener { onClick() } }
        body.forEach { addView(it) }
        glow(elev = 6)
    }

    /** Icon, title and a status line, like every tile on the board. */
    private fun heading(iconRes: Int, title: String, sub: String?, subColor: Int = BLUE, trailing: View? = null) = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(icon(iconRes, 24))
        addView(vbox(text(title, 14f).apply { isSingleLine = true },
            *(if (sub != null) arrayOf<View>(text(sub, 12f, subColor, bold = false).apply { isSingleLine = true }) else emptyArray()))
            .apply { layoutParams = lp(0, weight = 1f).apply { leftMargin = dp(10) } })
        trailing?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    /** The board's toggle: blue track when on. Shown only; the tile around it is what's pressed. */
    private fun switch(on: Boolean) = Switch(svc).apply {
        isChecked = on; isFocusable = false; isClickable = false
        thumbTintList = ColorStateList.valueOf(WHITE)
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(BLUE, 0xFF3A4660.toInt()))
    }

    /** Slider row: icon + label, then a blue bar. D-pad left/right moves it; applied a moment after it stops. */
    private fun slider(iconRes: Int, label: String, value: Int, apply: (Int) -> Unit): View {
        val bar = SeekBar(svc).apply {
            max = 100; progress = value; keyProgressIncrement = 5
            progressTintList = ColorStateList.valueOf(BLUE); thumbTintList = ColorStateList.valueOf(WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF2A3654.toInt())
            background = focusBg(0x00000000, rest = 0x00000000, radius = 50)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            val commit = Runnable { bg.post { apply(progress) } }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) { main.removeCallbacks(commit); main.postDelayed(commit, 250) }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        return vbox(hbox(icon(iconRes, 20), text(label, 14f), gap = 8).apply { gravity = Gravity.CENTER_VERTICAL }, bar)
    }

    /** Green-outlined preset pill; filled brighter when on. */
    private fun preset(iconRes: Int, label: String, on: Boolean, onClick: () -> Unit) = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10) + g, dp(6) + g, dp(10) + g, dp(6) + g)
        background = focusBg(if (on) 0x5539FF14 else 0x1A39FF14, rest = 0xB339FF14.toInt(), focused = GOOD, radius = 10)
        isFocusable = true; isClickable = true; setOnClickListener { onClick() }
        addView(icon(iconRes, 20, GOOD)); addView(text("  $label", 14f, GOOD).apply { isSingleLine = true }.apply { setShadowLayer(10f, 0f, 0f, 0x8839FF14.toInt()) })
        layoutParams = lp().apply { topMargin = dp(2) }
        glow(GOOD, 4)
    }

    /** One half of the 60 / 120 Hz segmented control. */
    private fun segment(label: String, selected: Boolean, onClick: () -> Unit) = text(label, 13f).apply {
        isSingleLine = true; gravity = Gravity.CENTER; setPadding(g, dp(6) + g, g, dp(6) + g)
        background = focusBg(if (selected) 0x550389FB else TILE_2, rest = if (selected) BLUE else BLUE_DIM, radius = 8)
        isFocusable = true; isClickable = true; setOnClickListener { onClick() }
        layoutParams = lp(0, weight = 1f)
        glow(elev = 4)
    }

    private fun circled(letter: String) = text(letter, 12f).apply {
        gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), WHITE) }
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
    }

    /** Run on the worker, then rebuild so the menu shows the new state. */
    private fun doThen(work: () -> Unit) = bg.post { work(); main.post { hide(); open() } }

    /** A Pulse fork command: Pulse applies it on its own coroutine, so give it a moment before re-reading. */
    private fun pulseThen(cmd: (com.kei.pulse.control.IPulseControl) -> Boolean) = doThen {
        if (host.pulse().call(cmd) == true) Thread.sleep(350) else main.post { toast("Pulse didn't take that") }
    }

    private fun toast(s: String) = Toast.makeText(svc, s, Toast.LENGTH_SHORT).show()

    // --- the menu ----------------------------------------------------------------

    /** Unscaled, so the scaled-down contents get room and nothing wraps. */
    private val panelWidth by lazy { TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 400f, svc.resources.displayMetrics).toInt() }

    private fun build(s: State) {
        val perfName = Actions.PERF_NAMES[s.perf] ?: "–"
        val fanName = Actions.FAN_NAMES[s.fan] ?: "–"
        val half = { v: View -> v.apply { layoutParams = lp(0, weight = 1f) } }
        // Safe mode: show the reason and nothing else. The tiles are not greyed out one by one —
        // every one of them is a write, and a half-greyed menu in an app that just put the device
        // back would invite the owner to find the one that isn't.
        val safe = SafeMode.active(svc)

        // Header: HandyHelper, the wordmark, and the close button.
        val close = FrameLayout(svc).apply {
            background = focusBg(0x00000000, rest = WHITE_DIM, focused = BLUE, radius = 8)
            isFocusable = true; isClickable = true; setOnClickListener { hide() }
            glow()
            addView(icon(R.drawable.ic_close, 22, WHITE).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            })
            layoutParams = LinearLayout.LayoutParams(dp(34) + 2 * g, dp(34) + 2 * g)
        }
        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            // Transparent cutout: the launcher icon's black square looked wrong on the navy panel.
            addView(ImageView(svc).apply { setImageResource(R.drawable.mascot); layoutParams = LinearLayout.LayoutParams(dp(64), dp(28)) })   // his 2.33:1 shape
            addView(text("  Handy", 21f)); addView(text("Tuner", 21f, BLUE).apply { layoutParams = lp(0, weight = 1f) })
            addView(close)
        }

        // Performance & Fan: a status tile. Pulse's while it runs; otherwise AYN's own modes, cycled by A.
        // Live, read-only: how hot and how hard the fan works, filling the tile without touching Pulse's job.
        fun live(icon: Int, label: String, value: String, color: Int) = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0)
            addView(icon(icon, 20, MUTED)); addView(text("  $label", 13f, MUTED, bold = false).apply { layoutParams = lp(0, weight = 1f) })
            addView(TextView(svc).apply { text = value; textSize = 17f; typeface = mono; setTextColor(color) })
        }
        val tempColor = s.cpuC?.let { if (it < 75) GOOD else if (it < 88) 0xFFFFB300.toInt() else 0xFFFF3B3B.toInt() } ?: MUTED
        val liveRows = arrayOf<View>(
            live(R.drawable.ic_speed, "CPU", s.cpuC?.let { "$it °C" } ?: "–", tempColor),
            live(R.drawable.ic_mode_fan, "Fan", s.fanPct?.let { "$it%" } ?: "–", WHITE),
        )
        val link = host.pulse()
        val fork = s.fork
        // No state from the engine: say why (not started yet, or its watcher is asleep) instead of offering controls.
        if (fork == null) Log.i("HandyTuner", "menu perf: connected=${link.connected} v${link.version} empty=${link.emptyState} problem=${link.problem()}")
        val perfTile = if (fork != null) pulseControls(fork, liveRows, host.currentGame())
        else tile(null, heading(R.drawable.ic_speed, "Performance & Fan", "$perfName • $fanName"),
            *liveRows, text((link.problem() ?: "PULSE isn't answering").replaceFirstChar { it.uppercase() },
                12f, MUTED, bold = false).apply { setPadding(0, dp(8), 0, 0) })

        // Brightness, volume, refresh rate.
        val refresh = hbox(*listOf(60, 120).map { hz ->
            segment("$hz Hz", s.hz == hz) { if (s.pulseInstalled) toast("Refresh rate is set by Pulse") else doThen { actions.setRefreshHz(hz) } }
        }.toTypedArray(), gap = 0)
        val screenTile = tile(null,
            slider(R.drawable.ic_light_mode, "Brightness", s.brightness) { actions.setBrightnessPct(it) },
            slider(R.drawable.ic_volume_up, "Volume", s.volume) { actions.setVolumePct(it) },
            text("Refresh Rate", 13f).apply { setPadding(0, dp(2), 0, dp(4)) }, refresh)

        // Quick settings apply now, for this game session only; the Preset tile below saves one for the game.
// Named apart on purpose: they were both "Presets", and one is forgotten when the game closes.
        val runner = host.runner()
        fun presetToggle(p: Profile) = doThen { runner.apply(if (runner.active == p) Profile.NORMAL else p) }
        val presetsTile = tile(null, heading(R.drawable.ic_tune, "Quick settings", "This session only"),
            preset(R.drawable.ic_battery_saver, "Battery Saver", runner.active == Profile.BATTERY_SAVER) { presetToggle(Profile.BATTERY_SAVER) },
            preset(R.drawable.ic_timer, "Low Latency", runner.active == Profile.LOW_PING) { presetToggle(Profile.LOW_PING) })

        val recording = actions.recordingPath != null
        fun capture(iconRes: Int, label: String, onClick: () -> Unit) = tile(onClick,
            icon(iconRes, 28).apply { (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL },
            text(label, 13f).apply { isSingleLine = true; gravity = Gravity.CENTER; layoutParams = lp().apply { topMargin = dp(4) } })
        val captureRow = hbox(
            half(capture(R.drawable.ic_photo_camera, "Screenshot") { hide(); main.postDelayed({ bg.post { actions.screenshot() } }, 400) }),
            half(capture(R.drawable.ic_videocam, if (recording) "Stop" else "Record") {
                if (recording) doThen { actions.stopRecording() } else { hide(); bg.post { actions.startRecording() } }
            }), gap = 0)

        // Preset for the game in front (D14/D16): A cycles the built-ins, then the owner's own presets.
        val game = host.currentGame()
        val saved = game?.let { GameStore.get(svc, it) } ?: GameSettings.of(Preset.NEW_GAME)
        val profileTile = if (game == null) tile(null, heading(R.drawable.ic_person, "Preset", noGame(), MUTED)).apply { alpha = 0.6f }
        else tile({
            val all = Preset.entries.map { it.def } + CustomPresets.all(svc)
            val next = all[(all.indexOfFirst { it.key == saved.preset.key } + 1) % all.size]
            doThen { GameStore.set(svc, game, GameSettings.of(next)); host.applyGameNow(game) }
        }, heading(R.drawable.ic_person, "Preset", "${GameId.label(game) ?: game.substringAfterLast('.')} · ${saved.label}"))
        val hudOn = host.hudShown()
        val hudTile = tile({ host.toggleHud(); hide(); open() },
            heading(R.drawable.ic_desktop_windows, "HUD", if (hudOn) "On" else "Off", if (hudOn) CYAN else MUTED, switch(hudOn)))

        val afkTile = tile({ hide(); host.startAfk() },
            heading(R.drawable.ic_bedtime, "AFK Mode", "Dim • lock buttons", MUTED, switch(host.afkOn())))
        // Key mapping: only for Android games; Windows games and emulators already speak controller.
        val mappable = KeyMapper.canMap(host.frontPkg())
        val keyTile = if (mappable) tile({ host.openKeyEditor() },
            heading(R.drawable.ic_sports_esports, "Key Mapping", host.keyCount().let { if (it == 0) "Map buttons to taps" else "$it button${if (it == 1) "" else "s"} mapped" }))
        else tile(null, heading(R.drawable.ic_sports_esports, "Key Mapping", "Android games only", MUTED)).apply { alpha = 0.6f }

        // Odin Assistant's "Speed up" and the A/B swap.
        val speedTile = tile({ hide(); bg.post {
            val mb = actions.speedUp()
            if (Notifier.allowed(svc)) Notifier.speedUp(svc, mb) else main.post { toast("Speed Up: freed $mb MB") }
        } },
            heading(R.drawable.ic_speed, "Speed Up", "Close background apps"))
        val abTile = tile({ doThen { actions.setButtonLayout(if (s.layout == 1) 0 else 1) } },
            heading(R.drawable.ic_sports_esports, "A/B Buttons", if (s.layout == 1) "Xbox (A bottom)" else "Swapped (A right)"))

        // Pulse Status, with the only Open Pulse button.
        val fanCircle = FrameLayout(svc).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), BLUE); setColor(0x220389FB) }
            glow(elev = 8)
            addView(icon(R.drawable.ic_mode_fan, 30).apply { layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER) })
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val status = when { !s.pulseInstalled -> "Not installed"; s.pulseRunning -> "Running"; else -> "Not running" }
        val openPulse = text("Pulse settings", 16f).apply {
            gravity = Gravity.CENTER; setPadding(dp(18) + g, dp(7) + g, dp(18) + g, dp(7) + g)
            background = focusBg(BLUE, rest = BLUE, focused = WHITE, radius = 10)
            isFocusable = true; isClickable = true; setOnClickListener { hide(); actions.openPulse() }
        }.glow(elev = 10)
        val pulseTile = tile(null, LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(fanCircle)
            addView(vbox(text("Pulse Status", 16f), text("", 13f).apply {
                isSingleLine = true
                text = android.text.SpannableStringBuilder(status).apply {
                    setSpan(android.text.style.ForegroundColorSpan(if (s.pulseRunning) GOOD else MUTED), 0, length, 0)
                    if (s.pulseRunning) append("  •  $perfName")
                }
                setTextColor(MUTED)
            }).apply { layoutParams = lp(0, weight = 1f).apply { leftMargin = dp(14) } })
            if (s.pulseInstalled) addView(openPulse)
        })

        val footer = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), 0)
            addView(icon(R.drawable.ic_add_circle, 22, WHITE)); addView(text("  D-Pad: Navigate", 13f).apply { layoutParams = lp(0, weight = 1f) })
            addView(circled("A")); addView(text(" : Select", 13f).apply { layoutParams = lp(0, weight = 1f) })
            addView(circled("B")); addView(text("  Back", 13f))
        }

        val gap = { v: View -> v.apply { (layoutParams as? LinearLayout.LayoutParams ?: lp()).also { it.topMargin = dp(2); layoutParams = it } } }
        // Two tabs (owner's call, docs/decisions.md D8): Game for play, Device for the handheld. L1/R1 switch.
        val gameTab = vbox(
            gap(perfTile),
            gap(hbox(half(profileTile), half(hudTile))),
            gap(hbox(half(afkTile), half(captureRow))),
            gap(pulseTile),
        )
        val deviceTab = vbox(
            gap(hbox(half(screenTile), half(presetsTile).apply { layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT })),
            gap(hbox(half(speedTile), half(abTile))),
            gap(keyTile),
        )
        val tabs = listOf(gameTab, deviceTab)
        lateinit var tabBar: LinearLayout
        fun showTab(i: Int) {
            tab = i
            tabs.forEachIndexed { n, t -> t.visibility = if (n == i) View.VISIBLE else View.GONE }
            tabBar.removeAllViews()
            listOf("Game", "Device").forEachIndexed { n, name -> tabBar.addView(segment(name, n == i) { showTab(n) }) }
        }
        tabBar = hbox(gap = 0).apply { layoutParams = lp().apply { topMargin = dp(4) } }
        switchTab = { showTab(1 - tab); tabs[tab].focusFirst() }
        showTab(tab)
        val safeNote = vbox(
            tile(null,
                heading(R.drawable.ic_build, "Safe mode", SafeMode.why(svc).replaceFirstChar { it.uppercase() }, MUTED),
                text("Everything HandyTuner changed has been put back, and it won't change anything else until you clear this.\n\nClear it in the app: Diagnostics → Safe mode.",
                    13f).apply { setTextColor(WHITE_DIM) })
        ).apply { layoutParams = lp().apply { topMargin = dp(8) } }
        val content = if (safe) vbox(header, safeNote, footer) else vbox(header, tabBar, *tabs.toTypedArray(), footer)
            .apply { setPadding(dp(14), dp(4), dp(14), dp(2)) }

        val panel = object : LinearLayout(svc) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BUTTON_B || e.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (e.action == KeyEvent.ACTION_UP) hide()
                    return true
                }
                if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || e.keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
                    if (e.action == KeyEvent.ACTION_UP) switchTab()
                    return true
                }
                return super.dispatchKeyEvent(e)
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.action == MotionEvent.ACTION_OUTSIDE) { hide(); return true }
                return super.onTouchEvent(e)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GlowDrawable(0xF0040914.toInt(), BLUE, dp(2).toFloat(), dp(14).toFloat(), dp(10).toFloat(), 200)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            // Room around the tiles so their glow isn't clipped at the edges.
            addView(ScrollView(svc).apply { addView(content); isVerticalScrollBarEnabled = false; clipToPadding = false })
            glow(elev = 16)
        }
        val lp = WindowManager.LayoutParams(
            panelWidth, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.TOP }
        // Docked to a TV, the menu opens there, and the controller's buttons follow it (it takes the focus).
        wm = host.tvScreen()?.let { d -> runCatching {
            svc.createDisplayContext(d).getSystemService(WindowManager::class.java).also { it.addView(panel, lp) }
        }.onFailure { Log.w("HandyTuner", "menu on the TV: $it") }.getOrNull() }
            ?: svc.getSystemService(WindowManager::class.java).also { it.addView(panel, lp) }
        root = panel
        panel.translationX = panelWidth.toFloat()
        panel.animate().translationX(0f).setDuration(180).start()
        panel.post { tabs[tab].focusFirst() }
    }

    /**
     * Why there's no game: nothing open, or an app HandyTuner doesn't count as a game (it doesn't say it's one,
     * like a Godot game without appCategory="game"). "Open a game first" read as wrong while a game was running.
     */
    private fun noGame(): String {
        val front = host.frontPkg() ?: return "Open a game first"
        val home = svc.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return if (front == home || front == svc.packageName) "Open a game first" else "Add this app on the Games page"
    }

    private var tab = 0          // the tab used last (owner's call: the menu opens on it)
    private var switchTab: () -> Unit = {}

    private fun View.focusFirst() { focusSearch(View.FOCUS_DOWN)?.requestFocus() ?: requestFocus() }

    /**
     * Pulse controls through the fork (docs/feature-registry.md F2). Pulse treats AutoTDP and a fixed tier as
     * one choice per game, so they share one row; changes go to the game in front (Pulse's per-game scope).
     */
    private fun pulseControls(f: org.json.JSONObject, liveRows: Array<View>, game: String?): View {
        val gameOpen = game != null
        // Every change is also saved to the game (D14: menu tweaks save on top of its preset).
        fun tweak(cmd: (com.kei.pulse.control.IPulseControl) -> Boolean, change: (PulsePart) -> PulsePart) = pulseThen { c ->
            cmd(c).also { ok -> if (ok && game != null) runCatching {
                val cur = GameStore.get(svc, game) ?: GameSettings.of(Preset.NEW_GAME)
                GameStore.set(svc, game, cur.copy(pulse = change(cur.pulse)))
            } }
        }
        val binding = f.optString("binding").takeIf { f.has("binding") && !f.isNull("binding") }
        val auto = f.optBoolean("autoTdp")
        val fps = f.optInt("fps", 60)
        val tierPick = binding?.removePrefix("tier:")?.takeIf { binding.startsWith("tier:") }
        val fan = if (f.isNull("fanMode")) null else f.optInt("fanMode")
        val options = f.optJSONArray("fpsOptions")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
        val tiers = listOf("MAX" to "Max", "BALANCED" to "Balanced", "POWER_SAVING" to "Saver")
        val capNow = f.optInt("frameCap")
        val mode = when {
            tierPick != null -> (tiers.firstOrNull { it.first == tierPick }?.second ?: "Custom") + (if (capNow > 0) " · cap $capNow" else "")
            auto -> "Auto $fps fps"; else -> "Stock"
        }
        fun row(vararg v: View) = hbox(*v, gap = 0).apply { layoutParams = lp().apply { topMargin = dp(8) } }
        val modeRow = row(segment("Auto", tierPick == null && auto) { tweak({ it.setAutoTdp(true, fps) }) { PulsePart(fps, null, 0, it.fan) } },
            *tiers.mapIndexed { i, (key, name) -> segment(name, tierPick == key) {
                tweak({ it.setTier(i) }) { p -> PulsePart(null, i, if (p.tier != null) p.cap else 0, p.fan) }
            } }.toTypedArray())
        val fpsRow = if (tierPick == null && auto && options.isNotEmpty())
            row(*options.map { o -> segment("$o fps", o == fps) { tweak({ it.setAutoTdp(true, o) }) { PulsePart(o, null, 0, it.fan) } } }.toTypedArray())
        // With a fixed tier, a frame cap (F5): only rates that divide the panel's, since Android floors the others
        // (90 → 60 on a 120 Hz panel). In Auto the target above already paces frames.
        else if (tierPick != null) {
            val link = host.pulse()
            // The fork gained setFrameCap in v3. Against an older one the call fails inside Pulse and
            // looks exactly like the cap not working, so the row is greyed with the reason instead.
            if (!link.supports(Applied.Feature.FRAME_CAP))
                text("Frame cap needs the HandyTuner Pulse fork v${Applied.needsVersion(Applied.Feature.FRAME_CAP)}+ — ${link.problem() ?: "this one is v${link.version}"}", 13f).apply {
                    setTextColor(MUTED); setPadding(0, dp(6), 0, dp(2))
                }
            else {
            // DisplayManager, not svc.display: a service isn't a visual context, and that threw on the Odin.
            val max = svc.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.supportedModes?.maxOfOrNull { it.refreshRate }?.toInt() ?: 120
            val cap = f.optInt("frameCap")
            row(segment("No cap", cap == 0) { tweak({ it.setFrameCap(0) }) { p -> p.copy(cap = 0) } },
                *listOf(30, 40, 60).filter { max % it == 0 && it < max }.map { c -> segment("Cap $c", cap == c) { tweak({ it.setFrameCap(c) }) { p -> p.copy(cap = c) } } }.toTypedArray())
            }
        } else null
        val fans = listOf(Actions.FAN_QUIET, Actions.FAN_SMART, Actions.FAN_SPORT, Actions.FAN_CUSTOM)
        val fanRow = row(*fans.map { v -> segment(Actions.FAN_NAMES.getValue(v), fan == v) { tweak({ it.setFanMode(v) }) { p -> p.copy(fan = v) } } }.toTypedArray())
        val fanName = fan?.let { Actions.FAN_NAMES[it] } ?: "Pulse's fan"
        // Mode and FPS are saved for the game in front, so without a game they'd land on the home screen.
        if (!gameOpen) return tile(null, heading(R.drawable.ic_speed, "Performance & Fan", "${noGame().replace("Open a game first", "Open a game")} to change its mode • $fanName"),
            fanRow, *liveRows)
        return tile(null, heading(R.drawable.ic_speed, "Performance & Fan", "$mode • $fanName"),
            modeRow, *listOfNotNull(fpsRow).toTypedArray(), fanRow, *liveRows)
    }

    companion object {
        private const val BLUE = 0xFF0389FB.toInt()
        private const val BLUE_DIM = 0x800389FB.toInt()
        private const val CYAN = 0xFF00E5FF.toInt()
        private const val TILE = 0xCC0A1428.toInt()      // navy glass
        private const val TILE_2 = 0xFF111E38.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val WHITE_DIM = 0x99FFFFFF.toInt()
        private const val MUTED = 0xFF7A8BA0.toInt()
        private const val GOOD = 0xFF39FF14.toInt()
    }
}
