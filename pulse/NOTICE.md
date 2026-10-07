# PULSE — Notices & Attribution

PULSE is licensed under the **GNU General Public License v2.0** (see `LICENSE`).

## Derived from ClusterTune

PULSE is a fork of **ClusterTune** by AurelioB, which is also licensed under GPL v2.0.
The PServer / no-root command-execution approach and the profile/apply pipeline originate
there. PULSE adds GPU (Adreno) frequency control, multi-device awareness (Odin 3 / Thor /
Retroid Pocket 6), and a new "PULSE" HUD interface.

- ClusterTune: https://github.com/AurelioB/cluster-tune

ClusterTune in turn credits:

- The PServer command-execution code is based on **O2P Tweaks** by FeralAI (GPL v2.0):
  https://github.com/FeralAI/o2ptweaks.app
- The original Odin 3 underclocking idea: **TheOldTaylor/Odin3-CPU-Underclock**, and Reddit
  users u/twoohfive205 and u/JoaozaoS in the r/OdinHandheld community.

## Bundled fonts

- **Chakra Petch** and **IBM Plex Mono** are licensed under the SIL Open Font License 1.1.
  Full license texts are in `licenses/OFL-ChakraPetch.txt` and `licenses/OFL-IBMPlexMono.txt`.

## AI assistance disclosure

AI assistance was used while building this fork. The author reviews the code and
understands what the app does.

## HandyTuner remote control (this fork)

`src/main/aidl/com/kei/pulse/control/IPulseControl.aidl` was written by ElectricBits. It was first released
under Apache-2.0; since 2026-10-06 its author licenses it under GPL-2.0-only, like the rest of HandyTuner. `control/ControlService.kt` is how HandyTuner's own
processes talk to the engine; it is not exported and accepts only HandyTuner's own uid.

## Changes in this fork (GPL-2.0 §2a: files changed, and when)

By ElectricBits, with AI assistance:
- 2026-09-28 `appwatch/ForegroundAppMonitorService.kt`: find the foreground game again after a
  mid-game restart.
- 2026-09-29/30 `appwatch/ForegroundAppMonitorService.kt`: remote-control hook
  (`remote`, `remoteState`, `remoteStats`; telemetry kept while HandyTuner reads it).
- 2026-09-29/30 new: `control/ControlService.kt`, `aidl/.../IPulseControl.aidl` (Apache-2.0),
  `test/.../control/ControlContractTest.kt`.
- 2026-09-30 `data/FrameLimiter.kt`: Android 13 frame cap (`setCapOverride`, `clearOverride`), used by
  HandyTuner's frame cap; AutoTDP's own path unchanged.
- 2026-09-30 `appwatch/ForegroundAppMonitorService.kt`: stopping AutoTDP mid-game gives the panel
  rate back (it stayed at 60 Hz until the game was left); all-games default via the remote (v4).
- 2026-09-29 `AndroidManifest.xml` (the CONTROL permission and service), `app/build.gradle.kts`
  (AIDL on).
- 2026-10-01 the fork project was renamed **pulse-handytuner**; no code change.

- 2026-10-06 **merged into HandyTuner** as the `pulse/` module (Android library, namespace `com.kei.pulse`):
  - removed PULSE's own app screens (`ui/` except `ui/theme`, `MainActivity`, `TileControlActivity`), its
    quick-settings tile (`tile/`), and its app icon, name, theme and color resources;
  - `control/ControlService.kt`: same-app caller check; v5 `engineSettings` / `setEngineSetting`; new
    `control/EngineSettings.kt` (whitelist) and its test;
  - `data/PerAppConfigStorage.kt`: per-app profiles on by default; switch toasts off by default;
  - `data/PerformanceRepository.kt`: `tierFrequencies` internal (HandyTuner's sleep profile);
  - `data/RgbController.kt`: switches the stick lights on/off itself, works before AYN's keys exist;
  - `appwatch/ForegroundAppMonitorService.kt`, `sleep/SleepProfileMonitorService.kt`: HandyTuner's name,
    icon and colour on notifications and toasts; notification opens HandyTuner; "Move overlay" action removed;
  - `boot/BootCompletedReceiver.kt`: no tile refresh.
- 2026-10-07 `model/DeviceProfiles.kt`: an Odin 2 Portal profile (AutoTDP targets 30/40/60/120, Game Mode cap on);
  `data/FrameLimiter.kt`: AutoTDP's cap falls back to the battery-mode override when Android 13 refuses mode 4.
  `data/FpsReader.kt` + `appwatch/ForegroundAppMonitorService.kt`: a ≥50 ms slow-frame count, used as AutoTDP's jank
  signal at a 30 fps target (33 ms frames are normal there).
  `appwatch/ForegroundAppMonitorService.kt`: the mode label is also refreshed while HandyTuner's HUD reads it. The FPS dump runs only for AutoTDP
  or PULSE's own overlay, not just because HandyTuner's HUD reads telemetry.

Not the official PULSE: it ships only inside HandyTuner, under HandyTuner's name.
