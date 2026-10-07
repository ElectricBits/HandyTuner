# HandyTuner user guide

Everything HandyTuner does, page by page, in plain words. HandyTuner is a beta tested on the **AYN Odin 2 Portal
(Android 13)** only.

**Contents:** [Setup](#setup) · [Hotkeys](#hotkeys) · [Home](#home) · [Quick Menu](#quick-menu) · [HUD](#hud) ·
[Games and presets](#games-and-presets) · [Battery](#battery) · [Network](#network) · [Tweaks](#tweaks) ·
[Controller](#controller) · [Diagnostics](#diagnostics) · [Safety](#safety-safe-mode-and-reset) · [FAQ](#faq-and-troubleshooting)

---

## Setup

The first time you open HandyTuner, **HandyHelper** walks you through setup. Press **A** to move on and **B** to go back.

He asks for three permissions. Each step has a button that opens the right page in Android's Settings:

| Permission | What HandyTuner uses it for |
|---|---|
| **Accessibility service** ("Overlay & hotkeys") | Drawing the HUD and Quick Menu over games, and noticing the button combos that open them. |
| **Usage access** | Knowing which game is in front, so it can apply that game's preset. |
| **Notifications** | The status notification, and short notices like "screenshot saved". |

> **Accessibility switch grayed out?** Android 13 locks it for apps installed from outside the Play Store. Open
> **Settings → Apps → HandyTuner**, tap **⋮ → Allow restricted settings**, then go back and switch it on. Setup
> offers a button for this.

Then he checks that the Odin's built-in system service answers (that's how HandyTuner works without root), offers
**Cocoon** (a launcher that tells HandyTuner exactly which game you start, even Windows games), lets you pick the HUD's
color, and has you try both hotkeys. You can run setup again any time from **Diagnostics → Run setup again**.

---

## Hotkeys

| Hotkey | What it does |
|---|---|
| **Both back buttons + both sticks pressed in** | Show or hide the HUD |
| **Both sticks pressed in + R1** | Open the Quick Menu |

They work in any game. Change them on the **HUD** page (**Record**, then press your new combo).

---

## Home

<img src="screenshots/home.png" width="640" alt="Home page">

Your Odin at a glance: battery level and how long it will last at the current rate, your Wi-Fi ping and signal, and
whether the PULSE engine is running. Tap HandyHelper and he moonwalks.

At the bottom, the **About** card shows the version, the license, and buttons for the **Source code**, **Licenses**,
**Discord** and **Ko-fi**.

---

## Quick Menu

Open it in any game with **both sticks + R1**. Changes apply to the game in front and are saved for that game.

| Tile | What it does |
|---|---|
| **Performance & Fan** | Pick **Auto** (AutoTDP holds an FPS target, 30, 40, 60 or 120, at the lowest power it can) or a fixed power level: **Max**, **Balanced** or **Saver**. With a fixed level you can add a **frame cap** (30, 40 or 60 FPS). Pick the fan: **Quiet**, **Smart**, **Sport** or **Hold temp**. Live CPU temperature and fan speed are shown. |
| **Preset** | Switch this game to another preset in one tap (see [Games and presets](#games-and-presets)). |
| **Quick settings** | Brightness, volume, refresh rate (60 or 120 Hz), **Screenshot** and screen **Record**. Screenshots go to *Pictures › HandyTuner*, recordings to *Movies › HandyTuner*. |
| **HUD** | Show or hide the HUD. |
| **AFK Mode** | Stepping away mid-game? Dims the screen and locks the buttons so nothing gets pressed. |
| **Key Mapping** | Turn your key map on or off for this game (see [Controller](#controller)). |
| **A/B Buttons** | Swap which face button is A. |
| **Speed Up** | Closes background apps to free memory (never your game, and never an app that's downloading). |
| **Pulse settings** | Opens the engine settings on the Tweaks page. |

---

## HUD

<img src="screenshots/hud.png" width="640" alt="HUD settings">

The HUD floats over your game. **Full** shows FPS, refresh rate, CPU and GPU load, CPU and GPU temperature, RAM,
battery time left, ping (to the game's server when HandyTuner can find it, and to the internet), Wi-Fi signal, the
time, and a one-line hint about what's slowing the game down. It says the chip is **throttling** only when the
system is really slowing it down for heat, not just because it's warm. **Compact** shows just FPS, ping and Wi-Fi.

On the HUD page you can also change the **hotkeys**, the HUD's **position** (any corner), **size** and **accent
color**. Changes apply live.

---

## Games and presets

<img src="screenshots/games.png" width="640" alt="Games page">

Every game you play shows up here with its **preset**. When a game opens, HandyTuner applies its preset
automatically. It works for Windows games in GameNative too, where each `.exe` gets its own preset.

| Preset | What it sets |
|---|---|
| **Battery** | Saver power level, 30 FPS cap, Quiet fan, plus HandyTuner's battery saver: screen at most 35% brightness, no background Wi-Fi/Bluetooth scans, background apps closed |
| **Optimal** | AutoTDP at 60 FPS, Smart fan. **New games start here.** |
| **Performance** | Max power level, Sport fan |
| **Competitive** | Max power level, Sport fan, plus Low Latency Wi-Fi |

Changes you make in the Quick Menu are saved to that game, and it's then shown as "(tweaked)".

**My presets, export & import:** make your own presets (power level or AutoTDP, frame cap, fan, and a battery or
network part), and share them as a file in your Downloads folder. Importing never overwrites your own presets or games.

---

## Battery

<img src="screenshots/battery.png" width="640" alt="Battery page">

- **Charge limit:** stops charging at the level you pick (default 80%) and starts again 5% below. Keeping the battery
  away from 100% helps it last longer. It uses AYN's own charging switch.
- **Skip the battery while gaming:** while a game runs on the charger, power goes straight to the Odin instead of
  through the battery, so the battery stays cool.
- **Google Play services:** turn them off while you play to free memory and background work. Notifications, Google
  sign-ins and the Play Store stop until you turn them back on.
- **Play sessions:** your recent gaming sessions, newest first.

---

## Network

<img src="screenshots/network.png" width="640" alt="Network page">

- **Ping:** live ping to Cloudflare or Google, with average, jitter (how jumpy it is) and lost packets. Under the gray
  50 ms line feels instant in most online games.
- **Network test:** measures your connection in about a minute, and can test **Low Latency mode** on your own Wi-Fi
  (about two minutes) so you can see whether it helps you.
- **DNS benchmark:** tests your network's DNS and well-known public providers (Cloudflare, Google, Quad9, AdGuard,
  Mullvad, OpenDNS, Control D), fastest first. A faster DNS makes stores and matchmaking connect sooner; it doesn't
  change in-game ping.
- **Private DNS:** switch to an encrypted DNS provider in one tap, or back to automatic.

---

## Tweaks

<img src="screenshots/tweaks.png" width="640" alt="Tweaks page">

| Tweak | What it does |
|---|---|
| **Low Latency mode** | Wi-Fi only: stops the Wi-Fi radio napping between packets and pauses background Wi-Fi and Bluetooth scans, which cause lag spikes. It never changes performance or presets. |
| **Speed Up** | Closes background apps and clears them from Recents. Never your last game, HandyTuner, or an app that's downloading. |
| **Keep Odin Assistant's game detection off** | Odin Assistant can change performance and fan per game, which fights HandyTuner. On by default; it switches off only that one feature, and the app stays. |
| **Hold temp fan** | The temperature the **Hold temp** fan keeps the chip at (60–88 °C). Lower is cooler and louder. Applies to every preset that uses Hold temp. |
| **AutoTDP style** | **Efficient**, **Balanced** or **Smooth**: how hard AutoTDP saves power while holding your FPS. |
| **Sleep underclock** | Slows the chip while the screen is off, and puts it back when you wake the Odin. |
| **Stick lights** | The lights around the sticks: **Off**, **Battery** (green to red as it drains), **Heat** (blue to red as it warms) or a **Color** and brightness you pick. |

### The PULSE engine

HandyTuner's performance control comes from **PULSE** by keiretrogaming, built in. It runs in the background,
watches which game is in front, and applies its power level, AutoTDP, frame cap and fan. You don't need to install
PULSE separately; installing the separate PULSE app alongside HandyTuner would make the two fight.

---

## Controller

- **Button layout:** **Xbox** (A at the bottom) or **Swapped** (A on the right).
- **Key mapping:** make layouts that turn controller buttons into screen taps (or holds) for Android games without
  controller support. Buttons only: on Android 13 the sticks can't be mapped. Show the layout over the game while you set it up, and export or import layouts as files.
- Shortcuts to the Odin's own **Deadzones, triggers & more** and **Key Test & stick calibration**.

---

## Diagnostics

- **Permissions & access:** every permission HandyTuner needs, with a ✅ or a button to fix it.
- **Apps HandyTuner works with:** the PULSE engine's status, and whether Cocoon is installed.
- **Run setup again** and **Export log file** (saves HandyTuner's own log to Downloads for bug reports; it contains
  nothing from your other apps).

---

## Safety: safe mode and reset

- **Reset everything to stock** (Diagnostics) puts back every setting HandyTuner changed: brightness, refresh rate,
  scanning, Private DNS, button layout, charging, stick lights, sleep underclock, frame caps and the engine's defaults.
- **Safe mode** turns on by itself if HandyTuner's overlay keeps crashing (3 restarts in 10 minutes). It puts your
  device back and stops HandyTuner changing anything until you clear it on the Diagnostics page.
- **Uninstalling:** run **Reset everything to stock first**. Uninstalling skips the clean-up, so settings like the
  charge limit would stay on.

---

## FAQ and troubleshooting

**Does it need root?** No. It uses the system service AYN builds into the Odin.

**Does it work on my device?** It's only tested on the Odin 2 Portal. On anything else HandyTuner shows a warning on
Home. If you try it, please [open an issue](https://github.com/ElectricBits/HandyTuner/issues/new/choose) with your
device and the log from **Diagnostics → Export log file**.

**The hotkeys or HUD stopped working.** Android turns the accessibility service off after some updates. Check
**Diagnostics → Overlay & hotkeys** and switch it back on.

**Performance controls say the engine isn't answering.** Check **Diagnostics → PULSE engine**. If its watcher is asleep,
give HandyTuner **Usage access**.

**My stick lights don't change color.** Pick a mode on the Tweaks page; HandyTuner switches the lights on itself. If
you also use AYN's own LED tile, choose a solid effect there, because animated effects override the color.

**What's the difference between the Smart and Hold temp fans?** **Smart** is AYN's own automatic fan. **Hold temp** is
PULSE's fan, which aims for the exact temperature you set on the Tweaks page.

**Something went wrong.** Use **Diagnostics → Reset everything to stock**, then tell us on
[Discord](https://discord.gg/uGQQ2n36QR) or in an [issue](https://github.com/ElectricBits/HandyTuner/issues).
